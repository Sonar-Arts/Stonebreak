"""World generator: absolute native-pixel coordinates -> elevation (m) + climate.

Pipeline per request:
  procedural controls (cells)  ->  planner windows (canonical 64x64 cells, stride 32,
  sin^2 blend = exact partition of unity)  ->  per-cell coarse height / descriptors /
  river probability  ->  canonical pixel regions (256 px core + 64 px apron, cross-faded
  over the 128 px overlap): synth -> refiner SDEdit  ->  native heights.

Every intermediate is computed in a canonical shape from a fixed lattice, so a pixel's
value never depends on which request asked for it (the bridge's seam requirement).
"""
from __future__ import annotations

import math
import threading
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path

import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.models import planner as P
from terrain_slm.models import refiner as R
from terrain_slm.synth import noise as S
from terrain_slm.world import rivers as RV

CP = D.CELL_PX
WIN = 64  # planner window, cells
STRIDE = 32
REGION_CORE_PX = 256
REGION_APRON_PX = 64
REGION_CELLS = (REGION_CORE_PX + 2 * REGION_APRON_PX) // CP  # 48
LAPSE_C_PER_M = 0.0065
PATCH_BLUR_CELLS = 1.0
HOME_RADIUS_CELLS = 150.0  # ~36 km


# ----------------------------------------------------------------------------- controls
def _value_noise(ci: torch.Tensor, cj: torch.Tensor, period: float, seed: int, stream: int) -> torch.Tensor:
    """Smooth lattice value noise in [-1, 1] at float cell coords, quintic interpolation."""
    x, y = ci / period, cj / period
    x0, y0 = torch.floor(x), torch.floor(y)
    fx, fy = x - x0, y - y0
    ux = fx * fx * fx * (fx * (fx * 6 - 15) + 10)
    uy = fy * fy * fy * (fy * (fy * 6 - 15) + 10)
    base = S._hash32(torch.tensor((seed * 0x9E3779B1 + stream * 0x632BE5AB) & S._MASK32, device=ci.device))

    def corner(dx, dy):
        h = S._hash32(S._hash32((x0.long() + dx) ^ base) ^ (y0.long() + dy))
        return h.float() / 2147483648.0 - 1.0

    a, b, c, d = corner(0, 0), corner(0, 1), corner(1, 0), corner(1, 1)
    return (a * (1 - uy) + b * uy) * (1 - ux) + (c * (1 - uy) + d * uy) * ux


def _fbm(ci, cj, period, seed, stream, octaves=5, gain=0.5):
    tot, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        tot = tot + amp * _value_noise(ci, cj, period / 2**o, seed, stream * 16 + o)
        norm += amp
        amp *= gain
    return tot / norm


# Climate archetypes: the median land climate of each training region, (t0 degC, tseason
# std*100, precip mm, pcv %). Real climates come in packages -- hot AND wet AND unseasonal
# (rainforest), hot AND dry (desert) -- and independent noise per variable almost never lines
# the tails up, so the classifier saw forest and taiga everywhere. Blending whole packages
# keeps the combinations biomes need, and keeps the planner on climates it trained on.
CLIMATE_ARCHETYPES = (
    ("alps", 13.5, 650.0, 1125.0, 23.5),
    ("borneo", 27.9, 30.0, 3555.0, 16.1),
    ("colorado_plateau", 22.5, 860.0, 281.0, 34.1),
    ("east_africa", 29.7, 134.0, 717.0, 85.5),
    ("great_plains", 13.7, 1072.0, 623.0, 57.6),
    ("norway", 6.9, 613.0, 1214.0, 32.0),
    ("sahara_erg", 25.2, 870.0, 31.0, 52.4),
)
CLIMATE_ZONE_CELLS = 250.0  # noise wavelength: zones ~60 km (~1000 blocks at 60 m)
CLIMATE_SHARPNESS = 10.0    # softmax temperature: one package dominates, borders blend


def _climate(ci, cj, seed):
    scores = torch.stack([_fbm(ci, cj, CLIMATE_ZONE_CELLS, seed, 20 + k, octaves=2)
                          for k in range(len(CLIMATE_ARCHETYPES))])
    w = torch.softmax(CLIMATE_SHARPNESS * scores, dim=0)
    arch = torch.tensor([a[1:] for a in CLIMATE_ARCHETYPES], dtype=torch.float32, device=ci.device)
    blend = lambda col: (w * arch[:, col].view(-1, 1, 1)).sum(dim=0)
    return {
        "t0": blend(0),
        "tseason": blend(1),
        # Precipitation spans 100x across the archetypes: blend it geometrically.
        "precip": torch.exp((w * torch.log(arch[:, 2]).view(-1, 1, 1)).sum(dim=0)),
        "pcv": blend(3),
    }


