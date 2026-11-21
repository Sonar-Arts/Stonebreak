"""Export DaedalusTGM networks to the Cenda inference engine's model format (.cim; OpenMason
openmason-engine/cenda/native/kernels/include/cenda/infer.h, design: Dev Working/docs/DaedalusTGM-Exp-2-Native-Engine.md).

    python -m terrain_slm.export.cenda_export checkpoints/exp2/world_exp2b --out /tmp/world_exp2b [--device cuda:1]
                                                                   [--only detail relief sr30 climate planner]

(The generator backend, export/cenda_backend.py, exports and caches the models itself; this CLI is for inspection.)
The planner has no Python native sampler: `_PlannerPlan` lowers it onto the engine's own fp32 transformer kernels
(OpenMason .../cenda/native/kernels/src/infer/cuda/ci_vit.cu), embedded like the csrc kernels.

How: the project's own native samplers (kernels/cuda_detail2.py for Detail2 and, through a Detail2 view, SR;
kernels/cuda_unet.py for the v4-style relief UNet; `_ClimatePlan` here for the climate node) already lower a network
to a static plan of kernel launches (csrc/conv.cu, hconv.cu, pixnorm.cu) over preallocated buffers, with every weight folded and packed in fp16
exactly once. The exporter builds that sampler, RECORDS its launches (cuda_conv.Launch: kernel, grid, block, shared
memory, by-value argument struct) and turns every pointer in them into a relocation -- against a weight tensor (any
storage the plan only reads) or an activation buffer (any storage it writes, plus the declared inputs). The engine
replays that plan bit for bit: same packed weights, same kernel sources (embedded in the file, compiled by NVRTC with
kernels/native.py's options), same launches. The .cim therefore carries the numbers of the Python sampler it was
recorded from, and a parity test can demand bitwise equality.

Generalisations the engine adds (it knows them from the file, not from kernel knowledge):
  * batch: the field that counts pixels (conv `M`, hconv `B`, pixnorm `P`) is multiplied by the batch and the grid
    follows (`scale` rule); buffers are per-window and the kernels index windows contiguously, so B neighbouring
    windows run in one launch with the numbers each window gets alone (fixed K order, no split-K);
  * gated experts: ONE plan for every packed expert, each launch tagged `when` (its expert must be active) and `drop`
    (its SiLU output is dropped while a later adapter at the same site is active) -- the engine derives the exact
    launch list of kernels/cuda_detail2.py's per-subset plan for any expert subset.

File layout: b"CENDAINF", u32 format (1), u32 0, u64 manifest bytes, manifest JSON, then (256-aligned) the blob
region (weights and kernel sources, each 256-aligned; manifest offsets are relative to the region).
"""
from __future__ import annotations

import ctypes
import json
import math
import re
import os
import struct
from pathlib import Path

import torch

from terrain_slm.kernels import cuda_conv as C
from terrain_slm.kernels import native

FORMAT = 1
MAGIC = b"CENDAINF"
ALIGN = 256

_CTYPE = {ctypes.c_uint64: "ptr", ctypes.c_int64: "i64", ctypes.c_int32: "i32", ctypes.c_float: "f32"}


def _layout(struct_cls) -> dict:
    fields = []
    for name, ct in struct_cls._fields_:
        fields.append([name, _CTYPE[ct], getattr(struct_cls, name).offset])
    return {"size": ctypes.sizeof(struct_cls), "fields": fields}


_LAYOUTS = {C.ConvArgs: "conv_args", C.HConvArgs: "hconv_args", C.PixNormArgs: "pixnorm_args"}
F_SILU = C.F_SILU


def _storage(t: torch.Tensor):
    s = t.untyped_storage()
    return s.data_ptr(), s.nbytes(), s


