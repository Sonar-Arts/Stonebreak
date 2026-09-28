"""DaedalusTGM-Exp world generator: absolute native-pixel coordinates -> elevation (m) + climate.

Pipeline per request:
  procedural controls (cells: continents, mountain ranges, wildness, climate)  ->
  relief sampler windows (canonical 448-cell windows, 256-cell cores, cross-faded): sampled
  valley networks on the smooth trend  ->  planner windows (canonical 64x64 cells, stride 32,
  sin^2 blend = exact partition of unity)  ->  per-cell coarse height / descriptors /
  river probability  ->  canonical pixel regions (256 px core + 64 px apron, cross-faded
  over the 128 px overlap): synth -> refiner SDEdit  ->  native heights.

Every intermediate is computed in a canonical shape from a fixed lattice, so a pixel's
value never depends on which request asked for it (tiles must meet without seams).
"""
from __future__ import annotations

import math
import threading
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path

import torch
import torch.nn.functional as F

from terrain_slm import MODEL_NAME
from terrain_slm.data import descriptors as D
from terrain_slm.models import planner as P
from terrain_slm.models import refiner as R
from terrain_slm.models import descgit as DG
from terrain_slm.models import detail as DT
from terrain_slm.models import biomenet as BN
from terrain_slm.models import hydro as HY
from terrain_slm.models import relief as RL
from terrain_slm.synth import noise as S
from terrain_slm.river import pipeline as RP
from terrain_slm.river.banks import BankConfig, BankNet
from terrain_slm.river.field import Drainage, RiverConfig, RiverField
from terrain_slm.river.scale import BlockScale

CP = D.CELL_PX
WIN = 64  # planner window, cells
STRIDE = 32
REGION_CORE_PX = 256
REGION_APRON_PX = 64
REGION_CELLS = (REGION_CORE_PX + 2 * REGION_APRON_PX) // CP  # 48
# Relief-sampler windows (cells): canonical, cross-faded like the pixel regions.
RELIEF_CORE = 256
RELIEF_APRON = 96
RELIEF_EXT = RELIEF_CORE + 2 * RELIEF_APRON  # 448 cells = 108 km
RELIEF_NOISE_STREAM = 8191
RIVER_THRESHOLD_RELIEF = 4.5
RIVER_THRESHOLD_SMOOTH = 3.3
ROUTE_NOISE_M = 3.0         # routing-surface noise (m, std) -- only steers paths across near-flat ground
ROUTE_NOISE_SIGMA = 2.0     # cells
ROUTE_NOISE_STREAM = 12301
RIVER_THRESHOLD_ROUTED = 7.5  # routed drainage (GenConfig.routed_drainage), calibrated 2026-09-28
RIVER_THRESHOLD_HYDRO = 7.0   # 2026-09-28: 5.5 (~1-5% water on land) read as far too many rivers; 7.0 needs ~4.5x the drainage area
# Descriptor sampler (MaskGIT) tiling: 64-cell windows around 32-cell cores, sampled in a fixed
# 4-phase checkerboard. A window conditions only on the cores of earlier-phase neighbours, so the
# dependency chain is at most 3 windows deep (request-independent) and nothing is cross-faded.
PLANNER_BATCH = 32      # planner windows per forward pass (fixed: one kernel shape, same numbers)
DG_CORE = 32
DG_RING = 16
DG_WIN = DG_CORE + 2 * DG_RING
DG_STEPS = 12
DG_SMOOTH_CELLS = 0.7   # softens codebook quantisation (mostly the smooth coarse bands)
# Detail sampler (v4) windows, in 60 m SAMPLES (= blocks): 512-sample canonical windows around
# 256-sample cores (one tile), cross-faded like the pixel regions. The apron is a third of the
# model's ~400-sample receptive field; the shared coordinate-hashed noise keeps overlaps consistent.
DETAIL_CORE = 256
DETAIL_APRON = 128
DETAIL_EXT = DETAIL_CORE + 2 * DETAIL_APRON
DETAIL_MARGIN_CELLS = 8
DETAIL_BASE_BLUR_CELLS = 1.5   # planner cells are blurred like train_detail's degradation (0.5-2 cells) before the base
# Guard retries, in order: (base blur cells, climate conditioning on). Training dropped climate 15% of the time,
# so climate-off keeps full detail; it is the retry that clears the tropical-archetype windows the r0 checkpoint
# blew up on (low seasonality + high rain over in-game terrain). Last resort: the smooth base alone.
DETAIL_RETRY = ((DETAIL_BASE_BLUR_CELLS, True), (2.5, True), (DETAIL_BASE_BLUR_CELLS, False), (2.5, False))
DETAIL_T_START = 0.0           # a later start (0.05) was tried: it cut val relief amplitude 0.94 -> 0.70;
                               # the t ~ 0 blow-ups are fixed by fine-tuning with t coverage instead (train_detail --t-*)
DETAIL_X_LIMIT = 7.0           # |asinh(residual / 10 m)| above this (~5.5 km) = an unstable sample
DETAIL_NOISE_STREAM = 12289
RELIEF_FLOOR_M = 30.0  # sampled valleys never push inland ground below this (no inland seas)
# Summit soft cap: above the knee, height approaches knee + span but never reaches it, so the
# rare summit taller than the world's height curve allows is rounded, not sheared flat at
# the build limit (the game's curve puts knee + span, 5150 m, at y ~254).
PEAK_KNEE_M = 4300.0
PEAK_SPAN_M = 850.0


def soft_cap_peaks(e: torch.Tensor) -> torch.Tensor:
    d = (e - PEAK_KNEE_M).clamp_min(0.0)
    return e - d + d / (1.0 + d / PEAK_SPAN_M)
LAPSE_C_PER_M = 0.0065
LAPSE_REGIONAL_SIGMA_CELLS = 6.0   # ~1.4 km: biome climate follows regional, not per-hill, elevation
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


def _smoothstep(x: torch.Tensor) -> torch.Tensor:
    x = x.clamp(0, 1)
    return x * x * (3 - 2 * x)


