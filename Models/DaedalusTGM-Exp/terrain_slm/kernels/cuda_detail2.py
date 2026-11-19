"""EXP-2 detail sampler (models/detail2.py `Detail2`, MeanFlow) on the project's own CUDA kernels.

The v4 sampler's machinery (kernels/cuda_unet.py: a static plan of fused convolutions over preallocated NHWC
fp16 buffers, all Euler steps recorded as ONE CUDA graph) with Detail2's magnitude-preserving layers folded into
plain convolution weights and epilogue constants (models/mp.py says how):

  * MPConv            its `effective_weight()` (unit norm per output channel / sqrt(fan_in) * gain), no bias
  * mp_silu           the kernels store plain SiLU; the 1/0.596 gain is folded into the weights of the conv that
                      reads it (every mp_silu output feeds exactly one conv)
  * encoder blocks    pixel_norm runs as its own small kernel (csrc/pixnorm.cu), in place on the raw tensor, and
                      writes the SiLU conv0 reads
  * Block             conv0 -> * (1 + emb_gain * emb_linear(emb)) [per-step fp32 table, the v4 FiLM slot with
                      shift 0] -> SiLU; conv1 (weights x the mp_sum weight of y) + rs * x (the mp_sum weight of x,
                      F_RSCALE) -> clip +-256 (F_CLIP) -> raw and SiLU
  * up path           conv3x3(nearest_up(mp_silu h)) + 1x1 merge of mp_cat(h, skip): the mp_cat weights and the
                      silu gain fold into v4's phase up-merge (one launch per level, composite weights in fp64)
  * output            out conv (gain = out_gain) and the Euler step x += dt * v in fp32
  * MeanFlow          the embedding of each step's (t, r) is a constant: computed once per sampler, in fp32
  * experts           a gated adapter after a hooked block: 1x1 down (rank padded to a multiple of 32) -> SiLU ->
                      3x3 -> SiLU -> 1x1 up (gain), then h + gate(pixel) * v in the up conv's epilogue (F_GATE;
                      the gate is the window's style gate average-pooled to the level, like Detail2.forward). Which
                      experts run depends on the window (an expert with an all-zero gate is skipped, as in torch),
                      so the sampler keeps one CUDA graph per active-expert subset, sharing buffers and weights.

Numerics as v4's sampler: fp16 tensor-core operands, fp32 accumulation, epilogues and Euler state, deterministic
(fixed K order, no split-K: configs only change speed). Which kernel a layer runs is a fixed rule (`_uses_hconv`).
"""
from __future__ import annotations

import math

import torch
import torch.nn.functional as F

from terrain_slm.kernels import cuda_conv as C
from terrain_slm.kernels.cuda_unet import EULER_NP, STEM_PAD
from terrain_slm.models import detail2 as D2
from terrain_slm.models.mp import SILU_GAIN

RANK_PAD = 32   # adapter ranks padded to the implicit-GEMM kernel's BK (zero rows / columns change nothing)


def _uses_hconv(kind: str, cin: int, cout: int, ks: int, stride: int) -> bool:
    """Kernel rule (part of the numbers): block and adapter 3x3 stride-1 convs -> the halo kernel, the rest ->
    the implicit-GEMM kernel. Same rule as v4 for the layers both have."""
    return kind in ("c1", "c2", "ad_mid") and ks == 3 and stride == 1 and cin % 16 == 0 and cout % 16 == 0


def _config(key: tuple, m: int, co: int, mode: int, kdim: int, device) -> tuple:
    hc = _uses_hconv(*key[:5])
    cfg = C.TUNED.get(torch.cuda.get_device_name(device), {}).get(key)
    if cfg is not None and (len(cfg) == 6) == hc:
        return cfg
    return C.default_hconv_config(key[2], key[5]) if hc else C.default_config(m, co, mode, kdim)


