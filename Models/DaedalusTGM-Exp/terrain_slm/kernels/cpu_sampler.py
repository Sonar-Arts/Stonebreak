"""Flow-matching UNet samplers (detail, relief) on the CPU: a static plan of fused fp32 convolutions
executed by kernels/csrc/unet_cpu.cpp.

`CpuSampler` is the CPU counterpart of `kernels.cuda_unet.NativeSampler`, with the same contract: built
once per model and window shape, then `sampler(cond, noise)` runs every Euler step in native code.

Plan (one Euler step), over NHWC fp32 buffers with a one-pixel zero border:
  stem       conv3x3 of [x_t, cond] (the state x_t lives in channel 0 of the stem input, fp32)
  ResBlock   conv1(silu x) -> FiLM -> silu  |  conv2(.) + x  (stored raw and as silu)
  down       conv3x3 stride 2 of silu(x)
  up+merge   Detail (1x1 merge): ONE convolution -- nearest 2x upsample + 3x3 run as four 2x2 phase
             convolutions, with the merge folded in: merge(cat(up(h), skip)) = (Wa o Wu) * up(silu h)
             + Wb * skip + (Wa bu + bm), exact because nothing nonlinear sits between them.
             Relief (3x3 merge): the phase up-convolution, then a 3x3 convolution of the concatenation.
  out        conv3x3 with the Euler update x += dt * v fused into its store

Numerics: fp32 throughout (FiLM tables included -- no autocast), FMA accumulation in a fixed
(segment, tap, channel) order per output, so a window's numbers do not depend on the thread count
or on any other request. Differs from torch's fp32 convolutions only by summation order (and the
folded / phase-collapsed weights summed once in float64).
"""
from __future__ import annotations

import ctypes

import torch
import torch.nn as nn

from terrain_slm.kernels import native
from terrain_slm.models.refiner import timestep_embedding

P = ctypes.c_void_p
_I = ctypes.c_int


XPAD = 8   # extra zero rows / columns at the bottom / right of every buffer (unet_cpu.cpp XPAD)


class ConvOp(ctypes.Structure):
    _fields_ = [("in0", P), ("in1", P), ("w", P), ("bias", P), ("film", P), ("res", P),
                ("out_raw", P), ("out_silu", P), ("stem", P), ("dts", P), ("wvec", P), ("w_phase", ctypes.c_longlong),
                ("kind", _I), ("ks", _I), ("stride", _I), ("co", _I), ("npanels", _I), ("gh", _I), ("gw", _I),
                ("nseg", _I), ("c0", _I), ("h0", _I), ("w0", _I), ("c1", _I), ("h1", _I), ("w1", _I),
                ("oh", _I), ("ow", _I), ("stem_c", _I), ("euler", _I)]


_LIB = None


def lib():
    global _LIB
    if _LIB is None:
        _LIB = native.cpu_library("unet_cpu")
        _LIB.tgm_unet_run.argtypes = [ctypes.POINTER(ConvOp), _I, _I, _I]
        _LIB.tgm_cpu_set_threads.argtypes = [_I]
        if _LIB.tgm_convop_size() != ctypes.sizeof(ConvOp) or _LIB.tgm_cpu_xpad() != XPAD:
            raise RuntimeError("unet_cpu.cpp ConvOp layout does not match kernels/cpu_sampler.py")
    return _LIB


def available() -> bool:
    if not native.cpu_available():
        return False
    try:
        lib()
    except (RuntimeError, OSError):
        return False
    return True


def set_threads(n: int) -> None:
    lib().tgm_cpu_set_threads(int(n))


def threads() -> int:
    return lib().tgm_cpu_threads()


# ----------------------------------------------------------------------------------- packing
_UP_TAPS = (((0,), (1, 2)), ((0, 1), (2,)))   # as kernels/cuda_conv.py: 3x3 taps each 2x2 phase tap collects


def _panels(co: int) -> int:
    return (co + 15) // 16


def _pack(mats: list[torch.Tensor], co: int) -> torch.Tensor:
    """K-major blocks (K_i, co) float64, concatenated along K -> [panel][K][16] fp32 (zero-padded couts)."""
    k = torch.cat(mats, dim=0)
    npn = _panels(co)
    out = torch.zeros(k.shape[0], npn * 16, dtype=torch.float64)
    out[:, :co] = k
    return out.view(k.shape[0], npn, 16).permute(1, 0, 2).contiguous().float()


def _taps(w: torch.Tensor) -> torch.Tensor:
    """(co, ci, k, k) -> (k*k*ci, co) float64, row = tap * ci + c."""
    co, ci, kh, kw = w.shape
    return w.detach().double().permute(2, 3, 1, 0).reshape(kh * kw * ci, co)


