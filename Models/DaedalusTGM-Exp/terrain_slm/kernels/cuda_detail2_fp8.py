"""EXP-2 detail sampler at mixed FP8 / fp16 precision: NativeDetail2Sampler's plan (kernels/cuda_detail2.py) with every
convolution in the format its layer was distilled for (train/train_qad.py; the checkpoint's "quant" record):

  * e4m3 layers run csrc/conv_fp8.cu (W8A8, fp32 accumulation): weights as e4m3 codes with one scale per output
    channel (per phase and channel for the up-merge), the input as e4m3 codes at the static per-tensor activation
    scale; the scales meet in the epilogue (sc[n] = w_scale[n] * act_scale);
  * fp16 layers run the fp16 kernels (csrc/conv.cu, hconv.cu) unchanged;
  * every PRODUCER writes what its consumer reads: fp16 or e4m3 SiLU (the e4m3 epilogue / pixnorm8 write e4m3
    directly; an fp16 kernel feeding an e4m3 conv is followed by a quant8 pass), the residual stream stays fp16 and an
    e4m3 copy of it is written where an e4m3 conv reads raw (the up-merge skip, the adapters' down conv).

The numbers are those of quant/detail2_sim.Detail2Sim with the same QConfig (tests/test_cuda_fp8.py): weights are
folded by the simulation's own fp32 code and quantised by the same rule. Everything else (graphs per active-expert
subset, the per-step FiLM / embedding tables, Euler in fp32) is cuda_detail2's.
"""
from __future__ import annotations

import math

import torch
import torch.nn.functional as F

from terrain_slm.kernels import cuda_conv as C
from terrain_slm.kernels import cuda_conv_fp8 as C8
from terrain_slm.kernels.cuda_detail2 import RANK_PAD, _Conv, _config, _uses_hconv
from terrain_slm.kernels.cuda_unet import EULER_NP, STEM_PAD
from terrain_slm.models import detail2 as D2
from terrain_slm.models.mp import SILU_GAIN
from terrain_slm.quant import detail2_sim as Q

FP8 = "e4m3"


def qconfig_of(ck: dict | None, act_scale: float = 0.125) -> Q.QConfig:
    """The QConfig a QAD checkpoint was trained for (train_qad's "quant" record)."""
    q = (ck or {}).get("quant") or {}
    keep = q.get("fp16_layers", ["stem"])
    s = act_scale
    acts = str(q.get("acts", ""))
    if "scale" in acts:
        try:
            s = float(acts.rsplit("scale", 1)[1].split()[0])
        except (ValueError, IndexError):
            pass
    return Q.QConfig(default=FP8, layers={k: "fp16" for k in keep}, act_scale=s)


class _Buf:
    """One activation buffer slot, in fp16 and / or e4m3: each storage is allocated on first use (plans are built
    before capture), so a slot whose consumers are all e4m3 costs one byte per element."""

    def __init__(self, h: int, w: int, c: int, dev, backing: torch.Tensor | None = None):
        self.shape, self.dev = (1, h, w, c), dev
        self._t = {}
        if backing is not None:   # an fp16 buffer that is dead while this slot lives (cuda_detail2's tmp aliasing)
            self._t = {"fp16": backing, FP8: backing.view(-1).view(torch.uint8)[: backing.numel()].view(self.shape)}
        self.redirect = None

    def get(self, fmt: str):
        if fmt not in self._t:
            self._t[fmt] = torch.zeros(self.shape, device=self.dev,
                                       dtype=torch.uint8 if fmt == FP8 else torch.float16)
        return self._t[fmt]


