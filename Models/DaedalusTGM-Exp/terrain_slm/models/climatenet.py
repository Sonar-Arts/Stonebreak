"""Climate node (DaedalusTGM-Exp-2 plan §5b): geography + belt + prevailing wind -> climate, learned from ERA5.

v4's climate is a softmax blend of 7 hand-picked region archetypes plus an analytic lapse: no coasts, no winds, no
mountains, so no rain shadows, no maritime vs continental contrast, no wet windward slopes. This node is trained on
the real planet at ERA5's resolution (0.25 deg, ~28 km): a world's ranges cast rain shadows downwind, coasts are
mild and wet, continental interiors are seasonal and dry. Finer than ~28 km the analytic lapse on regional elevation
stays (v4's ring fix).

Inputs (per grid cell, all things the game's controls can provide):
  elev      land elevation, km (sea = 0)
  land      land fraction
  land_k    land fraction blurred at ~250 / 1000 / 2500 km (continentality)
  coast     signed distance to the coast, asinh(km / 50) (+ inland, - offshore)
  belt      sin(latitude) and |sin(latitude)| (in game: the climate-belt control field)
  wind      prevailing 10 m wind u, v (m/s / 5) (in game: a wind control derived from the belt)
  upwind    (v2, 2026-11-18) sea fraction along the prevailing wind's path within 300 km and 300-1000 km (moisture
            supply: windward coasts are wet, lee coasts and interiors dry) and the highest ground within 300 km
            upwind above the cell, km (rain shadow). Calm winds blend toward the isotropic land_250 / land_1000.
            Shared by training and game (upwind_features + a position fetch), so both see the same definition.
  forcing   (v3) planet-wide constants: global land-mean temperature anomaly vs today (degC / 5) and age (ka / 20).
            Training mixes today's Earth with CHELSA-TraCE21k paleo planets whose climate differs by forcing (orbit,
            CO2, ice) as well as geography -- the 6 ka green Sahara has today's geography -- so the node is told which
            world it is in. The game passes 0, 0 (today); other values are an ice-age / warm-world dial.
Outputs (normalised; see OUT_NAMES / climatenet.denorm): sea-level temperature, seasonality, log precipitation,
precipitation seasonality, log aridity, soil moisture, log snow.

A plain conv UNet with MP layers (models/mp.py), regression (deterministic: climate given geography and wind is
smooth and mostly determined). ~1.5M params.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from terrain_slm.models.mp import MPConv, mp_cat, mp_silu, mp_sum, pixel_norm

IN_NAMES = ("elev", "land", "land_250", "land_1000", "land_2500", "coast", "belt", "belt_abs", "wind_u", "wind_v",
            "up_sea_near", "up_sea_far", "up_barrier", "forcing_dt", "forcing_age")
OUT_NAMES = ("t0", "tseason", "log_precip", "pcv", "log_aridity", "soil", "log_snow")
#: (offset, scale) so each output is ~unit range: value = raw * scale + offset.
OUT_NORM = {"t0": (15.0, 15.0), "tseason": (600.0, 400.0), "log_precip": (6.5, 1.0), "pcv": (60.0, 35.0),
            "log_aridity": (-0.5, 1.2), "soil": (0.25, 0.12), "log_snow": (0.0, 1.0)}
UPWIND_NEAR_KM = (50.0, 100.0, 150.0, 200.0, 250.0, 300.0)
UPWIND_FAR_KM = (400.0, 550.0, 700.0, 850.0, 1000.0)
CALM_MS = 3.0                       # below this mean wind speed the direction stops meaning much
FORCING_DT_SCALE, FORCING_AGE_SCALE = 5.0, 20.0


def upwind_features(fetch, u: torch.Tensor, v: torch.Tensor, km_per_row, km_per_col, elev_km: torch.Tensor,
                    land_250: torch.Tensor, land_1000: torch.Tensor) -> torch.Tensor:
    """(3, H, W) upwind sea near / far and rain-shadow barrier (see the module doc).

    u, v: prevailing wind, m/s (u east, v north); rows grow southward, columns eastward. km_per_row / km_per_col:
    scalars or broadcastable tensors (the globe's columns shrink with cos latitude). fetch(drow, dcol) -> (land,
    elev_km) at each cell's position offset by (drow, dcol) grid cells. The upwind point at distance d is p - d * wind
    direction: a northward wind (v > 0) comes from the south, i.e. + rows.
    """
    s = torch.sqrt(u * u + v * v)
    uh, vh = u / (s + 1e-6), v / (s + 1e-6)
    steady = (s / CALM_MS).clamp(0, 1)

    def at(d):
        return fetch(vh * d / km_per_row, -uh * d / km_per_col)

    sea_near, barrier = 0.0, torch.zeros_like(u)
    for d in UPWIND_NEAR_KM:
        land, elev = at(d)
        sea_near = sea_near + (1 - land) / len(UPWIND_NEAR_KM)
        barrier = torch.maximum(barrier, elev - elev_km)
    sea_far = 0.0
    for d in UPWIND_FAR_KM:
        land, _ = at(d)
        sea_far = sea_far + (1 - land) / len(UPWIND_FAR_KM)
    return torch.stack([steady * sea_near + (1 - steady) * (1 - land_250),
                        steady * sea_far + (1 - steady) * (1 - land_1000),
                        steady * barrier.clamp_min(0)])


@dataclass
class ClimateConfig:
    channels: tuple = (32, 64, 96, 128)
    blocks: int = 2
    arch: str = "climatenet"
    #: inputs used: the first n_in of IN_NAMES. 13 = v2 (no forcing; the shipped climate_r1, whose config predates
    #: this field); train_climate sets len(IN_NAMES).
    n_in: int = 13

    def to_dict(self):
        d = asdict(self)
        d["channels"] = list(self.channels)
        return d


class _Block(nn.Module):
    def __init__(self, c):
        super().__init__()
        self.conv0, self.conv1 = MPConv(c, c), MPConv(c, c)

    def forward(self, x):
        return mp_sum(x, self.conv1(mp_silu(self.conv0(mp_silu(pixel_norm(x))))), t=0.3)


class ClimateNet(nn.Module):
    def __init__(self, cfg: ClimateConfig = ClimateConfig()):
        super().__init__()
        self.cfg = cfg
        ch = cfg.channels
        self.stem = MPConv(cfg.n_in + 1, ch[0])
        self.enc = nn.ModuleList(nn.ModuleList(_Block(c) for _ in range(cfg.blocks)) for c in ch[:-1])
        self.down = nn.ModuleList(MPConv(ch[i], ch[i + 1], stride=2) for i in range(len(ch) - 1))
        self.mid = nn.ModuleList(_Block(ch[-1]) for _ in range(cfg.blocks))
        self.up = nn.ModuleList(MPConv(ch[i + 1], ch[i]) for i in range(len(ch) - 1))
        self.merge = nn.ModuleList(MPConv(2 * c, c, (1, 1)) for c in ch[:-1])
        self.dec = nn.ModuleList(nn.ModuleList(_Block(c) for _ in range(cfg.blocks)) for c in ch[:-1])
        self.out = MPConv(ch[0], len(OUT_NAMES))
        self.out_gain = nn.Parameter(torch.ones([]))

    @property
    def multiple(self) -> int:
        return 2 ** (len(self.cfg.channels) - 1)

    def forward(self, x):
        x = x[:, : self.cfg.n_in]                      # a model trained on fewer inputs ignores the later ones
        h = self.stem(torch.cat([x, torch.ones_like(x[:, :1])], 1))
        skips = []
        for blocks, down in zip(self.enc, self.down):
            for b in blocks:
                h = b(h)
            skips.append(h)
            h = down(mp_silu(h))
        for b in self.mid:
            h = b(h)
        for i in reversed(range(len(self.up))):
            h = self.up[i](F.interpolate(mp_silu(h), scale_factor=2, mode="nearest"))
            h = self.merge[i](mp_cat(h, skips[i]))
            for b in self.dec[i]:
                h = b(h)
        return self.out(mp_silu(h), gain=self.out_gain)


def norm_targets(raw: dict[str, np.ndarray]) -> np.ndarray:
    """ERA5 climatology fields (climate_era5 names) -> (len(OUT_NAMES), H, W) normalised targets."""
    v = {"t0": raw["t0"], "tseason": raw["tseason"], "log_precip": np.log(np.maximum(raw["precip"], 1.0)),
         "pcv": raw["pcv"], "log_aridity": np.log(np.maximum(raw["aridity"], 1e-3)), "soil": raw["soil"],
         "log_snow": np.log1p(np.maximum(raw["snow"], 0.0) * 100.0)}
    return np.stack([(v[k] - OUT_NORM[k][0]) / OUT_NORM[k][1] for k in OUT_NAMES]).astype(np.float32)


def denorm(y: torch.Tensor) -> dict[str, torch.Tensor]:
    """(B, len(OUT_NAMES), H, W) -> the climate_era5 field names in their units."""
    v = {k: y[:, i] * OUT_NORM[k][1] + OUT_NORM[k][0] for i, k in enumerate(OUT_NAMES)}
    return {"t0": v["t0"], "tseason": v["tseason"], "precip": v["log_precip"].exp(), "pcv": v["pcv"].clamp(0, 250),
            "aridity": v["log_aridity"].exp(), "soil": v["soil"].clamp(0, 0.8),
            "snow": (v["log_snow"].exp() - 1.0).clamp_min(0) / 100.0}


def count_params(m: nn.Module) -> int:
    return sum(p.numel() for p in m.parameters())
