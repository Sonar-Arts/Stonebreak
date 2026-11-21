"""Relief sampler: smooth trend (per cell) -> sampled valley-scale relief (per cell).

The planner reconstructs valleys that are already present in its trend input, but a smooth
(procedural or painted) trend carries none, and an L1 regressor cannot invent them: it
predicts their mean, a rough plateau. This model *samples* them instead. It is a conditional
flow-matching UNet at cell resolution (240 m) whose target is the real terrain's relief
between a light blur (what the planner expects as its trend, sigma RELIEF_FINE_SIGMA) and a
heavy one (what a painted trend looks like, sigma drawn from RELIEF_SMOOTH_SIGMA):

    x1 = (blur(coarse, fine) - blur(coarse, smooth)) / RELIEF_SCALE_M

Conditioning per cell (N_COND channels): the smooth trend and its slope, wildness,
climate, and presence flags for wildness and climate. Three downsampling levels give a
receptive field of ~100 cells (24 km), the scale of real valley networks.

Convolution-only with no normalisation layers, so a sample is a function of the
coordinate-hashed noise and the conditioning around it; the generator tiles canonical
windows and cross-fades them.

MeanFlow student (EXP-2, 2026-11-19, `ReliefConfig.mean_flow`): the same UNet with one extra time-embedding MLP for
h = t - r. The block FiLMs read e(t, r) = temb(phi(t)) + temb_h(phi(t - r)) instead of temb(phi(t)) (phi =
refiner.timestep_embedding), and the network predicts the AVERAGE velocity over [t, r] (r >= t, t = 0 noise -> t = 1
data), so `sample(..., steps=1 or 2)` jumps x_r = x_t + (r - t) U(x_t, t, r). Every convolution is the teacher's: a
native sampler only needs e(t, r) per step for its FiLM tables (`Relief.embed`). temb_h's last layer starts at zero,
so a student initialised from an FM teacher starts as that teacher.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

import torch
import torch.nn as nn
import torch.nn.functional as F

from terrain_slm.models import planner as P
from terrain_slm.models.refiner import ResBlock, timestep_embedding

# Conditioning channels.
C_TREND, C_SLOPE_X, C_SLOPE_Y, C_WILD, C_T0, C_TSEASON, C_PRECIP, C_PCV, C_HAS_WILD, C_HAS_CLIMATE = range(10)
N_COND = 10

RELIEF_SCALE_M = 250.0          # target normalisation (Alpine valley relief ~ +-1)
RELIEF_FINE_SIGMA = 2.0         # cells: the blur the planner's trend input is trained around
RELIEF_SMOOTH_SIGMA = (8.0, 20.0)  # cells: range of "painted" smoothness seen in training
TREND_PREBLUR_SIGMA = 4.0       # cells: procedural trends are blurred this much before use
SLOPE_SCALE_M = 100.0           # metres per cell -> ~unit
#: Amplitude law (EXP-2, 2026-11-18), fitted on data v2 (404k validation points, R^2 0.64): the local log-rms of the
#: relief target (Gaussian sigma 6 cells, RELIEF_SCALE_M units) = AMP_SLOPE * normalised wildness + AMP_ICPT. A model
#: with ReliefConfig.amp_norm predicts x1 / relief_amp(cond), so this slope holds by construction: the v2 run without
#: it followed only 88% of it (Karakoram 0.58 / Alps 0.62 amplitude ratio, Rhenish hills 1.37).
AMP_SLOPE, AMP_ICPT = 1.009, -1.858
AMP_LOG_RANGE = (-5.0, 1.5)


@dataclass
class ReliefConfig:
    channels: tuple = (16, 24, 32, 48)
    blocks: int = 2
    temb: int = 64
    amp_norm: bool = False          # predict relief / relief_amp(cond) (see AMP_SLOPE); False = v4 checkpoints
    mean_flow: bool = False         # MeanFlow student: + temb_h (average velocity over [t, r]); False = FM teacher

    def to_dict(self):
        d = asdict(self)
        d["channels"] = list(self.channels)
        return d


class Relief(nn.Module):
    """Generic N-level UNet (each level halves resolution), FiLM time conditioning."""

    def __init__(self, cfg: ReliefConfig = ReliefConfig()):
        super().__init__()
        self.cfg = cfg
        ch = cfg.channels
        te = cfg.temb * 2
        self.temb = nn.Sequential(nn.Linear(cfg.temb, te), nn.SiLU(), nn.Linear(te, te))
        if cfg.mean_flow:   # h = t - r embedding; zero last layer: starts as the FM teacher it is initialised from
            self.temb_h = nn.Sequential(nn.Linear(cfg.temb, te), nn.SiLU(), nn.Linear(te, te))
            nn.init.zeros_(self.temb_h[2].weight)
            nn.init.zeros_(self.temb_h[2].bias)
        self.stem = nn.Conv2d(1 + N_COND, ch[0], 3, padding=1)
        self.enc = nn.ModuleList(nn.ModuleList(ResBlock(c, te) for _ in range(cfg.blocks)) for c in ch[:-1])
        self.down = nn.ModuleList(nn.Conv2d(ch[i], ch[i + 1], 3, stride=2, padding=1) for i in range(len(ch) - 1))
        self.mid = nn.ModuleList(ResBlock(ch[-1], te) for _ in range(cfg.blocks))
        self.up = nn.ModuleList(nn.Conv2d(ch[i + 1], ch[i], 3, padding=1) for i in range(len(ch) - 1))
        self.merge = nn.ModuleList(nn.Conv2d(2 * c, c, 3, padding=1) for c in ch[:-1])
        self.dec = nn.ModuleList(nn.ModuleList(ResBlock(c, te) for _ in range(cfg.blocks)) for c in ch[:-1])
        self.out = nn.Conv2d(ch[0], 1, 3, padding=1)
        nn.init.zeros_(self.out.weight)
        nn.init.zeros_(self.out.bias)

    @property
    def multiple(self) -> int:
        return 2 ** (len(self.cfg.channels) - 1)

    @property
    def mean_flow(self) -> bool:
        return bool(getattr(self.cfg, "mean_flow", False))

    def embed(self, t: torch.Tensor, r: torch.Tensor | None = None) -> torch.Tensor:
        """(B,) times -> (B, 2 temb) FiLM input. FM teacher: temb(phi(t)) (r ignored). MeanFlow student:
        temb(phi(t)) + temb_h(phi(t - r)), r = None meaning r = t (the instantaneous velocity)."""
        e = self.temb(timestep_embedding(t, self.cfg.temb))
        if self.mean_flow:
            h = torch.zeros_like(t) if r is None else t - r
            e = e + self.temb_h(timestep_embedding(h, self.cfg.temb))
        return e

    def forward(self, x_t: torch.Tensor, t: torch.Tensor, cond: torch.Tensor, r: torch.Tensor | None = None) -> torch.Tensor:
        """x_t (B,1,H,W), t (B,), cond (B,N_COND,H,W) -> velocity (the average velocity over [t, r] for a MeanFlow
        student given r); H, W multiples of `multiple`."""
        e = self.embed(t, r)
        h = self.stem(torch.cat([x_t, cond], dim=1))
        skips = []
        for blocks, down in zip(self.enc, self.down):
            for b in blocks:
                h = b(h, e)
            skips.append(h)
            h = down(F.silu(h))
        for b in self.mid:
            h = b(h, e)
        for i in reversed(range(len(self.up))):
            h = self.up[i](F.interpolate(F.silu(h), scale_factor=2, mode="nearest"))
            h = self.merge[i](torch.cat([h, skips[i]], dim=1))
            for b in self.dec[i]:
                h = b(h, e)
        return self.out(F.silu(h))


def sample(model: Relief, cond: torch.Tensor, noise: torch.Tensor, steps: int = 16,
           mean_flow: bool | None = None) -> torch.Tensor:
    """Euler from noise (t = 0) to data (t = 1) on a uniform grid. `mean_flow` (default: the model's flag): step i
    uses the average velocity over [t_i, t_{i+1}] (t = i / steps, r = (i + 1) / steps), so 1 step = (0, 1) and 2 steps
    = (0, 0.5), (0.5, 1). With mean_flow False this is refiner.sample exactly (the FM teacher's numbers)."""
    mf = model.mean_flow if mean_flow is None else mean_flow
    x = noise
    ts = torch.linspace(0.0, 1.0, steps + 1, device=cond.device)
    for i in range(steps):
        t = torch.full((cond.shape[0],), float(ts[i]), device=cond.device)
        r = torch.full_like(t, float(ts[i + 1])) if mf else None
        with torch.autocast("cuda", dtype=torch.bfloat16, enabled=cond.is_cuda):
            v = (model(x, t, cond, r) if mf else model(x, t, cond)).float()
        x = x + (ts[i + 1] - ts[i]) * v
    return x


def relief_amp(cond: torch.Tensor) -> torch.Tensor:
    """(B,1,H,W) relief amplitude the conditioning's wildness implies (RELIEF_SCALE_M units); no wildness -> the
    amplitude at average wildness."""
    w, has = cond[:, C_WILD : C_WILD + 1], cond[:, C_HAS_WILD : C_HAS_WILD + 1]
    return torch.exp((has * AMP_SLOPE * w + AMP_ICPT).clamp(*AMP_LOG_RANGE))


def cell_slope(trend_m: torch.Tensor) -> torch.Tensor:
    """(B,1,H,W) metres -> (B,2,H,W) central-difference slope per cell / SLOPE_SCALE_M."""
    p = F.pad(trend_m, (1, 1, 1, 1), mode="replicate")
    gx = (p[..., 1:-1, 2:] - p[..., 1:-1, :-2]) * 0.5
    gy = (p[..., 2:, 1:-1] - p[..., :-2, 1:-1]) * 0.5
    return torch.cat([gx, gy], dim=1) / SLOPE_SCALE_M


def build_cond(smooth_m: torch.Tensor, wild_n: torch.Tensor, climate: dict | torch.Tensor,
               has_wild: torch.Tensor, has_climate: torch.Tensor) -> torch.Tensor:
    """Conditioning stack. `smooth_m` (B,1,H,W) metres; `wild_n` (B,1,H,W) planner-normalised
    wildness; `climate` either a dict of physical fields (B,1,H,W) or an already-normalised
    (B,4,H,W) tensor; `has_*` (B,1,1,1) presence flags (0 zeroes the field)."""
    if isinstance(climate, dict):
        climate = torch.cat([P.climate_input(n, climate[n]) for n in P.CLIMATE_NAMES], dim=1)
    b, _, h, w = smooth_m.shape
    ones = torch.ones(b, 1, h, w, device=smooth_m.device)
    return torch.cat([
        smooth_m / P.HEIGHT_SCALE_M,
        cell_slope(smooth_m),
        wild_n * has_wild,
        climate * has_climate,
        ones * has_wild,
        ones * has_climate,
    ], dim=1)
