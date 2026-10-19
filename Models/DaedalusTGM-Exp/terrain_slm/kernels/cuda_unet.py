"""Flow-matching UNet samplers (detail, relief) on the project's own CUDA kernels (csrc/conv.cu).

`NativeSampler` compiles a `Detail` or `Relief` into a static plan of fused convolutions over
preallocated NHWC fp16 buffers and records all `steps` Euler steps as ONE CUDA graph: a call is
copy the conditioning and noise in, replay, read the state out.

What is fused (one memory trip per activation instead of ~5):
  * every ResBlock conv carries its glue: conv1 -> + bias -> FiLM (per-step table) -> SiLU stored;
    conv2 -> + bias -> + residual -> raw (the skip / residual) and SiLU (the next layer's input) stored
  * up path: conv3x3(nearest_up(silu h)) runs as four 2x2 phase convolutions (4 taps instead of 9,
    the upsampled tensor never exists); with a 1x1 merge (Detail) the merge is FOLDED in -- there is no
    nonlinearity between the two, so merge(cat(up(h), skip)) = (Wa Wup) * up(silu h) + Wb skip + bias,
    one kernel, composite weights computed in fp64 and rounded once
  * the output conv applies the Euler step x += dt * v in fp32 and writes fp16(x) into the stem input

Numerics: fp16 tensor-core operands, fp32 accumulation and epilogues, FiLM tables in fp32, Euler state
in fp32. On real windows this is ~10x closer to the fp32 model than bf16 autocast (detail ~0.16 m RMS
vs ~1.9 m); activations peak around 50, far inside fp16 range. Deterministic and independent of the
tile configs (fixed K order, no split-K), so a window's numbers depend only on its inputs.
"""
from __future__ import annotations

import torch
import torch.nn as nn

from terrain_slm.kernels import cuda_conv as C
from terrain_slm.models.refiner import timestep_embedding

STEM_PAD = 32   # stem input channels (x_t + conditioning) padded to a BK multiple
EULER_NP = 16   # the output conv's single channel, padded to one 16-wide N tile


def uses_hconv(key: tuple) -> bool:
    """Which kernel a layer runs is a fixed RULE, never tuned: kernels sum in different orders, so the
    choice is part of the numbers (configs within one kernel are not). ResBlock 3x3 convs -> the
    halo-tiled kernel (csrc/hconv.cu); everything else -> the implicit-GEMM kernel (csrc/conv.cu)."""
    kind, cin, cout, ks, stride, hh = key
    return kind in ("c1", "c2") and ks == 3 and stride == 1 and cin % 16 == 0 and cout % 16 == 0


def _config(key: tuple, m: int, co: int, mode: int, kdim: int, device) -> tuple:
    """Tuned config for this GPU (scripts/tune_cuda_kernels.py), else a default for the layer's kernel."""
    cfg = C.TUNED.get(torch.cuda.get_device_name(device), {}).get(key)
    if cfg is not None and (len(cfg) == 6) == uses_hconv(key):
        return cfg
    if uses_hconv(key):
        return C.default_hconv_config(key[2], key[5])
    return C.default_config(m, co, mode, kdim)