def procedural_controls(ci0: int, cj0: int, hc: int, wc: int, seed: int, device) -> dict:
    """Per-cell control fields for cells [ci0, ci0+hc) x [cj0, cj0+wc).

    Stand-ins for painted maps: a continents-scale trend with ridged mountain belts and
    smooth climate fields, kept inside the Alps training ranges.
    """
    ci = torch.arange(ci0, ci0 + hc, device=device, dtype=torch.float32).view(-1, 1).expand(hc, wc)
    cj = torch.arange(cj0, cj0 + wc, device=device, dtype=torch.float32).view(1, -1).expand(hc, wc)
    continent = _fbm(ci, cj, 900.0, seed, 1, octaves=4)
    # "Home continent": a broad bump around the world origin so spawn is always on land.
    continent = continent + 0.7 * torch.exp(-(ci**2 + cj**2) / (2 * HOME_RADIUS_CELLS**2))
    land = ((continent + 0.35) / 0.2).clamp(0, 1)
    land = land * land * (3 - 2 * land)  # smoothstep: no cliffs at the coast
    belts = (1.0 - _fbm(ci, cj, 220.0, seed, 2, octaves=3).abs()) ** 2
    mountainous = (0.5 + 0.5 * _fbm(ci, cj, 600.0, seed, 3, octaves=2)).clamp(0, 1) ** 1.5
    hills = _fbm(ci, cj, 70.0, seed, 8, octaves=4)
    trend = (-300.0 + 600.0 * land * (0.6 + 0.5 * continent)
             + land * (2400.0 * belts * mountainous + 200.0 * hills * (0.3 + mountainous)))
    # Roughness in [0, 1] from the same fields that raise mountains, mapped to band-3 log
    # amplitude between gentle plains (~2 m) and alpine relief (~40 m).
    rough = (0.08 + 1.3 * belts * mountainous + 0.25 * (0.3 + mountainous) * hills.abs()).clamp(0, 1) * land
    wild = math.log(2.0) + rough * (math.log(40.0) - math.log(2.0))
    return {
        "wild": wild,
        "trend": trend,
        **_climate(ci, cj, seed),
    }


# ----------------------------------------------------------------------------- generator
@dataclass
class GenConfig:
    t_start: float = 0.6  # 0 = refiner generates from noise, 1 = pure synth
    steps: int = 8
    amp_scale: float = 1.0
    # River density knob (log1p upslope cells). Calibrated so generated worlds match the
    # Alps slice's ~3% river-cell density; the training definition is RV.LOG_THRESHOLD.
    river_log_threshold: float = 3.3


class _LRU(OrderedDict):
    def __init__(self, cap):
        super().__init__()
        self.cap = cap

    def get_or(self, key, fn):
        if key in self:
            self.move_to_end(key)
            return self[key]
        val = fn()
        self[key] = val
        if len(self) > self.cap:
            self.popitem(last=False)
        return val


