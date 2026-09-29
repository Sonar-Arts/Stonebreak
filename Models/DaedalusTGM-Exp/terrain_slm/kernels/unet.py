"""Flow-matching UNet samplers (detail, relief) as one CUDA graph of fused convolutions.

`models.detail.sample` / `models.refiner.sample` run the UNet eagerly under autocast: per Euler
step ~60 cuDNN convolutions plus ~150 elementwise kernels (SiLU, FiLM, residual adds, nearest
upsample, concat, bf16 casts of every weight) launched one by one from Python. At the batch of
one the generator always uses, the GPU spends more than half of each step on that glue or idle
between launches.

`FusedSampler` compiles a `Detail` or `Relief` into a static plan of `kernels.conv` launches --
every elementwise op folded into a convolution's prologue or epilogue, FiLM scale/shift
precomputed for the fixed time grid, the Euler update fused into the output convolution -- and
captures all `steps` steps as one CUDA graph over preallocated buffers. A call is: copy the
conditioning and noise in, replay, read the state out.

Numerics: bf16 tensor-core convolutions with fp32 accumulation, like the autocast path, but the
epilogues stay in fp32 until one final bf16 rounding (autocast rounds after the conv, after the
FiLM multiply, after the add, ...), and the Euler state never passes through bf16. Close to, not
bit-identical with, `sample()`; deterministic, and a window's result depends only on its inputs.
"""
from __future__ import annotations

import torch
import torch.nn as nn

from terrain_slm.kernels import conv as K
from terrain_slm.models.refiner import timestep_embedding

STEM_PAD = 32  # stem input channels (x_t + conditioning) padded to a BLOCK_K multiple


def _bias(c: nn.Conv2d) -> torch.Tensor:
    # Autocast hands cuDNN a bf16 bias; keep that rounding.
    return c.bias.detach().to(torch.bfloat16).float().contiguous()