# Mountain ranges (controls v2). Ranges run along the zero lines of a large, lightly
# domain-warped noise field -- long, winding chains -- with a Gaussian cross-profile (spine),
# broader foothills, spur ranges from a finer field, and height that rises and falls along
# the chain. The relief sampler adds the valleys inside them; these are only envelopes.
RANGE_PERIOD_CELLS = 560.0    # zero-line spacing ~ one range per ~70 km
RANGE_WARP_CELLS = 60.0
RANGE_SPINE_WIDTH = 0.5       # in std units of the (normalised) range field
RANGE_ALONG_PERIOD = 900.0    # how far a range runs before it dips into hills
RANGE_ALONG_BIAS = 0.2        # higher = more of every chain is mountain
RANGE_SPINE_M = 2800.0
RANGE_SPUR_M = 900.0
RANGE_FOOTHILL_M = 500.0
_FBM2_STD, _FBM3_STD = 0.27, 0.30  # measured std of _fbm with 2 / 3 octaves
HILL_PERIOD_CELLS = 350.0     # lowland province size (~84 km)
HILL_BIAS = 0.8               # higher = more of the lowland is hilly
HILL_SOFTNESS = 2.4           # wider = more rolling in-between ground, fewer hard plains/hills splits
HILL_PLAINS_M = 100.0         # hills-noise amplitude on plains (std ~30 m: gentle swells, not a table) ...
HILL_COUNTRY_M = 360.0        # ... and in hill country (std ~110 m)
HILL_HOME_MIN = 0.55          # hilliness floor around spawn (0 disables)
HILL_ROUGH = 0.55             # extra roughness in hill country: band-3 ~2.7 m -> ~14 m


def procedural_controls(ci0: int, cj0: int, hc: int, wc: int, seed: int, device) -> dict:
    """Per-cell control fields for cells [ci0, ci0+hc) x [cj0, cj0+wc).

    Stand-ins for painted maps: continents, mountain ranges and hills (the smooth trend),
    roughness (wildness) from the same fields, and climate archetypes. Pointwise in cell
    coordinates, so any window of it is exact.
    """
    ci = torch.arange(ci0, ci0 + hc, device=device, dtype=torch.float32).view(-1, 1).expand(hc, wc)
    cj = torch.arange(cj0, cj0 + wc, device=device, dtype=torch.float32).view(1, -1).expand(hc, wc)
    continent = _fbm(ci, cj, 900.0, seed, 1, octaves=4)
    # "Home continent": a broad bump around the world origin so spawn is always on land.
    continent = continent + 0.7 * torch.exp(-(ci**2 + cj**2) / (2 * HOME_RADIUS_CELLS**2))
    land = _smoothstep((continent + 0.35) / 0.2)  # no cliffs at the coast
    wi = ci + RANGE_WARP_CELLS * _fbm(ci, cj, 400.0, seed, 4, octaves=2)
    wj = cj + RANGE_WARP_CELLS * _fbm(ci, cj, 400.0, seed, 5, octaves=2)
    n_range = _fbm(wi, wj, RANGE_PERIOD_CELLS, seed, 2, octaves=2) / _FBM2_STD
    n_spur = _fbm(wi, wj, 130.0, seed, 6, octaves=2) / _FBM2_STD
    spine = torch.exp(-(n_range / RANGE_SPINE_WIDTH) ** 2)
    foot = torch.exp(-(n_range / (2.4 * RANGE_SPINE_WIDTH)) ** 2)
    # Spur ranges only inside the range body: out in the foothills a thin spur line reads
    # as an artificial winding wall across the lowland.
    body = torch.exp(-(n_range / (1.6 * RANGE_SPINE_WIDTH)) ** 2)
    spur = torch.exp(-(n_spur / 0.6) ** 2) * body
    along = _smoothstep((_fbm(ci, cj, RANGE_ALONG_PERIOD, seed, 7, octaves=2) / _FBM2_STD + RANGE_ALONG_BIAS) / 1.2)
    hills = _fbm(ci, cj, 70.0, seed, 8, octaves=4)
    # Lowland provinces (2026-09-28): plains, rolling hills and hill country, ~80 km across.
    # Calibrated on real hilly lowlands (Appalachian Plateau, Rhenish Massif, Ozarks, Massif
    # Central, Tuscany, Mar de Morros): hill relief at 5-25 km of 26-116 m std and a band-3
    # roughness of 5-18 m, where plains (Great Plains, Sahara) have ~15 m and ~2-3 m.
    hilly = _smoothstep((_fbm(ci, cj, HILL_PERIOD_CELLS, seed, 9, octaves=3) / _FBM3_STD + HILL_BIAS) / HILL_SOFTNESS)
    # Like the home continent: the spawn region is at least rolling hills, never a dead-flat plain.
    hilly = torch.maximum(hilly, HILL_HOME_MIN * torch.exp(-(ci**2 + cj**2) / (2 * HOME_RADIUS_CELLS**2)))
    mountains = along * (RANGE_SPINE_M * spine + RANGE_SPUR_M * spur + RANGE_FOOTHILL_M * foot)
    hill_m = HILL_PLAINS_M + (HILL_COUNTRY_M - HILL_PLAINS_M) * hilly + 200.0 * along * foot
    trend = -300.0 + 600.0 * land * (0.6 + 0.5 * continent) + land * (mountains + hill_m * hills)
    # Roughness in [0, 1] from the same fields, mapped to band-3 log amplitude between
    # gentle plains (~2 m) and alpine relief (~40 m).
    rough = (0.14 + HILL_ROUGH * hilly + along * (0.9 * spine + 0.45 * spur + 0.25 * foot)
             + 0.25 * (0.3 + along * foot) * hills.abs()).clamp(0, 1) * land
    wild = math.log(2.0) + rough * (math.log(40.0) - math.log(2.0))
    return {
        "wild": wild,
        "trend": trend,
        **_climate(ci, cj, seed),
    }


