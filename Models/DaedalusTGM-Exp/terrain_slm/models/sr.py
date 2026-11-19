"""SR node (DaedalusTGM-Exp-2 plan §2): 2x super-resolution of a height field, 60 m -> 30 m (SR-30, 1:2 worlds) and
later 30 m -> 15 m (SR-15, 1:1 worlds, trained on lidar DTMs).

The detail sampler draws 60 m samples; a 1:2 world needs 30 m blocks. SR adds the missing octave as a residual over
the bicubic upsample, sampled by flow matching (1-2 steps once distilled), from local context only (~2 km
receptive field), so it is cheap per tile.

Mean-preserving by construction: after sampling, the residual's 2x2 block means are subtracted, so every 2x2
block of the 30 m result averages EXACTLY to the 60 m sample under it. A 1:2 world is therefore the same terrain as
its 1:4 twin, only sharper (the 1:4 tile builder's 2x2 block mean returns the 60 m samples).

Layers are the EDM2 magnitude-preserving ones (models/mp.py), like the detail backbone.
Conditioning (N_COND channels at the fine resolution): the bicubic upsample (relative to its local mean, / 100 m),
its slope (2), and presence flags for optional climate (4 + flag) -- off until the climate node lands.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

import torch
import torch.nn as nn
import torch.nn.functional as F

from terrain_slm.models.mp import MPConv, MPFourier, mp_cat, mp_silu, mp_sum, pixel_norm

ASINH_M = 4.0           # residual compression knee (m): the missing octave is metres to tens of metres
REL_M = 100.0
N_COND = 1 + 2 + 4 + 1  # relative base, slope (2), climate (4), climate flag


@dataclass
class SRConfig:
    channels: tuple = (64, 128, 192)
    blocks: int = 2
    emb: int = 256
    fourier: int = 64
    dropout: float = 0.0
    arch: str = "sr"

    def to_dict(self):
        d = asdict(self)
        d["channels"] = list(self.channels)
        return d


class _Block(nn.Module):
    def __init__(self, c, emb, encoder):
        super().__init__()
        self.encoder = encoder
        self.conv0, self.conv1 = MPConv(c, c), MPConv(c, c)
        self.emb_linear = MPConv(emb, c, ())
        self.emb_gain = nn.Parameter(torch.zeros([]))

    def forward(self, x, emb):
        if self.encoder:
            x = pixel_norm(x)
        y = self.conv0(mp_silu(x))
        y = mp_silu(y * (self.emb_linear(emb, gain=self.emb_gain) + 1)[:, :, None, None].to(y.dtype))
        return mp_sum(x, self.conv1(y), t=0.3)


class SR(nn.Module):
    def __init__(self, cfg: SRConfig = SRConfig()):
        super().__init__()
        self.cfg = cfg
        ch, e = cfg.channels, cfg.emb
        self.fourier = MPFourier(cfg.fourier, seed=5)
        self.emb = MPConv(cfg.fourier, e, ())
        self.stem = MPConv(1 + N_COND + 1, ch[0])
        self.enc = nn.ModuleList(nn.ModuleList(_Block(c, e, True) for _ in range(cfg.blocks)) for c in ch[:-1])
        self.down = nn.ModuleList(MPConv(ch[i], ch[i + 1], stride=2) for i in range(len(ch) - 1))
        self.mid = nn.ModuleList(_Block(ch[-1], e, False) for _ in range(cfg.blocks))
        self.up = nn.ModuleList(MPConv(ch[i + 1], ch[i]) for i in range(len(ch) - 1))
        self.merge = nn.ModuleList(MPConv(2 * c, c, (1, 1)) for c in ch[:-1])
        self.dec = nn.ModuleList(nn.ModuleList(_Block(c, e, False) for _ in range(cfg.blocks)) for c in ch[:-1])
        self.out = MPConv(ch[0], 1)
        self.out_gain = nn.Parameter(torch.zeros([]))

    @property
    def multiple(self) -> int:
        return 2 ** (len(self.cfg.channels) - 1)

    def forward(self, x_t, t, cond, r=None):
        emb = mp_silu(self.emb(self.fourier(t))).to(x_t.dtype)
        h = self.stem(torch.cat([x_t, cond.to(x_t.dtype), torch.ones_like(x_t)], 1))
        skips = []
        for blocks, down in zip(self.enc, self.down):
            for b in blocks:
                h = b(h, emb)
            skips.append(h)
            h = down(mp_silu(h))
        for b in self.mid:
            h = b(h, emb)
        for i in reversed(range(len(self.up))):
            h = self.up[i](F.interpolate(mp_silu(h), scale_factor=2, mode="nearest"))
            h = self.merge[i](mp_cat(h, skips[i]))
            for b in self.dec[i]:
                h = b(h, emb)
        return self.out(mp_silu(h), gain=self.out_gain)


# ----------------------------------------------------------------------------- shared helpers
def upsample(coarse: torch.Tensor) -> torch.Tensor:
    """(B,1,h,w) -> (B,1,2h,2w) bicubic (align_corners False: fine pixel centres at quarter offsets)."""
    return F.interpolate(coarse, scale_factor=2, mode="bicubic", align_corners=False)


def block_mean(x: torch.Tensor) -> torch.Tensor:
    return F.avg_pool2d(x, 2)


def build_cond(up: torch.Tensor, px_m: float, climate: torch.Tensor | None = None) -> torch.Tensor:
    b, _, h, w = up.shape
    rel = (up - F.avg_pool2d(F.pad(up, (15, 15, 15, 15), mode="replicate"), 31, stride=1)) / REL_M
    p = F.pad(up, (1, 1, 1, 1), mode="replicate")
    gx = (p[..., 1:-1, 2:] - p[..., 1:-1, :-2]) / (2 * px_m)
    gy = (p[..., 2:, 1:-1] - p[..., :-2, 1:-1]) / (2 * px_m)
    if climate is None:
        clim, flag = torch.zeros(b, 4, h, w, device=up.device, dtype=up.dtype), torch.zeros(b, 1, h, w, device=up.device, dtype=up.dtype)
    else:
        clim, flag = climate, torch.ones(b, 1, h, w, device=up.device, dtype=up.dtype)
    return torch.cat([rel, gx, gy, clim, flag], 1)


def encode(res_m: torch.Tensor) -> torch.Tensor:
    return torch.asinh(res_m / ASINH_M)


def decode(x: torch.Tensor) -> torch.Tensor:
    return ASINH_M * torch.sinh(x)


def assemble(coarse: torch.Tensor, x: torch.Tensor) -> torch.Tensor:
    """Fine heights from a coarse field and a sampled (encoded) residual, exactly mean-preserving: the bicubic
    base's own 2x2 block-mean error is corrected with the residual's."""
    up = upsample(coarse)
    fine = up + decode(x)
    err = block_mean(fine) - coarse
    return fine - F.interpolate(err, scale_factor=2, mode="nearest")


@torch.no_grad()
def sample(model: SR, cond: torch.Tensor, noise: torch.Tensor, steps: int = 8) -> torch.Tensor:
    x = noise
    ts = torch.linspace(0.0, 1.0, steps + 1, device=cond.device)
    for i in range(steps):
        t = torch.full((cond.shape[0],), float(ts[i]), device=cond.device)
        with torch.autocast("cuda", dtype=torch.bfloat16, enabled=cond.is_cuda):
            v = model(x, t, cond).float()
        x = x + (ts[i + 1] - ts[i]) * v
    return x


def count_params(m: nn.Module) -> int:
    return sum(p.numel() for p in m.parameters())