class _Conv8:
    """One packed e4m3 convolution and its launch config."""

    def __init__(self, sampler, kind: str, w: torch.Tensor, hh: int, stride: int = 1):
        co, ci, k, _ = w.shape
        assert ci % 32 == 0, (kind, w.shape)
        self.ks, self.stride = k, stride
        ho = (hh + stride - 1) // stride
        self.h = kind in ("c1", "c2", "ad_mid", "out") and k == 3 and stride == 1
        key = (kind + "8", ci, co, k, stride, ho)
        tuned = sampler._tuned.get(key)
        if tuned is not None and len(tuned) != (6 if self.h else 5):
            tuned = None
        if self.h:
            cfg = tuned or C8.default_hconv8_config(co, ho)
            if co == 1 and tuned is None:
                cfg = (8, 32, 32, 8, 1, 2)
            np_ = C.round_up(co, cfg[2])
            self.w, sc = C8.pack_hconv8(w, np_)
        else:
            cfg = tuned or C8.default_conv8_config(ho * ho, co, 0)
            np_ = C.round_up(co, cfg[1])
            self.w, sc = C8.pack_conv8(w, np_)
        self.cfg = cfg
        self.sc = (sc * sampler.act_scale).contiguous()
        sampler.layer_keys.append((key, self.cfg))

    def __call__(self, x, **kw):
        if self.h:
            return C8.make_hconv8(x, self.w, self.sc, self.cfg, **kw)
        return C8.make_conv8(x, self.w, self.sc, self.cfg, ks=self.ks, stride=self.stride, **kw)