def _phase_taps(w3: torch.Tensor) -> list[torch.Tensor]:
    """3x3 (co, ci, 3, 3) float64 -> per phase (py, px) the 2x2 weights as (4 * ci, co), row = tap * ci + c."""
    co, ci = w3.shape[:2]
    out = []
    for py in range(2):
        for px in range(2):
            rows = []
            for ty in range(2):
                for tx in range(2):
                    acc = torch.zeros(co, ci, dtype=torch.float64)
                    for a in _UP_TAPS[py][ty]:
                        for b in _UP_TAPS[px][tx]:
                            acc += w3[:, :, a, b]
                    rows.append(acc.t())
            out.append(torch.cat(rows, dim=0))
    return out


# Winograd F(4x4, 3x3) weight transform G (6 x 3): V = G g G^T.
_WINO_G = torch.tensor([[1 / 4, 0, 0], [-1 / 6, -1 / 6, -1 / 6], [-1 / 6, 1 / 6, -1 / 6],
                        [1 / 24, 1 / 12, 1 / 6], [1 / 24, -1 / 12, 1 / 6], [0, 0, 1]], dtype=torch.float64)


# F(6x6, 3x3): G (8 x 3), points 0, +-1, +-2, +-1/2.
_WINO_G6 = torch.tensor([[1, 0, 0], [-2 / 9, -2 / 9, -2 / 9], [-2 / 9, 2 / 9, -2 / 9], [1 / 90, 1 / 45, 2 / 45],
                         [1 / 90, -1 / 45, 2 / 45], [32 / 45, 16 / 45, 8 / 45], [32 / 45, -16 / 45, 8 / 45], [0, 0, 1]],
                        dtype=torch.float64)
# F(2x2, 3x3): G (4 x 3).
_WINO_G2 = torch.tensor([[1, 0, 0], [0.5, 0.5, 0.5], [0.5, -0.5, 0.5], [0, 0, 1]], dtype=torch.float64)


def _wino_pack(w: torch.Tensor, m: int = 4) -> torch.Tensor:
    """3x3 weight (co, ci, 3, 3) -> transformed [panel][(m+2)^2][ci][16] fp32 (float64 transform, one rounding)."""
    g = {6: _WINO_G6, 4: _WINO_G, 2: _WINO_G2}[m]
    nx = g.shape[0] ** 2
    co, ci = w.shape[:2]
    v = torch.einsum("ia,ocab,jb->ijco", g, w.detach().double(), g).reshape(nx, ci, co)
    npn = _panels(co)
    out = torch.zeros(nx, ci, npn * 16, dtype=torch.float64)
    out[..., :co] = v
    return out.view(nx, ci, npn, 16).permute(2, 0, 1, 3).contiguous().float()


# Which Winograd variant a 3x3 stride-1 convolution runs as, by its weight count (ci * co). The transformed
# weights are (m+2)^2 / 9 times the direct ones -- F(6x6): 7.1x, F(4x4): 4x -- and once they stop fitting the
# caches every task streams them from DRAM. Measured on an i7-14700K (detail window, whole-step A/B; the
# 512-channel level ran 8x slower as F(4x4) with 38 MB of transformed weights): F(6x6) up to 192 channels
# (9.4 MB), F(4x4) up to 384 (21 MB), direct above. F(2x2) is kept for tuning (`CpuSampler(winograd=2)`).
WINOGRAD_F6_MAX_WEIGHTS = 192 * 192
WINOGRAD_F4_MAX_WEIGHTS = 384 * 384


def winograd_tile(c: nn.Conv2d, h: int, w: int) -> int:
    """Output tile of the Winograd variant a convolution runs as: 6 = F(6x6, 3x3), 4 = F(4x4, 3x3), 0 = direct.
    Winograd needs 3x3 stride 1 and channels a multiple of 8 (grids need not divide: edge tiles are partial)."""
    if not (c.kernel_size == (3, 3) and c.stride == (1, 1) and c.padding == (1, 1) and c.in_channels % 8 == 0):
        return 0
    weights = c.in_channels * c.out_channels
    return 6 if weights <= WINOGRAD_F6_MAX_WEIGHTS else 4 if weights <= WINOGRAD_F4_MAX_WEIGHTS else 0


def _bias(b: torch.Tensor, co: int) -> torch.Tensor:
    out = torch.zeros(_panels(co) * 16, dtype=torch.float32)
    out[:co] = b.detach().double().float()
    return out