class _Conv:
    """One packed convolution (weights fp16 once, bias zeros) and the kernel / config it runs on."""

    def __init__(self, sampler, kind: str, w: torch.Tensor, hh: int, stride: int = 1, cin_pad: int | None = None):
        co, ci, k, _ = w.shape
        cin = cin_pad or ci
        ho = (hh + stride - 1) // stride
        key = (kind, cin, co, k, stride, ho)
        self.cfg = sampler._configs.get(key) or _config(key, ho * ho, co, 0, k * k * cin, sampler.dev)
        sampler.layer_keys.append((key, ho * ho, co, 0, k * k * cin))
        self.h = len(self.cfg) == 6
        self.ks, self.stride = k, stride
        np_ = C.round_up(co, self.cfg[2] if self.h else self.cfg[1])
        w = w.float()
        if cin > ci:
            w = torch.cat([w, torch.zeros(co, cin - ci, k, k, device=w.device)], dim=1)
        self.w = C.pack_hconv(w, np_) if self.h else C.pack_conv(w, np_)
        self.b = torch.zeros(np_, device=w.device, dtype=torch.float32)

    def __call__(self, x, **kw):
        if self.h:
            return C.make_hconv(x, self.w, self.b, self.cfg, **kw)
        return C.make_conv(x, self.w, self.b, self.cfg, ks=self.ks, stride=self.stride, **kw)


