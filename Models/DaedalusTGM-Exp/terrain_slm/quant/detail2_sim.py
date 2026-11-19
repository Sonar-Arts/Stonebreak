"""Kernel-exact simulation of the native Detail2 sampler (kernels/cuda_detail2.py) at mixed precision: every
convolution in fp16 (what the shipping kernels run) or in an 8-bit format (FP8 e4m3, INT8), with the SAME folding and
the SAME rounding points as the kernels, so a number measured here is the number the kernels produce (up to fp32
accumulation order).

Why this and not `quant/fp8.py`'s conv2d patch under bf16 autocast: that measured the FP8 rounding PLUS bf16 storage
of every activation and of the dequantised operands (the bf16 torch path alone is 1.1 m off fp32), against a bf16
teacher; and it rounded the torch graph's tensors (the up conv's output, mp_silu's scaled values), which the kernels
never store. Here:

  * weights are folded like the packer (MP normalisation, SiLU gain, mp_sum / mp_cat weights, up + 1x1 merge as four
    2x2 phase convolutions over the low-res input + one skip tap) and rounded ONCE per layer: fp16, or 8 bits with a
    per-output-channel scale (per phase and channel for the up-merge);
  * every stored tensor is rounded where the kernels store it: raw residual stream fp16, SiLU / tmp buffers fp16 or
    the consumer conv's 8-bit format, Euler state fp32, the stem's input x fp16;
  * 8-bit convolutions run on the integer-valued codes (exact in TF32 / bf16) with the scales applied after the sum,
    as the kernel's epilogue does: so TF32 tensor cores give exact products here and training can use them.

QConfig picks the format per layer by name (see `layer_names`): "fp16", "e4m3" (per-out-channel weight scale, static
per-tensor activation scale `act_scale`), "int8" (per-out-channel weight scale, static per-layer activation scale from
`act_amax` / calibration). Training (`train/train_qad.py`) runs this forward with straight-through rounding (`ste`).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import torch
import torch.nn.functional as F

from terrain_slm.models import detail2 as D2
from terrain_slm.models.mp import SILU_GAIN

E4M3_MAX = 448.0
FORMATS = ("fp16", "e4m3", "e5m2", "int8", "bf16")  # + diagnostics "w8" (W8A16), "a8" (A8W16)
EIGHT_BIT = ("e4m3", "int8")


@dataclass
class QConfig:
    default: str = "fp16"                              # format of every conv not named in `layers`
    layers: dict = field(default_factory=dict)         # layer name -> format
    act_scale: float = 1.0                             # e4m3 activations: code = e4m3(x / scale), scale =
    act_scales: dict = field(default_factory=dict)     #   act_scales[layer] if present, else act_scale
    act_amax: dict = field(default_factory=dict)       # int8 activations: layer -> clip value (static, calibrated)
    ste: bool = False                                  # straight-through rounding (training)
    calibrate: dict | None = None                      # if a dict: record each conv input's amax here (max over calls)

    def fmt(self, name: str) -> str:
        return self.layers.get(name, self.default)


# ----------------------------------------------------------------------------- rounding
def _ste(x: torch.Tensor, y: torch.Tensor, ste: bool) -> torch.Tensor:
    return x + (y - x).detach() if ste else y


def r16(x: torch.Tensor, ste: bool = False) -> torch.Tensor:
    """Round to fp16 (saturating like the kernels' cvt.rn.f16: |x| > 65504 -> inf; the clip at 256 keeps us far)."""
    return _ste(x, x.detach().half().float() if ste else x.half().float(), ste)


def rbf16(x: torch.Tensor, ste: bool = False) -> torch.Tensor:
    return _ste(x, (x.detach() if ste else x).bfloat16().float(), ste)


def e4m3_codes(x: torch.Tensor) -> torch.Tensor:
    """x (already divided by its scale) -> the e4m3 value (saturating, RNE: cvt.rn.satfinite.e4m3x2.f32), as fp32."""
    return x.clamp(-E4M3_MAX, E4M3_MAX).to(torch.float8_e4m3fn).float()


def int8_codes(x: torch.Tensor) -> torch.Tensor:
    return torch.round(x).clamp(-127, 127)


def e5m2_codes(x: torch.Tensor) -> torch.Tensor:
    return x.clamp(-57344.0, 57344.0).to(torch.float8_e5m2).float()


def codes(x: torch.Tensor, fmt: str) -> torch.Tensor:
    if fmt == "e4m3":
        return e4m3_codes(x)
    return e5m2_codes(x) if fmt == "e5m2" else int8_codes(x)


def weight_codes(w: torch.Tensor, fmt: str, ste: bool, rows_dim: int = 0) -> tuple[torch.Tensor, torch.Tensor]:
    """w (CO, ...) -> (codes, scale (CO,)): per-output-channel amax scale. Codes carry the STE gradient of w / scale."""
    co = w.shape[rows_dim]
    amax = w.detach().abs().transpose(0, rows_dim).reshape(co, -1).amax(1).clamp_min(1e-12)
    scale = amax / {"e4m3": E4M3_MAX, "e5m2": 57344.0}.get(fmt, 127.0)
    shape = [1] * w.ndim
    shape[rows_dim] = co
    ws = w / scale.view(shape)
    return _ste(ws, codes(ws.detach() if ste else ws, fmt), ste), scale


def act_codes(x: torch.Tensor, fmt: str, scale: float, ste: bool) -> torch.Tensor:
    xs = x / scale
    return _ste(xs, codes(xs.detach() if ste else xs, fmt), ste)


# ----------------------------------------------------------------------------- the simulated sampler
class Detail2Sim:
    """Mirrors NativeDetail2Sampler's plan on a (possibly training) Detail2. `velocity` is one network evaluation
    (batched, per-sample t / r); `sample` is the sampler (Euler, fp32 state, fp16 x into the stem)."""

    def __init__(self, model: D2.Detail2, qc: QConfig | None = None):
        self.m = model
        self.qc = qc or QConfig()
        cfg = model.cfg
        rb = cfg.res_balance
        self.rs_x = (1 - rb) / math.sqrt((1 - rb) ** 2 + rb ** 2)
        self.rs_y = rb / math.sqrt((1 - rb) ** 2 + rb ** 2)
        self.n_lvl = len(model.down)

    # ---------------------------------------------------------------- folded weights (fp32, differentiable)
    @staticmethod
    def ew(mod, gain=1.0) -> torch.Tensor:
        return mod.effective_weight(gain)

    def upmerge_phases(self, i: int) -> tuple[torch.Tensor, torch.Tensor]:
        """(phase weights (4, C, Clow, 2, 2), skip weights (C, C)) of up[i] + merge[i], as cuda_detail2 folds them."""
        m = self.m
        c = m.cfg.channels[i]
        ct = m.cfg.concat_balance
        wm = self.ew(m.merge[i])[:, :, 0, 0]
        k = math.sqrt((c + c) / ((1 - ct) ** 2 + ct ** 2))
        wa, wb = wm[:, :c] * (k / math.sqrt(c) * (1 - ct)), wm[:, c:] * (k / math.sqrt(c) * ct)
        comp = torch.einsum("oc,cikl->oikl", wa, self.ew(m.up[i]) * SILU_GAIN)        # (C, Clow, 3, 3)
        # cuda_conv._phase_weights: per axis, phase 0 reads offset -1 with tap 0 and offset 0 with taps 1+2; phase 1
        # reads offset 0 with taps 0+1 and offset +1 with tap 2.
        sel = (((0,), (1, 2)), ((0, 1), (2,)))
        out = []
        for py in range(2):
            for px in range(2):
                rows = []
                for ty in range(2):
                    cols = []
                    for tx in range(2):
                        cols.append(sum(comp[:, :, a, b] for a in sel[py][ty] for b in sel[px][tx]))
                    rows.append(torch.stack(cols, -1))
                out.append(torch.stack(rows, -2))                                     # (C, Clow, 2, 2)
        return torch.stack(out), wb

    # ---------------------------------------------------------------- one conv as the kernel computes it
    def _wq(self, name: str, w: torch.Tensor, fmt: str):
        """Weight operand for format `fmt`: (operand, per-out-channel scale or None)."""
        ste = self.qc.ste
        if fmt in ("fp16", "a8"):            # a8: diagnostics only (8-bit activations, fp16 weights)
            return r16(w, ste), None
        if fmt == "w8":                      # W8A16: e4m3 weights (per-out-channel), fp16 activations
            return weight_codes(w, "e4m3", ste)
        if fmt == "bf16":
            return rbf16(w, ste), None
        return weight_codes(w, fmt, ste)

    def _xq(self, name: str, x: torch.Tensor, fmt: str):
        """Activation operand (what the producer stored for this conv): (operand, scale)."""
        qc = self.qc
        if qc.calibrate is not None:
            a = float(x.detach().abs().amax())
            qc.calibrate[name] = max(qc.calibrate.get(name, 0.0), a)
        if fmt in ("fp16", "w8"):
            return r16(x, qc.ste), 1.0
        if fmt == "a8":
            fmt = "e4m3"
        if fmt == "bf16":
            return rbf16(x, qc.ste), 1.0
        if fmt in ("e4m3", "e5m2"):
            s = qc.act_scales.get(name, qc.act_scale)
        else:
            s = qc.act_amax.get(name, 8.0) / 127.0
        return act_codes(x, fmt, s, qc.ste), s

    def conv(self, name: str, x: torch.Tensor, w: torch.Tensor, stride: int = 1) -> torch.Tensor:
        fmt = self.qc.fmt(name)
        xo, sa = self._xq(name, x, fmt)
        wo, sw = self._wq(name, w, fmt)
        y = F.conv2d(xo, wo, stride=stride, padding=w.shape[-1] // 2)
        if sw is not None:
            y = y * (sw * sa).view(1, -1, 1, 1)
        return y

    def upmerge(self, i: int, h_silu: torch.Tensor, skip_raw: torch.Tensor) -> torch.Tensor:
        """dec[i] input = 4 phase 2x2 convs over silu(h_low) + the skip tap, one accumulator per phase."""
        name = f"up{i}"
        fmt = self.qc.fmt(name)
        ph, wb = self.upmerge_phases(i)
        xo, sa = self._xq(name, h_silu, fmt)
        if fmt in ("fp16", "bf16", "w8"):
            so, _ = self._xq(name + ".skip", skip_raw, fmt)
        else:                                 # one accumulator: the skip shares the low-res input's scale
            so = act_codes(skip_raw, "e4m3" if fmt == "a8" else fmt, sa, self.qc.ste)
        b, clow, hh, ww = h_silu.shape
        c = ph.shape[1]
        xp = F.pad(xo, (1, 1, 1, 1))
        outs = []
        for p in range(4):
            py, px = p >> 1, p & 1
            wcat = torch.cat([ph[p].reshape(c, clow * 4), wb], dim=1)                 # one accumulator: one scale
            wq, sw = self._wq(name, wcat, fmt)
            wp, wsk = wq[:, : clow * 4].reshape(c, clow, 2, 2), wq[:, clow * 4:, None, None]
            o = F.conv2d(xp[:, :, py: py + hh + 1, px: px + ww + 1], wp) + F.conv2d(so[:, :, py::2, px::2], wsk)
            if sw is not None:
                o = o * (sw * sa).view(1, -1, 1, 1)
            outs.append(o)
        o = torch.stack(outs, 2).reshape(b, c * 4, hh, ww)
        return F.pixel_shuffle(o, 2)

    # ---------------------------------------------------------------- network
    def velocity(self, x16: torch.Tensor, cond: torch.Tensor, emb: torch.Tensor, gates: torch.Tensor | None):
        """x16 (B,1,H,W) already fp16-rounded, cond (B,N,H,W), emb (B, E) fp32 -> v (B,1,H,W) fp32."""
        m, ste, sg = self.m, self.qc.ste, SILU_GAIN
        silu = F.silu
        b = x16.shape[0]
        stem_in = torch.cat([x16, r16(cond.float(), ste), torch.ones_like(x16)], 1)
        raw = r16(self.conv("stem", stem_in, self.ew(m.stem)), ste)

        active = []
        if gates is not None and len(m.adapters):
            for si, style in enumerate(D2.STYLES):
                if style in m.adapters and bool((gates[:, si] > 0).any()):
                    active.append((style, gates[:, si: si + 1].float()))

        def adapters(site, raw, pick, need_silu):
            """raw fp16 residual stream after a block -> (raw, silu source fp32 or None)."""
            ads = [(st, g, pick(m.adapters[st])) for st, g in active]
            ads = [a for a in ads if a[2] is not None]
            v = None
            for st, g, ad in ads:
                nm = f"ad.{st}.{site}"
                a1 = silu(self.conv(nm + ".down", raw, self.ew(ad.down)))
                a2 = silu(self.conv(nm + ".mid", a1, self.ew(ad.mid) * sg))
                u = self.conv(nm + ".up", a2, self.ew(ad.up, ad.gain) * sg)
                gl = F.adaptive_avg_pool2d(g, raw.shape[-2:]) if g.shape[-1] != raw.shape[-1] else g
                v = u * gl + raw
                raw = r16(v, ste)
            return raw, v

        def block(blk, raw, src, site, pick, encoder, need_silu):
            """raw fp16 + the fp32 value the producer took its SiLU from -> the same pair after the block."""
            if encoder:   # pixnorm kernel: fp16 raw normalised in place (fp32 math), SiLU of the normalised value
                n = torch.linalg.vector_norm(raw, dim=1, keepdim=True) * math.sqrt(1.0 / raw.shape[1]) + 1e-4
                pn = raw / n
                raw = r16(pn, ste)
                src = pn
            film = (blk.emb_linear(emb, gain=blk.emb_gain) + 1).float()[:, :, None, None]
            y = silu(self.conv(site + ".c0", silu(src), self.ew(blk.conv0) * sg) * film)
            v = self.conv(site + ".c1", y, self.ew(blk.conv1) * (sg * self.rs_y)) + self.rs_x * raw
            v = v.clamp(-D2.CLIP_ACT, D2.CLIP_ACT)
            raw = r16(v, ste)
            raw2, va = adapters(site, raw, pick, need_silu)
            return raw2, (va if va is not None else v)

        skips = []
        src = None
        n_lvl = self.n_lvl
        for li in range(n_lvl):
            for bi, blk in enumerate(m.enc[li]):
                raw, src = block(blk, raw, src, f"enc{li}.{bi}",
                                 lambda ex, li=li, bi=bi: ex.enc[str(li)][bi] if str(li) in ex.enc else None, True, True)
            skips.append(raw)
            v = self.conv(f"down{li}", silu(src), self.ew(m.down[li]) * sg, stride=2)
            raw = r16(v, ste)
            src = v
        for bi, blk in enumerate(m.mid):
            raw, src = block(blk, raw, src, f"mid.{bi}", lambda ex, bi=bi: ex.mid[bi], False, True)
        for i in reversed(range(n_lvl)):
            v = self.upmerge(i, silu(src), skips[i])
            raw, src = r16(v, ste), v
            for bi, blk in enumerate(m.dec[i]):
                raw, src = block(blk, raw, src, f"dec{i}.{bi}",
                                 lambda ex, i=i, bi=bi: ex.dec[str(i)][bi] if str(i) in ex.dec else None, False, True)
        return self.conv("out", silu(src), self.ew(m.out, m.out_gain) * sg)

    def sample(self, cond: torch.Tensor, noise: torch.Tensor, steps: int, t_start: float = 0.0,
               gates: torch.Tensor | None = None, mean_flow: bool | None = None) -> torch.Tensor:
        """The native sampler's loop: per-step embedding in fp32, Euler state fp32, x fp16 into the stem."""
        m = self.m
        mf = getattr(m, "mean_flow", False) if mean_flow is None else mean_flow
        dev = cond.device
        ts = torch.linspace(t_start, 1.0, steps + 1, device=dev)
        x = (1.0 - t_start) * noise.float() if t_start > 0 else noise.float()
        b = cond.shape[0]
        for s in range(steps):
            t = ts[s: s + 1].float().expand(b)
            r = ts[s + 1: s + 2].float().expand(b) if mf else None
            emb = m.embed(t, r).float()
            v = self.velocity(r16(x), cond, emb, gates)
            x = x + float(ts[s + 1] - ts[s]) * v
        return x


def layer_names(model: D2.Detail2, gates_styles=()) -> list[str]:
    """Every conv the sampler runs, in plan order (adapters for `gates_styles`)."""
    n_lvl = len(model.down)
    out = ["stem"]
    ad = lambda site, st_has: [f"ad.{st}.{site}.{k}" for st in gates_styles if st_has(model.adapters[st])
                               for k in ("down", "mid", "up")]
    for li in range(n_lvl):
        for bi in range(len(model.enc[li])):
            out += [f"enc{li}.{bi}.c0", f"enc{li}.{bi}.c1"] + ad(f"enc{li}.{bi}", lambda ex, li=li: str(li) in ex.enc)
        out.append(f"down{li}")
    for bi in range(len(model.mid)):
        out += [f"mid.{bi}.c0", f"mid.{bi}.c1"] + ad(f"mid.{bi}", lambda ex: True)
    for i in reversed(range(n_lvl)):
        out.append(f"up{i}")
        for bi in range(len(model.dec[i])):
            out += [f"dec{i}.{bi}.c0", f"dec{i}.{bi}.c1"] + ad(f"dec{i}.{bi}", lambda ex, i=i: str(i) in ex.dec)
    out.append("out")
    return out


@torch.no_grad()
def fp32_sample(model, cond, noise, steps, t_start=0.0, gates=None):
    """detail2.sample in plain fp32, TF32 off (the reference every precision is measured against)."""
    old = torch.backends.cudnn.allow_tf32, torch.backends.cuda.matmul.allow_tf32
    torch.backends.cudnn.allow_tf32 = torch.backends.cuda.matmul.allow_tf32 = False
    try:
        x = (1.0 - t_start) * noise
        ts = torch.linspace(t_start, 1.0, steps + 1, device=cond.device)
        mf = getattr(model, "mean_flow", False)
        for i in range(steps):
            t = torch.full((cond.shape[0],), float(ts[i]), device=cond.device)
            r = torch.full_like(t, float(ts[i + 1])) if mf else None
            x = x + (ts[i + 1] - ts[i]) * model(x, t, cond, r, gates=gates).float()
        return x
    finally:
        torch.backends.cudnn.allow_tf32, torch.backends.cuda.matmul.allow_tf32 = old