class CpuSampler:
    """Euler sampler for one model and one window shape (B=1, H x W) on the CPU. Not thread-safe
    (one set of buffers); the generator calls it under its lock."""

    @staticmethod
    def supported(model: nn.Module) -> bool:
        if not all(hasattr(model, a) for a in ("stem", "enc", "down", "mid", "up", "merge", "dec", "out", "temb")):
            return False
        convs = [m for m in model.modules() if isinstance(m, nn.Conv2d) and m is not model.out]
        return all(c.out_channels % 16 == 0 for c in convs) and model.out.out_channels == 1

    def __init__(self, model: nn.Module, h: int, w: int, steps: int, t_start: float = 0.0, winograd: bool | int = True):
        """`winograd`: True = per-layer choice (`winograd_tile`), False = direct everywhere, 2 / 4 / 6 = that variant
        wherever Winograd applies (tests, tuning)."""
        L = lib()
        self.winograd = winograd
        mult = 2 ** len(model.down)
        assert h % mult == 0 and w % mult == 0, (h, w, mult)
        self.h, self.w, self.steps, self.t_start = h, w, steps, t_start
        self.n_cond = model.stem.in_channels - 1
        self._keep = []   # every tensor a ConvOp points at

        def keep(t: torch.Tensor) -> int:
            t = t.contiguous()
            self._keep.append(t)
            return t.data_ptr()

        with torch.no_grad():
            ts = torch.linspace(t_start, 1.0, steps + 1)          # the reference's time grid (fp32, CPU)
            self.dts = (ts[1:] - ts[:-1]).contiguous()
            blocks = [b for lvl in model.enc for b in lvl] + list(model.mid) + [b for lvl in model.dec for b in lvl]
            temb_dim = model.cfg.temb
            films = {id(b): [] for b in blocks}
            for i in range(steps):
                t = torch.full((1,), float(ts[i]))
                e = model.temb(timestep_embedding(t, temb_dim))
                for b in blocks:
                    scale, shift = b.film(e)[0].float().chunk(2)
                    films[id(b)].append(torch.cat([1 + scale, shift]))
            film = {k: keep(torch.stack(v)) for k, v in films.items()}

        n_lvl = len(model.down)
        chans = [model.stem.out_channels] + [d.out_channels for d in model.down]
        res = [(h >> i, w >> i) for i in range(n_lvl + 1)]
        xp = 2 + XPAD
        buf = lambda i, c=None: torch.zeros(res[i][0] + xp, res[i][1] + xp, chans[i] if c is None else c)
        self.stem_in = buf(0, model.stem.in_channels)
        enc = [(buf(i), buf(i)) for i in range(n_lvl + 1)]      # (raw, silu); enc[n_lvl] = mid
        dec = [(buf(i), buf(i)) for i in range(n_lvl)]
        tmp = [buf(i) for i in range(n_lvl + 1)]
        # up-conv outputs only where the merge is not folded into the up-conv (3x3 merges: Relief)
        upb = [buf(i) if model.merge[i].kernel_size[0] != 1 else None for i in range(n_lvl)]
        for t in [self.stem_in, *[x for p in enc + dec for x in p], *tmp, *[u for u in upb if u is not None]]:
            self._keep.append(t)
        dts_ptr = keep(self.dts)

        ops: list[ConvOp] = []

        def conv(src, c: nn.Conv2d, lvl_in, lvl_out, *, stride=1, src2=None, film_ptr=None, res_t=None,
                 out_raw=None, out_silu=None, euler=False):
            co = c.out_channels
            ci0 = src.shape[-1]
            wt = c.weight.detach().double()
            mats = [_taps(wt[:, :ci0])]
            ci1 = 0
            if src2 is not None:
                ci1 = src2.shape[-1]
                mats.append(_taps(wt[:, ci0:]))
            oh, ow = res[lvl_out]
            wino = winograd_tile(c, oh, ow) if self.winograd and src2 is None and not euler else 0
            if wino and self.winograd in (2, 4, 6):   # tests / tuning: one variant wherever any Winograd applies
                wino = self.winograd
            op = ConvOp(in0=src.data_ptr(), in1=src2.data_ptr() if src2 is not None else None,
                        w=keep(_wino_pack(wt, wino) if wino else _pack(mats, co)), bias=keep(_bias(c.bias, co)), film=film_ptr,
                        res=res_t.data_ptr() if res_t is not None else None,
                        out_raw=out_raw.data_ptr() if out_raw is not None else None,
                        out_silu=out_silu.data_ptr() if out_silu is not None else None,
                        stem=self.stem_in.data_ptr() if euler else None, dts=dts_ptr if euler else None,
                        wvec=keep(mats[0][:, 0].float()) if euler else None,
                        w_phase=0, kind={4: 2, 2: 3, 6: 4}.get(wino, 0), ks=c.kernel_size[0], stride=stride, co=co, npanels=_panels(co),
                        gh=oh, gw=ow, nseg=2 if src2 is not None else 1,
                        c0=ci0, h0=res[lvl_in][0], w0=res[lvl_in][1], c1=ci1,
                        h1=res[lvl_in][0], w1=res[lvl_in][1], oh=oh, ow=ow,
                        stem_c=self.stem_in.shape[-1], euler=int(euler))
            assert c.kernel_size[0] in (1, 3) and c.padding[0] == c.kernel_size[0] // 2
            ops.append(op)

        def upphase(src, w3: torch.Tensor, bias: torch.Tensor, lvl_lo, *, skip=None, wskip=None, out_raw=None,
                    out_silu=None):
            co = w3.shape[0]
            ph = _phase_taps(w3)
            blocks_ = [_pack([p] + ([wskip] if skip is not None else []), co) for p in ph]
            wpk = torch.stack(blocks_).contiguous()
            lo_h, lo_w = res[lvl_lo]
            op = ConvOp(in0=src.data_ptr(), in1=skip.data_ptr() if skip is not None else None, w=keep(wpk),
                        bias=keep(_bias(bias, co)), film=None, res=None,
                        out_raw=out_raw.data_ptr() if out_raw is not None else None,
                        out_silu=out_silu.data_ptr() if out_silu is not None else None,
                        stem=None, dts=None, w_phase=wpk[0].numel(), kind=1, ks=3, stride=1, co=co,
                        npanels=_panels(co), gh=lo_h, gw=lo_w, nseg=2 if skip is not None else 1,
                        c0=src.shape[-1], h0=lo_h, w0=lo_w, c1=skip.shape[-1] if skip is not None else 0,
                        h1=2 * lo_h, w1=2 * lo_w, oh=2 * lo_h, ow=2 * lo_w, stem_c=0, euler=0)
            ops.append(op)

        def block(b, x, lvl):
            conv(x[1], b.conv1, lvl, lvl, film_ptr=film[id(b)], out_silu=tmp[lvl])
            conv(tmp[lvl], b.conv2, lvl, lvl, res_t=x[0], out_raw=x[0], out_silu=x[1])

        conv(self.stem_in, model.stem, 0, 0, out_raw=enc[0][0], out_silu=enc[0][1])
        for i in range(n_lvl):
            for b in model.enc[i]:
                block(b, enc[i], i)
            conv(enc[i][1], model.down[i], i, i + 1, stride=2, out_raw=enc[i + 1][0], out_silu=enc[i + 1][1])
        for b in model.mid:
            block(b, enc[n_lvl], n_lvl)
        for i in reversed(range(n_lvl)):
            src = enc[n_lvl][1] if i == n_lvl - 1 else dec[i + 1][1]
            up, mg = model.up[i], model.merge[i]
            c = up.out_channels
            wu, bu = up.weight.detach().double(), up.bias.detach().double()
            if mg.kernel_size[0] == 1:
                wm = mg.weight.detach().double()[:, :, 0, 0]
                wa, wb = wm[:, :c], wm[:, c:]
                wc = torch.einsum("om,mcab->ocab", wa, wu)
                bc = wa @ bu + mg.bias.detach().double()
                upphase(src, wc, bc, i + 1, skip=enc[i][0], wskip=wb.t().contiguous(), out_raw=dec[i][0],
                        out_silu=dec[i][1])
            else:
                upphase(src, wu, bu, i + 1, out_raw=upb[i])
                conv(upb[i], mg, i, i, src2=enc[i][0], out_raw=dec[i][0], out_silu=dec[i][1])
            for b in model.dec[i]:
                block(b, dec[i], i)
        conv(dec[0][1], model.out, 0, 0, euler=True)
        self._ops = (ConvOp * len(ops))(*ops)
        self._n = len(ops)
        self._lib = L

    @torch.no_grad()
    def __call__(self, cond: torch.Tensor, noise: torch.Tensor) -> torch.Tensor:
        """cond (1, n_cond, H, W), noise (1, 1, H, W) -> x (1, 1, H, W) fp32, like `sample()`."""
        assert cond.shape == (1, self.n_cond, self.h, self.w), cond.shape
        noise = noise.detach().float().cpu()
        x0 = (1.0 - self.t_start) * noise if self.t_start > 0 else noise
        inner = self.stem_in[1 : 1 + self.h, 1 : 1 + self.w]
        inner[..., 0] = x0[0, 0]
        inner[..., 1:] = cond[0].detach().float().cpu().permute(1, 2, 0)
        self._lib.tgm_unet_run(self._ops, self._n, 0, self.steps)
        return inner[..., 0].clone().view(1, 1, self.h, self.w)

    def run_ops(self, s0: int, s1: int) -> None:
        self._lib.tgm_unet_run(self._ops, self._n, s0, s1)