class PlanRecorder:
    """Launches -> manifest ops with relocations. `buffers`: storages the engine owns per window (activations and
    inputs); every other storage a launch points at is a read-only weight."""

    def __init__(self):
        self.buffers: list = []           # (ptr, nbytes, storage, name)
        self.tensors: list = []           # (ptr, nbytes, storage)
        self.ops: list = []
        self.sources: dict[str, str] = {}
        self.layouts: dict[str, dict] = {}

    # ------------------------------------------------------------------ storages
    def buffer(self, t: torch.Tensor, name: str = "") -> int:
        ptr, n, s = _storage(t)
        for i, b in enumerate(self.buffers):
            if b[0] == ptr:
                return i
        self.buffers.append((ptr, n, s, name))
        return len(self.buffers) - 1

    def _find(self, p: int):
        for i, (bp, n, _s, _nm) in enumerate(self.buffers):
            if bp <= p < bp + n:
                return {"b": i, "o": p - bp}
        for i, (tp, n, _s) in enumerate(self.tensors):
            if tp <= p < tp + n:
                return {"t": i, "o": p - tp}
        return None

    def _weight(self, t: torch.Tensor) -> None:
        ptr, n, s = _storage(t)
        for bp, bn, _s, _nm in self.buffers:
            if bp <= ptr < bp + bn:
                return
        for tp, tn, _s in self.tensors:
            if tp == ptr:
                return
        self.tensors.append((ptr, n, s))

    # ------------------------------------------------------------------ launches
    def add(self, lc: C.Launch, when: int = 0, drop_unless: int = 0) -> None:
        """One recorded launch. Storages the launch writes become buffers before any pointer is resolved."""
        args = lc.args
        lname = getattr(lc, "layout", None) or _LAYOUTS[type(args)]
        self.layouts[lname] = _layout(type(args))
        src = getattr(lc, "source", "conv")
        if src not in self.sources:
            self.sources[src] = getattr(lc, "source_text", None) or native._read_source(native.CSRC / f"{src}.cu")
        keep = [t for t in lc._keep if isinstance(t, torch.Tensor)]
        if hasattr(lc, "writes"):
            written = set(lc.writes)
        else:
            written = {args.out_raw, args.out_silu} if hasattr(args, "out_raw") else {args.raw, args.out_silu}
            if getattr(args, "xs", 0):
                written |= {args.xs, args.stem_in}
        for t in keep:
            if t.data_ptr() in written and t.data_ptr() != 0:
                self.buffer(t)
        for t in keep:
            self._weight(t)
        a = {}
        for name, ct in type(args)._fields_:
            v = getattr(args, name)
            if ct is ctypes.c_uint64:
                if v == 0:
                    a[name] = None
                else:
                    r = self._find(v)
                    if r is None:
                        raise RuntimeError(f"pointer {name}={v:#x} of {lc.name} is not in any recorded storage")
                    a[name] = r
            elif ct is ctypes.c_float:
                a[name] = float(v)
            else:
                a[name] = int(v)
        op = {"src": src, "name": lc.name, "layout": lname, "grid": list(lc.grid) + [1] * (3 - len(lc.grid)),
              "block": int(lc.block), "smem": int(lc.smem), "args": a}
        op["scale"] = lc.scale if hasattr(lc, "scale") else self._scale_rule(lc)
        if when:
            op["when"] = int(when)
        if hasattr(lc, "write_fields"):
            op["writes"] = list(lc.write_fields)
        if drop_unless and isinstance(args, (C.ConvArgs, C.HConvArgs)) and (args.flags & F_SILU):
            op["drop"] = {"unless": int(drop_unless), "ptr": "out_silu", "flags": "flags", "bit": F_SILU}
        self.ops.append(op)

    @staticmethod
    def _scale_rule(lc: C.Launch) -> dict:
        a = lc.args
        if isinstance(a, C.ConvArgs):
            bm, bn = (int(x) for x in re.match(r"conv_kernel<(\d+),(\d+),", lc.name).groups())
            tiles = a.NP // bn
            assert lc.grid[0] == -(-a.M // bm) * tiles, (lc.name, lc.grid, a.M)
            return {"field": "M", "rule": "ceil", "tile": bm, "tiles": tiles}
        if isinstance(a, C.HConvArgs):
            assert a.B == 1, "record plans at batch 1"
            return {"field": "B", "rule": "linear"}
        if isinstance(a, C.PixNormArgs):
            assert lc.grid[0] == -(-a.P // 8)
            return {"field": "P", "rule": "ceil", "tile": 8, "tiles": 1}
        raise TypeError(type(a))

    def check_writes(self) -> None:
        """Every pointer a launch writes through must be a buffer (else a weight was misclassified)."""
        for op in self.ops:
            for f in ("out_raw", "out_silu", "xs", "stem_in", "raw") + tuple(op.get("writes", ())):
                r = op["args"].get(f)
                if r is not None and "b" not in r:
                    raise RuntimeError(f"{op['name']} writes {f} into a weight tensor")

    def buffers_from(self, obj, name: str = "") -> None:
        """Register every tensor in a nested tuple / list / dict as a buffer."""
        if isinstance(obj, torch.Tensor):
            self.buffer(obj, name)
        elif isinstance(obj, dict):
            for v in obj.values():
                self.buffers_from(v, name)
        elif isinstance(obj, (tuple, list)):
            for v in obj:
                self.buffers_from(v, name)

    # ------------------------------------------------------------------ file
    def write(self, path: Path, name: str, model_id: str, io: dict, styles=(), extra: dict | None = None,
              fills: dict | None = None) -> dict:
        self.check_writes()
        blobs, off = [], 0

        def blob(data: bytes) -> dict:
            nonlocal off
            ref = {"offset": off, "nbytes": len(data)}
            blobs.append(data)
            pad = (-len(data)) % ALIGN
            if pad:
                blobs.append(b"\0" * pad)
            off += len(data) + pad
            return ref

        tensors = []
        for _p, n, s in self.tensors:
            tensors.append(blob(_storage_bytes(s)))
        sources = {k: blob(v.encode()) for k, v in sorted(self.sources.items())}
        buffers = []
        fills = fills or {}
        for i, (_p, n, _s, nm) in enumerate(self.buffers):
            b = {"nbytes": int(n)}
            if nm:
                b["name"] = nm
            if i in fills:
                b["fill"] = fills[i]
            buffers.append(b)
        manifest = {"format": FORMAT, "name": name, "model_id": model_id, "kind": io["kind"], "styles": list(styles),
                    "layouts": self.layouts, "sources": sources, "tensors": tensors, "buffers": buffers, "io": io,
                    "ops": self.ops}
        if extra:
            manifest["meta"] = extra
        mj = json.dumps(manifest, separators=(",", ":")).encode()
        head = MAGIC + struct.pack("<IIQ", FORMAT, 0, len(mj)) + mj
        head += b"\0" * ((-len(head)) % ALIGN)
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + ".tmp")
        with open(tmp, "wb") as f:
            f.write(head)
            for b in blobs:
                f.write(b)
        tmp.replace(path)
        return {"path": str(path), "bytes": path.stat().st_size, "ops": len(self.ops), "tensors": len(tensors),
                "buffers": len(buffers), "weight_bytes": sum(n for _p, n, _s in self.tensors)}


def _storage_bytes(s) -> bytes:
    t = torch.empty(0, dtype=torch.uint8, device=s.device)
    t.set_(s)
    return t.cpu().numpy().tobytes()


def _fill_ones(stem: torch.Tensor, channel: int) -> list:
    """Buffer fill for a constant channel of an NHWC fp16 tensor (Detail2 / SR / climate stems' ones channel)."""
    val = float(stem.reshape(-1, stem.shape[-1])[0, channel])
    bits = int(torch.tensor([val], dtype=torch.float16).view(torch.int16).item()) & 0xFFFF
    return [{"stride": int(stem.shape[-1]), "at": int(channel), "dtype": "f16", "bits": bits}]


def _check_constant_channels(stem: torch.Tensor, n_written: int) -> list[int]:
    """Channels of the stem input that are non-zero before any load (constant channels to fill)."""
    flat = stem.reshape(-1, stem.shape[-1]).float()
    out = []
    for c in range(n_written, stem.shape[-1]):
        col = flat[:, c]
        if bool((col != 0).any()):
            assert bool((col == col[0]).all()), f"stem channel {c} is not constant"
            out.append(c)
    return out


# =============================================================================== Detail2 (gated experts, MeanFlow)
def export_detail2(model, h: int, w: int, steps: int, t_start: float, path: Path, model_id: str = "",
                   mean_flow: bool | None = None, clip: bool = True, name: str = "detail2") -> dict:
    """Detail2 (+ every expert the model carries) for (h, w) windows -> .cim. The plan is kernels/cuda_detail2.py's,
    recorded with every expert active and tagged so the engine can drop any subset (see module doc). `clip` False
    drops the +-256 activation clip (F_CLIP) from every launch: an MP UNet whose blocks do not clip (SR)."""
    from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler
    from terrain_slm.models import detail2 as D2

    ns = NativeDetail2Sampler(model, h, w, steps, t_start, mean_flow=mean_flow)
    bit = {s: i for i, s in enumerate(ns.experts)}
    rec = PlanRecorder()
    enc, dec, tmp_enc, tmp_dec = ns._bufs
    rec.buffer(ns.stem_in, "stem_in")
    rec.buffer(ns.xs, "xs")
    for (si, lvl), g in ns._gates.items():
        rec.buffer(g, f"gate_{D2.STYLES[si]}_{lvl}")
    rec.buffers_from(ns._bufs)
    rec.buffers_from(ns._ad_bufs)
    L, n_lvl = ns._L, ns.n_lvl
    menc, mmid, mdec = ns._model_blocks
    plan = []   # (s -> Launch, when, drop_unless): cuda_detail2._plan with every expert, tagged

    def block(b, x, tmp, encoder: bool, site, silu_out: bool, raw_out: bool = True):
        ads = list(ns._sites.get(site, []))
        if encoder:
            plan.append((lambda s, x=x: C.make_pixnorm(x[0], x[1]), 0, 0))
        c0, c1 = L[id(b), 0], L[id(b), 1]
        tab = ns._film[id(b)]
        plan.append((lambda s, c0=c0, x=x, tmp=tmp, tab=tab: c0(x[1], film=tab[s], out_silu=tmp), 0, 0))
        o = x[1] if silu_out else None
        site_mask = sum(1 << bit[st] for st in ads)
        r_out = x[0] if (raw_out or ads) else None   # K4: the sampler skips the dead raw store of a level's last block
        plan.append((lambda s, c1=c1, x=x, tmp=tmp, o=o, r_out=r_out: c1(tmp, res=x[0], res_scale=ns._rs_x, clip=True,
                                                                         out_raw=r_out, out_silu=o), 0, site_mask))
        for k, style in enumerate(ads):
            dn, md, up, rp = ns._adapters[style, site]
            a1, a2 = ns._ad_bufs[site[1], rp]
            g = ns._gates[D2.STYLES.index(style), site[1]]
            wb = 1 << bit[style]
            later = sum(1 << bit[st] for st in ads[k + 1:])
            plan.append((lambda s, dn=dn, x=x, a1=a1: dn(x[0], out_silu=a1), wb, 0))
            plan.append((lambda s, md=md, a1=a1, a2=a2: md(a1, out_silu=a2), wb, 0))
            plan.append((lambda s, up=up, x=x, a2=a2, g=g, o=o: up(a2, res=x[0], gate=g, out_raw=x[0], out_silu=o),
                         wb, later))

    plan.append((lambda s: L["stem"](ns.stem_in, out_raw=enc[0][0]), 0, 0))
    for i in range(n_lvl):
        for bi, b in enumerate(menc[i]):
            block(b, enc[i], tmp_enc[i], True, ("enc", i, bi), silu_out=bi == len(menc[i]) - 1)
        plan.append((lambda s, i=i: L["down", i](enc[i][1], out_raw=enc[i + 1][0],
                                                  out_silu=enc[i + 1][1] if i + 1 == n_lvl else None), 0, 0))
    for bi, b in enumerate(mmid):
        block(b, enc[n_lvl], tmp_enc[n_lvl], False, ("mid", n_lvl, bi), silu_out=True, raw_out=bi < len(mmid) - 1)
    for i in reversed(range(n_lvl)):
        src = enc[n_lvl][1] if i == n_lvl - 1 else dec[i + 1][1]
        wq, bq, cfg = ns._upmerge[i]
        mk = C.make_uconv if len(cfg) == 6 else (lambda *a, **k: C.make_conv(*a, mode=1, **k))   # K4 generation 2
        plan.append((lambda s, src=src, i=i, wq=wq, bq=bq, cfg=cfg, mk=mk: mk(
            src, wq, bq, cfg, x2=enc[i][0], out_raw=dec[i][0], out_silu=dec[i][1]), 0, 0))
        for bi, b in enumerate(mdec[i]):
            block(b, dec[i], tmp_dec[i], False, ("dec", i, bi), silu_out=True, raw_out=bi < len(mdec[i]) - 1)
    plan.append((lambda s: C.make_hconv(dec[0][1], ns.out_w, ns.out_b, ns.out_cfg,
                                        euler=(ns.xs, ns.stem_in, ns.dts[s])), 0, 0))
    for s in range(steps):
        for op, when, drop in plan:
            rec.add(op(s), when, drop)
    if not clip:
        for op in rec.ops:
            if "flags" in op["args"]:
                op["args"]["flags"] &= ~C.F_CLIP

    gates = []
    for (si, lvl), g in ns._gates.items():
        style = D2.STYLES[si]
        gates.append({"bit": bit[style], "channel": si, "buffer": rec.buffer(g), "factor": h // ns.res[lvl][0]})
    stem_b = rec.buffer(ns.stem_in)
    ones = _check_constant_channels(ns.stem_in, 1 + ns.n_cond)
    assert ones == [1 + ns.n_cond], ones
    io = {"kind": "flow", "H": h, "W": w, "n_cond": ns.n_cond,
          "stem": {"buffer": stem_b, "channels": int(ns.stem_in.shape[-1]), "x0": 0, "cond": 1},
          "xs": rec.buffer(ns.xs), "x0_scale": float(torch.tensor(1.0 - t_start, dtype=torch.float32)),
          "apply_scale": t_start > 0, "gate_channels": len(D2.STYLES), "gates": gates,
          "output": {"buffer": rec.buffer(ns.xs), "kind": "f32", "channels": 1}}
    meta = {"network": name, "steps": steps, "t_start": t_start, "mean_flow": bool(ns.mean_flow),
            "experts": list(ns.experts), "dts": ns.dts, "clip": clip, "recorded_from": "kernels/cuda_detail2.py"}
    return rec.write(path, name, model_id, io, styles=ns.experts, extra=meta,
                     fills={stem_b: _fill_ones(ns.stem_in, ones[0])})


# =============================================================================== SR node (MP UNet, FM)
def sr_as_detail2(sr):
    """models/sr.py SR viewed as a Detail2 (same EDM2 block, level and merge structure; models/detail2.py) so
    kernels/cuda_detail2.py can lower it: SR's modules are shared (not copied), its time embedding
    mp_silu(emb(fourier(t))) replaces Detail2.embed, no experts. Differences the export handles: SR blocks do not clip
    (export with clip=False); res_balance 0.3 and concat 0.5 are SR's constants."""
    from terrain_slm.models import detail2 as D2
    from terrain_slm.models.mp import mp_silu

    cfg = D2.Detail2Config(channels=tuple(sr.cfg.channels), blocks=sr.cfg.blocks, mid_blocks=len(sr.mid),
                           emb=sr.cfg.emb, fourier=sr.cfg.fourier, dropout=0.0, res_balance=0.3, concat_balance=0.5)
    d = D2.Detail2(cfg).to(next(sr.parameters()).device).eval()
    for k in ("stem", "enc", "down", "mid", "up", "merge", "dec", "out"):
        setattr(d, k, getattr(sr, k))
    d.out_gain = sr.out_gain
    d.embed = lambda t, r=None: mp_silu(sr.emb(sr.fourier(t)))
    d.mean_flow = False
    return d


def export_sr(sr, h: int, w: int, steps: int, path: Path, model_id: str = "") -> dict:
    return export_detail2(sr_as_detail2(sr), h, w, steps, 0.0, path, model_id, mean_flow=False, clip=False, name="sr")


# =============================================================================== climate node (MP UNet, regression)
class _ClimatePlan:
    """models/climatenet.py ClimateNet lowered onto csrc/conv.cu + hconv.cu + pixnorm.cu with kernels/cuda_detail2.py's
    folding rules (MPConv effective weights, mp_silu gain into the reading conv, mp_sum weights into conv1 + F_RSCALE,
    mp_cat + 1x1 merge into the phase up-merge). A ClimateNet block normalises only the conv path
    (mp_sum(x, conv1(silu(conv0(silu(pixel_norm(x))))))), while the pixnorm kernel normalises in place, so each block
    first copies x with an identity 1x1 conv (exact in fp16). Output conv: 7 channels padded to 8, fp16."""

    def __init__(self, model, h: int, w: int):
        from terrain_slm.kernels import cuda_detail2 as KD
        from terrain_slm.kernels.cuda_unet import STEM_PAD
        from terrain_slm.models.mp import SILU_GAIN as sg

        dev = next(model.parameters()).device
        self.dev, self._configs, self.layer_keys = dev, {}, []
        ch = list(model.cfg.channels)
        n_lvl = len(model.down)
        assert h % (1 << n_lvl) == 0 and w % (1 << n_lvl) == 0
        self.n_in = model.cfg.n_in
        assert self.n_in + 1 <= STEM_PAD
        res = [(h >> i, w >> i) for i in range(n_lvl + 1)]
        hf = dict(device=dev, dtype=torch.float16)
        buf = lambda i, c=None: torch.zeros(1, res[i][0], res[i][1], c or ch[i], **hf)
        self.stem_in = torch.zeros(1, h, w, STEM_PAD, **hf)
        self.stem_in[..., self.n_in] = 1.0
        lv = [dict(raw=buf(i), silu=buf(i), n=buf(i), ns=buf(i), t=buf(i)) for i in range(n_lvl + 1)]
        dec = [dict(raw=buf(i), silu=buf(i), n=lv[i]["n"], ns=lv[i]["ns"], t=lv[i]["t"]) for i in range(n_lvl)]
        self.out_buf = torch.zeros(1, h, w, 8, **hf)
        self.bufs = (lv, dec, self.out_buf)
        rb = 0.3
        rs_x, rs_y = (1 - rb) / math.sqrt((1 - rb) ** 2 + rb ** 2), rb / math.sqrt((1 - rb) ** 2 + rb ** 2)
        ew = lambda m, gain=1.0: m.effective_weight(gain).double()
        Conv = KD._Conv
        launches = []
        eye = {}

        def copy(x, out, lvl):
            c = x.shape[-1]
            if c not in eye:
                eye[c] = Conv(self, "copy", torch.eye(c, dtype=torch.float64, device=dev)[:, :, None, None], res[lvl][0])
            launches.append(eye[c](x, out_raw=out))

        def block(b, x, lvl, silu_out):
            hh = res[lvl][0]
            copy(x["raw"], x["n"], lvl)
            launches.append(C.make_pixnorm(x["n"], x["ns"]))
            c0 = Conv(self, "c1", ew(b.conv0) * sg, hh)
            c1 = Conv(self, "c2", ew(b.conv1) * (sg * rs_y), hh)
            launches.append(c0(x["ns"], out_silu=x["t"]))
            launches.append(c1(x["t"], res=x["raw"], res_scale=rs_x, out_raw=x["raw"],
                               out_silu=x["silu"] if silu_out else None))

        launches.append(Conv(self, "stem", ew(model.stem), h, cin_pad=STEM_PAD)(self.stem_in, out_raw=lv[0]["raw"]))
        for i in range(n_lvl):
            for bi, b in enumerate(model.enc[i]):
                block(b, lv[i], i, bi == len(model.enc[i]) - 1)
            launches.append(Conv(self, "down", ew(model.down[i]) * sg, res[i][0], stride=2)(
                lv[i]["silu"], out_raw=lv[i + 1]["raw"]))
        for bi, b in enumerate(model.mid):
            block(b, lv[n_lvl], n_lvl, bi == len(model.mid) - 1)
        ct = 0.5
        for i in reversed(range(n_lvl)):
            src = lv[n_lvl]["silu"] if i == n_lvl - 1 else dec[i + 1]["silu"]
            c = ch[i]
            up, mg = model.up[i], model.merge[i]
            wm = ew(mg)[:, :, 0, 0]
            k = math.sqrt((c + c) / ((1 - ct) ** 2 + ct ** 2))
            wa, wb = wm[:, :c] * (k / math.sqrt(c) * (1 - ct)), wm[:, c:] * (k / math.sqrt(c) * ct)
            comp = torch.einsum("oc,cikl->oikl", wa, ew(up) * sg)
            ph = C._phase_weights(comp)
            clow = up.weight.shape[1]
            lo = res[i + 1][0]
            key = ("upmerge", clow, c, 2, 1, lo)
            cfg = KD._config(key, lo * lo, c, 1, 4 * clow + c, dev)
            np_ = C.round_up(c, cfg[1])
            wq = C._finish(torch.cat([torch.cat([ph[p].reshape(4 * clow, c), wb.t()]) for p in range(4)]).float(), np_)
            launches.append(C.make_conv(src, wq, torch.zeros(np_, device=dev), cfg, mode=1, x2=lv[i]["raw"],
                                        out_raw=dec[i]["raw"]))
            for bi, b in enumerate(model.dec[i]):
                block(b, dec[i], i, bi == len(model.dec[i]) - 1)
        wo = ew(model.out, model.out_gain) * sg
        wo = torch.cat([wo, wo.new_zeros(8 - wo.shape[0], *wo.shape[1:])])
        launches.append(Conv(self, "out", wo, h)(dec[0]["silu"], out_raw=self.out_buf))
        self.launches = launches
        self.n_out = model.out.weight.shape[0]
        C.bind(launches, dev)

    @torch.no_grad()
    def __call__(self, x: torch.Tensor) -> torch.Tensor:
        """(1, >= n_in, H, W) -> (1, n_out, H, W) fp32 (the Python-side reference of the recorded plan)."""
        self.stem_in[..., : self.n_in] = x[0, : self.n_in].permute(1, 2, 0).to(torch.float16)
        stream = torch.cuda.current_stream(self.dev).cuda_stream
        for lc in self.launches:
            lc(stream)
        return self.out_buf[..., : self.n_out].permute(0, 3, 1, 2).float()


def export_climate(model, h: int, w: int, path: Path, model_id: str = "") -> dict:
    cp = _ClimatePlan(model, h, w)
    rec = PlanRecorder()
    stem_b = rec.buffer(cp.stem_in, "stem_in")
    rec.buffers_from(cp.bufs)
    for lc in cp.launches:
        rec.add(lc)
    out_b = rec.buffer(cp.out_buf)
    io = {"kind": "forward", "H": h, "W": w, "n_in": cp.n_in,
          "stem": {"buffer": stem_b, "channels": int(cp.stem_in.shape[-1]), "cond": 0},
          "output": {"buffer": out_b, "kind": "nhwc_f16", "channels": cp.n_out, "stride": 8}}
    meta = {"network": "climate", "recorded_from": "terrain_slm/export/cenda_export.py _ClimatePlan"}
    return rec.write(path, "climate", model_id, io, extra=meta, fills={stem_b: _fill_ones(cp.stem_in, cp.n_in)})


# =============================================================================== planner (ViT, fp32)
def cenda_source(name: str) -> str:
    """An engine kernel source from the Cenda checkout (OpenMason openmason-engine/cenda/native/kernels/src/infer/cuda)
    -- embedded in the model like the csrc kernels."""
    from terrain_slm.export.cenda_engine import _CENDA
    return (_CENDA / "native" / "kernels" / "src" / "infer" / "cuda" / f"{name}.cu").read_text()


class GemmArgs(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in ("A", "B", "bias", "R", "C")] + [
        (n, ctypes.c_int64) for n in ("lda", "ldb", "ldc", "ldr", "sA1", "sA2", "sB1", "sB2", "sC1", "sC2", "sR1",
                                      "sR2")] + [(n, ctypes.c_int32) for n in ("M", "N", "K", "zdiv", "act")] + [
        ("alpha", ctypes.c_float)]


class LayerNormArgs(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in ("x", "w", "b", "y")] + [("rows", ctypes.c_int32), ("D", ctypes.c_int32),
                                                                        ("eps", ctypes.c_float)]


class RopeArgs(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in ("qkv", "cos_t", "sin_t", "q", "k", "v")] + [
        (n, ctypes.c_int32) for n in ("B", "N", "H", "hd")]


class SoftmaxArgs(ctypes.Structure):
    _fields_ = [("x", ctypes.c_uint64), ("rows", ctypes.c_int64), ("L", ctypes.c_int32)]


class PatchArgs(ctypes.Structure):
    _fields_ = [("src", ctypes.c_uint64), ("dst", ctypes.c_uint64)] + [
        (n, ctypes.c_int32) for n in ("B", "C", "Hp", "Wp", "P", "dir")]


class AttnArgs(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in ("q", "k", "v", "o")] + [
        (n, ctypes.c_int32) for n in ("B", "H", "N", "ldo")] + [("scale", ctypes.c_float)]


class KLaunch:
    """A launch of an engine kernel (ci_vit.cu) for the recorder: what cuda_conv.Launch carries plus the pointers it
    writes and its batch rule."""

    def __init__(self, name, grid, block, args, keep, writes, scale, layout, smem=0):
        self.name, self.grid, self.block, self.smem, self.args = name, tuple(grid), block, smem, args
        self._keep, self.layout, self.scale = keep, layout, scale
        self.writes = {getattr(args, f) for f in writes}
        self.write_fields = tuple(writes)
        self.source = "ci_vit"
        self.source_text = cenda_source("ci_vit")


def _p(t):
    return 0 if t is None else t.data_ptr()


class _PlannerPlan:
    """models/planner.py Planner (pre-norm ViT, axial RoPE, patch tokens) as engine launches, batch 1 window."""

    def __init__(self, model, h: int, w: int, attention: str = "fused", gemm_kind: str = "tf32x3"):
        from terrain_slm.models import planner as P
        dev = next(model.parameters()).device
        cfg = model.cfg
        p, D, H = cfg.patch, cfg.dim, cfg.heads
        hd = D // H
        hp, wp = h // p, w // p
        T = hp * wp
        n_in, n_out = model.embed.in_features // (p * p), model.head.out_features // (p * p)
        f32 = dict(device=dev, dtype=torch.float32)
        self.x_in = torch.zeros(1, n_in, h, w, **f32)
        self.out = torch.zeros(1, n_out, h, w, **f32)
        tok_in, tok_out = torch.zeros(T, n_in * p * p, **f32), torch.zeros(T, n_out * p * p, **f32)
        X, Hn, O = torch.zeros(T, D, **f32), torch.zeros(T, D, **f32), torch.zeros(T, D, **f32)
        QKV, F_ = torch.zeros(T, 3 * D, **f32), torch.zeros(T, cfg.mlp_ratio * D, **f32)
        q, k, v = (torch.zeros(H, T, hd, **f32) for _ in range(3))
        S = torch.zeros(H, T, T, **f32) if attention != "fused" else torch.zeros(1, **f32)
        self.bufs = (self.x_in, self.out, tok_in, tok_out, X, Hn, O, QKV, F_, q, k, v, S)
        cos, sin = P._rope_tables(hp, wp, hd, dev)
        self.weights = [cos.contiguous(), sin.contiguous()]
        wt = lambda t: self.weights.append(t.detach().float().contiguous()) or self.weights[-1]
        L = []

        def gemm(A, Bm, C_, M, N, K, bias=None, R=None, act=0, nt=True, alpha=1.0, lda=None, ldb=None, ldc=None,
                 z=1, zdiv=1, s=(0,) * 8, batched=False):
            a = GemmArgs(A=_p(A), B=_p(Bm), bias=_p(bias), R=_p(R), C=_p(C_), lda=lda or K,
                         ldb=ldb or (K if nt else N), ldc=ldc or N, ldr=N if R is not None else 0,
                         M=M, N=N, K=K, zdiv=zdiv, act=act, alpha=alpha)
            for name, val in zip(("sA1", "sA2", "sB1", "sB2", "sC1", "sC2", "sR1", "sR2"), s):
                setattr(a, name, val)
            grid = (-(-N // 128), -(-M // 128), z)
            scale = {"rule": "linear", "dim": 2} if batched else {"field": "M", "rule": "ceil", "tile": 128,
                                                                  "tiles": 1, "dim": 1}
            tc = gemm_kind == "tf32x3" and nt and K % 16 == 0 and a.lda % 4 == 0 and a.ldb % 4 == 0
            name = "ci_gemm_tf32x3" if tc else f"ci_sgemm<{1 if nt else 0}>"
            L.append(KLaunch(name, grid, 256, a, [A, Bm, bias, R, C_], ("C",), scale, "gemm_args"))

        def layernorm(x, ln, y):
            a = LayerNormArgs(x=_p(x), w=_p(wt(ln.weight)), b=_p(wt(ln.bias)), y=_p(y), rows=T, D=D, eps=ln.eps)
            L.append(KLaunch("ci_layernorm", (-(-T // 8), 1, 1), 256, a, [x, y] + self.weights[-2:], ("y",),
                             {"field": "rows", "rule": "ceil", "tile": 8, "tiles": 1}, "layernorm_args"))

        def patches(src, dst, C, direction):
            a = PatchArgs(src=_p(src), dst=_p(dst), B=1, C=C, Hp=hp, Wp=wp, P=p, dir=direction)
            per = hp * wp * C * p * p
            L.append(KLaunch("ci_patches", (-(-per // 256), 1, 1), 256, a, [src, dst], ("dst",),
                             {"field": "B", "rule": "ceil", "tile": 256, "tiles": 1, "mul": per}, "patch_args"))

        patches(self.x_in, tok_in, n_in, 0)
        gemm(tok_in, wt(model.embed.weight), X, T, D, n_in * p * p, bias=wt(model.embed.bias))
        half = hd // 2
        for blk in model.blocks:
            layernorm(X, blk.norm1, Hn)
            gemm(Hn, wt(blk.qkv.weight), QKV, T, 3 * D, D, bias=wt(blk.qkv.bias))
            ra = RopeArgs(qkv=_p(QKV), cos_t=_p(self.weights[0]), sin_t=_p(self.weights[1]), q=_p(q), k=_p(k), v=_p(v),
                          B=1, N=T, H=H, hd=hd)
            per = T * 3 * H * half
            L.append(KLaunch("ci_rope_split", (-(-per // 256), 1, 1), 256, ra, [QKV, q, k, v] + self.weights[:2],
                             ("q", "k", "v"), {"field": "B", "rule": "ceil", "tile": 256, "tiles": 1, "mul": per},
                             "rope_args"))
            if attention == "fused":                  # flash-style, the scores never reach memory
                assert hd == 64, "ci_attention64 needs head_dim 64"
                aa = AttnArgs(q=_p(q), k=_p(k), v=_p(v), o=_p(O), B=1, H=H, N=T, ldo=D, scale=1.0 / math.sqrt(hd))
                L.append(KLaunch("ci_attention64", (-(-T // 64), H, 1), 256, aa, [q, k, v, O], ("o",),
                                 {"field": "B", "rule": "linear", "dim": 1}, "attn_args", smem=4 * 64 * 65 * 4))
            else:
                # S = q k^T / sqrt(hd) per (window, head); z = window * H + head
                gemm(q, k, S, T, T, hd, nt=True, alpha=1.0 / math.sqrt(hd), lda=hd, ldb=hd, ldc=T, z=H, zdiv=1,
                     s=(T * hd, 0, T * hd, 0, T * T, 0, 0, 0), batched=True)
                sa = SoftmaxArgs(x=_p(S), rows=H * T, L=T)
                L.append(KLaunch("ci_softmax", (-(-(H * T) // 8), 1, 1), 256, sa, [S], ("x",),
                                 {"field": "rows", "rule": "ceil", "tile": 8, "tiles": 1}, "softmax_args"))
                # O[(b, n), h * hd + d] = sum_m P[b, h, n, m] v[b, h, m, d]
                gemm(S, v, O, T, hd, T, nt=False, lda=T, ldb=hd, ldc=D, z=H, zdiv=H,
                     s=(H * T * T, T * T, H * T * hd, T * hd, T * D, hd, 0, 0), batched=True)
            gemm(O, wt(blk.proj.weight), X, T, D, D, bias=wt(blk.proj.bias), R=X)
            layernorm(X, blk.norm2, Hn)
            gemm(Hn, wt(blk.fc1.weight), F_, T, cfg.mlp_ratio * D, D, bias=wt(blk.fc1.bias), act=1)
            gemm(F_, wt(blk.fc2.weight), X, T, D, cfg.mlp_ratio * D, bias=wt(blk.fc2.bias), R=X)
        layernorm(X, model.norm, Hn)
        gemm(Hn, wt(model.head.weight), tok_out, T, n_out * p * p, D, bias=wt(model.head.bias))
        patches(tok_out, self.out, n_out, 1)
        self.launches, self.n_in, self.n_out, self.T, self.H = L, n_in, n_out, T, H


def export_planner(model, h: int, w: int, path: Path, model_id: str = "", attention: str = "fused",
                   gemm: str = "tf32x3") -> dict:
    """The planner as fp32 transformer launches (ci_vit.cu). `gemm`: "tf32x3" (tensor cores, fp32-level accuracy via
    hi / lo tf32 splitting) or "simt" (plain fp32 FMA); `attention`: "fused" (flash-style) or "gemm" (scores in
    memory)."""
    pp = _PlannerPlan(model, h, w, attention, gemm)
    rec = PlanRecorder()
    rec.buffers_from(pp.bufs)
    # The batched attention GEMMs index windows through their z strides: per-window buffers must be laid out
    # window-major, which they are (each buffer is one window; the engine stacks windows contiguously).
    for lc in pp.launches:
        rec.add(lc)
    io = {"kind": "tensor", "H": h, "W": w, "n_in": pp.n_in,
          "stem": {"buffer": rec.buffer(pp.x_in), "channels": pp.n_in},
          "output": {"buffer": rec.buffer(pp.out), "kind": "f32", "channels": pp.n_out}}
    meta = {"network": "planner", "precision": "fp32", "attention": attention, "gemm": gemm, "recorded_from": "terrain_slm/export/cenda_export.py _PlannerPlan"}
    return rec.write(path, "planner", model_id, io, extra=meta)


# =============================================================================== hydro sidecar (plain conv UNet, regression)
class _HydroPlan:
    """models/hydro.py Hydro (stem, residual blocks x + conv2(silu(conv1(silu(x)))), stride-2 downs, nearest-up conv +
    3x3 merge of cat(up, skip), output conv; plain convs with biases) on csrc/conv.cu + hconv.cu: kernels/cuda_unet.py's
    lowering without FiLM / Euler. Channels are zero-padded to the kernels' multiples (32): zero weights and biases keep
    the padded channels exactly 0 (SiLU(0) = 0), so the real channels' numbers are unchanged. Output: 11 channels
    padded to 16, fp16."""

    def __init__(self, model, h: int, w: int):
        from terrain_slm.kernels import cuda_unet as KU
        from terrain_slm.kernels.cuda_unet import STEM_PAD

        dev = next(model.parameters()).device
        self.dev = dev
        rup = lambda c: C.round_up(c, 32)
        ch = [m.out_channels for m in [model.stem] + list(model.down)]
        cp = [rup(c) for c in ch]
        n_lvl = len(model.down)
        assert h % (1 << n_lvl) == 0 and w % (1 << n_lvl) == 0
        self.n_in = model.stem.in_channels
        assert self.n_in <= STEM_PAD
        res = [(h >> i, w >> i) for i in range(n_lvl + 1)]
        hf = dict(device=dev, dtype=torch.float16)
        buf = lambda i, c=None: torch.zeros(1, res[i][0], res[i][1], c or cp[i], **hf)
        self.stem_in = torch.zeros(1, h, w, STEM_PAD, **hf)
        enc = [(buf(i), buf(i)) for i in range(n_lvl + 1)]          # (raw, silu)
        tmp = [buf(i) for i in range(n_lvl + 1)]
        up = [buf(i) for i in range(n_lvl)]
        dec = [(buf(i), buf(i)) for i in range(n_lvl)]
        self.n_out = model.out.out_channels
        self.out_buf = torch.zeros(1, h, w, C.round_up(self.n_out, 16), **hf)
        self.bufs = (enc, tmp, up, dec, self.out_buf)

        def pad(wt, co, ci):                                       # (CO, CI, k, k) -> zero-padded, fp32
            out = torch.zeros(co, ci, *wt.shape[2:], device=dev, dtype=torch.float32)
            out[: wt.shape[0], : wt.shape[1]] = wt.detach().float()
            return out

        def bias(b, n):
            return C.pad_bias(b, n)

        L = []

        def conv(kind, conv_m, x, hh, co, ci, stride=1, ks=3, **kw):
            ho = (hh + stride - 1) // stride
            key = (kind, ci, co, ks, stride, ho)
            cfg = KU._config(key, ho * ho, co, 0, ks * ks * ci, dev)
            wt = pad(conv_m.weight, co, ci)
            if len(cfg) == 6:
                np_ = C.round_up(co, cfg[2])
                L.append(C.make_hconv(x, C.pack_hconv(wt, np_), bias(conv_m.bias, np_), cfg, **kw))
            else:
                np_ = C.round_up(co, cfg[1])
                L.append(C.make_conv(x, C.pack_conv(wt, np_), bias(conv_m.bias, np_), cfg, ks=ks, stride=stride, **kw))

        def block(r, x, lvl):
            hh = res[lvl][0]
            conv("c1", r.conv1, x[1], hh, cp[lvl], cp[lvl], out_silu=tmp[lvl])
            conv("c2", r.conv2, tmp[lvl], hh, cp[lvl], cp[lvl], res=x[0], out_raw=x[0], out_silu=x[1])

        stem_w = pad(model.stem.weight, cp[0], STEM_PAD)
        key = ("stem", STEM_PAD, cp[0], 3, 1, h)
        cfg = KU._config(key, h * w, cp[0], 0, 9 * STEM_PAD, dev)
        np_ = C.round_up(cp[0], cfg[2] if len(cfg) == 6 else cfg[1])
        if len(cfg) == 6:
            L.append(C.make_hconv(self.stem_in, C.pack_hconv(stem_w, np_), bias(model.stem.bias, np_), cfg,
                                  out_raw=enc[0][0], out_silu=enc[0][1]))
        else:
            L.append(C.make_conv(self.stem_in, C.pack_conv(stem_w, np_), bias(model.stem.bias, np_), cfg,
                                 out_raw=enc[0][0], out_silu=enc[0][1]))
        for i in range(n_lvl):
            for r in model.enc[i]:
                block(r, enc[i], i)
            conv("down", model.down[i], enc[i][1], res[i][0], cp[i + 1], cp[i], stride=2,
                 out_raw=enc[i + 1][0], out_silu=enc[i + 1][1])
        for r in model.mid:
            block(r, enc[n_lvl], n_lvl)
        for i in reversed(range(n_lvl)):
            src = enc[n_lvl][1] if i == n_lvl - 1 else dec[i + 1][1]
            lo = res[i + 1][0]
            upc, mg = model.up[i], model.merge[i]
            key = ("up", cp[i + 1], cp[i], 2, 1, lo)
            cfg = KU._config(key, lo * lo, cp[i], 1, 4 * cp[i + 1], dev)
            np_ = C.round_up(cp[i], cfg[1])
            L.append(C.make_conv(src, C.pack_up(pad(upc.weight, cp[i], cp[i + 1]), np_), bias(upc.bias, np_), cfg,
                                 mode=1, out_raw=up[i]))
            # merge over cat(up (ch[i] real of cp[i]), skip (ch[i] real of cp[i])): place both halves in padded slots
            wm = torch.zeros(cp[i], 2 * cp[i], 3, 3, device=dev)
            c = ch[i]
            wm[:c, :c] = mg.weight.detach().float()[:, :c]
            wm[:c, cp[i] : cp[i] + c] = mg.weight.detach().float()[:, c:]
            key = ("merge", 2 * cp[i], cp[i], 3, 1, res[i][0])
            cfg = KU._config(key, res[i][0] * res[i][1], cp[i], 0, 9 * 2 * cp[i], dev)
            if len(cfg) == 6:                                      # merge is never a halo layer (two inputs)
                cfg = C.default_config(res[i][0] * res[i][1], cp[i], 0, 9 * 2 * cp[i])
            np_ = C.round_up(cp[i], cfg[1])
            L.append(C.make_conv(up[i], C.pack_concat(wm, cp[i], np_), bias(mg.bias, np_), cfg, ks=3, x2=enc[i][0],
                                 out_raw=dec[i][0], out_silu=dec[i][1]))
            for r in model.dec[i]:
                block(r, dec[i], i)
        co = self.out_buf.shape[-1]
        key = ("hout", cp[0], co, 3, 1, h)
        cfg = C.default_config(h * w, co, 0, 9 * cp[0])
        np_ = C.round_up(co, cfg[1])
        L.append(C.make_conv(dec[0][1], C.pack_conv(pad(model.out.weight, co, cp[0]), np_), bias(model.out.bias, np_),
                             cfg, out_raw=self.out_buf))
        self.launches = L
        C.bind(L, dev)

    @torch.no_grad()
    def __call__(self, x: torch.Tensor) -> torch.Tensor:
        self.stem_in[..., : self.n_in] = x[0, : self.n_in].permute(1, 2, 0).to(torch.float16)
        stream = torch.cuda.current_stream(self.dev).cuda_stream
        for lc in self.launches:
            lc(stream)
        return self.out_buf[..., : self.n_out].permute(0, 3, 1, 2).float()


def export_hydro(model, h: int, w: int, path: Path, model_id: str = "") -> dict:
    hp = _HydroPlan(model, h, w)
    rec = PlanRecorder()
    stem_b = rec.buffer(hp.stem_in, "stem_in")
    rec.buffers_from(hp.bufs)
    for lc in hp.launches:
        rec.add(lc)
    io = {"kind": "forward", "H": h, "W": w, "n_in": hp.n_in,
          "stem": {"buffer": stem_b, "channels": int(hp.stem_in.shape[-1]), "cond": 0},
          "output": {"buffer": rec.buffer(hp.out_buf), "kind": "nhwc_f16", "channels": hp.n_out,
                     "stride": int(hp.out_buf.shape[-1])}}
    meta = {"network": "hydro", "recorded_from": "terrain_slm/export/cenda_export.py _HydroPlan"}
    return rec.write(path, "hydro", model_id, io, extra=meta)


# =============================================================================== v4-style UNet (relief; MeanFlow)
class _MeanFlowTemb(torch.nn.Module):
    """Stands in for `model.temb` while kernels/cuda_unet.NativeSampler builds its FiLM tables: the sampler asks for
    temb(phi(t_i)) of its own time grid once; a MeanFlow relief needs e(t_i, t_{i+1}) = model.embed(t, r) instead
    (models/relief.py). Everything else in the sampler is the teacher's."""

    def __init__(self, model, ts: torch.Tensor, steps: int):
        super().__init__()
        self.model, self.ts, self.steps = model, ts, steps

    def forward(self, phi: torch.Tensor) -> torch.Tensor:
        from terrain_slm.models.refiner import timestep_embedding
        t = self.ts[: self.steps].float()
        assert torch.equal(phi, timestep_embedding(t, self.model.cfg.temb).float()), "unexpected time grid"
        del self.model.__dict__["temb"]                       # the real temb while embed() runs
        try:
            return self.model.embed(t, self.ts[1:].float())
        finally:
            object.__setattr__(self.model, "temb", self)


def unet_sampler(model, h: int, w: int, steps: int, t_start: float = 0.0, mean_flow: bool | None = None):
    """kernels/cuda_unet.NativeSampler for a v4-style UNet; a MeanFlow relief gets e(t, r) FiLM tables."""
    from terrain_slm.kernels.cuda_unet import NativeSampler

    import inspect
    mf = bool(getattr(model, "mean_flow", False)) if mean_flow is None else mean_flow
    if not mf or "model.embed(" in inspect.getsource(NativeSampler.__init__):
        return NativeSampler(model, h, w, steps, t_start)          # FM, or a sampler that builds e(t, r) itself
    ts = torch.linspace(t_start, 1.0, steps + 1, device=next(model.parameters()).device)
    object.__setattr__(model, "temb", _MeanFlowTemb(model, ts, steps))   # bypass Module registration: restored below
    try:
        return NativeSampler(model, h, w, steps, t_start)
    finally:
        model.__dict__.pop("temb", None)                      # back to the registered module


def export_unet(model, h: int, w: int, steps: int, t_start: float, path: Path, model_id: str = "",
                name: str = "relief") -> dict:
    """A v4-style flow UNet (models/relief.py Relief, models/detail.py Detail) on kernels/cuda_unet.py's sampler.
    A MeanFlow relief (ReliefConfig.mean_flow) has the same convolutions; only its per-step FiLM tables differ, which
    the sampler builds from `model.embed(t, r)` (kernels/cuda_unet.py, mean_flow)."""
    ns = unet_sampler(model, h, w, steps, t_start)
    rec = PlanRecorder()
    rec.buffer(ns.stem_in, "stem_in")
    rec.buffer(ns.xs, "xs")
    rec.buffers_from(ns._bufs)
    for st in ns._steps:
        for lc in st:
            rec.add(lc)
    stem_b = rec.buffer(ns.stem_in)
    consts = _check_constant_channels(ns.stem_in, 1 + ns.n_cond)
    assert not consts, consts
    io = {"kind": "flow", "H": h, "W": w, "n_cond": ns.n_cond,
          "stem": {"buffer": stem_b, "channels": int(ns.stem_in.shape[-1]), "x0": 0, "cond": 1},
          "xs": rec.buffer(ns.xs), "x0_scale": float(torch.tensor(1.0 - t_start, dtype=torch.float32)),
          "apply_scale": t_start > 0, "gate_channels": 0, "gates": [],
          "output": {"buffer": rec.buffer(ns.xs), "kind": "f32", "channels": 1}}
    meta = {"network": name, "steps": steps, "t_start": t_start,
            "mean_flow": bool(getattr(model, "mean_flow", False)), "recorded_from": "kernels/cuda_unet.py"}
    return rec.write(path, name, model_id, io, extra=meta)


# =============================================================================== command line
def main(argv=None) -> None:
    import argparse

    from terrain_slm.world import generator as G

    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("model_dir", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--only", nargs="*", default=None, help="detail relief sr30 climate planner")
    args = ap.parse_args(argv)
    from terrain_slm.world import generator2 as G2
    gen = G2.WorldGenerator2(args.model_dir, 0, args.device)
    want = lambda k: args.only is None or k in args.only
    res = {}
    with torch.no_grad():
        if want("detail") and gen.detail is not None:
            res["detail"] = export_detail2(gen.detail, G.DETAIL_EXT, G.DETAIL_EXT, gen.cfg.detail_steps,
                                           G.DETAIL_T_START, args.out / "detail.cim", gen.model_id)
        if want("relief") and gen.relief is not None:
            res["relief"] = export_unet(gen.relief, G.RELIEF_EXT, G.RELIEF_EXT, gen.cfg.relief_steps, 0.0,
                                        args.out / "relief.cim", gen.model_id)
        if want("sr30") and getattr(gen, "sr30", None) is not None:
            n = G2.SR_CORE + 2 * G2.SR_APRON
            res["sr30"] = export_sr(gen.sr30, n, n, G2.SR_STEPS, args.out / "sr30.cim", gen.model_id)
        if want("climate") and getattr(gen, "climate_net", None) is not None:
            res["climate"] = export_climate(gen.climate_net, G2.CLIMATE_WIN, G2.CLIMATE_WIN, args.out / "climate.cim",
                                            gen.model_id)
        if want("planner"):
            res["planner"] = export_planner(gen.planner, G.WIN, G.WIN, args.out / "planner.cim", gen.model_id)
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()


# =============================================================================== Detail2 at FP8 (agent "detail", phase 3)
# kernels/cuda_detail2_fp8.py's plan (e4m3 W8A8 csrc/conv_fp8.cu launches + the fp16 layers of the student's QAD record)
# recorded for the engine. The FP8 sampler builds a DIFFERENT launch list per active-expert subset (which launch writes
# the SiLU a consumer reads, which writes the e4m3 raw copy an adapter / up-merge reads), so the export records the
# sampler's own plan for EVERY subset and merges them into one tagged op list: an op runs when its `when` experts are
# all active (the engine's rule), a write a subset does not want is dropped by the engine's `drop` rule when "drop if any
# of these experts is active" describes it, else kept as an extra write of the same value (an e4m3 / SiLU copy of the
# current raw that nothing reads before it is rewritten). `_verify_fp8_merge` then replays, for every subset, exactly
# the launch list the engine will derive (same kernels, same buffers) against the sampler's own plan on random inputs and
# demands bitwise equal outputs, so a merge that is not exact can never ship (the backend then keeps the generator's own
# FP8 path).
_LAYOUTS8 = {}


def _flat_fields(cls, prefix: str = "", base: int = 0) -> list:
    """(name, ctype, offset) of every scalar field of a ctypes struct; nested structs flatten as 'outer.inner'."""
    out = []
    for name, ct in cls._fields_:
        off = base + getattr(cls, name).offset
        if isinstance(ct, type) and issubclass(ct, ctypes.Structure):
            out += _flat_fields(ct, prefix + name + ".", off)
        else:
            out.append((prefix + name, ct, off))
    return out


def _flat_get(args, path: str):
    for p in path.split("."):
        args = getattr(args, p)
    return args


def _flat_set(args, path: str, v) -> None:
    *head, last = path.split(".")
    for p in head:
        args = getattr(args, p)
    setattr(args, last, v)


def _layout_name8(args) -> str:
    from terrain_slm.kernels import cuda_conv_fp8 as C8
    t = type(args)
    if t in _LAYOUTS:
        return _LAYOUTS[t]
    return {C8.Conv8Args: "conv8_args", C8.HConv8Args: "hconv8_args", C8.PixNorm8Args: "pixnorm8_args",
            C8.Quant8Args: "quant8_args"}[t]


def _scale_rule8(lc) -> dict:
    from terrain_slm.kernels import cuda_conv_fp8 as C8
    a = lc.args
    if isinstance(a, C8.Conv8Args):
        bm, bn = (int(x) for x in re.match(r"conv8_kernel<(\d+),(\d+),", lc.name).groups())
        tiles = a.NP // bn
        assert lc.grid[0] == -(-a.M // bm) * tiles, (lc.name, lc.grid, a.M)
        return {"field": "M", "rule": "ceil", "tile": bm, "tiles": tiles}
    if isinstance(a, C8.HConv8Args):
        assert a.B == 1, "record plans at batch 1"
        return {"field": "B", "rule": "linear"}
    if isinstance(a, C8.PixNorm8Args):
        assert lc.grid[0] == -(-a.P // 8)
        return {"field": "P", "rule": "ceil", "tile": 8, "tiles": 1}
    if isinstance(a, C8.Quant8Args):
        assert lc.grid[0] == -(-a.n8 // 256)
        return {"field": "n8", "rule": "ceil", "tile": 256, "tiles": 1}
    return PlanRecorder._scale_rule(lc)


# Fields a subset may set differently from another (the written SiLU / e4m3 raw copies, their scales, the flag word).
_VARY8 = {"e.out_silu": ("e.flags", 16), "e.out_silu8": ("e.flags", 512), "e.out_raw8": ("e.flags", 1024),
          "out_silu": ("flags", 4)}
_VARY8_SCALAR = {"e.qs": "e.out_silu8", "e.qs_raw": "e.out_raw8"}
_WRITES8 = ("out_raw", "out_silu", "e.out_raw", "e.out_silu", "e.out_silu8", "e.out_raw8", "e.xs", "e.stem_in",
            "out_silu8", "xs", "stem_in", "y")


def _op_fields(rec: PlanRecorder, lc) -> tuple[str, dict, list]:
    """(layout name, {field: value | relocation}, written fields) of one launch."""
    lname = _layout_name8(lc.args)
    flat = _flat_fields(type(lc.args))
    rec.layouts[lname] = {"size": ctypes.sizeof(type(lc.args)), "fields": [[n, _CTYPE[ct], off] for n, ct, off in flat]}
    src = getattr(lc, "source", "conv")
    if src not in rec.sources:
        rec.sources[src] = native._read_source(native.CSRC / f"{src}.cu")
    for t in lc._keep:
        if isinstance(t, torch.Tensor):
            rec._weight(t)
    a, writes = {}, []
    for name, ct, _off in flat:
        v = _flat_get(lc.args, name)
        if ct is ctypes.c_uint64:
            if v == 0:
                a[name] = None
            else:
                r = rec._find(v)
                if r is None:
                    raise RuntimeError(f"pointer {name}={v:#x} of {lc.name} is not in any recorded storage")
                a[name] = r
                if name in _WRITES8:
                    writes.append(name)
        elif ct is ctypes.c_float:
            a[name] = float(v)
        else:
            a[name] = int(v)
    return lname, a, writes


def _signature(lc, a: dict) -> tuple:
    keep = tuple((k, json.dumps(v, sort_keys=True)) for k, v in a.items()
                 if k not in _VARY8 and k not in _VARY8_SCALAR and k not in ("e.flags", "flags"))
    return lc.name, tuple(lc.grid), int(lc.block), int(lc.smem), keep


def _merge_fp8(ns, rec: PlanRecorder) -> tuple[list, list]:
    """Every subset's plan of the FP8 sampler `ns` -> one tagged op list (see the section comment). Returns (ops, info):
    info[j] = (full-plan launch, {field: base raw value}, when, drop) for the verifier."""
    experts = ns.experts
    n = len(experts)
    subsets = [(m, tuple(experts[i] for i in range(n) if m >> i & 1)) for m in range(1 << n)]
    plans = {m: [lc for st in ns._plan(act) for lc in st] for m, act in subsets}
    full_mask = (1 << n) - 1
    # Every activation storage the plans touch is a buffer before any pointer is resolved (reads before writes).
    rec.buffer(ns.stem_in, "stem_in")
    rec.buffer(ns.xs, "xs")
    for (si, lvl), g in ns._gates.items():
        rec.buffer(g, f"gate_{D2_STYLES_()[si]}_{lvl}")
    slots = list(ns.S) + list(ns.T_enc) + list(ns.T_dec) + list(ns._R8) + list(ns._Q8)
    slots += [b for v in ns._ad_bufs.values() for b in v if b is not None]
    rec.buffers_from([ns.A, ns.B])
    for b in slots:
        rec.buffers_from(list(b._t.values()))
    rec.buffers_from(list(getattr(ns, "_q8", {}).values()))
    resolved = {m: [(lc,) + _op_fields(rec, lc) for lc in plans[m]] for m, _ in subsets}
    full = resolved[full_mask]
    fsig = [_signature(lc, a) for lc, _l, a, _w in full]
    member = [dict() for _ in full]             # op j -> {mask: (lc, args)}
    for m, _ in subsets:
        k = 0
        for lc, _l, a, _w in resolved[m]:
            s = _signature(lc, a)
            while k < len(full) and fsig[k] != s:
                k += 1
            if k == len(full):
                raise RuntimeError(f"FP8 plan of expert subset {m:#x}: {lc.name} has no counterpart in the full plan")
            member[k][m] = (lc, a)
            k += 1
    ops, info = [], []
    all_masks = [m for m, _ in subsets]
    for j, (lc_full, lname, a_full, _w) in enumerate(full):
        mem = member[j]
        when = full_mask
        for m in mem:
            when &= m
        if set(mem) != {m for m in all_masks if m & when == when}:
            raise RuntimeError(f"FP8 op {j} ({lc_full.name}) runs in expert subsets the engine's `when` rule cannot express")
        base = dict(a_full)
        raw = {}
        for f, (ff, bit) in _VARY8.items():
            if f not in base:
                continue
            nz = {}
            for m, (lc, a) in mem.items():
                if a[f] is not None:
                    nz[_flat_get(lc.args, f)] = a[f]
            if len(nz) > 1:
                raise RuntimeError(f"FP8 op {j} ({lc_full.name}) writes {f} to different places per subset")
            if nz:
                raw[f], base[f] = next(iter(nz.items()))
        for f, ptr in _VARY8_SCALAR.items():
            if f not in base:
                continue
            vs = {a[f] for (lc, a) in mem.values() if a.get(ptr) is not None}
            if len(vs) > 1:
                raise RuntimeError(f"FP8 op {j}: {f} differs per subset")
            if vs:
                base[f] = vs.pop()
                raw[f] = base[f]
        flag_f = "e.flags" if "e.flags" in base else "flags" if "flags" in base else None
        fl = 0
        for (lc, a) in mem.values():
            fl |= a[flag_f] if flag_f else 0
        if flag_f:
            base[flag_f] = fl
            raw[flag_f] = fl
        # Which subsets do NOT write each varying field: a drop rule when "any of U active" says it, else extra write.
        drop = None
        for f, (ff, bit) in _VARY8.items():
            if base.get(f) is None:
                continue
            zero = {m for m, (lc, a) in mem.items() if a[f] is None}
            if not zero:
                continue
            u = 0
            for b in range(n):
                with_b = [m for m in mem if m >> b & 1]
                if with_b and all(m in zero for m in with_b):
                    u |= 1 << b
            if drop is None and u and zero == {m for m in mem if m & u}:
                drop = {"unless": int(u), "ptr": f, "flags": ff, "bit": int(bit)}
        op = {"src": getattr(lc_full, "source", "conv"), "name": lc_full.name, "layout": lname,
              "grid": list(lc_full.grid) + [1] * (3 - len(lc_full.grid)), "block": int(lc_full.block),
              "smem": int(lc_full.smem), "args": base,
              "scale": lc_full.scale if hasattr(lc_full, "scale") else _scale_rule8(lc_full)}   # K4: uconv / uconv8
        if when:
            op["when"] = int(when)
        if drop is not None:
            op["drop"] = drop
        wf = [f for f in _WRITES8 if base.get(f) is not None]
        if wf:
            op["writes"] = wf
        ops.append(op)
        info.append((lc_full, raw, when, drop))
    return ops, info


def D2_STYLES_():
    from terrain_slm.models import detail2 as D2
    return D2.STYLES


def _derived_launches(info: list, mask: int) -> list:
    """The launch list the engine derives for expert `mask` from the merged ops, as Python launches over the sampler's
    own buffers (the engine's rule: `when` all active; `drop` zeroes the pointer and its flag bit if any is active)."""
    out = []
    for lc, raw, when, drop in info:
        if when & mask != when:
            continue
        args = type(lc.args).from_buffer_copy(lc.args)
        for f, v in raw.items():
            _flat_set(args, f, v)
        if drop is not None and mask & drop["unless"]:
            _flat_set(args, drop["ptr"], 0)
            _flat_set(args, drop["flags"], _flat_get(args, drop["flags"]) & ~drop["bit"])
        x = C.Launch(lc.name, lc.grid, lc.block, lc.smem, args)
        x.module = lc.module
        out.append(x)
    return out


def _verify_fp8_merge(ns, info: list, seed: int = 7) -> None:
    """Every subset: the derived launch list == the sampler's own plan, bitwise, on random inputs (raises if not)."""
    from terrain_slm.models import detail2 as D2
    dev, h, w = ns.dev, ns.h, ns.w
    g = torch.Generator(device=dev).manual_seed(seed)
    n = len(ns.experts)
    stream = torch.cuda.current_stream(dev).cuda_stream
    for m in range(1 << n):
        act = tuple(ns.experts[i] for i in range(n) if m >> i & 1)
        cond = torch.randn(1, ns.n_cond, h, w, device=dev, generator=g)
        noise = torch.randn(1, 1, h, w, device=dev, generator=g)
        gates = torch.zeros(1, len(D2.STYLES), h, w, device=dev)
        for s in act:
            gates[0, D2.STYLES.index(s), : h // 2 + 7] = torch.rand(h // 2 + 7, w, device=dev, generator=g) + 0.05
        assert ns.active_for(gates) == act, (act, ns.active_for(gates))
        outs = []
        for launches in (ns._plan(act), [_derived_launches(info, m)]):
            ns._load(cond, noise, gates, act)
            for st in launches:
                for lc in st:
                    lc(stream)
            outs.append(ns.xs.clone())
        if not torch.equal(outs[0].view(torch.int32), outs[1].view(torch.int32)):
            d = float((outs[0] - outs[1]).abs().nan_to_num(float("inf")).max())
            raise RuntimeError(f"FP8 merged plan differs from the sampler's for experts {act} (max |d| {d:.3g})")


def export_detail2_fp8(model, h: int, w: int, steps: int, t_start: float, path: Path, model_id: str = "",
                       mean_flow: bool | None = None, name: str = "detail2_fp8", verify: bool = True) -> dict:
    """The FP8 Detail2 (kernels/cuda_detail2_fp8.NativeDetail2SamplerFP8 on `model`, an FP8 QAD student carrying
    `fp8_qconfig`) for (h, w) windows -> .cim, every expert subset in one tagged plan (see the section comment). Same io
    as export_detail2 (the engine's flow program: stem / xs / gates loading is the samplers' shared `_load`)."""
    from terrain_slm.kernels.cuda_detail2_fp8 import NativeDetail2SamplerFP8
    from terrain_slm.models import detail2 as D2

    ns = NativeDetail2SamplerFP8(model, h, w, steps, t_start, mean_flow=mean_flow)
    rec = PlanRecorder()
    ops, info = _merge_fp8(ns, rec)
    if verify:
        _verify_fp8_merge(ns, info)
    rec.ops = ops
    bit = {s: i for i, s in enumerate(ns.experts)}
    gates = []
    for (si, lvl), g in ns._gates.items():
        style = D2.STYLES[si]
        gates.append({"bit": bit[style], "channel": si, "buffer": rec.buffer(g), "factor": h // ns.res[lvl][0]})
    stem_b = rec.buffer(ns.stem_in)
    ones = _check_constant_channels(ns.stem_in, 1 + ns.n_cond)
    assert ones == [1 + ns.n_cond], ones
    io = {"kind": "flow", "H": h, "W": w, "n_cond": ns.n_cond,
          "stem": {"buffer": stem_b, "channels": int(ns.stem_in.shape[-1]), "x0": 0, "cond": 1},
          "xs": rec.buffer(ns.xs), "x0_scale": float(torch.tensor(1.0 - t_start, dtype=torch.float32)),
          "apply_scale": t_start > 0, "gate_channels": len(D2.STYLES), "gates": gates,
          "output": {"buffer": rec.buffer(ns.xs), "kind": "f32", "channels": 1}}
    qc = ns.qc
    meta = {"network": name, "steps": steps, "t_start": t_start, "mean_flow": bool(ns.mean_flow),
            "experts": list(ns.experts), "dts": ns.dts, "fp8": {"act_scale": ns.act_scale,
            "fp16_layers": sorted(k for k, v in qc.layers.items() if v == "fp16")},
            "drops": sum(1 for o in ops if "drop" in o), "verified": bool(verify),
            "recorded_from": "kernels/cuda_detail2_fp8.py"}
    return rec.write(path, name, model_id, io, styles=ns.experts, extra=meta,
                     fills={stem_b: _fill_ones(ns.stem_in, ones[0])})


# =============================================================================== biome sidecar (models/biomenet.py)
# Agent "tiles" (native-engine phase 3, 2026-11-19): BiomeNet as an fp32 NCHW "tensor" program on its own kernels
# (BIOME_SOURCE, embedded in the .cim), one model per frame size (the tile frame: tile + 2 * BLOCK_MARGIN blocks at
# 1:4, half / a quarter of it at 1:2 / 1:1). Both the Python generator (cenda_backend routes gen.biomenet to it) and
# libstonebreak_terrain (stages/tiles, BN.classify) run the same plan, so their biome ids agree bit for bit; against
# torch (cuDNN TF32 convolutions) the logits differ at ~1e-5, the classes only at near-ties.
BIOME_SOURCE = r"""
// BiomeNet (models/biomenet.py) fp32 NCHW kernels, batch 1. Direct 3x3 / 1x1 convolutions over one or two
// concatenated inputs (the first optionally nearest-upsampled, torch's nearest index), ChannelNorm + SiLU.
struct BiomeConvArgs {
    const float* a;          // (CA, HA, WA): nearest-upsampled to (H, W) when up
    const float* b;          // (CB, H, W) or null
    const float* w;          // (CO, CA + CB, k, k)
    const float* bias;       // (CO)
    const float* res;        // (CO, Ho, Wo) or null: y = res + act(conv)  (SurfaceNet's residual trunk)
    float* y;                // (CO, Ho, Wo)
    int CA, HA, WA, CB, H, W, CO, Ho, Wo, k, stride, pad, act, up;
};
__device__ __forceinline__ float bn_silu(float v) { return v / (1.0f + expf(-v)); }
__device__ __forceinline__ int bn_nearest(int o, int in, int out) {
    if (in == out) return o;
    if (out == 2 * in) return o >> 1;
    float s = (float)in / (float)out;
    int i = (int)floorf((float)o * s);
    return i < in - 1 ? i : in - 1;
}
#define BN_CO 8
extern "C" __global__ void __launch_bounds__(256) biome_conv(const BiomeConvArgs a) {
    const long long pix = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    const int co0 = blockIdx.y * BN_CO;
    if (pix >= (long long)a.Ho * a.Wo) return;
    const int oy = (int)(pix / a.Wo), ox = (int)(pix - (long long)oy * a.Wo);
    const int CI = a.CA + a.CB, kk = a.k * a.k;
    float acc[BN_CO];
#pragma unroll
    for (int q = 0; q < BN_CO; ++q) acc[q] = (co0 + q < a.CO) ? a.bias[co0 + q] : 0.0f;
    for (int ky = 0; ky < a.k; ++ky) {
        const int iy = oy * a.stride - a.pad + ky;
        if (iy < 0 || iy >= a.H) continue;
        for (int kx = 0; kx < a.k; ++kx) {
            const int ix = ox * a.stride - a.pad + kx;
            if (ix < 0 || ix >= a.W) continue;
            long long ia;
            if (a.up) ia = (long long)bn_nearest(iy, a.HA, a.H) * a.WA + bn_nearest(ix, a.WA, a.W);
            else ia = (long long)iy * a.WA + ix;
            const long long ib = (long long)iy * a.W + ix;
            const int tap = ky * a.k + kx;
            for (int ci = 0; ci < CI; ++ci) {
                const float v = ci < a.CA ? a.a[(long long)ci * a.HA * a.WA + ia]
                                          : a.b[(long long)(ci - a.CA) * a.H * a.W + ib];
                const float* wp = a.w + ((long long)co0 * CI + ci) * kk + tap;
#pragma unroll
                for (int q = 0; q < BN_CO; ++q)
                    if (co0 + q < a.CO) acc[q] = fmaf(v, __ldg(wp + (long long)q * CI * kk), acc[q]);
            }
        }
    }
    const long long plane = (long long)a.Ho * a.Wo;
#pragma unroll
    for (int q = 0; q < BN_CO; ++q)
        if (co0 + q < a.CO) {
            const long long o = (long long)(co0 + q) * plane + pix;
            const float v = a.act ? bn_silu(acc[q]) : acc[q];
            a.y[o] = a.res ? a.res[o] + v : v;
        }
}
// ChannelNorm (LayerNorm over channels per pixel, affine) + SiLU, in place.
struct BiomeNormArgs {
    float* x;                // (C, P)
    const float* w;          // (C)
    const float* b;          // (C)
    long long P;
    int C;
};
extern "C" __global__ void biome_norm_silu(const BiomeNormArgs a) {
    const long long p = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    if (p >= a.P) return;
    float s = 0.0f;
    for (int c = 0; c < a.C; ++c) s += a.x[(long long)c * a.P + p];
    const float mu = s / (float)a.C;
    float v = 0.0f;
    for (int c = 0; c < a.C; ++c) {
        const float d = a.x[(long long)c * a.P + p] - mu;
        v += d * d;
    }
    const float r = rsqrtf(v / (float)a.C + 1e-5f);
    for (int c = 0; c < a.C; ++c) {
        const long long i = (long long)c * a.P + p;
        a.x[i] = bn_silu((a.x[i] - mu) * r * a.w[c] + a.b[c]);
    }
}
// biome_conv_t<BN>: biome_conv's numbers (bias, then taps ky-major, then input channels ascending, one fmaf each,
// out-of-image taps skipped), as a register-tiled implicit GEMM: a block computes BT_BM output pixels x BN output
// channels, staging BT_BK input channels of one tap (A) and their weights (B) in shared memory; each thread owns
// TM = 8 pixels x TN = BN / 16 channels. Same accumulation order per output => bitwise biome_conv.
#define BT_BM 128
#define BT_BK 16
template <int BN>
__global__ void __launch_bounds__(256) biome_conv_t(const BiomeConvArgs a) {
    constexpr int TN = BN / 16;
    constexpr int TM = BT_BM / 16;
    __shared__ float As[BT_BK][BT_BM];
    __shared__ float Bs[BT_BK][BN];
    const int tid = threadIdx.x, tx = tid & 15, ty = tid >> 4;
    const long long P = (long long)a.Ho * a.Wo;
    const long long p0 = (long long)blockIdx.x * BT_BM;
    const int co0 = blockIdx.y * BN;
    const int CI = a.CA + a.CB, KK = a.k * a.k;
    int oy[TM], ox[TM];
    bool live[TM];
#pragma unroll
    for (int i = 0; i < TM; ++i) {
        const long long p = p0 + tx + 16 * i;
        live[i] = p < P;
        oy[i] = live[i] ? (int)(p / a.Wo) : 0;
        ox[i] = live[i] ? (int)(p - (long long)oy[i] * a.Wo) : 0;
    }
    float acc[TM][TN];
#pragma unroll
    for (int j = 0; j < TN; ++j) {
        const int co = co0 + ty + 16 * j;
        const float b = co < a.CO ? a.bias[co] : 0.0f;
#pragma unroll
        for (int i = 0; i < TM; ++i) acc[i][j] = b;
    }
    const long long planeA = (long long)a.HA * a.WA, planeB = (long long)a.H * a.W;
    for (int ky = 0; ky < a.k; ++ky) {
        for (int kx = 0; kx < a.k; ++kx) {
            const int tap = ky * a.k + kx;
            bool v[TM];
#pragma unroll
            for (int i = 0; i < TM; ++i) {
                const int iy = oy[i] * a.stride - a.pad + ky, ix = ox[i] * a.stride - a.pad + kx;
                v[i] = live[i] && iy >= 0 && iy < a.H && ix >= 0 && ix < a.W;
            }
            for (int c0 = 0; c0 < CI; c0 += BT_BK) {
#pragma unroll
                for (int r = 0; r < (BT_BK * BT_BM) / 256; ++r) {
                    const int e = tid + 256 * r, kk = e / BT_BM, pp = e - kk * BT_BM;
                    const int ci = c0 + kk;
                    const long long p = p0 + pp;
                    float val = 0.0f;
                    if (ci < CI && p < P) {
                        const int py = (int)(p / a.Wo), px = (int)(p - (long long)py * a.Wo);
                        const int iy = py * a.stride - a.pad + ky, ix = px * a.stride - a.pad + kx;
                        if (iy >= 0 && iy < a.H && ix >= 0 && ix < a.W) {
                            if (ci < a.CA) {
                                const long long ia = a.up ? (long long)bn_nearest(iy, a.HA, a.H) * a.WA + bn_nearest(ix, a.WA, a.W)
                                                          : (long long)iy * a.WA + ix;
                                val = a.a[(long long)ci * planeA + ia];
                            } else {
                                val = a.b[(long long)(ci - a.CA) * planeB + (long long)iy * a.W + ix];
                            }
                        }
                    }
                    As[kk][pp] = val;
                }
                for (int e = tid; e < BT_BK * BN; e += 256) {
                    const int kk = e / BN, c = e - kk * BN;
                    const int ci = c0 + kk, co = co0 + c;
                    Bs[kk][c] = (ci < CI && co < a.CO) ? __ldg(a.w + ((long long)co * CI + ci) * KK + tap) : 0.0f;
                }
                __syncthreads();
                const int nk = CI - c0 < BT_BK ? CI - c0 : BT_BK;
                for (int kk = 0; kk < nk; ++kk) {
                    float av[TM], bv[TN];
#pragma unroll
                    for (int i = 0; i < TM; ++i) av[i] = As[kk][tx + 16 * i];
#pragma unroll
                    for (int j = 0; j < TN; ++j) bv[j] = Bs[kk][ty + 16 * j];
#pragma unroll
                    for (int i = 0; i < TM; ++i)
                        if (v[i]) {
#pragma unroll
                            for (int j = 0; j < TN; ++j) acc[i][j] = fmaf(av[i], bv[j], acc[i][j]);
                        }
                }
                __syncthreads();
            }
        }
    }
#pragma unroll
    for (int j = 0; j < TN; ++j) {
        const int co = co0 + ty + 16 * j;
        if (co >= a.CO) continue;
#pragma unroll
        for (int i = 0; i < TM; ++i) {
            if (!live[i]) continue;
            const long long o = (long long)co * P + p0 + tx + 16 * i;
            const float val = a.act ? bn_silu(acc[i][j]) : acc[i][j];
            a.y[o] = a.res ? a.res[o] + val : val;
        }
    }
}
"""


class BiomeConvArgs(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in ("a", "b", "w", "bias", "res", "y")] + [
        (n, ctypes.c_int32) for n in ("CA", "HA", "WA", "CB", "H", "W", "CO", "Ho", "Wo", "k", "stride", "pad", "act",
                                      "up")]


class BiomeNormArgs(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in ("x", "w", "b")] + [("P", ctypes.c_int64), ("C", ctypes.c_int32)]


# The sidecars' convolution kernel (agent PX, 2026-11-19): "tiled" = biome_conv_t<BN> (register-tiled implicit GEMM,
# shared-memory staging; the same fmaf sequence per output, so bitwise biome_conv - verified on biome / veg / surface at
# 320 / 160 / 80 frames: 2.5-3.4x faster at 320), "naive" = biome_conv (TGM_BIOME_CONV=naive, A/B). Numbers identical
# either way: no model_id marker.
BIOME_CONV = os.getenv("TGM_BIOME_CONV", "tiled").strip().lower()


def _biome_conv_launch(pixels: int, co: int):
    if BIOME_CONV == "naive":
        return "biome_conv", (-(-pixels // 256), -(-co // 8), 1)
    bn = 16 if co <= 16 else 32 if co <= 32 else 48 if co <= 48 else 64
    return f"biome_conv_t<{bn}>", (-(-pixels // 128), -(-co // bn), 1)


class _BiomeLaunch(KLaunch):
    def __init__(self, name, grid, args, keep, writes, layout):
        # batch 1 only (BATCH["biome"] = 1): the grid follows nothing
        super().__init__(name, grid, 256, args, keep, writes, {"rule": "linear", "dim": 2}, layout)
        self.source = "biome"
        self.source_text = BIOME_SOURCE


class _BiomePlan:
    """models/biomenet.py BiomeNet (norm=True or False, extras or not) for one (h, w) frame, fp32 NCHW, batch 1."""

    def __init__(self, model, h: int, w: int):
        dev = next(model.parameters()).device
        f32 = dict(device=dev, dtype=torch.float32)
        cfg = model.cfg
        c0, c1, c2 = cfg.channels
        self.n_in = model.e0[0].in_channels
        self.n_out = model.out.out_channels
        h2, w2 = (h - 1) // 2 + 1, (w - 1) // 2 + 1
        h4, w4 = (h2 - 1) // 2 + 1, (w2 - 1) // 2 + 1
        buf = lambda c, hh, ww: torch.zeros(c, hh, ww, **f32)
        self.x_in = torch.zeros(1, self.n_in, h, w, **f32)
        self.out = torch.zeros(1, self.n_out, h, w, **f32)
        t0, h0, u0a, t4, u0 = (buf(c0, h, w) for _ in range(5))
        h1a, t1, h1, u1a, t3, u1 = (buf(c1, h2, w2) for _ in range(6))
        h2a, t2, h2_ = (buf(c2, h4, w4) for _ in range(3))
        self.bufs = (self.x_in, self.out, t0, h0, u0a, t4, u0, h1a, t1, h1, u1a, t3, u1, h2a, t2, h2_)
        self.weights = []
        wt = lambda t: self.weights.append(t.detach().float().contiguous()) or self.weights[-1]
        L = []

        def conv(m, a, y, ho, wo, act=0, b=None, up=0, stride=1):
            ca, ha, wa = a.shape[-3:]
            hh, ww = (b.shape[-2:] if b is not None else ((ho, wo) if up else (ha, wa)))
            k = m.kernel_size[0]
            wq, bq = wt(m.weight), wt(m.bias)
            args = BiomeConvArgs(a=a.data_ptr(), b=0 if b is None else b.data_ptr(), w=wq.data_ptr(),
                                 bias=bq.data_ptr(), y=y.data_ptr(), CA=ca, HA=ha, WA=wa,
                                 CB=0 if b is None else b.shape[-3], H=hh, W=ww, CO=m.out_channels, Ho=ho, Wo=wo,
                                 k=k, stride=stride, pad=m.padding[0], act=act, up=up)
            name, grid = _biome_conv_launch(ho * wo, m.out_channels)
            keep = [a, y, wq, bq] + ([b] if b is not None else [])
            L.append(_BiomeLaunch(name, grid, args, keep, ("y",), "biome_conv_args"))

        def norm(n, x):
            wq, bq = wt(n.weight.flatten()), wt(n.bias.flatten())
            P = x.shape[-1] * x.shape[-2]
            args = BiomeNormArgs(x=x.data_ptr(), w=wq.data_ptr(), b=bq.data_ptr(), P=P, C=x.shape[-3])
            L.append(_BiomeLaunch("biome_norm_silu", (-(-P // 256), 1, 1), args, [x, wq, bq], ("x",),
                                  "biome_norm_args"))

        def block(blk, x, t, y, hh, ww):
            if cfg.norm:
                conv(blk[0], x, t, hh, ww)
                norm(blk[1], t)
                conv(blk[3], t, y, hh, ww)
                norm(blk[4], y)
            else:
                conv(blk[0], x, t, hh, ww, act=1)
                conv(blk[2], t, y, hh, ww, act=1)

        block(model.e0, self.x_in[0], t0, h0, h, w)
        conv(model.d0, h0, h1a, h2, w2, act=1, stride=2)
        block(model.e1, h1a, t1, h1, h2, w2)
        conv(model.d1, h1, h2a, h4, w4, act=1, stride=2)
        block(model.mid, h2a, t2, h2_, h4, w4)
        conv(model.u1, h2_, u1a, h2, w2, act=1, b=h1, up=1)
        block(model.dec1, u1a, t3, u1, h2, w2)
        conv(model.u0, u1, u0a, h, w, act=1, b=h0, up=1)
        block(model.dec0, u0a, t4, u0, h, w)
        conv(model.out, u0, self.out[0], h, w)
        self.launches = L


def export_biome(model, h: int, w: int, path: Path, model_id: str = "") -> dict:
    """BiomeNet for (h, w) frames -> .cim ("tensor" program: (1, n_in, h, w) fp32 in, (1, n_out, h, w) logits out)."""
    bp = _BiomePlan(model, h, w)
    rec = PlanRecorder()
    rec.buffers_from(bp.bufs)
    for lc in bp.launches:
        rec.add(lc)
    io = {"kind": "tensor", "H": h, "W": w, "n_in": bp.n_in,
          "stem": {"buffer": rec.buffer(bp.x_in), "channels": bp.n_in},
          "output": {"buffer": rec.buffer(bp.out), "kind": "f32", "channels": bp.n_out}}
    meta = {"network": "biome", "precision": "fp32", "recorded_from": "terrain_slm/export/cenda_export.py _BiomePlan"}
    return rec.write(path, "biome", model_id, io, extra=meta)


# =============================================================================== surface nodes (vegetation, surface)
# Agent "tiles" (release r3, 2026-11-19): models/vegnet.py VegNet (agent V) and models/surfacenet.py SurfaceNet
# (agent L) on the BiomeNet kernels (BIOME_SOURCE: fp32 NCHW direct convolutions, ChannelNorm + SiLU, residual add),
# one "tensor" program per frame size, exactly like export_biome. VegNet returns its logits (the provider applies the
# sigmoid); SurfaceNet returns head60 (F) and head15 (F * 16, before the pixel shuffle) concatenated on the channels.
class _ConvKit:
    """The launch helpers of _BiomePlan for any ChannelNorm / plain conv network."""

    def __init__(self):
        self.weights, self.launches = [], []

    def wt(self, t):
        self.weights.append(t.detach().float().contiguous())
        return self.weights[-1]

    def conv(self, m, a, y, ho, wo, act=0, b=None, up=0, stride=1, res=None):
        ca, ha, wa = a.shape[-3:]
        hh, ww = (b.shape[-2:] if b is not None else ((ho, wo) if up else (ha, wa)))
        wq, bq = self.wt(m.weight), self.wt(m.bias)
        args = BiomeConvArgs(a=a.data_ptr(), b=0 if b is None else b.data_ptr(), w=wq.data_ptr(), bias=bq.data_ptr(),
                             res=0 if res is None else res.data_ptr(), y=y.data_ptr(), CA=ca, HA=ha, WA=wa,
                             CB=0 if b is None else b.shape[-3], H=hh, W=ww, CO=m.out_channels, Ho=ho, Wo=wo,
                             k=m.kernel_size[0], stride=stride, pad=m.padding[0], act=act, up=up)
        name, grid = _biome_conv_launch(ho * wo, m.out_channels)
        keep = [a, y, wq, bq] + ([b] if b is not None else []) + ([res] if res is not None else [])
        self.launches.append(_BiomeLaunch(name, grid, args, keep, ("y",), "biome_conv_args"))

    def norm(self, n, x):
        wq, bq = self.wt(n.weight.flatten()), self.wt(n.bias.flatten())
        P = x.shape[-1] * x.shape[-2]
        args = BiomeNormArgs(x=x.data_ptr(), w=wq.data_ptr(), b=bq.data_ptr(), P=P, C=x.shape[-3])
        self.launches.append(_BiomeLaunch("biome_norm_silu", (-(-P // 256), 1, 1), args, [x, wq, bq], ("x",),
                                          "biome_norm_args"))


class _VegPlan:
    """models/vegnet.py VegNet for one (h, w) frame: logits (N_OUT, h, w), fp32 NCHW, batch 1."""

    def __init__(self, model, h: int, w: int):
        dev = next(model.parameters()).device
        f32 = dict(device=dev, dtype=torch.float32)
        c0, c1 = model.cfg.channels
        self.n_in = model.e0[0].in_channels
        self.n_out = model.out.out_channels
        h2, w2 = (h - 1) // 2 + 1, (w - 1) // 2 + 1
        buf = lambda c, hh, ww: torch.zeros(c, hh, ww, **f32)
        self.x_in = torch.zeros(1, self.n_in, h, w, **f32)
        self.out = torch.zeros(1, self.n_out, h, w, **f32)
        t0, h0, u, t4 = (buf(c0, h, w) for _ in range(4))
        h1a, t1, e1o, t2, h1 = (buf(c1, h2, w2) for _ in range(5))
        self.bufs = (self.x_in, self.out, t0, h0, u, t4, h1a, t1, e1o, t2, h1)
        k = _ConvKit()
        k.conv(model.e0[0], self.x_in[0], t0, h, w)
        k.norm(model.e0[1], t0)
        k.conv(model.e0[3], t0, h0, h, w)
        k.norm(model.e0[4], h0)
        k.conv(model.d0, h0, h1a, h2, w2, act=1, stride=2)
        for blk, x, t, y in ((model.e1, h1a, t1, e1o), (model.mid, e1o, t2, h1)):
            k.conv(blk[0], x, t, h2, w2)
            k.norm(blk[1], t)
            k.conv(blk[3], t, y, h2, w2)
            k.norm(blk[4], y)
        k.conv(model.u0, h1, u, h, w, b=h0, up=1)
        k.norm(model.dec0[0], u)
        k.conv(model.dec0[2], u, t4, h, w)
        k.norm(model.dec0[3], t4)
        k.conv(model.out, t4, self.out[0], h, w)
        self.weights, self.launches = k.weights, k.launches


class _SurfacePlan:
    """models/surfacenet.py SurfaceNet for one (h, w) frame: [head60 (F), head15 (F * SUB^2)] raw, fp32 NCHW."""

    def __init__(self, model, h: int, w: int):
        dev = next(model.parameters()).device
        f32 = dict(device=dev, dtype=torch.float32)
        cw = model.cfg.width
        self.n_in = model.stem.in_channels
        n60, n15 = model.head60.out_channels, model.head15.out_channels
        self.n_out = n60 + n15
        self.x_in = torch.zeros(1, self.n_in, h, w, **f32)
        self.out = torch.zeros(1, self.n_out, h, w, **f32)
        ha, hb = torch.zeros(cw, h, w, **f32), torch.zeros(cw, h, w, **f32)
        self.bufs = (self.x_in, self.out, ha, hb)
        k = _ConvKit()
        k.conv(model.stem, self.x_in[0], ha, h, w, act=1)
        cur, nxt = ha, hb
        for c in model.trunk:                       # h = h + silu(c(h))
            k.conv(c, cur, nxt, h, w, act=1, res=cur)
            cur, nxt = nxt, cur
        k.conv(model.head60, cur, self.out[0, :n60], h, w)
        k.conv(model.head15, cur, self.out[0, n60:], h, w)
        self.weights, self.launches = k.weights, k.launches


def _export_small(plan, name: str, path: Path, model_id: str) -> dict:
    rec = PlanRecorder()
    rec.buffers_from(plan.bufs)
    for lc in plan.launches:
        rec.add(lc)
    io = {"kind": "tensor", "H": plan.x_in.shape[-2], "W": plan.x_in.shape[-1], "n_in": plan.n_in,
          "stem": {"buffer": rec.buffer(plan.x_in), "channels": plan.n_in},
          "output": {"buffer": rec.buffer(plan.out), "kind": "f32", "channels": plan.n_out}}
    meta = {"network": name, "precision": "fp32", "recorded_from": f"terrain_slm/export/cenda_export.py {type(plan).__name__}"}
    return rec.write(path, name, model_id, io, extra=meta)


def export_vegnet(model, h: int, w: int, path: Path, model_id: str = "") -> dict:
    """VegNet for (h, w) frames -> .cim ((1, N_IN, h, w) fp32 in, (1, N_OUT, h, w) logits out)."""
    return _export_small(_VegPlan(model, h, w), "veg", path, model_id)


def export_surfacenet(model, h: int, w: int, path: Path, model_id: str = "") -> dict:
    """SurfaceNet for (h, w) frames -> .cim ((1, N_IN, h, w) fp32 in, (1, F + F * 16, h, w) raw heads out)."""
    return _export_small(_SurfacePlan(model, h, w), "surface", path, model_id)