def routed_drainage(height: torch.Tensor, precip_mm: torch.Tensor, inflow_logacc: torch.Tensor | None,
                    flat_noise: torch.Tensor | None = None) -> torch.Tensor:
    """Route water over one window's coarse height: fill depressions (every land cell gets a
    descending path to the sea or the window edge), steepest-descent D8, precipitation-weighted
    accumulation -- the training data's own definition (data/build.py), 3x3-dilated like every
    model's drainage target. Basins reaching past the window enter through its edge: a ring cell
    that drains inward also carries `inflow_logacc` (the hydro sidecar's estimate of the
    upslope area it has) as extra weight. Returns (3, H, W) [log1p(acc), river, D8 class]."""
    import numpy as np
    from terrain_slm.data.build import D8_OFFSETS
    from terrain_slm.data.hydrology.fill import fill_depressions, ocean_mask
    from terrain_slm.data.hydrology.flow import d8_receivers, flow_accumulation

    z = height.detach().double().cpu().numpy()
    h, w = z.shape
    invalid = ocean_mask(z)
    eps_field = None
    if flat_noise is not None:  # meandering paths across filled flats (fill.epsilon_field)
        from terrain_slm.data.hydrology.fill import DEFAULT_EPSILON
        u = np.tanh(flat_noise.detach().double().cpu().numpy())
        eps_field = DEFAULT_EPSILON * (1.0 + 0.9 * u)
    recv = d8_receivers(fill_depressions(z, invalid=invalid, epsilon_field=eps_field), invalid)
    weights = np.clip(precip_mm.detach().double().cpu().numpy(), 0.0, None) / 1000.0
    idx = np.arange(h * w)
    if inflow_logacc is not None:
        ring = np.zeros((h, w), dtype=bool)
        ring[0, :] = ring[-1, :] = ring[:, 0] = ring[:, -1] = True
        inward = ring.ravel() & (recv >= 0)
        extra = np.expm1(inflow_logacc.detach().double().cpu().numpy().clip(0.0, 30.0)).ravel()
        wv = weights.ravel().copy()
        wv[inward] = np.maximum(wv[inward], extra[inward])
        weights = wv.reshape(h, w)
    acc = flow_accumulation(recv, ~invalid, weights=weights)
    d8 = np.full(h * w, 8, dtype=np.int64)
    live = recv >= 0
    dr = recv[live] // w - idx[live] // w
    dc = recv[live] % w - idx[live] % w
    for k, (a, b) in enumerate(D8_OFFSETS):
        d8[np.flatnonzero(live)[(dr == a) & (dc == b)]] = k
    dev = height.device
    acc_t = torch.from_numpy(acc).float().to(dev)
    acc_t = F.max_pool2d(acc_t[None, None], 3, stride=1, padding=1)[0, 0]
    river = (acc_t >= P.RIVER_MIN_UPSLOPE_CELLS).float()
    return torch.stack([torch.log1p(acc_t), river, torch.from_numpy(d8.reshape(h, w)).float().to(dev)])