class NativeSampler:
    """Euler sampler for one model and one window shape (B=1, H x W). Not thread-safe (one set of
    buffers); the generator calls it under its lock."""

    @staticmethod
    def supported(model: nn.Module) -> bool:
        if not all(hasattr(model, a) for a in ("stem", "enc", "down", "mid", "up", "merge", "dec", "out", "temb")):
            return False
        if model.stem.in_channels > STEM_PAD:
            return False
        convs = [m for m in model.modules() if isinstance(m, nn.Conv2d) and m is not model.stem]
        return (all(c.in_channels % C.BK == 0 for c in convs)
                and all(c.out_channels % 16 == 0 for c in convs if c is not model.out)
                and model.out.out_channels == 1)

    def __init__(self, model: nn.Module, h: int, w: int, steps: int, t_start: float = 0.0, configs: dict | None = None):
        dev = next(model.parameters()).device
        self.h, self.w, self.steps, self.dev = h, w, steps, dev
        mult = 2 ** len(model.down)
        assert h % mult == 0 and w % mult == 0, (h, w, mult)
        self.n_cond = model.stem.in_channels - 1
        hf = dict(device=dev, dtype=torch.float16)
        ts = torch.linspace(t_start, 1.0, steps + 1, device=dev)   # exactly the reference's time grid
        self.t_start = t_start
        dts = [float(ts[i + 1] - ts[i]) for i in range(steps)]
        self._configs = configs or {}
        self.layer_keys = []   # (key, m, co, mode, kdim) of every launch, for the tuner

        # ---------------------------------------------------------------- FiLM tables (fp32)
        blocks = [b for lvl in model.enc for b in lvl] + list(model.mid) + [b for lvl in model.dec for b in lvl]
        film = {}
        with torch.no_grad():
            e = model.temb(timestep_embedding(ts[:steps].float(), model.cfg.temb).float())   # (steps, te)
            for b in blocks:
                c = b.conv1.out_channels
                tab = b.film(e).float()                       # (steps, 2c): scale, shift
                tab[:, :c] += 1.0
                film[id(b)] = tab.contiguous()

        # ---------------------------------------------------------------- buffers
        n_lvl = len(model.down)
        chans = [model.stem.out_channels] + [d.out_channels for d in model.down]
        res = [(h >> i, w >> i) for i in range(n_lvl + 1)]
        buf = lambda i: torch.zeros(1, res[i][0], res[i][1], chans[i], **hf)
        self.stem_in = torch.zeros(1, h, w, STEM_PAD, **hf)
        self.xs = torch.zeros(h * w, device=dev, dtype=torch.float32)
        enc = [(buf(i), buf(i)) for i in range(n_lvl + 1)]      # (raw, silu); enc[n_lvl] = mid
        dec = [(buf(i), buf(i)) for i in range(n_lvl)]
        tmp = [buf(i) for i in range(n_lvl + 1)]                 # conv1 -> FiLM -> SiLU
        up = [buf(i) for i in range(n_lvl)]
        self._bufs = (enc, dec, tmp, up)

        # ---------------------------------------------------------------- weights
        def cfg_for(kind, cin, cout, ks, stride, hh, mode, kdim):
            key = (kind, cin, cout, ks, stride, hh)
            m = (hh * hh) if mode == 0 else hh * hh
            self.layer_keys.append((key, m, cout, mode, kdim))
            return self._configs.get(key) or _config(key, m, cout, mode, kdim, dev)

        def conv_w(c: nn.Conv2d, np_, cin_pad=None):
            return C.pack_conv(c.weight, np_, cin_pad), C.pad_bias(c.bias, np_)

        # ---------------------------------------------------------------- plan
        plan = []   # (step-independent builder) -> per step Launch list

        def conv_op(kind, conv, x1, hh, stride=1, ks=3, x2=None, res_t=None, out_raw=None, out_silu=None,
                    film_of=None, cin_pad=None, concat_c1=None):
            cin = (cin_pad or conv.in_channels)
            ho = (hh + stride - 1) // stride
            cfg = cfg_for(kind, cin, conv.out_channels, ks, stride, ho, 0, ks * ks * cin)
            if len(cfg) == 6:   # halo-tiled 3x3
                np_ = C.round_up(conv.out_channels, cfg[2])
                wq, bq = C.pack_hconv(conv.weight, np_), C.pad_bias(conv.bias, np_)
                plan.append(lambda s: C.make_hconv(x1, wq, bq, cfg, film=film_of[s] if film_of is not None else None,
                                                   res=res_t, out_raw=out_raw, out_silu=out_silu))
                return
            np_ = C.round_up(conv.out_channels, cfg[1])
            if concat_c1 is not None:
                wq, bq = C.pack_concat(conv.weight, concat_c1, np_), C.pad_bias(conv.bias, np_)
            else:
                wq, bq = conv_w(conv, np_, cin_pad)
            plan.append(lambda s: C.make_conv(x1, wq, bq, cfg, ks=ks, stride=stride, x2=x2,
                                              film=film_of[s] if film_of is not None else None,
                                              res=res_t, out_raw=out_raw, out_silu=out_silu))

        def block_ops(b, x, lvl):
            tab = film[id(b)]
            hh = res[lvl][0]
            conv_op("c1", b.conv1, x[1], hh, out_silu=tmp[lvl], film_of=tab)
            conv_op("c2", b.conv2, tmp[lvl], hh, res_t=x[0], out_raw=x[0], out_silu=x[1])

        conv_op("stem", model.stem, self.stem_in, h, out_raw=enc[0][0], out_silu=enc[0][1], cin_pad=STEM_PAD)
        for i in range(n_lvl):
            for b in model.enc[i]:
                block_ops(b, enc[i], i)
            conv_op("down", model.down[i], enc[i][1], res[i][0], stride=2, out_raw=enc[i + 1][0], out_silu=enc[i + 1][1])
        for b in model.mid:
            block_ops(b, enc[n_lvl], n_lvl)
        for i in reversed(range(n_lvl)):
            src = enc[n_lvl][1] if i == n_lvl - 1 else dec[i + 1][1]
            upc, mg = model.up[i], model.merge[i]
            lo = res[i + 1][0]
            if mg.kernel_size == (1, 1):
                cfg = cfg_for("upmerge", upc.in_channels, mg.out_channels, 2, 1, lo, 1, 4 * upc.in_channels + chans[i])
                wq, bq = C.pack_upmerge(upc, mg, C.round_up(mg.out_channels, cfg[1]))
                plan.append(lambda s, src=src, i=i, wq=wq, bq=bq, cfg=cfg: C.make_conv(
                    src, wq, bq, cfg, mode=1, x2=enc[i][0], out_raw=dec[i][0], out_silu=dec[i][1]))
            else:
                cfg = cfg_for("up", upc.in_channels, upc.out_channels, 2, 1, lo, 1, 4 * upc.in_channels)
                np_ = C.round_up(upc.out_channels, cfg[1])
                wq, bq = C.pack_up(upc.weight, np_), C.pad_bias(upc.bias, np_)
                plan.append(lambda s, src=src, i=i, wq=wq, bq=bq, cfg=cfg: C.make_conv(
                    src, wq, bq, cfg, mode=1, out_raw=up[i]))
                conv_op("merge", mg, up[i], res[i][0], ks=mg.kernel_size[0], x2=enc[i][0],
                        out_raw=dec[i][0], out_silu=dec[i][1], concat_c1=upc.out_channels)
            for b in model.dec[i]:
                block_ops(b, dec[i], i)
        # Output conv (CO = 1, one 16-wide N tile) on the halo kernel, Euler update fused.
        out_key = ("out", chans[0], 1, 3, 1, h)
        out_cfg = self._configs.get(out_key) or C.TUNED.get(torch.cuda.get_device_name(dev), {}).get(out_key) \
            or (8, 32, EULER_NP, 8, 1, 2)
        self.layer_keys.append((out_key, h * w, 1, 0, 9 * chans[0]))
        wo, bo = C.pack_hconv(model.out.weight, EULER_NP), C.pad_bias(model.out.bias, EULER_NP)
        plan.append(lambda s: C.make_hconv(dec[0][1], wo, bo, out_cfg, euler=(self.xs, self.stem_in, dts[s])))

        self._steps = [[op(s) for op in plan] for s in range(steps)]
        C.bind([lc for st in self._steps for lc in st], dev)
        self._graph = None

    def _run_steps(self) -> None:
        stream = torch.cuda.current_stream(self.dev).cuda_stream
        for st in self._steps:
            for lc in st:
                lc(stream)

    def _capture(self) -> None:
        with torch.cuda.device(self.dev):
            g = torch.cuda.CUDAGraph()
            stream = torch.cuda.Stream(self.dev)
            stream.wait_stream(torch.cuda.current_stream(self.dev))
            with torch.cuda.stream(stream):
                # thread_local: the service's other threads may touch CUDA while the GPU thread captures.
                with torch.cuda.graph(g, stream=stream, capture_error_mode="thread_local"):
                    self._run_steps()
            torch.cuda.current_stream(self.dev).wait_stream(stream)
            self._graph = g

    def _load(self, cond: torch.Tensor, noise: torch.Tensor) -> None:
        x0 = (1.0 - self.t_start) * noise.float() if self.t_start > 0 else noise.float()
        self.xs.copy_(x0.reshape(-1))
        self.stem_in[..., 0] = x0[0, 0].to(torch.float16)
        self.stem_in[..., 1 : 1 + self.n_cond] = cond[0].permute(1, 2, 0).to(torch.float16)

    @torch.no_grad()
    def __call__(self, cond: torch.Tensor, noise: torch.Tensor) -> torch.Tensor:
        """cond (1, n_cond, H, W), noise (1, 1, H, W) -> x (1, 1, H, W) fp32, like `sample()`."""
        assert cond.shape == (1, self.n_cond, self.h, self.w), cond.shape
        with torch.cuda.device(self.dev):
            if self._graph is None:
                self._capture()
            self._load(cond, noise)
            self._graph.replay()
            return self.xs.view(1, 1, self.h, self.w).clone()

    @torch.no_grad()
    def eager(self, cond: torch.Tensor, noise: torch.Tensor) -> torch.Tensor:
        """The same computation launched op by op (no graph): tests and the tuner."""
        with torch.cuda.device(self.dev):
            self._load(cond, noise)
            self._run_steps()
            return self.xs.view(1, 1, self.h, self.w).clone()