class NativeDetail2SamplerFP8:
    """Like NativeDetail2Sampler (same call signature and contract), at the formats of `qc` (default: the checkpoint's
    QAD record via `qconfig_of`). Not thread-safe; the generator calls it under its lock."""

    supported = staticmethod(lambda model: _supported(model))

    def __init__(self, model: D2.Detail2, h: int, w: int, steps: int, t_start: float = 0.0,
                 mean_flow: bool | None = None, configs: dict | None = None, experts=None, qc: Q.QConfig | None = None,
                 tuned: dict | None = None):
        dev = next(model.parameters()).device
        if tuned is None:                     # kernels/tune_fp8.py's table for this GPU (configs never change numbers)
            from terrain_slm.kernels.tune_fp8 import load_table
            tuned = load_table().get(torch.cuda.get_device_name(dev), {})
        self._tuned = tuned
        assert _supported(model)
        self.qc = qc or getattr(model, "fp8_qconfig", None) or Q.QConfig(default=FP8, layers={"stem": "fp16"})
        self.act_scale = float(self.qc.act_scale)
        assert not self.qc.act_scales, "per-layer activation scales: not wired into the sampler"
        self.qs = 1.0 / self.act_scale
        self.h, self.w, self.steps, self.dev, self.t_start = h, w, steps, dev, t_start
        assert h % model.multiple == 0 and w % model.multiple == 0, (h, w, model.multiple)
        self.mean_flow = getattr(model, "mean_flow", False) if mean_flow is None else mean_flow
        self.n_cond = model.stem.weight.shape[1] - 2
        self._configs = configs or {}
        self.layer_keys = []
        self.experts = tuple(s for s in D2.STYLES if s in model.adapters and (experts is None or s in experts))
        self._all_styles = tuple(s for s in D2.STYLES if s in model.adapters)
        hf = dict(device=dev, dtype=torch.float16)
        n_lvl = self.n_lvl = len(model.down)
        chans = self.chans = list(model.cfg.channels)
        res = self.res = [(h >> i, w >> i) for i in range(n_lvl + 1)]
        sg = SILU_GAIN
        sim = Q.Detail2Sim(model, self.qc)
        self._rs_x = sim.rs_x
        fmt = self.qc.fmt

        with torch.no_grad():
            ts = torch.linspace(t_start, 1.0, steps + 1, device=dev)
            self.dts = [float(ts[i + 1] - ts[i]) for i in range(steps)]
            emb = model.embed(ts[:steps].float(), ts[1:].float() if self.mean_flow else None).float()
            blocks = [b for lvl in model.enc for b in lvl] + list(model.mid) + [b for lvl in model.dec for b in lvl]
            self._film = {}
            for b in blocks:
                c = (b.emb_linear(emb, gain=b.emb_gain) + 1).float()
                self._film[id(b)] = torch.cat([c, torch.zeros_like(c)], dim=1).contiguous()

            # ---------------------------------------------------------- buffers (per level)
            self.stem_in = torch.zeros(1, h, w, STEM_PAD, **hf)
            self.stem_in[..., 1 + self.n_cond] = 1.0
            self.xs = torch.zeros(h * w, device=dev, dtype=torch.float32)
            self.A = [torch.zeros(1, *res[i], chans[i], **hf) for i in range(n_lvl + 1)]   # encoder raw / skip
            self.B = [torch.zeros(1, *res[i], chans[i], **hf) for i in range(n_lvl)]       # decoder raw
            self.S = [_Buf(*res[i], chans[i], dev) for i in range(n_lvl + 1)]             # SiLU (fp16 | e4m3)
            # block tmp: the decoder raw while encoding, the (consumed) skip while decoding, as cuda_detail2
            self.T_enc = [_Buf(*res[i], chans[i], dev, self.B[i]) for i in range(n_lvl)] + [_Buf(*res[n_lvl], chans[n_lvl], dev)]
            self.T_dec = [_Buf(*res[i], chans[i], dev, self.A[i]) for i in range(n_lvl)]
            self._R8 = [_Buf(*res[i], chans[i], dev) for i in range(n_lvl + 1)]          # e4m3 raw copies
            self._Q8 = [_Buf(*res[i], chans[i], dev) for i in range(n_lvl + 1)]          # quant8 scratch

            # ---------------------------------------------------------- weights
            ew = Q.Detail2Sim.ew
            L = {}

            def conv(name, kind, wt, hh, stride=1, cin_pad=None):
                if fmt(name) == FP8:
                    L[name] = (FP8, _Conv8(self, kind, wt.float(), hh, stride))
                else:
                    L[name] = ("fp16", _Conv(self, kind, wt.double(), hh, stride=stride, cin_pad=cin_pad))

            assert fmt("stem") == "fp16", "the stem stays fp16 (26 m alone in e4m3)"
            L["stem"] = ("fp16", _Conv(self, "stem", ew(model.stem).double(), h, cin_pad=STEM_PAD))
            self._ad_bufs, self._ad_rank = {}, {}
            self._sites = []          # (block, level, site name, encoder, adapters [(style, ad)])
            for li in range(n_lvl):
                for bi, b in enumerate(model.enc[li]):
                    self._sites.append((b, li, f"enc{li}.{bi}", True,
                                        [(s, model.adapters[s].enc[str(li)][bi]) for s in self.experts
                                         if str(li) in model.adapters[s].enc]))
            for bi, b in enumerate(model.mid):
                self._sites.append((b, n_lvl, f"mid.{bi}", False, [(s, model.adapters[s].mid[bi]) for s in self.experts]))
            for li in reversed(range(n_lvl)):
                for bi, b in enumerate(model.dec[li]):
                    self._sites.append((b, li, f"dec{li}.{bi}", False,
                                        [(s, model.adapters[s].dec[str(li)][bi]) for s in self.experts
                                         if str(li) in model.adapters[s].dec]))
            for b, lvl, site, _, ads in self._sites:
                hh = res[lvl][0]
                conv(site + ".c0", "c1", ew(b.conv0) * sg, hh)
                conv(site + ".c1", "c2", ew(b.conv1) * (sg * sim.rs_y), hh)
                for style, ad in ads:
                    nm = f"ad.{style}.{site}"
                    c = chans[lvl]
                    r = ad.down.weight.shape[0]
                    rp = C.round_up(r, RANK_PAD)
                    wd = torch.cat([ew(ad.down), ew(ad.down).new_zeros(rp - r, c, 1, 1)])
                    wmid = F.pad(ew(ad.mid) * sg, (0, 0, 0, 0, 0, rp - r, 0, rp - r))
                    wup = F.pad(ew(ad.up, ad.gain) * sg, (0, 0, 0, 0, 0, rp - r))
                    conv(nm + ".down", "ad_down", wd, hh)
                    conv(nm + ".mid", "ad_mid", wmid, hh)
                    conv(nm + ".up", "ad_up", wup, hh)
                    key = (lvl, rp)
                    self._ad_rank[nm] = rp
                    if key not in self._ad_bufs:
                        self._ad_bufs[key] = (_Buf(*res[lvl], rp, dev), _Buf(*res[lvl], rp, dev), None)
            for i in range(n_lvl):
                conv(f"down{i}", "down", ew(model.down[i]) * sg, res[i][0], stride=2)
            self._up = {}
            for i in range(n_lvl):
                ph, wb = sim.upmerge_phases(i)
                c, clow = chans[i], chans[i + 1]
                lo = res[i + 1][0]
                if fmt(f"up{i}") == FP8:
                    cfg = tuple(self._tuned.get(("upmerge8", clow, c, 2, 1, lo)) or C8.default_conv8_config(lo * lo, c, 1))
                    np_ = C.round_up(c, cfg[1])
                    parts = [C8.e4m3_rows(torch.cat([ph[p].permute(0, 2, 3, 1).reshape(c, 4 * clow), wb], 1), np_)
                             for p in range(4)]
                    wq = torch.cat([p[0] for p in parts]).contiguous()
                    sc = (torch.cat([p[1] for p in parts]) * self.act_scale).contiguous()
                    self._up[i] = (FP8, wq, sc, cfg)
                    self.layer_keys.append((("upmerge8", clow, c, 2, 1, lo), cfg))
                else:
                    key = ("upmerge", clow, c, 2, 1, lo)
                    cfg = self._configs.get(key) or _config(key, lo * lo, c, 1, 4 * clow + c, dev)
                    self.layer_keys.append((key, lo * lo, c, 1, 4 * clow + c))
                    np_ = C.round_up(c, cfg[1])
                    phd = ph.double().permute(0, 3, 4, 2, 1).reshape(4, 4, clow, c)       # (phase, tap, ci, co)
                    wq = C._finish(torch.cat([torch.cat([phd[p].reshape(4 * clow, c), wb.double().t()])
                                              for p in range(4)]).float(), np_)
                    self._up[i] = ("fp16", wq, torch.zeros(np_, device=dev), cfg)
            wo = ew(model.out, model.out_gain) * sg
            if fmt("out") == FP8:
                L["out"] = (FP8, _Conv8(self, "out", wo.float(), h))
            else:
                out_key = ("out", chans[0], 1, 3, 1, h)
                self.out_cfg = self._configs.get(out_key) or C.TUNED.get(torch.cuda.get_device_name(dev), {}).get(out_key) \
                    or (8, 32, EULER_NP, 8, 1, 2)
                L["out"] = ("fp16", (C.pack_hconv(wo.float(), EULER_NP), torch.zeros(EULER_NP, device=dev)))
            self._gates = {}
            for style in self.experts:
                si = D2.STYLES.index(style)
                for _, lvl, _, _, ads in self._sites:
                    if any(s == style for s, _ in ads) and (si, lvl) not in self._gates:
                        self._gates[si, lvl] = torch.zeros(res[lvl][0] * res[lvl][1], device=dev, dtype=torch.float32)
        self._L = L
        self._plans, self._graphs = {}, {}

    # ---------------------------------------------------------------- plan
    def _plan(self, active: tuple) -> list:
        if active in self._plans:
            return self._plans[active]
        L, n_lvl, fmt, qs = self._L, self.n_lvl, self.qc.fmt, self.qs
        plan = []
        ops = []    # (name, callable(step) -> Launch) appended in order

        def run(name, inp, **out):
            """Emit layer `name` reading `inp` (in the layer's format). `out` keys: film, res, res_scale, clip, gate,
            out_raw (fp16), silu=(consumer name, _Buf or tensor pair) -> written in the consumer's format,
            raw8=(tensor) e4m3 raw copy, euler."""
            f, conv = L[name]
            silu = out.pop("silu", None)
            raw8 = out.pop("raw8", None)
            kw = dict(out)
            post = []
            if f == FP8:
                if silu is not None:
                    cons, buf = silu
                    if fmt(cons) == FP8:
                        kw["out_silu8"], kw["qs"] = buf.get(FP8), qs
                    else:
                        kw["out_silu"] = buf.get("fp16")
                if raw8 is not None:
                    kw["out_raw8"], kw["qs_raw"] = raw8, qs
                plan.append(lambda s, conv=conv, inp=inp, kw=kw: conv(inp, **_film(kw, s)))
            else:
                if silu is not None:
                    cons, buf = silu
                    kw["out_silu"] = buf.get("fp16")
                    if fmt(cons) == FP8:
                        post.append((buf.get("fp16"), buf, cons))
                if raw8 is not None:
                    post.append((kw["out_raw"], raw8, None))
                kw16 = {k: v for k, v in kw.items()}
                plan.append(lambda s, conv=conv, inp=inp, kw=kw16: conv(inp, **_film(kw, s)))
                for src, dst, _ in post:
                    # fp16 producer -> e4m3 consumer: quant pass into a scratch, the consumer reads the scratch
                    tgt = dst if torch.is_tensor(dst) else self._q8_for(src)
                    plan.append(lambda s, src=src, tgt=tgt: C8.make_quant8(src, tgt, qs))
                    if not torch.is_tensor(dst):
                        dst.redirect = tgt

        def read(name, buf):
            """The tensor layer `name` reads from SiLU buffer `buf` (redirected to a quant scratch if its producer
            was an fp16 kernel)."""
            if fmt(name) == FP8:
                r = getattr(buf, "redirect", None)
                buf.redirect = None
                return r if r is not None else buf.get(FP8)
            return buf.get("fp16")

        def adapters_at(ads):
            return [(s, ad) for s, ad in ads if s in active]

        sites = self._sites
        # The SiLU consumer after each site, and whether the site's final raw feeds an e4m3 skip.
        nxt = {}
        for k, (b, lvl, site, enc, ads) in enumerate(sites):
            after = sites[k + 1][2] if k + 1 < len(sites) else ""
            if site.startswith("enc"):
                nxt[site] = None if after.startswith(f"enc{lvl}.") else f"down{lvl}"
            elif site.startswith("mid"):
                nxt[site] = after + ".c0" if after.startswith("mid") else f"up{n_lvl - 1}"
            else:
                nxt[site] = after + ".c0" if after.startswith(f"dec{lvl}.") else (f"up{lvl - 1}" if lvl > 0 else "out")

        def block(b, lvl, site, enc, ads, raw, tmp):
            ads = adapters_at(ads)
            S = self.S[lvl]
            if enc:
                if fmt(site + ".c0") == FP8:
                    plan.append(lambda s, raw=raw, o=S.get(FP8): C8.make_pixnorm8(raw, o, qs))
                else:
                    plan.append(lambda s, raw=raw, o=S.get("fp16"): C.make_pixnorm(raw, o))
            tab = self._film[id(b)]
            run(site + ".c0", read(site + ".c0", S), film=tab, silu=(site + ".c1", tmp))
            last_skip = site.startswith("enc") and nxt[site] is not None and fmt(f"up{lvl}") == FP8
            first_ad = f"ad.{ads[0][0]}.{site}.down" if ads else None
            out = dict(res=raw, res_scale=self._rs_x, clip=True, out_raw=raw)
            if not ads and nxt[site] is not None:
                out["silu"] = (nxt[site], S)
            if (ads and fmt(first_ad) == FP8) or (not ads and last_skip):
                out["raw8"] = self._R8[lvl].get(FP8)
            run(site + ".c1", read(site + ".c1", tmp), **out)
            for k, (style, _) in enumerate(ads):
                nm = f"ad.{style}.{site}"
                a1, a2, _ = self._ad_bufs[lvl, self._ad_rank[nm]]
                g = self._gates[D2.STYLES.index(style), lvl]
                inp = self._R8[lvl].get(FP8) if fmt(nm + ".down") == FP8 else raw
                run(nm + ".down", inp, silu=(nm + ".mid", a1))
                run(nm + ".mid", read(nm + ".mid", a1), silu=(nm + ".up", a2))
                o = dict(res=raw, gate=g, out_raw=raw)
                last = k == len(ads) - 1
                if last and nxt[site] is not None:
                    o["silu"] = (nxt[site], S)
                nfp8 = (not last and fmt(f"ad.{ads[k + 1][0]}.{site}.down") == FP8) or (last and last_skip)
                if nfp8:
                    o["raw8"] = self._R8[lvl].get(FP8)
                run(nm + ".up", read(nm + ".up", a2), **o)

        # stem
        f, conv = L["stem"]
        plan.append(lambda s, conv=conv: conv(self.stem_in, out_raw=self.A[0]))
        k = 0
        for li in range(n_lvl):
            while k < len(sites) and sites[k][2].startswith(f"enc{li}."):
                b, lvl, site, enc, ads = sites[k]
                block(b, lvl, site, enc, ads, self.A[li], self.T_enc[li])
                k += 1
            o = dict(out_raw=self.A[li + 1])
            if li + 1 == n_lvl:
                o["silu"] = (sites[k][2] + ".c0", self.S[n_lvl])
            run(f"down{li}", read(f"down{li}", self.S[li]), **o)
        while k < len(sites) and sites[k][2].startswith("mid"):
            b, lvl, site, enc, ads = sites[k]
            block(b, lvl, site, enc, ads, self.A[n_lvl], self.T_enc[n_lvl])
            k += 1
        for i in reversed(range(n_lvl)):
            src = self.S[i + 1]
            f_up, wq, sc, cfg = self._up[i]
            first = sites[k][2] + ".c0"
            if f_up == FP8:
                o = {"out_silu8": self.S[i].get(FP8), "qs": qs} if fmt(first) == FP8 else {"out_silu": self.S[i].get("fp16")}
                x1 = read(f"up{i}", src)
                plan.append(lambda s, x1=x1, wq=wq, sc=sc, cfg=cfg, i=i, o=o: C8.make_conv8(
                    x1, wq, sc, cfg, mode=1, x2=self._R8[i].get(FP8), out_raw=self.B[i], **o))
            else:
                x1 = src.get("fp16")
                plan.append(lambda s, x1=x1, wq=wq, b=sc, cfg=cfg, i=i: C.make_conv(
                    x1, wq, b, cfg, mode=1, x2=self.A[i], out_raw=self.B[i], out_silu=self.S[i].get("fp16")))
                if fmt(first) == FP8:
                    tgt = self._Q8[i].get(FP8)
                    plan.append(lambda s, i=i, tgt=tgt: C8.make_quant8(self.S[i].get("fp16"), tgt, qs))
                    self.S[i].redirect = tgt
            while k < len(sites) and sites[k][2].startswith(f"dec{i}."):
                b, lvl, site, enc, ads = sites[k]
                block(b, lvl, site, enc, ads, self.B[i], self.T_dec[i])
                k += 1
        f, conv = L["out"]
        x = read("out", self.S[0])
        eu = lambda s: (self.xs, self.stem_in, self.dts[s])
        if f == FP8:
            plan.append(lambda s, x=x, conv=conv: conv(x, euler=eu(s)))
        else:
            wq, bq = conv
            plan.append(lambda s, x=x, wq=wq, bq=bq: C.make_hconv(x, wq, bq, self.out_cfg, euler=eu(s)))
        steps = [[op(s) for op in plan] for s in range(self.steps)]
        C.bind([lc for st in steps for lc in st], self.dev)
        self._plans[active] = steps
        return steps

    def _q8_for(self, src: torch.Tensor) -> torch.Tensor:
        if not hasattr(self, "_q8"):
            self._q8 = {}
        key = tuple(src.shape)
        if key not in self._q8:
            self._q8[key] = torch.zeros(src.shape, device=self.dev, dtype=torch.uint8)
        return self._q8[key]

    # ---------------------------------------------------------------- run (as NativeDetail2Sampler)
    def active_for(self, gates):
        if gates is None:
            return ()
        on = (gates > 0).flatten(2).any(-1).any(0).tolist()
        act = tuple(s for i, s in enumerate(D2.STYLES) if i < len(on) and on[i] and s in self._all_styles)
        if any(s not in self.experts for s in act):
            return None
        return act

    def _run(self, active: tuple) -> None:
        stream = torch.cuda.current_stream(self.dev).cuda_stream
        for st in self._plan(active):
            for lc in st:
                lc(stream)

    def capture(self, active: tuple = ()) -> None:
        if active in self._graphs:
            return
        self._plan(active)
        with torch.cuda.device(self.dev):
            g = torch.cuda.CUDAGraph()
            stream = torch.cuda.Stream(self.dev)
            stream.wait_stream(torch.cuda.current_stream(self.dev))
            with torch.cuda.stream(stream):
                with torch.cuda.graph(g, stream=stream, capture_error_mode="thread_local"):
                    self._run(active)
            torch.cuda.current_stream(self.dev).wait_stream(stream)
            self._graphs[active] = g

    def _load(self, cond, noise, gates, active) -> None:
        x0 = (1.0 - self.t_start) * noise.float() if self.t_start > 0 else noise.float()
        self.xs.copy_(x0.reshape(-1))
        self.stem_in[..., 0] = x0[0, 0].to(torch.float16)
        self.stem_in[..., 1: 1 + self.n_cond] = cond[0].permute(1, 2, 0).to(torch.float16)
        for style in active:
            si = D2.STYLES.index(style)
            g = gates[:, si: si + 1].float()
            for (gi, lvl), buf in self._gates.items():
                if gi != si:
                    continue
                hl, wl = self.res[lvl]
                p = F.adaptive_avg_pool2d(g, (hl, wl)) if g.shape[-1] != wl else g
                buf.copy_(p.reshape(-1))

    @torch.no_grad()
    def __call__(self, cond, noise, gates=None):
        assert cond.shape == (1, self.n_cond, self.h, self.w), cond.shape
        active = self.active_for(gates)
        if active is None:
            return None
        with torch.cuda.device(self.dev):
            if active not in self._graphs:
                self.capture(active)
            self._load(cond, noise, gates, active)
            self._graphs[active].replay()
            return self.xs.view(1, 1, self.h, self.w).clone()

    @torch.no_grad()
    def eager(self, cond, noise, gates=None):
        active = self.active_for(gates)
        if active is None:
            return None
        with torch.cuda.device(self.dev):
            self._load(cond, noise, gates, active)
            self._run(active)
            return self.xs.view(1, 1, self.h, self.w).clone()


def _film(kw: dict, s: int) -> dict:
    """Per-step FiLM row: plans store the (steps, 2C) table, launches take row s."""
    if "film" in kw and kw["film"] is not None and kw["film"].ndim == 2:
        kw = dict(kw)
        kw["film"] = kw["film"][s]
    return kw


def _supported(model) -> bool:
    from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler
    if not NativeDetail2Sampler.supported(model):
        return False
    if any(c % 32 for c in model.cfg.channels):
        return False
    return torch.cuda.is_available() and torch.cuda.get_device_capability(next(model.parameters()).device) >= (8, 9)