# ----------------------------------------------------------------------------- generator
@dataclass
class GenConfig:
    t_start: float = 0.6  # 0 = refiner generates from noise, 1 = pure synth
    steps: int = 8
    amp_scale: float = 1.0
    # River density knob (log1p upslope cells); the training definition is log1p(434) ~ 6.08.
    # None = calibrated default for the model: on a smooth trend the planner under-predicts
    # drainage and needs 3.3 to reach the Alps' ~3% river cells; on a relief-sampled trend
    # its drainage is realistic, 3.3 draws a looping mesh and 4.5 gives ~2% tree-like rivers.
    river_log_threshold: float | None = None
    relief_steps: int = 16  # Euler steps of the relief sampler (from pure noise)
    detail_steps: int = 16  # Euler steps of the detail sampler (from pure noise)
    # Drainage by routing water over the generated coarse height (fill -> D8 -> precipitation-
    # weighted accumulation) in each canonical hydro window, instead of the hydro sidecar's
    # regression: accumulation then grows downstream by construction, so rivers form connected
    # networks instead of fragments that start and stop wherever the regressed flow wobbles.
    routed_drainage: bool = True


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
        # Optional relief sampler: without relief.pt the smooth trend goes straight to the
        # planner (the pre-v2 pipeline).
        self.relief = None
        rel_step = "none"
        if (model_dir / "relief.pt").exists():
            lk = torch.load(model_dir / "relief.pt", map_location=device, weights_only=False)
            lc = lk["config"]
            lc["channels"] = tuple(lc["channels"])
            self.relief = RL.Relief(RL.ReliefConfig(**lc)).to(device).eval()
            self.relief.load_state_dict(lk["model"])
            rel_step = lk.get("step", 0)
        # Optional hydrology sidecar (drainage from the final coarse height) and bank model
        # (the learned bank stage of the river pipeline).
        self.hydro, hyd_step = self._optional(model_dir / "hydro.pt", lambda c: HY.Hydro(HY.HydroConfig(**c)))
        self.banks, bank_step = self._optional(model_dir / "bank.pt", lambda c: BankNet(BankConfig(**c)))
        self.descgit, dg_step = self._optional(model_dir / "descgit.pt", lambda c: DG.DescGit(DG.DescGitConfig(**c)))
        # Optional detail sampler (v4): replaces synth + refiner (and so the descriptor sampler)
        # for heights, drawing block-resolution terrain straight from the coarse cells + drainage.
        self.detail, det_step = self._optional(model_dir / "detail.pt", lambda c: DT.Detail(DT.DetailConfig(**c)))
        if self.detail is not None:
            self.detail = self.detail.to(memory_format=torch.channels_last)
        self.model_id = (f"{MODEL_NAME}/{model_dir.name}:p{pk.get('step', 0)}:r{rk.get('step', 0)}:l{rel_step}"
                         f":h{hyd_step}:b{bank_step}:g{dg_step}:d{det_step}")
        # Optional biome sidecar (v4): coherent biomes instead of the rule classifier's per-column decisions.
        self.biomenet, bio_step = self._optional(model_dir / "biome.pt", lambda c: BN.BiomeNet(BN.BiomeNetConfig(**c)))
        if bio_step != "none":
            self.model_id += f":B{bio_step}"
        self._detail_windows = _LRU(max(64, cache_regions // 4))
        self.detail_failures = 0
        self._dg_windows = _LRU(8192)
        if cfg.river_log_threshold is None:
            from dataclasses import replace
            thr = (RIVER_THRESHOLD_ROUTED if cfg.routed_drainage else
                   RIVER_THRESHOLD_HYDRO if self.hydro is not None else
                   RIVER_THRESHOLD_RELIEF if self.relief is not None else RIVER_THRESHOLD_SMOOTH)
            self.cfg = replace(cfg, river_log_threshold=thr)
        self.river = RP.default_pipeline(self.banks)
        self.river_cfg = RiverConfig(log_threshold=self.cfg.river_log_threshold)
        self.scale = BlockScale.game_default(device)
        self._hydro_windows = _LRU(64)
        self._relief_windows = _LRU(64)
        self._windows = _LRU(4096)
        self._regions = _LRU(cache_regions)
        self.lock = threading.RLock()

    def _optional(self, path: Path, build):
        if not path.exists():
            return None, "none"
        k = torch.load(path, map_location=self.device, weights_only=False)
        c = dict(k["config"])
        if "channels" in c:
            c["channels"] = tuple(c["channels"])
        m = build(c).to(self.device).eval()
        m.load_state_dict(k["model"])
        return m, k.get("step", 0)

    def set_block_scale(self, scale: BlockScale) -> None:
        """The game's metres<->blocks mapping (the service passes the one from its handshake)."""
        self.scale = scale

    def use_seed(self, seed: int) -> None:
        """Generate for `seed` from now on (hold `lock` across this and the calls it is for).

        The weights do not depend on the seed and every cache is keyed by it, so switching seeds
        costs nothing: one loaded model serves the world and the terrain mapper side by side, and
        the old seed's entries simply age out of the LRUs."""
        self.seed = seed

    # ---------------------------------------------------------------- window blending (cells)
    @staticmethod
    def _fade(ext: int, apron: int, device) -> torch.Tensor:
        ramp = 2 * apron
        t = (torch.arange(ramp, device=device, dtype=torch.float32) + 0.5) / ramp
        wv = torch.ones(ext, device=device)
        wv[:ramp] = torch.sin(0.5 * math.pi * t) ** 2
        wv[-ramp:] = torch.cos(0.5 * math.pi * t) ** 2
        return wv[:, None] * wv[None, :]

    def _blend(self, ci0: int, cj0: int, hc: int, wc: int, fetch, channels: int) -> torch.Tensor:
        """Cross-fade canonical RELIEF_EXT-cell windows (core RELIEF_CORE, apron RELIEF_APRON):
        `fetch(wi, wj)` -> (channels, EXT, EXT). Returns (channels, hc, wc)."""
        out = torch.zeros(channels, hc, wc, device=self.device)
        ext = RELIEF_EXT
        w2 = self._fade(ext, RELIEF_APRON, self.device)
        for wi in range(math.floor((ci0 - ext + RELIEF_APRON) / RELIEF_CORE),
                        math.floor((ci0 + hc - 1 + RELIEF_APRON) / RELIEF_CORE) + 1):
            for wj in range(math.floor((cj0 - ext + RELIEF_APRON) / RELIEF_CORE),
                            math.floor((cj0 + wc - 1 + RELIEF_APRON) / RELIEF_CORE) + 1):
                a0, b0 = wi * RELIEF_CORE - RELIEF_APRON, wj * RELIEF_CORE - RELIEF_APRON
                r0, r1 = max(ci0, a0), min(ci0 + hc, a0 + ext)
                s0, s1 = max(cj0, b0), min(cj0 + wc, b0 + ext)
                if r0 >= r1 or s0 >= s1:
                    continue
                win = fetch(wi, wj)
                out[:, r0 - ci0 : r1 - ci0, s0 - cj0 : s1 - cj0] += (
                    win[:, r0 - a0 : r1 - a0, s0 - b0 : s1 - b0] * w2[r0 - a0 : r1 - a0, s0 - b0 : s1 - b0])
        return out

    def _owned(self, ci0: int, cj0: int, hc: int, wc: int, fetch) -> torch.Tensor:
        """Categorical window output, taken from the window whose CORE owns each cell (classes
        cannot be cross-faded). `fetch(wi, wj)` -> (EXT, EXT)."""
        out = torch.zeros(hc, wc, dtype=torch.long, device=self.device)
        for wi in range(math.floor(ci0 / RELIEF_CORE), math.floor((ci0 + hc - 1) / RELIEF_CORE) + 1):
            for wj in range(math.floor(cj0 / RELIEF_CORE), math.floor((cj0 + wc - 1) / RELIEF_CORE) + 1):
                a0, b0 = wi * RELIEF_CORE, wj * RELIEF_CORE
                r0, r1 = max(ci0, a0), min(ci0 + hc, a0 + RELIEF_CORE)
                s0, s1 = max(cj0, b0), min(cj0 + wc, b0 + RELIEF_CORE)
                win = fetch(wi, wj)
                oa, ob = a0 - RELIEF_APRON, b0 - RELIEF_APRON
                out[r0 - ci0 : r1 - ci0, s0 - cj0 : s1 - cj0] = win[r0 - oa : r1 - oa, s0 - ob : s1 - ob]
        return out

    # ---------------------------------------------------------------- relief (cells)
    def _relief_window(self, wi: int, wj: int) -> torch.Tensor:
        """Trend with sampled relief for the canonical window whose core starts at cell
        (wi*RELIEF_CORE, wj*RELIEF_CORE): (RELIEF_EXT, RELIEF_EXT) metres, apron included."""
        def run():
            ci0, cj0 = wi * RELIEF_CORE - RELIEF_APRON, wj * RELIEF_CORE - RELIEF_APRON
            n, m = RELIEF_EXT, math.ceil(3 * RL.TREND_PREBLUR_SIGMA)
            c = procedural_controls(ci0 - m, cj0 - m, n + 2 * m, n + 2 * m, self.seed, self.device)
            crop = lambda x: x[..., m : m + n, m : m + n]
            smooth = crop(D.blur(c["trend"][None, None], RL.TREND_PREBLUR_SIGMA))
            wild = (crop(c["wild"])[None, None] - P.WILD_NORM[0]) / P.WILD_NORM[1]
            climate = {k: crop(c[k])[None, None] for k in P.CLIMATE_NAMES}
            one = torch.ones(1, 1, 1, 1, device=self.device)
            cond = RL.build_cond(smooth, wild, climate, one, one)
            noise = S.white_noise(ci0, cj0, n, n, self.seed, RELIEF_NOISE_STREAM, self.device)[None, None]
            with torch.no_grad():
                x = R.sample(self.relief, cond, noise, steps=self.cfg.relief_steps)
            # Oceans keep the trend (the training regions have little deep sea); inland,
            # sampled valleys may not open basins below sea level.
            gate = _smoothstep((smooth + 100.0) / 300.0)
            out = smooth + gate * RL.RELIEF_SCALE_M * x
            floor = torch.minimum(smooth, torch.full_like(smooth, RELIEF_FLOOR_M))
            out = torch.where(smooth > 0, torch.maximum(out, floor), out)
            return out[0, 0]
        return self._relief_windows.get_or((self.seed, wi, wj), run)

    def trend(self, ci0: int, cj0: int, hc: int, wc: int) -> torch.Tensor:
        """The planner's trend for cells [ci0, ci0+hc) x [cj0, cj0+wc): the procedural trend
        plus sampled valley-scale relief (plain procedural trend without a relief model)."""
        if self.relief is None:
            return procedural_controls(ci0, cj0, hc, wc, self.seed, self.device)["trend"]
        return self._blend(ci0, cj0, hc, wc, lambda wi, wj: self._relief_window(wi, wj)[None], 1)[0]

    # ---------------------------------------------------------------- planner (cells)
    def _planner_input(self, ci0: int, cj0: int, n: int, m: int | None = None) -> tuple[torch.Tensor, dict]:
        """The planner's (and descriptor sampler's) input for n x m cells from (ci0, cj0) (m = n).
        Every field is pointwise or blended from canonical windows, so any sub-window of a larger
        call is identical to calling it on that sub-window alone."""
        m = n if m is None else m
        c = procedural_controls(ci0, cj0, n, m, self.seed, self.device)
        c["trend"] = self.trend(ci0, cj0, n, m)
        x = torch.zeros(1, P.N_IN, n, m, device=self.device)
        x[0, P.IN_TREND] = c["trend"] / P.HEIGHT_SCALE_M
        for k, name in enumerate(P.CLIMATE_NAMES):
            x[0, P.IN_T0 + k] = P.climate_input(name, c[name])
        x[0, P.IN_WILD] = (c["wild"] - P.WILD_NORM[0]) / P.WILD_NORM[1]
        x[0, P.IN_HAS_TREND] = 1.0
        x[0, P.IN_HAS_CLIMATE] = 1.0
        x[0, P.IN_HAS_WILD] = 1.0
        return x, c

    def _run_planner_windows(self, keys: list[tuple[int, int]]) -> None:
        """Compute and cache the canonical planner windows `keys` (not yet cached), batched.

        The inputs of all of them come from one call over their bounding box (exact, see
        _planner_input), and the planner runs PLANNER_BATCH windows at a time -- always padded to
        that size, a single window included, so a window's numbers never depend on which request
        computed it (one kernel shape). Per-window work was ~31 ms of launch overhead each; a
        far-zoom preview needs thousands of windows."""
        if not keys:
            return
        i0 = min(k[0] for k in keys) * STRIDE
        j0 = min(k[1] for k in keys) * STRIDE
        hn = max(k[0] for k in keys) * STRIDE + WIN - i0
        wn = max(k[1] for k in keys) * STRIDE + WIN - j0
        if hn * wn > 16 * len(keys) * WIN * WIN:     # scattered: the bounding box would be wasteful
            for k in keys:
                self._run_planner_windows([k])
            return
        xa, ca = self._planner_input(i0, j0, hn, wn)
        for start in range(0, len(keys), PLANNER_BATCH):
            chunk = keys[start : start + PLANNER_BATCH]
            xs = torch.zeros(PLANNER_BATCH, P.N_IN, WIN, WIN, device=self.device)
            for b, (wi, wj) in enumerate(chunk):
                a, c0 = wi * STRIDE - i0, wj * STRIDE - j0
                xs[b] = xa[0, :, a : a + WIN, c0 : c0 + WIN]
            with torch.no_grad():
                out = self.planner(xs).float()
            # A light blur hides the 4-cell patch grid of the ViT head; window edges (where the
            # blur sees reflected context) carry ~0 weight in the sin^2 blend.
            out = D.blur(out, PATCH_BLUR_CELLS)
            for b, (wi, wj) in enumerate(chunk):
                o = out[b]
                a, c0 = wi * STRIDE - i0, wj * STRIDE - j0
                trend = ca["trend"][a : a + WIN, c0 : c0 + WIN]
                height = o[P.OUT_HEIGHT] * P.HEIGHT_SCALE_M
                desc = o[P.OUT_DESC] * self.desc_std.view(-1, 1, 1) + self.desc_mean.view(-1, 1, 1)
                river = torch.sigmoid(o[P.OUT_RIVER])
                logacc = o[P.OUT_LOGACC] * P.LOGACC_SCALE
                # Ocean: trust the trend below sea level (the slice has little deep sea).
                sea = ((-trend) / 150.0).clamp(0, 1)
                height = (1 - sea) * height + sea * torch.minimum(height, trend)
                val = torch.cat([height[None], desc, river[None], logacc[None]], dim=0)
                self._windows.get_or((self.seed, wi, wj), lambda v=val: v)

    def _window(self, wi: int, wj: int) -> torch.Tensor:
        """Planner output for the canonical window whose top-left cell is (wi*STRIDE, wj*STRIDE):
        (11, WIN, WIN) = [height_m, desc(8, raw units), river_prob, log1p(upslope cells)]."""
        if (self.seed, wi, wj) not in self._windows:
            self._run_planner_windows([(wi, wj)])
        return self._windows.get_or((self.seed, wi, wj), lambda: None)

    def _planner_cells(self, ci0: int, cj0: int, hc: int, wc: int) -> torch.Tensor:
        """Blended planner output (11, hc, wc) for cells [ci0, ci0+hc) x [cj0, cj0+wc)."""
        wis = range(math.floor((ci0 - WIN) / STRIDE) + 1, math.floor((ci0 + hc - 1) / STRIDE) + 1)
        wjs = range(math.floor((cj0 - WIN) / STRIDE) + 1, math.floor((cj0 + wc - 1) / STRIDE) + 1)
        # Batch every missing window first, a band of rows at a time (bounded by the cache).
        band = max(1, (self._windows.cap // 2) // max(1, len(wjs)))
        for r in range(0, len(wis), band):
            self._run_planner_windows([(wi, wj) for wi in wis[r : r + band] for wj in wjs
                                       if (self.seed, wi, wj) not in self._windows])
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
        return out

    # ---------------------------------------------------------------- descriptors (cells)
    @staticmethod
    def _dg_phase(wi: int, wj: int) -> int:
        return (wi & 1) + 2 * (wj & 1)

    def _dg_window(self, wi: int, wj: int) -> tuple[torch.Tensor, torch.Tensor]:
        """Sampled descriptor tokens (amp, shape) for the core cells [wi*DG_CORE, +DG_CORE) x
        [wj*DG_CORE, +DG_CORE), conditioned on earlier-phase neighbours' cores."""
        def run():
            ci0, cj0 = wi * DG_CORE - DG_RING, wj * DG_CORE - DG_RING
            x, _ = self._planner_input(ci0, cj0, DG_WIN)
            a = torch.full((1, DG_WIN, DG_WIN), DG.K_AMP, dtype=torch.long, device=self.device)
            s = torch.full((1, DG_WIN, DG_WIN), DG.K_SHAPE, dtype=torch.long, device=self.device)
            known = torch.zeros(1, DG_WIN, DG_WIN, dtype=torch.bool, device=self.device)
            me = self._dg_phase(wi, wj)
            for di in (-1, 0, 1):
                for dj in (-1, 0, 1):
                    if (di, dj) == (0, 0) or self._dg_phase(wi + di, wj + dj) >= me:
                        continue
                    na, ns = self._dg_window(wi + di, wj + dj)
                    # Neighbour core in this window's coordinates, clipped to the window.
                    r0, c0 = DG_RING + di * DG_CORE, DG_RING + dj * DG_CORE
                    rr0, rr1 = max(0, r0), min(DG_WIN, r0 + DG_CORE)
                    cc0, cc1 = max(0, c0), min(DG_WIN, c0 + DG_CORE)
                    a[0, rr0:rr1, cc0:cc1] = na[rr0 - r0 : rr1 - r0, cc0 - c0 : cc1 - c0]
                    s[0, rr0:rr1, cc0:cc1] = ns[rr0 - r0 : rr1 - r0, cc0 - c0 : cc1 - c0]
                    known[0, rr0:rr1, cc0:cc1] = True
            ii = torch.arange(ci0, ci0 + DG_WIN, device=self.device, dtype=torch.int64).view(-1, 1)
            jj = torch.arange(cj0, cj0 + DG_WIN, device=self.device, dtype=torch.int64).view(1, -1)
            keys = S._hash32(S._hash32(ii ^ (self.seed * 0x9E3779B1 & S._MASK32)) ^ jj)[None]
            sa, ss = DG.sample(self.descgit, x, a, s, known, keys, steps=DG_STEPS)
            core = lambda t: t[0, DG_RING : DG_RING + DG_CORE, DG_RING : DG_RING + DG_CORE]
            return core(sa), core(ss)
        return self._dg_windows.get_or((self.seed, wi, wj), run)

    def sampled_desc(self, ci0: int, cj0: int, hc: int, wc: int) -> torch.Tensor:
        """(8, hc, wc) raw descriptors sampled by the MaskGIT sampler (hard core ownership)."""
        m = 2
        a0, b0, h, w = ci0 - m, cj0 - m, hc + 2 * m, wc + 2 * m
        a = torch.zeros(h, w, dtype=torch.long, device=self.device)
        s = torch.zeros(h, w, dtype=torch.long, device=self.device)
        for wi in range(math.floor(a0 / DG_CORE), math.floor((a0 + h - 1) / DG_CORE) + 1):
            for wj in range(math.floor(b0 / DG_CORE), math.floor((b0 + w - 1) / DG_CORE) + 1):
                ca, cs = self._dg_window(wi, wj)
                r0, c0 = wi * DG_CORE, wj * DG_CORE
                rr0, rr1 = max(a0, r0), min(a0 + h, r0 + DG_CORE)
                cc0, cc1 = max(b0, c0), min(b0 + w, c0 + DG_CORE)
                a[rr0 - a0 : rr1 - a0, cc0 - b0 : cc1 - b0] = ca[rr0 - r0 : rr1 - r0, cc0 - c0 : cc1 - c0]
                s[rr0 - a0 : rr1 - a0, cc0 - b0 : cc1 - b0] = cs[rr0 - r0 : rr1 - r0, cc0 - c0 : cc1 - c0]
        d = self.descgit.codebook.decode(a[None], s[None])
        d = D.blur(d, DG_SMOOTH_CELLS)[0, :, m:-m, m:-m]
        return d * self.desc_std.view(-1, 1, 1) + self.desc_mean.view(-1, 1, 1)

    # ---------------------------------------------------------------- hydrology (cells)
    def _hydro_window(self, wi: int, wj: int) -> torch.Tensor:
        """Drainage for one canonical window on the planner's final coarse height:
        (3, EXT, EXT) = [log1p(upslope cells), river probability, D8 class (as float)].
        Routed (cfg.routed_drainage) or the hydro sidecar's prediction."""
        def run():
            ci0, cj0 = wi * RELIEF_CORE - RELIEF_APRON, wj * RELIEF_CORE - RELIEF_APRON
            n = RELIEF_EXT
            height = self._planner_cells(ci0, cj0, n, n)[0]
            c = procedural_controls(ci0, cj0, n, n, self.seed, self.device)
            pred = None
            if self.hydro is not None:
                x = HY.build_input(height[None, None], {k: c[k][None, None] for k in P.CLIMATE_NAMES})
                with torch.no_grad(), torch.autocast("cuda", dtype=torch.bfloat16, enabled=x.is_cuda):
                    out = self.hydro(x)[0].float()
                pred = torch.stack([out[HY.OUT_LOGACC] * P.LOGACC_SCALE, torch.sigmoid(out[HY.OUT_RIVER]),
                                    out[HY.OUT_D8].argmax(0).float()])
            if not self.cfg.routed_drainage and pred is not None:
                return pred
            # Route over the height plus a few metres of smooth, coordinate-hashed noise: on near-flat
            # plains a steepest-descent path otherwise runs dead straight along the cell grid.
            jit = D.blur(S.white_noise(ci0, cj0, n, n, self.seed, ROUTE_NOISE_STREAM, self.device)[None, None],
                         ROUTE_NOISE_SIGMA)[0, 0]
            jit = jit * (ROUTE_NOISE_M * 2.0 * ROUTE_NOISE_SIGMA * math.sqrt(math.pi))  # std of blurred white noise = 1/(2 sigma sqrt(pi))
            return routed_drainage(height + jit * (height > 0), c["precip"], None if pred is None else pred[0],
                                   flat_noise=jit / ROUTE_NOISE_M)
        return self._hydro_windows.get_or((self.seed, wi, wj), run)

    def drainage(self, ci0: int, cj0: int, hc: int, wc: int) -> dict:
        """Per-cell drainage (logacc, river, d8): routed and/or the hydro sidecar's, else {}."""
        if self.hydro is None and not self.cfg.routed_drainage:
            return {}
        fields = self._blend(ci0, cj0, hc, wc, lambda wi, wj: self._hydro_window(wi, wj)[:2], 2)
        d8 = self._owned(ci0, cj0, hc, wc, lambda wi, wj: self._hydro_window(wi, wj)[2].long())
        return {"logacc": fields[0], "river": fields[1], "d8": d8}

    def cells(self, ci0: int, cj0: int, hc: int, wc: int) -> dict:
        """Blended planner fields + controls for cells [ci0, ci0+hc) x [cj0, cj0+wc); drainage
        (logacc, river, d8) from the hydro sidecar when one is loaded."""
        out = self._planner_cells(ci0, cj0, hc, wc)
        ctrl = procedural_controls(ci0, cj0, hc, wc, self.seed, self.device)
        ctrl["trend_raw"] = ctrl["trend"]
        ctrl["trend"] = self.trend(ci0, cj0, hc, wc)
        res = {"height": out[0], "desc": out[1:9], "river": out[9], "logacc": out[10], "d8": None, **ctrl}
        res.update(self.drainage(ci0, cj0, hc, wc))
        if self.descgit is not None:
            res["desc_planner"] = res["desc"]
            res["desc"] = self.sampled_desc(ci0, cj0, hc, wc)
        return res

    def overview_cells(self, ci0: int, cj0: int, hc: int, wc: int) -> dict:
        """The cheap, coarse view of cells [ci0, ci0+hc) x [cj0, cj0+wc) for far-zoomed previews:
        soft-capped coarse height, drainage (logacc, d8) and lapse-adjusted climate, from the planner,
        relief and hydro windows only -- no descriptor sampling, synth, refiner or river pipeline."""
        with self.lock:
            height = soft_cap_peaks(self._planner_cells(ci0, cj0, hc, wc)[0])
            dr = self.drainage(ci0, cj0, hc, wc)
            if not dr:
                dr = {"logacc": self._planner_cells(ci0, cj0, hc, wc)[10], "d8": None}
            c = procedural_controls(ci0, cj0, hc, wc, self.seed, self.device)
            m = int(math.ceil(3 * LAPSE_REGIONAL_SIGMA_CELLS)) + 1   # regional lapse, as full tiles use
            reg = D.blur(soft_cap_peaks(self._planner_cells(ci0 - m, cj0 - m, hc + 2 * m, wc + 2 * m)[0])[None, None],
                         LAPSE_REGIONAL_SIGMA_CELLS)[0, 0, m:-m, m:-m]
            climate = torch.stack([c["t0"] - LAPSE_C_PER_M * reg.clamp_min(0.0),
                                   c["tseason"], c["precip"], c["pcv"]])
            return {"height": height, "logacc": dr["logacc"], "d8": dr.get("d8"), "climate": climate}

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
        return self._regions.get_or((self.seed, ri, rj), run)

    # ---------------------------------------------------------------- detail sampler (60 m samples)
    def _detail_window(self, wi: int, wj: int) -> torch.Tensor:
        """Heights (m) of canonical detail window (wi, wj): samples [wi*CORE - APRON, +EXT)^2."""
        def run():
            spc = DT.SAMPLES_PER_CELL
            si0, sj0 = wi * DETAIL_CORE - DETAIL_APRON, wj * DETAIL_CORE - DETAIL_APRON
            mc = DETAIL_MARGIN_CELLS
            nc = DETAIL_EXT // spc + 2 * mc
            ci0, cj0 = si0 // spc - mc, sj0 // spc - mc
            # Blur the planner's cells by one cell before building the base, exactly as training and
            # validation did (train_detail: blur 0.5-2 cells; val 1.0). Unblurred planner output keeps a faint
            # 4-cell (16-sample) patch texture that the sampler never saw and that its stride-16 level resonates with.
            raw_height = self._planner_cells(ci0, cj0, nc, nc)[0][None, None]
            ctrl = procedural_controls(ci0, cj0, nc, nc, self.seed, self.device)
            dr = self.drainage(ci0, cj0, nc, nc)
            if dr:
                logacc = dr["logacc"][None, None] / P.LOGACC_SCALE
                river = D.blur(dr["river"][None, None], 0.7)   # as in training (train_detail)
                has_flow = 1.0
            else:
                logacc = river = torch.zeros_like(height)
                has_flow = 0.0
            ms = mc * spc
            crop = lambda x: x[..., ms : ms + DETAIL_EXT, ms : ms + DETAIL_EXT]
            up = lambda x: crop(DT.up_cells(x))
            wild = ((ctrl["wild"] - P.WILD_NORM[0]) / P.WILD_NORM[1])[None, None]
            climate = torch.stack([P.climate_input(n, ctrl[n]) for n in P.CLIMATE_NAMES])[None]
            flag = lambda v: torch.full((1, 1, 1, 1), v, device=self.device)
            noise = S.white_noise(si0, sj0, DETAIL_EXT, DETAIL_EXT, self.seed, DETAIL_NOISE_STREAM, self.device)[None, None]
            # Guard: a window whose sample is non-finite or implausible is resampled from a smoother base
            # (the known trigger is fine texture in the base), and loudly reported; if it still fails, the
            # window falls back to the smooth base alone rather than ever shipping garbage terrain.
            for blur, use_climate in DETAIL_RETRY:
                base = crop(DT.detail_base(D.blur(raw_height, blur)))
                cond = DT.build_cond(base, torch.zeros(1, 8, DETAIL_EXT, DETAIL_EXT, device=self.device), up(wild),
                                     up(climate), up(logacc), up(river), flag(0.0), flag(1.0), flag(float(use_climate)),
                                     flag(has_flow))
                with torch.no_grad():
                    x = DT.sample(self.detail, cond.contiguous(memory_format=torch.channels_last), noise,
                                  steps=self.cfg.detail_steps, t_start=DETAIL_T_START)
                res = DT.decode(x.clamp(-DETAIL_X_LIMIT, DETAIL_X_LIMIT))
                ok = bool(torch.isfinite(x).all()) and float(x.abs().max()) < DETAIL_X_LIMIT
                if ok:
                    break
                self.detail_failures += 1
                print(f"[detail] WARNING: window ({wi},{wj}) seed {self.seed} unstable (base blur {blur} cells, "
                      f"climate {'on' if use_climate else 'off'}, max |x| {float(x.nan_to_num(1e9).abs().max()):.3g}); retrying",
                      flush=True)
            else:
                print(f"[detail] WARNING: window ({wi},{wj}) seed {self.seed} fell back to the smooth base", flush=True)
                res = torch.zeros_like(base)
            return (base + res)[0, 0]
        return self._detail_windows.get_or((self.seed, wi, wj), run)

    def _detail_samples(self, s1: int, t1: int, s2: int, t2: int) -> torch.Tensor:
        """Heights (m) for 60 m samples [s1, s2) x [t1, t2), cross-faded across detail windows."""
        h, w = s2 - s1, t2 - t1
        out = torch.zeros(h, w, device=self.device)
        w2 = self._fade(DETAIL_EXT, DETAIL_APRON, self.device)
        for wi in range(math.floor((s1 - DETAIL_EXT + DETAIL_APRON) / DETAIL_CORE),
                        math.floor((s2 - 1 + DETAIL_APRON) / DETAIL_CORE) + 1):
            for wj in range(math.floor((t1 - DETAIL_EXT + DETAIL_APRON) / DETAIL_CORE),
                            math.floor((t2 - 1 + DETAIL_APRON) / DETAIL_CORE) + 1):
                a0, b0 = wi * DETAIL_CORE - DETAIL_APRON, wj * DETAIL_CORE - DETAIL_APRON
                r0, r1 = max(s1, a0), min(s2, a0 + DETAIL_EXT)
                q0, q1 = max(t1, b0), min(t2, b0 + DETAIL_EXT)
                if r0 >= r1 or q0 >= q1:
                    continue
                win = self._detail_window(wi, wj)
                out[r0 - s1 : r1 - s1, q0 - t1 : q1 - t1] += (
                    win[r0 - a0 : r1 - a0, q0 - b0 : q1 - b0] * w2[r0 - a0 : r1 - a0, q0 - b0 : q1 - b0])
        return out

    def _native_detail(self, i1: int, j1: int, i2: int, j2: int) -> torch.Tensor:
        """Native pixels from the detail sampler: each 60 m sample covers 2x2 native pixels
        (nearest), so the tile builder's 2x2 block mean returns the model's samples exactly."""
        ds = DT.DS
        s1, t1 = i1 // ds, j1 // ds
        s2, t2 = -(-i2 // ds), -(-j2 // ds)
        samp = self._detail_samples(s1, t1, s2, t2)
        px = samp.repeat_interleave(ds, 0).repeat_interleave(ds, 1)
        oi, oj = i1 - s1 * ds, j1 - t1 * ds
        return px[oi : oi + (i2 - i1), oj : oj + (j2 - j1)]

    def native(self, i1: int, j1: int, i2: int, j2: int) -> torch.Tensor:
        """Elevation (m) for native pixels [i1, i2) x [j1, j2), blended across regions."""
        with self.lock:
            if self.detail is not None:
                return self._native_detail(i1, j1, i2, j2)
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

    def river_field(self, i1: int, j1: int, i2: int, j2: int):
        """Run the river pipeline over native pixels [i1, i2) x [j1, j2): (RiverField cropped to
        the request, RiverContext whose px_origin is (i1, j1))."""
        with self.lock:
            hp = RP.HALO_PX
            a1, b1, a2, b2 = i1 - hp, j1 - hp, i2 + hp, j2 + hp
            heights = soft_cap_peaks(self.native(a1, b1, a2, b2))
            ci0, cj0 = a1 // CP - RP.CELL_HALO, b1 // CP - RP.CELL_HALO
            ci1, cj1 = -(-a2 // CP) + RP.CELL_HALO, -(-b2 // CP) + RP.CELL_HALO
            c = self.cells(ci0, cj0, ci1 - ci0, cj1 - cj0)
            field, ctx = self.river.run(heights, Drainage(c["logacc"], (ci0, cj0), c["d8"]), (a1, b1),
                                        self.scale, self.river_cfg, self.seed)
            crop = lambda x: x[hp:-hp, hp:-hp] if isinstance(x, torch.Tensor) and x.dim() == 2 else x
            field = RiverField(**{k: crop(v) for k, v in vars(field).items()})
            ctx.px_origin = (i1, j1)
            return field, ctx

    def terrain(self, i1: int, j1: int, i2: int, j2: int) -> tuple[torch.Tensor, torch.Tensor]:
        """Final native terrain with rivers: (carved elevation m, water surface m / NaN dry)."""
        field, _ = self.river_field(i1, j1, i2, j2)
        return field.carved, field.top

    def regional_elevation(self, i1: int, j1: int, i2: int, j2: int) -> torch.Tensor:
        """(H, W) metres at native pixels [i1, i2) x [j1, j2): the planner's coarse height blurred by
        LAPSE_REGIONAL_SIGMA_CELLS, for the climate lapse rate. Built from canonical cells, so it is
        request-independent. Per-column elevation made biome boundaries trace every hill's contours."""
        m = int(math.ceil(3 * LAPSE_REGIONAL_SIGMA_CELLS)) + 1
        ci0, cj0 = i1 // CP - m, j1 // CP - m
        hc, wc = (i2 - 1) // CP + 1 + m - ci0, (j2 - 1) // CP + 1 + m - cj0
        with self.lock:
            cells = self._planner_cells(ci0, cj0, hc, wc)[0]
        reg = D.blur(soft_cap_peaks(cells)[None, None], LAPSE_REGIONAL_SIGMA_CELLS)
        up = F.interpolate(reg, size=(hc * CP, wc * CP), mode="bilinear", align_corners=False)[0, 0]
        oi, oj = i1 - ci0 * CP, j1 - cj0 * CP
        return up[oi : oi + (i2 - i1), oj : oj + (j2 - j1)]

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