class FusedSampler:
    """Euler sampler for one model and one window shape (B=1, H x W). Not thread-safe (one set of
    buffers); the generator calls it under its lock."""

    @staticmethod
    def supported(model: nn.Module) -> bool:
        """The fused convolutions tile input channels in blocks of 16 (tensor-core K); older
        checkpoints with other widths (v3's relief: 24 / 48) stay on the torch sampler."""
        if not all(hasattr(model, a) for a in ("stem", "enc", "down", "mid", "up", "merge", "dec", "out", "temb")):
            return False
        if model.stem.in_channels > STEM_PAD:
            return False
        convs = [m for m in model.modules() if isinstance(m, nn.Conv2d) and m is not model.stem]
        return all(c.in_channels % 16 == 0 for c in convs) and all(c.out_channels % 16 == 0 for c in convs if c is not model.out)

    def __init__(self, model: nn.Module, h: int, w: int, steps: int, t_start: float = 0.0):
        dev = next(model.parameters()).device
        self.h, self.w, self.steps, self.dev = h, w, steps, dev
        mult = 2 ** len(model.down)
        assert h % mult == 0 and w % mult == 0, (h, w, mult)
        self.n_cond = model.stem.in_channels - 1
        assert model.stem.in_channels <= STEM_PAD
        bf = dict(device=dev, dtype=torch.bfloat16)
        ts = torch.linspace(t_start, 1.0, steps + 1, device=dev)   # exactly the reference's time grid
        self.t_start = t_start
        self.dts = [float(ts[i + 1] - ts[i]) for i in range(steps)]

        # ---------------------------------------------------------------- weights + FiLM tables
        blocks = [b for lvl in model.enc for b in lvl] + list(model.mid) + [b for lvl in model.dec for b in lvl]
        with torch.no_grad(), torch.autocast("cuda", dtype=torch.bfloat16):
            films = []
            for i in range(steps):
                t = torch.full((1,), float(ts[i]), device=dev)
                e = model.temb(timestep_embedding(t, model.cfg.temb))
                films.append([b.film(e)[0].float() for b in blocks])
        self._film = {}
        for j, b in enumerate(blocks):
            c = b.conv1.out_channels
            tab = torch.stack([films[i][j] for i in range(steps)])          # (steps, 2c): scale, shift
            tab[:, :c] += 1.0                                                # 1 + scale, in fp32
            self._film[id(b)] = tab.contiguous()
        self._w = {}

        def wt(c: nn.Conv2d, cin_pad=None):
            if id(c) not in self._w:
                self._w[id(c)] = (K.pack_weight(c.weight, cin_pad), _bias(c))
            return self._w[id(c)]

        # ---------------------------------------------------------------- buffers
        n_lvl = len(model.down)
        chans = [model.stem.out_channels] + [d.out_channels for d in model.down]
        res = [(h >> i, w >> i) for i in range(n_lvl + 1)]
        buf = lambda i, c=None: torch.zeros(1, res[i][0], res[i][1], chans[i] if c is None else c, **bf)
        self.stem_in = torch.zeros(1, h, w, STEM_PAD, **bf)
        self.xs = torch.zeros(h * w, device=dev, dtype=torch.float32)
        enc = [(buf(i), buf(i)) for i in range(n_lvl + 1)]      # (raw, silu); enc[n_lvl] = mid
        dec = [(buf(i), buf(i)) for i in range(n_lvl)]
        tmp = [buf(i) for i in range(n_lvl + 1)]                 # conv1 -> FiLM -> SiLU
        up = [buf(i) for i in range(n_lvl)]
        self._bufs = (enc, dec, tmp, up)

        # ---------------------------------------------------------------- plan (one step)
        def block_ops(b, x, lvl):
            w1, b1 = wt(b.conv1)
            w2, b2 = wt(b.conv2)
            film = self._film[id(b)]
            return [
                lambda s, w1=w1, b1=b1: K.conv(x[1], w1, b1, film=film[s], out_silu=tmp[lvl]),
                lambda s, w2=w2, b2=b2: K.conv(tmp[lvl], w2, b2, res=x[0], out_raw=x[0], out_silu=x[1]),
            ]

        ops = []
        ws, bs = wt(model.stem, STEM_PAD)
        ops.append(lambda s: K.conv(self.stem_in, ws, bs, out_raw=enc[0][0], out_silu=enc[0][1]))
        for i in range(n_lvl):
            for b in model.enc[i]:
                ops += block_ops(b, enc[i], i)
            wd, bd = wt(model.down[i])
            ops.append(lambda s, i=i, wd=wd, bd=bd: K.conv(enc[i][1], wd, bd, stride=2,
                                                           out_raw=enc[i + 1][0], out_silu=enc[i + 1][1]))
        for b in model.mid:
            ops += block_ops(b, enc[n_lvl], n_lvl)
        for i in reversed(range(n_lvl)):
            src = enc[n_lvl][1] if i == n_lvl - 1 else dec[i + 1][1]
            wu, bu = K.pack_up_weight(model.up[i].weight), _bias(model.up[i])
            ops.append(lambda s, i=i, src=src, wu=wu, bu=bu: K.upconv(src, wu, bu, out=up[i]))
            mg = model.merge[i]
            wm, bm = wt(mg)
            ops.append(lambda s, i=i, mg=mg, wm=wm, bm=bm: K.conv(up[i], wm, bm, ks=mg.kernel_size[0], x2=enc[i][0],
                                                                  out_raw=dec[i][0], out_silu=dec[i][1]))
            for b in model.dec[i]:
                ops += block_ops(b, dec[i], i)
        wo, bo = wt(model.out)
        ops.append(lambda s: K.conv(dec[0][1], wo, bo, euler=(self.xs, self.stem_in, self.dts[s])))
        self._ops = ops
        self._graph = None

    def _run_steps(self) -> None:
        for s in range(self.steps):
            for op in self._ops:
                op(s)

    def _capture(self) -> None:
        with torch.cuda.device(self.dev):
            self._capture_on_device()

    def _capture_on_device(self) -> None:
        self._run_steps()               # compiles every kernel variant before capture
        torch.cuda.synchronize(self.dev)
        g = torch.cuda.CUDAGraph()
        stream = torch.cuda.Stream(self.dev)
        stream.wait_stream(torch.cuda.current_stream(self.dev))
        with torch.cuda.stream(stream):
            # thread_local: the service's other threads may touch CUDA while the GPU thread captures.
            with torch.cuda.graph(g, stream=stream, capture_error_mode="thread_local"):
                self._run_steps()
        torch.cuda.current_stream(self.dev).wait_stream(stream)
        self._graph = g

    @torch.no_grad()
    def __call__(self, cond: torch.Tensor, noise: torch.Tensor) -> torch.Tensor:
        """cond (1, n_cond, H, W), noise (1, 1, H, W) -> x (1, 1, H, W) fp32, like `sample()`."""
        assert cond.shape == (1, self.n_cond, self.h, self.w), cond.shape
        x0 = (1.0 - self.t_start) * noise.float() if self.t_start > 0 else noise.float()
        self.xs.copy_(x0.reshape(-1))
        self.stem_in[..., 0] = x0[0, 0].to(torch.bfloat16)
        self.stem_in[..., 1 : 1 + self.n_cond] = cond[0].permute(1, 2, 0).to(torch.bfloat16)
        if self._graph is None:
            self._capture()
            self.xs.copy_(x0.reshape(-1))   # the capture run advanced the state
            self.stem_in[..., 0] = x0[0, 0].to(torch.bfloat16)
        self._graph.replay()
        return self.xs.view(1, 1, self.h, self.w).clone()