class WorldGenerator:
    def __init__(self, model_dir: Path, seed: int, device: str, cfg: GenConfig = GenConfig(),
                 cache_regions: int = 512):
        self.seed, self.device, self.cfg = seed, device, cfg
        pk = torch.load(model_dir / "planner.pt", map_location=device, weights_only=False)
        self.planner = P.Planner(P.PlannerConfig(**pk["config"])).to(device).eval()
        self.planner.load_state_dict(pk["model"])
        rk = torch.load(model_dir / "refiner.pt", map_location=device, weights_only=False)
        rc = rk["config"]
        rc["channels"] = tuple(rc["channels"])
        self.refiner = R.Refiner(R.RefinerConfig(**rc)).to(device).eval()
        self.refiner.load_state_dict(rk["model"])
        self.desc_mean = torch.tensor(pk["norms"]["desc_mean"], device=device)
        self.desc_std = torch.tensor(pk["norms"]["desc_std"], device=device)
        self.model_id = f"{model_dir.name}:p{pk.get('step', 0)}:r{rk.get('step', 0)}"
        self._windows = _LRU(4096)
        self._regions = _LRU(cache_regions)
        self.lock = threading.RLock()

    # ---------------------------------------------------------------- planner (cells)
    def _window(self, wi: int, wj: int) -> torch.Tensor:
        """Planner output for the canonical window whose top-left cell is (wi*STRIDE, wj*STRIDE):
        (11, WIN, WIN) = [height_m, desc(8, raw units), river_prob, log1p(upslope cells)]."""
        def run():
            ci0, cj0 = wi * STRIDE, wj * STRIDE
            c = procedural_controls(ci0, cj0, WIN, WIN, self.seed, self.device)
            x = torch.zeros(1, P.N_IN, WIN, WIN, device=self.device)
            x[0, P.IN_TREND] = c["trend"] / P.HEIGHT_SCALE_M
            for k, name in enumerate(P.CLIMATE_NAMES):
                x[0, P.IN_T0 + k] = P.climate_input(name, c[name])
            x[0, P.IN_WILD] = (c["wild"] - P.WILD_NORM[0]) / P.WILD_NORM[1]
            x[0, P.IN_HAS_TREND] = 1.0
            x[0, P.IN_HAS_CLIMATE] = 1.0
            x[0, P.IN_HAS_WILD] = 1.0
            with torch.no_grad():
                out = self.planner(x)[0].float()
            # A light blur hides the 4-cell patch grid of the ViT head; window edges (where the
            # blur sees reflected context) carry ~0 weight in the sin^2 blend.
            out = D.blur(out[None], PATCH_BLUR_CELLS)[0]
            height = out[P.OUT_HEIGHT] * P.HEIGHT_SCALE_M
            desc = out[P.OUT_DESC] * self.desc_std.view(-1, 1, 1) + self.desc_mean.view(-1, 1, 1)
            river = torch.sigmoid(out[P.OUT_RIVER])
            logacc = out[P.OUT_LOGACC] * P.LOGACC_SCALE
            # Ocean: trust the trend below sea level (the slice has little deep sea).
            sea = ((-c["trend"]) / 150.0).clamp(0, 1)
            height = (1 - sea) * height + sea * torch.minimum(height, c["trend"])
            return torch.cat([height[None], desc, river[None], logacc[None]], dim=0)
        return self._windows.get_or((wi, wj), run)

    def cells(self, ci0: int, cj0: int, hc: int, wc: int) -> dict:
        """Blended planner fields + controls for cells [ci0, ci0+hc) x [cj0, cj0+wc)."""
        out = torch.zeros(11, hc, wc, device=self.device)
        u = torch.arange(WIN, device=self.device, dtype=torch.float32)
        w1 = torch.sin(math.pi * (u + 0.5) / WIN) ** 2  # sums to 1 at stride WIN/2
        w2 = w1[:, None] * w1[None, :]
        for wi in range(math.floor((ci0 - WIN) / STRIDE) + 1, math.floor((ci0 + hc - 1) / STRIDE) + 1):
            for wj in range(math.floor((cj0 - WIN) / STRIDE) + 1, math.floor((cj0 + wc - 1) / STRIDE) + 1):
                a0, b0 = wi * STRIDE, wj * STRIDE
                r0, r1 = max(ci0, a0), min(ci0 + hc, a0 + WIN)
                s0, s1 = max(cj0, b0), min(cj0 + wc, b0 + WIN)
                if r0 >= r1 or s0 >= s1:
                    continue
                win = self._window(wi, wj)
                out[:, r0 - ci0 : r1 - ci0, s0 - cj0 : s1 - cj0] += (
                    win[:, r0 - a0 : r1 - a0, s0 - b0 : s1 - b0] * w2[r0 - a0 : r1 - a0, s0 - b0 : s1 - b0])
        ctrl = procedural_controls(ci0, cj0, hc, wc, self.seed, self.device)
        return {"height": out[0], "desc": out[1:9], "river": out[9], "logacc": out[10], **ctrl}

    # ---------------------------------------------------------------- pixels
    def _region(self, ri: int, rj: int) -> torch.Tensor:
        """Native heights for canonical region (ri, rj): core [ri*256, +256) plus apron, (384, 384)."""
        def run():
            m = S.MARGIN_CELLS
            ci0 = (ri * REGION_CORE_PX - REGION_APRON_PX) // CP
            cj0 = (rj * REGION_CORE_PX - REGION_APRON_PX) // CP
            n = REGION_CELLS
            c = self.cells(ci0 - m, cj0 - m, n + 2 * m, n + 2 * m)
            coarse = c["height"][None, None]
            desc = c["desc"][None]
            river = c["river"][None, None]
            origin = (ci0 * CP, cj0 * CP)
            with torch.no_grad():
                # Rivers are carved afterwards by the river pass (terrain()), not here.
                synth = S.synth(coarse, desc, origin, self.seed, amp_scale=self.cfg.amp_scale)
                crop = lambda x: x[..., m * CP : (m + n) * CP, m * CP : (m + n) * CP]
                base = crop(S.coarse_surface(coarse))
                up = lambda x: F.interpolate(x, size=((n + 2 * m) * CP,) * 2, mode="bilinear", align_corners=False)
                desc_px = crop(up(desc))
                river_px = crop(D.blur(up(river), 3.0))
                if self.cfg.t_start >= 1.0:
                    return synth[0, 0]
                scale = R.scale_field(desc_px)
                cond = R.build_cond(base, desc_px, river_px, self.desc_mean, self.desc_std)
                noise = S.white_noise(origin[0], origin[1], n * CP, n * CP, self.seed, 4096, self.device)[None, None]
                x = R.sample(self.refiner, cond, noise, steps=self.cfg.steps,
                             x_start=(synth - base) / scale, t_start=self.cfg.t_start)
                return (base + scale * x)[0, 0]
        return self._regions.get_or((ri, rj), run)

    def native(self, i1: int, j1: int, i2: int, j2: int) -> torch.Tensor:
        """Elevation (m) for native pixels [i1, i2) x [j1, j2), blended across regions."""
        with self.lock:
            h, w = i2 - i1, j2 - j1
            out = torch.zeros(h, w, device=self.device)
            ext = REGION_CORE_PX + 2 * REGION_APRON_PX
            ramp = 2 * REGION_APRON_PX
            u = torch.arange(ext, device=self.device, dtype=torch.float32)
            # Partition of unity at stride REGION_CORE_PX: flat 1 in the core's middle, sin^2 ramps.
            wv = torch.ones(ext, device=self.device)
            t = (u[:ramp] + 0.5) / ramp
            wv[:ramp] = torch.sin(0.5 * math.pi * t) ** 2
            wv[-ramp:] = torch.cos(0.5 * math.pi * t) ** 2
            w2 = wv[:, None] * wv[None, :]
            for ri in range(math.floor((i1 - ext + REGION_APRON_PX) / REGION_CORE_PX),
                            math.floor((i2 - 1 + REGION_APRON_PX) / REGION_CORE_PX) + 1):
                for rj in range(math.floor((j1 - ext + REGION_APRON_PX) / REGION_CORE_PX),
                                math.floor((j2 - 1 + REGION_APRON_PX) / REGION_CORE_PX) + 1):
                    a0 = ri * REGION_CORE_PX - REGION_APRON_PX
                    b0 = rj * REGION_CORE_PX - REGION_APRON_PX
                    r0, r1 = max(i1, a0), min(i2, a0 + ext)
                    s0, s1 = max(j1, b0), min(j2, b0 + ext)
                    if r0 >= r1 or s0 >= s1:
                        continue
                    reg = self._region(ri, rj)
                    out[r0 - i1 : r1 - i1, s0 - j1 : s1 - j1] += (
                        reg[r0 - a0 : r1 - a0, s0 - b0 : s1 - b0] * w2[r0 - a0 : r1 - a0, s0 - b0 : s1 - b0])
            return out

    def terrain(self, i1: int, j1: int, i2: int, j2: int) -> tuple[torch.Tensor, torch.Tensor]:
        """Final native terrain with rivers: (carved elevation m, water surface m / NaN dry)."""
        with self.lock:
            hp = RV.HALO_PX
            a1, b1, a2, b2 = i1 - hp, j1 - hp, i2 + hp, j2 + hp
            heights = self.native(a1, b1, a2, b2)
            ci0, cj0 = a1 // CP - RV.CELL_HALO, b1 // CP - RV.CELL_HALO
            ci1, cj1 = -(-a2 // CP) + RV.CELL_HALO, -(-b2 // CP) + RV.CELL_HALO
            c = self.cells(ci0, cj0, ci1 - ci0, cj1 - cj0)
            carved, surface = RV.carve(heights, c["logacc"], (ci0, cj0), (a1, b1),
                                       log_threshold=self.cfg.river_log_threshold)
            return carved[hp:-hp, hp:-hp], surface[hp:-hp, hp:-hp]

    def climate_native(self, i1: int, j1: int, i2: int, j2: int, elev: torch.Tensor) -> torch.Tensor:
        """(4, H, W) upstream-unit climate at native pixels, temperature lapse-adjusted to `elev`."""
        ci0, cj0 = i1 // CP - 1, j1 // CP - 1
        hc, wc = (i2 - 1) // CP + 2 - ci0, (j2 - 1) // CP + 2 - cj0
        with self.lock:
            ctrl = procedural_controls(ci0, cj0, hc, wc, self.seed, self.device)
        stack = torch.stack([ctrl["t0"], ctrl["tseason"], ctrl["precip"], ctrl["pcv"]])[None]
        up = F.interpolate(stack, size=(hc * CP, wc * CP), mode="bilinear", align_corners=False)[0]
        oi, oj = i1 - ci0 * CP, j1 - cj0 * CP
        clim = up[:, oi : oi + (i2 - i1), oj : oj + (j2 - j1)].clone()
        clim[0] = clim[0] - LAPSE_C_PER_M * elev.clamp_min(0.0)
        return clim