class NativeDetail2Sampler:
    """MeanFlow / Euler sampler for one Detail2 and one window shape (B=1, H x W). `__call__(cond, noise, gates)`
    returns the encoded sample like `detail2.sample(..., mean_flow=...)`, or None when a gate is active for an
    expert this sampler did not pack (the caller then uses the torch path). Not thread-safe (one set of buffers);
    the generator calls it under its lock."""

    @staticmethod
    def supported(model) -> bool:
        if not (isinstance(model, D2.Detail2) or getattr(model, "detail2_like", False)):   # (cuda_sr.py's SR view)
            return False
        ch = model.cfg.channels
        if any(c % C.BK for c in ch) or model.stem.weight.shape[1] > STEM_PAD or model.out.weight.shape[0] != 1:
            return False
        for ex in model.adapters.values():
            if any(not (0 <= int(l) < len(ch) - 1) for l in ex.enc) or any(not (0 <= int(l) < len(ch) - 1) for l in ex.dec):
                return False
        return True

    def __init__(self, model: D2.Detail2, h: int, w: int, steps: int, t_start: float = 0.0,
                 mean_flow: bool | None = None, configs: dict | None = None, experts=None):
        dev = next(model.parameters()).device
        assert self.supported(model)
        self.h, self.w, self.steps, self.dev, self.t_start = h, w, steps, dev, t_start
        mult = model.multiple
        assert h % mult == 0 and w % mult == 0, (h, w, mult)
        self.mean_flow = getattr(model, "mean_flow", False) if mean_flow is None else mean_flow
        self.clip = bool(getattr(model, "clip_act", True))     # Detail2 clips block outputs at +-256; SR does not
        self.n_cond = model.stem.weight.shape[1] - 2           # x_t + cond + ones
        self._configs = configs or {}
        self.layer_keys = []
        self.experts = tuple(s for s in D2.STYLES if s in model.adapters and (experts is None or s in experts))
        hf = dict(device=dev, dtype=torch.float16)
        n_lvl = len(model.down)
        self.n_lvl = n_lvl
        chans = list(model.cfg.channels)
        res = [(h >> i, w >> i) for i in range(n_lvl + 1)]
        self.res = res
        sg = SILU_GAIN
        rb = model.cfg.res_balance
        rs_x, rs_y = (1 - rb) / math.sqrt((1 - rb) ** 2 + rb ** 2), rb / math.sqrt((1 - rb) ** 2 + rb ** 2)
        self._rs_x = rs_x
        self._all_styles = tuple(s for s in D2.STYLES if s in model.adapters)   # Detail2.forward's candidates

        with torch.no_grad():
            # ------------------------------------------------------------ per-step constants (fp32)
            ts = torch.linspace(t_start, 1.0, steps + 1, device=dev)          # exactly the reference's grid
            self.dts = [float(ts[i + 1] - ts[i]) for i in range(steps)]
            t = ts[:steps].float()
            emb = model.embed(t, ts[1:].float() if self.mean_flow else None).float()   # (steps, emb)
            blocks = [b for lvl in model.enc for b in lvl] + list(model.mid) + [b for lvl in model.dec for b in lvl]
            film = {}
            for b in blocks:
                c = (b.emb_linear(emb, gain=b.emb_gain) + 1).float()            # (steps, C)
                film[id(b)] = torch.cat([c, torch.zeros_like(c)], dim=1).contiguous()
            self._film = film

            # ------------------------------------------------------------ buffers (v4's lifetimes, cuda_unet.py)
            buf = lambda i, c=None: torch.zeros(1, res[i][0], res[i][1], c or chans[i], **hf)
            self.stem_in = torch.zeros(1, h, w, STEM_PAD, **hf)
            self.stem_in[..., 1 + self.n_cond] = 1.0                            # Detail2's ones channel (stem bias)
            self.xs = torch.zeros(h * w, device=dev, dtype=torch.float32)
            enc = [(buf(i), buf(i)) for i in range(n_lvl + 1)]
            dec = [(buf(i), enc[i][1]) for i in range(n_lvl)]
            tmp_enc = [dec[i][0] for i in range(n_lvl)] + [buf(n_lvl)]
            tmp_dec = [enc[i][0] for i in range(n_lvl)]
            self._bufs = (enc, dec, tmp_enc, tmp_dec)

            # ------------------------------------------------------------ weights (folded, fp16 once)
            ew = lambda m, gain=1.0: m.effective_weight(gain).double()
            L = {}
            L["stem"] = _Conv(self, "stem", ew(model.stem), h, cin_pad=STEM_PAD)
            for b in blocks:
                lvl = self._level_of(model, b)
                hh = res[lvl][0]
                L[id(b), 0] = _Conv(self, "c1", ew(b.conv0) * sg, hh)
                L[id(b), 1] = _Conv(self, "c2", ew(b.conv1) * (sg * rs_y), hh)
            for i in range(n_lvl):
                L["down", i] = _Conv(self, "down", ew(model.down[i]) * sg, res[i][0], stride=2)
            ct = model.cfg.concat_balance
            self._upmerge = {}
            for i in range(n_lvl):
                c = chans[i]
                up, mg = model.up[i], model.merge[i]
                wm = ew(mg)[:, :, 0, 0]                                         # (C, 2C)
                na = nb = c
                k = math.sqrt((na + nb) / ((1 - ct) ** 2 + ct ** 2))
                wa, wb = wm[:, :c] * (k / math.sqrt(na) * (1 - ct)), wm[:, c:] * (k / math.sqrt(nb) * ct)
                comp = torch.einsum("oc,cikl->oikl", wa, ew(up) * sg)            # (C, Clow, 3, 3), fp64
                ph = C._phase_weights(comp)
                clow = up.weight.shape[1]
                lo = res[i + 1][0]
                key = ("upmerge", clow, c, 2, 1, lo)
                cfg = self._configs.get(key) or _config(key, lo * lo, c, 1, 4 * clow + c, dev)
                self.layer_keys.append((key, lo * lo, c, 1, 4 * clow + c))
                np_ = C.round_up(c, cfg[1])
                wq = C._finish(torch.cat([torch.cat([ph[p].reshape(4 * clow, c), wb.t()]) for p in range(4)]).float(), np_)
                self._upmerge[i] = (wq, torch.zeros(np_, device=dev), cfg)
            out_key = ("out", chans[0], 1, 3, 1, h)
            self.out_cfg = self._configs.get(out_key) or C.TUNED.get(torch.cuda.get_device_name(dev), {}).get(out_key) \
                or (8, 32, EULER_NP, 8, 1, 2)
            self.layer_keys.append((out_key, h * w, 1, 0, 9 * chans[0]))
            self.out_w = C.pack_hconv((ew(model.out, model.out_gain) * sg).float(), EULER_NP)
            self.out_b = torch.zeros(EULER_NP, device=dev)

            # ------------------------------------------------------------ experts (adapters)
            self._ad_bufs = {}
            self._gates = {}
            self._adapters = {}           # (style, site) -> (down, mid, up) packed convs; site = ("enc"|"mid"|"dec", lvl, bi)
            for style in self.experts:
                ex = model.adapters[style]
                si = D2.STYLES.index(style)
                sites = [(("enc", int(l), bi), ad) for l, ads in ex.enc.items() for bi, ad in enumerate(ads)]
                sites += [(("mid", n_lvl, bi), ad) for bi, ad in enumerate(ex.mid)]
                sites += [(("dec", int(l), bi), ad) for l, ads in ex.dec.items() for bi, ad in enumerate(ads)]
                for site, ad in sites:
                    lvl = site[1]
                    hh, c = res[lvl][0], chans[lvl]
                    r = ad.down.weight.shape[0]
                    rp = C.round_up(r, RANK_PAD)
                    wd = ew(ad.down)
                    wd = torch.cat([wd, wd.new_zeros(rp - r, c, 1, 1)])           # (rp, c, 1, 1)
                    wmid = ew(ad.mid) * sg
                    wmid = F.pad(wmid, (0, 0, 0, 0, 0, rp - r, 0, rp - r))        # (rp, rp, 3, 3)
                    wup = ew(ad.up, ad.gain) * sg
                    wup = F.pad(wup, (0, 0, 0, 0, 0, rp - r))                    # (c, rp, 1, 1)
                    self._adapters[style, site] = (_Conv(self, "ad_down", wd, hh), _Conv(self, "ad_mid", wmid, hh),
                                                   _Conv(self, "ad_up", wup, hh), rp)
                    if (lvl, rp) not in self._ad_bufs:
                        self._ad_bufs[lvl, rp] = (buf(lvl, rp), buf(lvl, rp))
                    if (si, lvl) not in self._gates:
                        self._gates[si, lvl] = torch.zeros(res[lvl][0] * res[lvl][1], device=dev, dtype=torch.float32)
            self._sites = {}
            for (style, site) in self._adapters:
                self._sites.setdefault(site, []).append(style)
        self._L = L
        self._model_blocks = (list(model.enc), list(model.mid), list(model.dec))
        self._plans = {}               # active styles -> per-step launch lists
        self._graphs = {}              # active styles -> CUDA graph

    @staticmethod
    def _level_of(model, b) -> int:
        for i, lvl in enumerate(model.enc):
            if any(x is b for x in lvl):
                return i
        for i, lvl in enumerate(model.dec):
            if any(x is b for x in lvl):
                return i
        return len(model.down)

    # ---------------------------------------------------------------- plan
    def _plan(self, active: tuple) -> list:
        if active in self._plans:
            return self._plans[active]
        enc, dec, tmp_enc, tmp_dec = self._bufs
        L, n_lvl = self._L, self.n_lvl
        menc, mmid, mdec = self._model_blocks
        plan = []

        def adapters_at(site) -> list:
            return [s for s in self._sites.get(site, []) if s in active]

        def block(b, x, tmp, encoder: bool, site, silu_out: bool):
            """x = (raw, silu) of the level. silu_out: something downstream reads silu(h) of this block's output."""
            ads = adapters_at(site)
            if encoder:
                plan.append(lambda s, x=x: C.make_pixnorm(x[0], x[1]))
            c0, c1 = L[id(b), 0], L[id(b), 1]
            tab = self._film[id(b)]
            plan.append(lambda s, c0=c0, x=x, tmp=tmp, tab=tab: c0(x[1], film=tab[s], out_silu=tmp))
            last_silu = x[1] if (silu_out and not ads) else None
            plan.append(lambda s, c1=c1, x=x, tmp=tmp, o=last_silu: c1(
                tmp, res=x[0], res_scale=self._rs_x, clip=self.clip, out_raw=x[0], out_silu=o))
            for k, style in enumerate(ads):
                dn, md, up, rp = self._adapters[style, site]
                a1, a2 = self._ad_bufs[site[1], rp]
                g = self._gates[D2.STYLES.index(style), site[1]]
                o = x[1] if (silu_out and k == len(ads) - 1) else None
                plan.append(lambda s, dn=dn, x=x, a1=a1: dn(x[0], out_silu=a1))
                plan.append(lambda s, md=md, a1=a1, a2=a2: md(a1, out_silu=a2))
                plan.append(lambda s, up=up, x=x, a2=a2, g=g, o=o: up(a2, res=x[0], gate=g, out_raw=x[0], out_silu=o))

        plan.append(lambda s: L["stem"](self.stem_in, out_raw=enc[0][0]))
        for i in range(n_lvl):
            for bi, b in enumerate(menc[i]):
                block(b, enc[i], tmp_enc[i], True, ("enc", i, bi), silu_out=bi == len(menc[i]) - 1)
            plan.append(lambda s, i=i: L["down", i](enc[i][1], out_raw=enc[i + 1][0],
                                                    out_silu=enc[i + 1][1] if i + 1 == n_lvl else None))
        for bi, b in enumerate(mmid):
            block(b, enc[n_lvl], tmp_enc[n_lvl], False, ("mid", n_lvl, bi), silu_out=True)
        for i in reversed(range(n_lvl)):
            src = enc[n_lvl][1] if i == n_lvl - 1 else dec[i + 1][1]
            wq, bq, cfg = self._upmerge[i]
            plan.append(lambda s, src=src, i=i, wq=wq, bq=bq, cfg=cfg: C.make_conv(
                src, wq, bq, cfg, mode=1, x2=enc[i][0], out_raw=dec[i][0], out_silu=dec[i][1]))
            for bi, b in enumerate(mdec[i]):
                block(b, dec[i], tmp_dec[i], False, ("dec", i, bi), silu_out=True)
        plan.append(lambda s: C.make_hconv(dec[0][1], self.out_w, self.out_b, self.out_cfg,
                                           euler=(self.xs, self.stem_in, self.dts[s])))
        steps = [[op(s) for op in plan] for s in range(self.steps)]
        C.bind([lc for st in steps for lc in st], self.dev)
        self._plans[active] = steps
        return steps

    # ---------------------------------------------------------------- run
    def active_for(self, gates: torch.Tensor | None) -> tuple | None:
        """Styles whose gate is > 0 anywhere in the window (Detail2.forward's rule), or None if one of them is
        not packed here."""
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
                # thread_local: the service's other threads may touch CUDA while the GPU thread captures.
                with torch.cuda.graph(g, stream=stream, capture_error_mode="thread_local"):
                    self._run(active)
            torch.cuda.current_stream(self.dev).wait_stream(stream)
            self._graphs[active] = g

    def _load(self, cond: torch.Tensor, noise: torch.Tensor, gates: torch.Tensor | None, active: tuple) -> None:
        x0 = (1.0 - self.t_start) * noise.float() if self.t_start > 0 else noise.float()
        self.xs.copy_(x0.reshape(-1))
        self.stem_in[..., 0] = x0[0, 0].to(torch.float16)
        self.stem_in[..., 1 : 1 + self.n_cond] = cond[0].permute(1, 2, 0).to(torch.float16)
        for style in active:
            si = D2.STYLES.index(style)
            g = gates[:, si : si + 1].float()
            for (gi, lvl), buf in self._gates.items():
                if gi != si:
                    continue
                hl, wl = self.res[lvl]
                p = F.adaptive_avg_pool2d(g, (hl, wl)) if g.shape[-1] != wl else g
                buf.copy_(p.reshape(-1))

    @torch.no_grad()
    def __call__(self, cond: torch.Tensor, noise: torch.Tensor, gates: torch.Tensor | None = None):
        """cond (1, n_cond, H, W), noise (1, 1, H, W), gates (1, len(STYLES), H, W) or None -> x (1, 1, H, W) fp32,
        like `detail2.sample(..., mean_flow=...)`; None if an active expert is not packed here."""
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
    def eager(self, cond: torch.Tensor, noise: torch.Tensor, gates: torch.Tensor | None = None):
        """The same computation launched op by op (no graph): tests and the tuner."""
        active = self.active_for(gates)
        if active is None:
            return None
        with torch.cuda.device(self.dev):
            self._load(cond, noise, gates, active)
            self._run(active)
            return self.xs.view(1, 1, self.h, self.w).clone()
