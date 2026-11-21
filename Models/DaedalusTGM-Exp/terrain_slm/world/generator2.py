"""EXP-2 generator (plan §3, §5b, §2): the base WorldGenerator (EXP-1's v4 pipeline; v4 itself was removed 2026-11-19)
with the EXP-2 nodes plugged into its hooks. The only generator the service, the warm-up and make_release run.

    gen = WorldGenerator2(model_dir, seed, device)          # same API as WorldGenerator

Uses what the model directory holds and falls back to the base pipeline for what it does not, so it runs at every
stage of EXP-2's training:
  controls      always `world/controls2.py` (islands, volcanoes, ocean floors, belts, wind, style gates)
  climate.pt    the climate node (models/climatenet.py) replaces v4's archetype climate (t0 / tseason / precip / pcv)
                and adds aridity / soil / snow; it also fills the aeolian gate
  detail.pt     a Detail2 with experts gets the style gates per window (experts at gate 0 are skipped)
  sr30.pt       the SR node draws 30 m pixels at 1:2 and 1:1 (instead of the bilinear stand-in)
  sr15.pt       SR-15 draws the 1:1 world's 15 m blocks from those 30 m pixels (native_fine)

Request independence: the climate node runs on canonical windows of its own ~28 km grid (ERA5's resolution) and is
cross-faded, then sampled bilinearly at cells; SR runs on canonical windows cross-faded with weights constant inside
each 2x2 block, so the result stays exactly mean-preserving.
"""
from __future__ import annotations

import math
from dataclasses import replace
from pathlib import Path

import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.kernels import cuda_blend as KB
from terrain_slm.models import climatenet as CN
from terrain_slm.models import detail as DT
from terrain_slm.models import detail2 as D2
from terrain_slm.models import sr as SRM
from terrain_slm.synth import noise as S
from terrain_slm.world import controls2 as C2
from terrain_slm.world import generator as G

CLIMATE_GRID_CELLS = 116.0                  # ~27.8 km at 240 m cells: ERA5's 0.25 deg, the node's training grid
CLIMATE_WIN, CLIMATE_CORE, CLIMATE_APRON = 96, 48, 24
CLIMATE_SUB = 4                             # sub-points per grid cell and axis (land fraction, mean elevation)
GRID_KM = CLIMATE_GRID_CELLS * 0.24
CLIMATE_FETCH_CELLS = 38                    # > 1000 km (the far upwind fetch) in grid cells
WIND_SCALE = 0.55                           # ERA5 10 m VECTOR-mean wind / controls2 belt wind (sea 30-55 deg: 3.45 vs ~7)
LAND_WIND = (0.08, 0.92)                    # x (a + b (1 - land share within 250 km)^2): interiors ~0.1x, coasts ~0.3x
# Lowland micro-relief (2026-11-18): at 1:4 a lowland block is 16 m, so real 10-30 m swales read as 0-1 block. Small
# deviations of the 60 m terrain from its own ~500 m smoothed surface are amplified (up to x(1 + LOWLAND_BOOST) for
# |d| << LOWLAND_D0_M, fading for larger relief), only in lowland: regional elevation, coasts and mountains unchanged.
MEANFLOW_DETAIL_STEPS = 2
MEANFLOW_RELIEF_STEPS = 2                   # 2026-11-19 (agent B): a MeanFlow relief student (relief.ReliefConfig.mean_flow)
                                            # samples in this many (t, r) steps instead of the FM teacher's 16
SEA_DETAIL_SIGMA_CELLS = 6.0                # planner relief finer than ~1.5 km rides on the layout's depth
SEA_DETAIL_MAX_M = 1500.0
# Coastal shelf (2026-11-19, ocean census vs 16 real coasts of data v2): the layout put the sea floor 150-200 m deep within
# 0.5-2 km of the shore (real: 34 m pooled, 1-158 m per region; 2-5 km 358 vs 80, 5-15 km 642 vs 201) and 4-6x too
# rough in metres (540 m windows: 83 vs 14 m at 0.5-2 km). `_shelf_remap` compresses depths below sea level by
# SEA_SHELF_SLOPE at the waterline, easing back to 1:1 by SEA_SHELF_DEPTH_M (monotone, identity at 0: coastlines stay
# put; deeper water keeps its shape, ~560 m shallower). It applies to the final native terrain of height-curve v2
# worlds only (`use_sea_shelf`, set per tile by TileBuilder from WorldConfig.curve_version), so saved v1 worlds keep
# their sea bed. 1.0 = off; env TERRAIN_SLM_SEA_SHELF_SLOPE overrides it for A/B work (model_id names it).
# Round 2: the first SEA_SHELF_KEEP_M metres keep their depth (slope SEA_SHELF_KEEP at the waterline, easing into the
# compression): compressing already-shallow water x0.25 left 18 % of the coastal sea < 3 blocks deep (real coasts ~7 %).
SEA_SHELF_SLOPE = float(__import__("os").environ.get("TERRAIN_SLM_SEA_SHELF_SLOPE", "0.25"))
SEA_SHELF_DEPTH_M = 1500.0
SEA_SHELF_KEEP = 1.0
#: v2 worlds: cells whose layout trend is below this seed the true sea (world/ocean.py), besides EXP-2's ocean basins.
SEA_SEED_TREND_M = -60.0
# BV (beaches, 2026-11-20): ... but only where the LAYOUT links them to the open ocean (continent2 land < SEA_LAND):
# the coast provinces' island noise and the basin field also drown CLOSED basins inside the continents (pits 1-4 km,
# inland basins 5-35 km with rivers running in), which the trend gate alone made "sea": ocean ids, a sea floor and a
# beach ring round every inland lake (seeds 12345 / 777 / 2 / 5: every "sea" within 70 km of spawn was one). Linked =
# reachable from the open ocean through layout cells with trend < SEA_LINK_TREND_M in SEA_LINK_STEPS steps on a
# canonical grid of SEA_LINK_CELLS-cell super cells (sampled at their centres; 256 x 4 cells = 245 km: 64 left straight
# lake fronts across real bays whose open ocean lay 61-123 km away; 128+ converged on every probe), plus one super
# cell of slack. Pointwise layout only (no planner), canonical grid => request-independent. Off: env
# TERRAIN_SLM_SEA_LINK=0 (A/B); model_id marker :sl<version>.
SEA_LINK = __import__("os").environ.get("TERRAIN_SLM_SEA_LINK", "1") != "0"
SEA_LINK_CELLS = 4
SEA_LINK_STEPS = 256
SEA_LINK_TREND_M = 60.0
SEA_LINK_VERSION = 1
SEA_SHELF_KEEP_M = 30.0
HILL_TRANSFER = 1.0                         # 2026-11-19: planner smooths hills (43% -> 56% hill country restored)
GRAPHED_CONTROLS = 0                        # OFF (2026-11-19): capture breaks under the multi-threaded TGMPipe service
                                            # ("operation not permitted when stream is capturing"); native kernel instead
NATIVE_CONTROLS = True                      # 2026-11-19: EXP-2 layout as one CUDA kernel (kernels/cuda_controls2.py),
                                            # bit-identical to controls2._exp2 (tests/test_cuda_controls2.py); CUDA only
NATIVE_DETAIL = True                        # 2026-11-19: Detail2 (+ gated experts) on native fp16 kernels (kernels/cuda_detail2.py,
                                            # tests/test_cuda_detail2.py); model_id marker :kd1. CUDA only, fused_kernels on
DETAIL_FP8 = __import__("os").environ.get("TERRAIN_SLM_DETAIL_FP8", "1") != "0"   # 2026-11-20 SHIPS ON (user decision;
                                            # TERRAIN_SLM_DETAIL_FP8=0 = native fp16 for A/B). Needs sm_89+ and the folder's
                                            # detail_fp8.pt; otherwise native fp16 (own model_id, logged once)
                                            # FP8 (e4m3 W8A8) detail sampler (kernels/cuda_detail2_fp8.py, agent C 2026-11-19):
                                            # runs the QAD student `detail_fp8.pt` of the model folder (train/train_qad.py)
                                            # when present, sm_89+; model_id marker :kd8-<student step>-<fp16 layer set>.
                                            # Report: Dev Working/docs/exp2-work/C-fp8.md
EXPERTS_ENABLED = frozenset({"undersea", "aeolian", "skerries"})   # 2026-11-19 bench: these 3 = 4.9 / 3.0 / 1.4 % error cut, ~free;
                                            # volcanic (0.6 %) was gated on in 11 of 21 spawn tiles for +7 % tile time
HILL_RELIEF = 0.0                           # tried 1-2: grows mountains (foothills share the province), not hills
RELIEF_GAIN = 1.6                           # relief node x this: v2c draws ~half v4's epic relief at 1.0
HILL_TRANSFER_SIGMA_CELLS = 4.0             # ~1 km: the band the planner smooths (2 cells gave 50%, 4 gave 56%)
LOWLAND_BOOST = 0.0                         # 2026-11-19: OFF. 3.5 (sweep 50/30/18/2) made meadows jagged in play (user); 60 m
                                            # amplification is the wrong tool -> lowland landform provinces in controls2 instead
LOWLAND_D0_M = 40.0
LOWLAND_SIGMA_SAMPLES = 4.0                 # 60 m samples: ~240 m std, a ~500 m neighbourhood
LOWLAND_TOP_M = (400.0, 800.0)             # full boost below 400 m regional elevation, none above 800 m
NATIVE_SR = True                            # 2026-11-19: SR nodes on the native fp16 sampler (kernels/cuda_sr.py), :ks1
CANONICAL_CLIMATE = True                    # 2026-11-19: climate cells sampled request-independently (model_id :cg1)
# FX4 block look (2026-11-19, height-curve v2 worlds only; world/tiles.py "block look"): alpine biome climate (snow line /
# treeline for the game's exaggerated mountains), treeline rock band, block despeckle; 1:2 SR mean correction without
# the 2x2 grid imprint. Version in model_id (:bl<v>); env TERRAIN_SLM_BLOCK_LOOK=0 = off (A/B; v2 tiles as before).
BLOCK_LOOK = int(__import__("os").environ.get("TERRAIN_SLM_BLOCK_LOOK", "1"))
# FX4 lowland hill band (block look only): the play-test found ~1/3 of v4's block-scale hills (lowland 9-block relief
# > 8 blocks: 7 % vs 23 %). Block roughness at a given km-scale relief is near real terrain (agent I), so the gap is hill
# AMPLITUDE at the 1-4 km scale, not 60 m texture (the rejected lowland boost amplified pixels and made meadows jagged).
# The detail window's band between Gaussian sigmas LOOK_HILL_BAND (60 m samples: ~180 m and ~720 m) gains x(1 + this)
# in lowland (regional < 400 m, fading out by 800 m) away from coasts (regional 30 -> 120 m): existing hills grow, flats
# stay flat, the pixel scale is untouched. Env TERRAIN_SLM_LOOK_HILL_GAIN overrides (A/B; in model_id).
LOOK_HILL_GAIN = float(__import__("os").environ.get("TERRAIN_SLM_LOOK_HILL_GAIN", "0.8"))
LOOK_HILL_BAND = (3.0, 12.0)
LOOK_HILL_REGIONAL_SAMPLES = 16.0
LOOK_HILL_TOP_M = (400.0, 800.0)
LOOK_HILL_COAST_M = (30.0, 120.0)
CENDA_ENGINE = (__import__("os").environ.get("TGM_ENGINE", "") == "cenda"    # default OFF (agent D 2026-11-19): every
                or __import__("os").environ.get("TGM_NATIVE_PIPELINE", "0") not in ("0", "", "false", "off"))
                                            # network on the Cenda inference engine (OpenMason libcenda_kernels ci_*,
                                            # export/cenda_backend.py); model_id marker :ce1
SR_CORE, SR_APRON = 256, 32                 # fine (30 m) pixels
SR_STEPS = 8
SR_GAIN = 1.18                               # 2026-11-19: 8-step samples are 17 % too smooth (amp 0.83 -> 0.98 vs real
                                            # 30 m terrain, 8 regions' val crops; 32 steps reach only 0.93 at 4x cost)
SR_NOISE_STREAM = 30011
# SR-15 (1:1 worlds, 15 m blocks; 2026-11-19): 30 m -> 15 m on USGS 3DEP 1/3" (public domain), same node and recipe.
# Windows are in 15 m pixels with SR-30's core / apron; its input is the 30 m terrain (detail -> SR-30).
SR15_STEPS = 8
SR15_GAIN = 1.14                            # eval/sr_gain.py, 160 held-out 3DEP crops: amp 0.883 at 1.0, 1.007 at 1.15
SR15_NOISE_STREAM = 15013


def _shelf_remap(h: torch.Tensor, slope: float = None, depth_m: float = None, keep: float = None,
                 keep_m: float = None) -> torch.Tensor:
    """Sub-sea-level heights remapped by the depth rate
        s(d) = k + (1 - k) smoothstep(d / D) + (k0 - k) (1 - smoothstep(d / d0))
    (k = SEA_SHELF_SLOPE, D = SEA_SHELF_DEPTH_M, k0 = SEA_SHELF_KEEP at the waterline, fading over d0 = SEA_SHELF_KEEP_M):
        f(d) = k d + (1 - k) (D S(d / D) + max(d - D, 0)) + (k0 - k) (min(d, d0) - d0 S(min(d, d0) / d0) + 0)
    with S the integral of the quintic smoothstep (S(1) = 1/2). C1, strictly monotone, f(0) = 0; heights >= 0 are
    returned unchanged."""
    k = SEA_SHELF_SLOPE if slope is None else slope
    dm = SEA_SHELF_DEPTH_M if depth_m is None else depth_m
    k0 = SEA_SHELF_KEEP if keep is None else keep
    d0 = SEA_SHELF_KEEP_M if keep_m is None else keep_m
    if k >= 1.0:
        return h
    S = lambda t: t ** 4 * (t * t - 3.0 * t + 2.5)
    d = (-h).clamp_min(0.0)
    t = (d / dm).clamp(0.0, 1.0)
    f = k * d + (1.0 - k) * (dm * S(t) + (d - dm).clamp_min(0.0))
    if k0 != k and d0 > 0:
        u = (d / d0).clamp(0.0, 1.0)
        f = f + (k0 - k) * (d.clamp_max(d0) - d0 * S(u))
    return torch.where(h < 0, -f, h)


# Coastal shelf by distance from shore + lake beds (FX3, 2026-11-19; v2 worlds only, like the shelf above; report
# Dev Working/docs/exp2-work/FX3-layout-ocean-fixes.md). The pointwise `_shelf_remap` squeezed EVERY sub-sea depth, so
# seas the layout had already made shallow (drowned plains, shelf seas tens of km wide) were halved: 8 of 14 seeds with
# sea near spawn had a median sea <= 7 blocks deep, seed 4242's spawn sea went 46 m -> 23 m, 95.6 % block-flat out to
# 25 km (review-terrain.md item 2). Now only the depth BEYOND the real coastal profile for a column's distance from
# shore is compressed (with `_shelf_remap`'s own curve): a floor at or above
#     P(r) = SEA_PROFILE_M0 + SEA_PROFILE_SLOPE * r          (r = metres from the nearest land cell, capped)
# keeps its depth exactly; the 200 m-deep floors the layout put 0.5-2 km off steep coasts still come up to near the real
# profile (16 real coasts, pooled medians 11 / 34 / 80 / 201 / 839 m at 0-0.5 / 0.5-2 / 2-5 / 5-15 / 15-40 km: about
# 25 m per km). Distance is measured on the canonical planner cells (Euclidean, capped at SEA_PROFILE_MAX_CELLS) over a
# fixed margin, so tiles stay request-independent.
SEA_PROFILE_M0 = 5.0
SEA_PROFILE_SLOPE = 0.02                    # m of depth per m from shore (20 m / km; real pooled ~25)
SEA_PROFILE_MAX_CELLS = 64                  # distance cap (~15 km): the profile holds at P(cap) beyond
SEA_PROFILE_COMPRESS = 0.15                 # the excess over P: slope at its start (after the keep band) ...
SEA_PROFILE_KEEP_M = 15.0                   # ... which the first metres of excess keep (C1 at P) ...
SEA_PROFILE_RETURN_M = 800.0                # ... easing back to 1:1 by this much excess (1500: deep floors 15-40 km
                                            # out lost their relief, 75 % block-flat; ocean_census sweep, FX3 report)
# Inland sub-sea basins (lakes: sub-sea cells the true sea never reaches, world/ocean.py) fill with water to sea level.
# The EXP-2 planner digs 70-130 of them per seed, some 1 km deep (v4: never below -56 m); a small one became an 80-block
# water shaft in the hills. Their bed is capped at LAKE_DEPTH_M0 + LAKE_DEPTH_SLOPE * (distance to their shore): small
# lakes get a plausible bowl, Baikal-sized ones keep their depth in the middle. Real seas and fjords (connected to the
# sea) are not lakes and never touched.
LAKE_DEPTH_M0 = 8.0
LAKE_SEED_MIN_CELLS = 6                     # layout-drowned water seeds the sea only this far (~1.4 km) from land
LAKE_DEPTH_SLOPE = 0.08                     # real lakes ~0.02-0.15 (Laacher See 0.05, Baikal 0.08, Crater Lake 0.15)
#: Bump with any change to the profile / lake numbers above (model_id marker :sp<version>; TGMPipe caches by it).
SEA_PROFILE_VERSION = 1
#: Off = round 2's pointwise `_shelf_remap` for v2 worlds (A/B; env TERRAIN_SLM_SEA_PROFILE=0; in model_id).
SEA_PROFILE = __import__("os").environ.get("TERRAIN_SLM_SEA_PROFILE", "1") != "0"
CP = G.CP                                   # native pixels (30 m) per 240 m cell
NATIVE_PX_M = 30.0


def _shelf_profile_remap(h: torch.Tensor, dist_m: torch.Tensor, lake: torch.Tensor | None = None) -> torch.Tensor:
    """v2 worlds' sea floor (FX3): sub-sea heights deeper than P(dist) get their EXCESS compressed by `_shelf_remap`'s
    curve (keep band, then x SEA_SHELF_SLOPE, easing back to 1:1), heights at or above P(dist) and on land are returned
    unchanged; `lake` (weight 0..1, same shape) blends in the lake-bed cap min(depth, LAKE_DEPTH_M0 + LAKE_DEPTH_SLOPE *
    dist). Monotone in h for a fixed distance; continuous in space as long as `dist_m` and `lake` are."""
    d = (-h).clamp_min(0.0)
    p = SEA_PROFILE_M0 + SEA_PROFILE_SLOPE * dist_m
    e = (d - p).clamp_min(0.0)
    g = -_shelf_remap(-e, SEA_PROFILE_COMPRESS, SEA_PROFILE_RETURN_M, 1.0, SEA_PROFILE_KEEP_M)
    sea_d = torch.where(d > p, p + g, d)
    if lake is not None:
        lake_d = torch.minimum(d, LAKE_DEPTH_M0 + LAKE_DEPTH_SLOPE * dist_m)
        sea_d = sea_d + lake * (lake_d - sea_d)
    return torch.where(h < 0, -sea_d, h)


def _lake_shore_marker() -> str:
    """model_id marker of tiles.LAKE_SHORE_DESERT_M (v2 worlds' tiles: no sidecar sand ring on lake shores)."""
    from terrain_slm.world import tiles as T
    return f":ld{T.LAKE_SHORE_DESERT_M:g}" if T.LAKE_SHORE_DESERT_M > 0 else ""


def _distance_cells(land: torch.Tensor, cap: int) -> torch.Tensor:
    """(H, W) Euclidean distance (cells, centre to centre) to the nearest `land` cell, capped at `cap`: an exact
    separable min-plus transform over a (2 cap + 1)^2 box (rows, then columns; row chunks bound the memory). Exact
    (= the same value whatever the window) for cells at least `cap` cells inside the window."""
    h, w = land.shape
    big = float(4 * (cap + 1) ** 2)
    k2 = torch.arange(-cap, cap + 1, device=land.device, dtype=torch.float32) ** 2
    f = torch.where(land, torch.zeros((), device=land.device), torch.full((), big, device=land.device))

    def pass_(x: torch.Tensor) -> torch.Tensor:            # min over the last dim's +-cap neighbours of (x + k^2)
        n, m = x.shape
        xp = F.pad(x[None], (cap, cap), value=big)[0]
        step = max(1, (1 << 25) // max(1, m * (2 * cap + 1)))
        return torch.cat([(xp[r:r + step].unfold(1, 2 * cap + 1, 1) + k2).amin(-1) for r in range(0, n, step)])

    d2 = pass_(pass_(f).t().contiguous()).t()
    return torch.sqrt(d2).clamp_max(float(cap))


def _blur_hw(x: torch.Tensor, sigma: float) -> torch.Tensor:
    """D.blur over the last two dims of (H, W) or (N, H, W): the planner post-processes a whole batch of windows at
    once (2026-11-19); the blur is batch-invariant (tests/test_pipeline_parity.py pins it)."""
    return D.blur(x.reshape(-1, 1, *x.shape[-2:]), sigma).reshape(x.shape)


#: FX4 (block look): sigma (fine px) of the low-pass whose remainder SR_GAIN / SR15_GAIN amplify in `_sr_gain_look`.
SR_LOOK_SIGMA_PX = 1.0


def _sr_gain_look(fine: torch.Tensor, coarse: torch.Tensor, gain: float) -> torch.Tensor:
    """(1, 1, 2h, 2w) SR output at gain 1 (exact 2x2 means) -> its detail above a Gaussian low-pass (SR_LOOK_SIGMA_PX)
    amplified x gain, 2x2 means restored exactly. The encoded-space gain (x * gain before decoding) also scaled the
    residual's correction of the bicubic base's 2x2 phase, flipping it: neighbour steps across 2x2 blocks came out
    8-15 % larger than inside them (a period-2 grid imprint at 1:2), and sinh grew big residuals > gain. Linear and
    phase-free here (amplitude in typical windows within 1 % of the encoded gain's)."""
    f = fine + (gain - 1.0) * (fine - D.blur(fine, SR_LOOK_SIGMA_PX))
    return f - F.interpolate(SRM.block_mean(f) - coarse, scale_factor=2, mode="nearest")


def _hill_band(win: torch.Tensor, gain: float) -> torch.Tensor:
    """A canonical detail window (m, 60 m samples) with its LOOK_HILL_BAND band x(1 + gain) in lowland away from coasts
    (fades from the window's own LOOK_HILL_REGIONAL_SAMPLES blur): pointwise per window, before the cross-fade."""
    x = win[None, None]
    band = D.blur(x, LOOK_HILL_BAND[0]) - D.blur(x, LOOK_HILL_BAND[1])
    reg = D.blur(x, LOOK_HILL_REGIONAL_SAMPLES)
    fade = ((1 - C2._ss((reg - LOOK_HILL_TOP_M[0]) / (LOOK_HILL_TOP_M[1] - LOOK_HILL_TOP_M[0])))
            * C2._ss((reg - LOOK_HILL_COAST_M[0]) / (LOOK_HILL_COAST_M[1] - LOOK_HILL_COAST_M[0])))
    return win + gain * (band * fade)[0, 0]


_LOGGED: set = set()


def _log_once(key: str, msg: str) -> None:
    """One line per process on stderr (the TGMPipe service's stdout carries frames)."""
    if key not in _LOGGED:
        _LOGGED.add(key)
        print(msg, file=__import__("sys").stderr, flush=True)


class WorldGenerator2(G.WorldGenerator):
    def __init__(self, model_dir: Path, seed: int, device: str, cfg: G.GenConfig = G.GenConfig(),
                 cache_regions: int = 512):
        super().__init__(model_dir, seed, device, cfg, cache_regions)
        self.climate_net, cl = self._optional(model_dir / "climate.pt", lambda c: CN.ClimateNet(CN.ClimateConfig(
            **{k: v for k, v in c.items()})))
        self.sr30, sr = self._optional(model_dir / "sr30.pt", lambda c: SRM.SR(SRM.SRConfig(**c)))
        self.model_id += f":X2:c{cl}:s{sr}:sg{SR_GAIN:g}"
        gain15 = {}

        def _sr15(c):   # a checkpoint may carry its measured sampling gain (config.sample_gain, eval/sr_gain.py)
            gain15["g"] = c.pop("sample_gain", None)
            return SRM.SR(SRM.SRConfig(**c))
        self.sr15, sr15 = self._optional(model_dir / "sr15.pt", _sr15)
        self.sr15_gain = float(gain15.get("g") or SR15_GAIN)
        if self.sr15 is not None:                    # only folders that carry SR-15 change their id
            self.model_id += f":S15-{sr15}:sg15-{self.sr15_gain:g}"
        from terrain_slm.models import vegnet as VEG   # vegetation node (agent V, 2026-11-19): surface channels
        self.vegnet, veg = self._optional(model_dir / "veg.pt", lambda c: VEG.VegNet(VEG.VegNetConfig(**c)))
        if self.vegnet is not None:                  # only folders that carry veg.pt change their id
            self.model_id += f":V{veg}"
        # Surface-detail node (agent L, models/surfacenet.py): sub-block rock / scree / boulder / roughness channels
        from terrain_slm.models import surfacenet as SFN
        self.surfacenet, sf = self._optional(model_dir / "surface.pt", lambda c: SFN.SurfaceNet(SFN.SurfaceNetConfig(
            **{k: v for k, v in c.items() if k in SFN.SurfaceNetConfig.__dataclass_fields__})))
        if self.surfacenet is not None:              # only folders that carry it change their id
            self.model_id += f":SF{sf}"
        self._detail2_native = None                   # NativeDetail2Sampler class when the detail runs natively
        self.detail_native_windows = self.detail_torch_windows = 0
        if NATIVE_DETAIL and self.cfg.fused_kernels and isinstance(self.detail, D2.Detail2):
            from terrain_slm import kernels as K
            if K.available(device):
                from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler
                if NativeDetail2Sampler.supported(self.detail):
                    self._detail2_native = NativeDetail2Sampler
                    self.model_id += ":kd1"          # native numbers differ from torch's: tile caches keep them apart
                    if DETAIL_FP8:
                        self._use_detail_fp8(model_dir, device)
        if DETAIL_FP8 and (Path(model_dir) / "detail_fp8.pt").exists() and ":kd8-" not in self.model_id:
            _log_once("fp8-off", f"[detail] FP8 detail sampler not used on {device} ({self._fp8_reason(device)}); "
                      f"running the {'native fp16' if self._detail2_native else 'torch'} detail path (model_id without :kd8)")
        if CANONICAL_CLIMATE and self.climate_net is not None:
            self.model_id += ":cg1"                  # request-independent climate sampling (climate_cells)
        self._sr_native = None                       # {(id(model), cond shape, steps): sampler} with native SR
        if NATIVE_SR and self.cfg.fused_kernels and self.sr30 is not None:
            from terrain_slm import kernels as K
            from terrain_slm.kernels import cuda_sr as KS
            if K.available(device) and KS.supported(self.sr30):
                self._sr_native = {}
                self.model_id += ":ks1"              # native fp16 SR (1:2 tiles) differs from bf16 torch: caches apart
        self._sr15_native = False                    # SR-15 on the same native sampler (2026-11-19, :ks15)
        if self._sr_native is not None and self.sr15 is not None:
            from terrain_slm.kernels import cuda_sr as KS
            if KS.supported(self.sr15):
                self._sr15_native = True
                self.model_id += ":ks15"
        if self._detail2_native is not None or self._sr_native is not None:
            from terrain_slm.kernels.cuda_detail2 import MARK as _KMARK
            if _KMARK:   # K4 kernel generation (kernels/cuda_detail2.KGEN): its own fp32 summation order -> own caches
                self.model_id += ":" + _KMARK
        # A MeanFlow detail student is trained, scanned and shipped at 2 steps; the GenConfig default (16) is the FM
        # teacher's. An explicit non-default detail_steps is kept.
        if getattr(self.detail, "mean_flow", False) and self.cfg.detail_steps == G.GenConfig.detail_steps:
            self.cfg = replace(self.cfg, detail_steps=MEANFLOW_DETAIL_STEPS)
        if getattr(self.relief, "mean_flow", False) and self.cfg.relief_steps == G.GenConfig.relief_steps:
            self.cfg = replace(self.cfg, relief_steps=MEANFLOW_RELIEF_STEPS)   # same rule for a MeanFlow relief
        self._climate_windows = G._LRU(32, 96)
        self.climate_forcing = (0.0, 0.0)      # (global dT degC, age ka): today; other values = ice-age / warm dial
        # World option (2026-11-18): 'belts' (default: warped latitude, random pole axis) or 'zones' (no axis). Part of
        # the world's identity like the seed: set it before the first tile (use_climate_layout clears the caches).
        self.climate_layout = "belts"
        self.relief_gain = RELIEF_GAIN
        self.lowland_boost = LOWLAND_BOOST           # 0 = off (A/B knob)
        self.sea_detail = True                       # planner sea-floor relief on the layout's depth (False = v4 rule)
        self.hill_transfer = HILL_TRANSFER           # 0..1: hill-scale relief from the relief node instead of the planner
        # CUDA-graph replay of the EXP-2 layout (bit-identical, 2-4x faster; ~70 MiB per cached window shape)
        self._graphed = (C2.GraphedControls2(device, GRAPHED_CONTROLS) if GRAPHED_CONTROLS and str(device).startswith("cuda")
                         else None)
        self.experts_enabled = EXPERTS_ENABLED       # None = every expert the detail checkpoint carries
        self.hill_relief = HILL_RELIEF               # extra relief gain in hill provinces (x(1 + this) at full province)
        self.block_look = BLOCK_LOOK                 # FX4: read by TileBuilder (v2 worlds); 0 = off
        self.look_hill_gain = LOOK_HILL_GAIN         # FX4 lowland hill band in block-look builds (0 = off)
        self._cenda = None                           # Cenda inference engine backend (CENDA_ENGINE; export/cenda_backend.py)
        if CENDA_ENGINE:
            from terrain_slm.export import cenda_backend
            self._cenda_files = [Path(model_dir) / f for f in ("planner.pt", "relief.pt", "detail.pt", "sr30.pt", "climate.pt")]
            cenda_backend.install(self)              # reroutes the networks, model_id marker :ce1
        self._base_model_id = self.model_id
        self._refresh_model_id()
        self._sr_windows = G._LRU(32, 224)
        self._sr15_windows = G._LRU(32, 224)
        # FX4 block look: SR windows of the current build's mode (use_block_look swaps them; the caches stay apart)
        self._sr_windows_look, self._sr15_windows_look = G._LRU(32, 224), G._LRU(32, 224)
        self._sr_windows_plain, self._sr15_windows_plain = self._sr_windows, self._sr15_windows
        self.look_build = False

    # ------------------------------------------------------------------ native detail sampler
    def _fp8_reason(self, device: str) -> str:
        from terrain_slm import kernels as K
        if not str(device).startswith("cuda") or not torch.cuda.is_available():
            return "not a CUDA device"
        cc = torch.cuda.get_device_capability(torch.device(device))
        if cc < (8, 9):
            return f"GPU sm_{cc[0]}{cc[1]} has no FP8 tensor cores (needs sm_89+: Ada / Hopper / Blackwell)"
        if not K.available(device):
            return "native kernels unavailable (NVRTC / libcuda missing or TGM_KERNELS=0)"
        if not self.cfg.fused_kernels or not NATIVE_DETAIL:
            return "native detail sampler disabled"
        return "FP8 sampler does not support this detail model"

    def _use_detail_fp8(self, model_dir: Path, device: str) -> None:
        """DETAIL_FP8: swap the detail weights for the folder's FP8 QAD student and run it on the FP8 sampler (the
        torch fallback for unpacked experts then runs the student too). No student / no sm_89+: stay fp16."""
        import copy
        import zlib
        path = Path(model_dir) / "detail_fp8.pt"
        if not path.exists():
            return
        from terrain_slm.kernels.cuda_detail2_fp8 import NativeDetail2SamplerFP8, qconfig_of
        ck = torch.load(path, map_location=device, weights_only=False)
        student = copy.deepcopy(self.detail)
        student.load_state_dict(ck["model"])
        student.fp8_qconfig = qconfig_of(ck)
        if not NativeDetail2SamplerFP8.supported(student):
            return
        self.detail = student.eval()
        _log_once("fp8-on", f"[detail] FP8 detail sampler on {device} (e4m3 W8A8, {path.name} step {ck.get('step', 0)}, "
                            f"fp16 layers {sorted(k for k, v in student.fp8_qconfig.layers.items() if v == 'fp16')})")
        self._detail2_native = NativeDetail2SamplerFP8
        keep = "-".join(sorted(k for k, v in student.fp8_qconfig.layers.items() if v == "fp16"))
        self.model_id += f":kd8-{ck.get('step', 0)}-{zlib.crc32(keep.encode()) % 100003}"

    def _sample(self, model, cond, noise, steps, t_start=0.0, gates=None):
        """The detail model on its native sampler (one per window shape; one CUDA graph per active-expert subset),
        everything else as v4. A window whose active expert the sampler lacks falls back to torch."""
        if self._detail2_native is None or model is not self.detail:
            return super()._sample(model, cond, noise, steps, t_start, gates=gates)
        key = ("detail2", tuple(cond.shape), steps, t_start)
        fs = self._samplers.get(key)
        on_cpu = next(model.parameters()).device.type == "cpu"
        if fs is None:
            if on_cpu:                                   # offloaded after warm-up: borrow the GPU to pack
                model.to(self.device)
            try:
                fs = self._samplers[key] = self._detail2_native(model, cond.shape[-2], cond.shape[-1], steps, t_start)
            finally:
                if on_cpu:
                    model.to("cpu")
        x = fs(cond, noise, gates)
        if x is not None:
            self.detail_native_windows += 1
            return x
        self.detail_torch_windows += 1
        if on_cpu:
            model.to(self.device)
        try:
            return D2.sample(model, cond.contiguous(memory_format=torch.channels_last), noise, steps=steps,
                             t_start=t_start, mean_flow=getattr(model, "mean_flow", False), gates=gates)
        finally:
            if on_cpu:
                model.to("cpu")

    def _sr_sample(self, model, cond: torch.Tensor, noise: torch.Tensor, steps: int) -> torch.Tensor:
        """`models.sr.sample` on the native fp16 sampler (kernels/cuda_sr.py), else torch. One sampler per model and
        window shape, built on first use (only 1:2 / 1:1 worlds pay its ~75 MiB of buffers) and launched eagerly: no
        CUDA-graph capture under the service's threads (eager measured no slower on a shared GPU)."""
        if self._sr_native is None:
            return SRM.sample(model, cond.contiguous(memory_format=torch.channels_last), noise, steps)
        key = (id(model), tuple(cond.shape), steps)
        fs = self._sr_native.get(key)
        if fs is None:
            from terrain_slm.kernels import cuda_sr as KS
            fs = self._sr_native[key] = KS.native_sr_sampler(model, cond.shape[-2], cond.shape[-1], steps)
        return fs(cond, noise) if () in fs._graphs else fs.eager(cond, noise)

    def warm_up(self, report=lambda what: None) -> None:
        super().warm_up(report)
        if self._sr_native is not None:
            report("SR sampler")
            with self.lock, torch.no_grad():            # compile + load its kernels now (they stay loaded), then drop
                ext = SR_CORE + 2 * SR_APRON            # the buffers: the first 1:2 tile rebuilds the sampler (~ms)
                z = lambda c: torch.zeros(1, c, ext, ext, device=self.device)
                self._sr_sample(self.sr30, z(SRM.N_COND), z(1), SR_STEPS)
                self._sr_native.clear()
                torch.cuda.synchronize(self.device)
                torch.cuda.empty_cache()
        if self._detail2_native is None:
            return
        with self.lock, torch.no_grad():
            report("detail sampler")
            n = G.DETAIL_EXT
            z = lambda c: torch.zeros(1, c, n, n, device=self.device)
            self._sample(self.detail, z(DT.N_COND), z(1), self.cfg.detail_steps, G.DETAIL_T_START)
            fs = self._samplers[("detail2", (1, DT.N_COND, n, n), self.cfg.detail_steps, G.DETAIL_T_START)]
            # Every expert subset's graph now (captures never happen mid-service; ~ms each).
            on = [s for s in fs.experts if self.experts_enabled is None or s in self.experts_enabled]
            for k in range(1, 1 << len(on)):
                fs.capture(tuple(s for i, s in enumerate(on) if k >> i & 1))
            torch.cuda.synchronize(self.device)
            self.detail.to("cpu")                        # the packed fp16 copy is what runs; torch only for fallbacks
            torch.cuda.empty_cache()

    def _relief_gain_at(self, c: dict, crop):
        """Province-dependent relief strength (2026-11-19): the relief node draws real valley / ridge shapes but almost
        ignores `wild`, so hill country is made by scaling its relief up inside v4's lowland hill provinces; plains
        (meadows) keep the base gain."""
        if self.hill_relief <= 0 or "hilly" not in c:
            return self.relief_gain
        return self.relief_gain * (1.0 + self.hill_relief * crop(c["hilly"])[None, None])

    def _sea_merge(self, height: torch.Tensor, trend: torch.Tensor) -> torch.Tensor:
        """EXP-2 (planners trained with deep ocean): the layout keeps the absolute depth (trenches, ridges, abyss where
        the world asked for them), the planner adds its local sea-floor relief on top (seamounts, trench walls, ridge
        fabric) -- v4's min(planner, trend) threw that away. Shallow water (< 150 m) stays the planner's own."""
        if self.hill_transfer > 0:
            # Hill transfer (2026-11-19): the planner (a regression) averages hill-scale relief away; the relief node's
            # trend draws it. On land, swap the planner's sub-~1 km band for the trend's (river valleys stay: the river
            # pass carves them later at block scale).
            hp = lambda x: x - _blur_hw(x, HILL_TRANSFER_SIGMA_CELLS)
            land = G._smoothstep(trend / 60.0)
            height = height + self.hill_transfer * land * (hp(trend) - hp(height))
        if not self.sea_detail:
            return super()._sea_merge(height, trend)
        sea = ((-trend) / 150.0).clamp(0, 1)
        local = (height - _blur_hw(height, SEA_DETAIL_SIGMA_CELLS)).clamp(-SEA_DETAIL_MAX_M, SEA_DETAIL_MAX_M)
        return (1 - sea) * height + sea * torch.minimum(trend + local, torch.full_like(trend, -5.0))

    def _refresh_model_id(self) -> None:
        """model_id names everything that changes tiles (TGMPipe keys its disk cache by it): layout and lowland boost."""
        self.model_id = (f"{self._base_model_id}:ds{self.cfg.detail_steps}:L{self.climate_layout}"
                         f":lb{self.lowland_boost:g}:rg{self.relief_gain:g}:pb{self.relief_preblur:g}:sd{int(self.sea_detail)}:ht{self.hill_transfer:g}:hr{self.hill_relief:g}"
                         + ("" if self.experts_enabled is None else ":e" + "-".join(sorted(self.experts_enabled)))
                         + (f":rs{self.cfg.relief_steps}" if getattr(self.relief, "mean_flow", False) else "")
                         + (f":sh{SEA_SHELF_SLOPE:g}x{SEA_SHELF_DEPTH_M:g}k{SEA_SHELF_KEEP:g}x{SEA_SHELF_KEEP_M:g}"
                            if SEA_SHELF_SLOPE < 1.0 else "")
                         + f":lf{C2.LANDFORM_GAIN:g}v{C2.LANDFORM_VERSION}"        # lowland landform provinces (controls2.landforms)
                         # FX3 (v2 worlds' tiles only; v1 tile contents unchanged): karst2 provinces (bias / home
                         # radius) and the distance-aware shelf + lake-bed cap (SEA_PROFILE_* / LAKE_*)
                         + (f":k2{C2.KARST_BIAS_V2:g}h{C2.KARST_HOME_CELLS[0]:g}" if C2.KARST_V2 else "")
                         + (f":sp{SEA_PROFILE_VERSION}" if SEA_PROFILE else "")
                         + (f":sl{SEA_LINK_VERSION}" if SEA_LINK else "")      # BV: linked sea seeds (v2 worlds)
                         + _lake_shore_marker()                                    # BV: no sidecar sand on lake shores
                         + (f":bl{self.block_look}h{self.look_hill_gain:g}" if getattr(self, "block_look", 0) else ""))   # FX4

    def use_relief_gain(self, gain: float) -> None:
        if gain != self.relief_gain:
            self.relief_gain = gain
            for v in list(vars(self).values()):          # trend feeds every later stage
                if isinstance(v, G._LRU):
                    v.clear()
            self._refresh_model_id()

    def use_hill_transfer(self, w: float) -> None:
        if w != self.hill_transfer:
            self.hill_transfer = w
            for v in list(vars(self).values()):
                if isinstance(v, G._LRU):
                    v.clear()
            self._refresh_model_id()

    def use_relief_preblur(self, sigma: float) -> None:
        if sigma != self.relief_preblur:
            self.relief_preblur = sigma
            for v in list(vars(self).values()):
                if isinstance(v, G._LRU):
                    v.clear()
            self._refresh_model_id()

    def use_lowland_boost(self, boost: float) -> None:
        if boost != self.lowland_boost:
            self.lowland_boost = boost
            # Every LRU (review-python LOW-4): the SR-30 / SR-15 windows and everything else built on the detail
            # terrain would otherwise keep the old boost (a dev / A-B knob: clearing upstream ones too is cheap).
            for v in list(vars(self).values()):
                if isinstance(v, G._LRU):
                    v.clear()
            self._refresh_model_id()

    def use_block_look(self, on: bool) -> None:
        """FX4 block look for the builds from now on (TileBuilder sets it per build, under `lock`, for height-curve v2
        worlds): SR-30 / SR-15 windows gain their detail linearly (`_sr_gain_look`) instead of in the encoded space,
        which over-corrected the bicubic base's 2x2 phase and left a period-2 grid imprint at 1:2 / 1:1."""
        on = bool(on) and bool(self.block_look)
        self.look_build = on
        self._sr_windows = self._sr_windows_look if on else self._sr_windows_plain
        self._sr15_windows = self._sr15_windows_look if on else self._sr15_windows_plain

    def use_sea_shelf(self, on: bool) -> None:
        """Coastal shelf (`_shelf_remap`) on the native terrain from now on: height-curve v2 worlds (TileBuilder sets it
        per build, under `lock`). Applied after every cache (river_field's native heights, overview_cells), so worlds
        with and without it share every cached window."""
        self.sea_shelf = bool(on)

    def shelf(self, heights: torch.Tensor, i1: int | None = None, j1: int | None = None, ppc: int = CP) -> torch.Tensor:
        """`heights` with the coastal shelf when the current world has it (`use_sea_shelf`), else unchanged. With the
        window origin (i1, j1) in pixels of `ppc` per 240 m cell (native 30 m: CP; 1:1 15 m blocks: 2 CP; cells: 1)
        and SEA_PROFILE on: the distance-aware shelf + lake-bed cap (`_shelf_profile_remap`); without an origin (or
        SEA_PROFILE off) A's pointwise `_shelf_remap`."""
        if not getattr(self, "sea_shelf", False):
            return heights
        if not SEA_PROFILE or i1 is None:
            return _shelf_remap(heights)
        dist_m, lake = self.sea_fields_px(i1, j1, heights.shape[-2], heights.shape[-1], ppc, heights.device)
        return _shelf_profile_remap(heights, dist_m, lake)

    def sea_fields_cells(self, ci0: int, cj0: int, hc: int, wc: int, device=None) -> tuple[torch.Tensor, torch.Tensor]:
        """(distance from the nearest land cell in metres, enclosed bool) for cells [ci0, ci0+hc) x [cj0, cj0+wc): land =
        canonical planner cells >= 0 m; enclosed = sub-sea cells the sea never reaches (world/ocean.sea_cells growth)
        from its seeds: open ocean (this world's `continent_land_cells`), but layout-drowned cells (trend below
        SEA_SEED_TREND_M) seed only where they lie at least LAKE_SEED_MIN_CELLS from land -- the coast provinces' island
        noise also drowns closed pits 1-4 km wide to -1 km inside the land, which the true-sea classification calls sea
        and which are shafts, not seas; real seas, gulfs and fjords open onto water that does seed. Built over a fixed
        margin, so request-independent. Distance is from cell centre to the nearest land cell's centre minus half a
        cell (~ the coastline), capped at SEA_PROFILE_MAX_CELLS."""
        from terrain_slm.world import ocean as O
        m = max(O.SEA_CELL_MARGIN + LAKE_SEED_MIN_CELLS + 1, SEA_PROFILE_MAX_CELLS + 1)
        h, w = hc + 2 * m, wc + 2 * m
        with self.lock:
            cells = self._planner_cells(ci0 - m, cj0 - m, h, w)[0]
            trend = self._layout(ci0 - m, cj0 - m, h, w)["trend"].to(cells.device)
        ci = torch.arange(ci0 - m, ci0 - m + h, device=cells.device, dtype=torch.float32).view(-1, 1).expand(h, w)
        cj = torch.arange(cj0 - m, cj0 - m + w, device=cells.device, dtype=torch.float32).view(1, -1).expand(h, w)
        open_land = C2.continent2(ci, cj, seed=self.seed)[1]
        seed_land = torch.where(trend < SEA_SEED_TREND_M, torch.zeros_like(open_land), open_land)   # = continent_land_cells
        sub = cells < 0.0
        offshore = _distance_cells(~sub, LAKE_SEED_MIN_CELLS) >= LAKE_SEED_MIN_CELLS    # exact >= its cap inside
        seeds = sub & (torch.where(offshore, seed_land, open_land) < O.SEA_LAND)
        # = O.sea_cells(cells, that land) with a fixed number of growth steps (no per-step convergence sync: ~4x faster)
        sea = seeds.float()[None, None]
        through = sub.float()[None, None]
        for _ in range(O.SEA_GROW_CELLS):
            sea = F.max_pool2d(sea, 3, 1, 1) * through
        r = O.SEA_COAST_CELLS
        lake = sub & ~(F.max_pool2d(sea, 2 * r + 1, 1, r)[0, 0] > 0.5)
        dist = _distance_cells(~sub, SEA_PROFILE_MAX_CELLS)
        dist_m = (dist - 0.5).clamp_min(0.0) * (CP * NATIVE_PX_M)
        crop = lambda x: x[m:m + hc, m:m + wc]
        dev = device if device is not None else cells.device
        return crop(dist_m).to(dev), crop(lake).to(dev)

    def sea_fields_px(self, i1: int, j1: int, h: int, w: int, ppc: int, device) -> tuple[torch.Tensor, torch.Tensor]:
        """`sea_fields_cells` at pixels [i1, i1+h) x [j1, j1+w) of `ppc` per cell: bilinear between cell centres
        (distance m, lake weight 0..1). Depends only on canonical cells, so request-independent."""
        if ppc == 1:
            d, lk = self.sea_fields_cells(i1, j1, h, w, device)
            return d, lk.float()
        ci0, cj0 = (i1 // ppc) - 1, (j1 // ppc) - 1
        ci1, cj1 = (i1 + h - 1) // ppc + 2, (j1 + w - 1) // ppc + 2
        d, lk = self.sea_fields_cells(ci0, cj0, ci1 - ci0, cj1 - cj0, device)

        def lerp(x: torch.Tensor, p0: int, n: int, c0: int, axis: int) -> torch.Tensor:
            u = (torch.arange(p0, p0 + n, device=x.device, dtype=torch.float64) + 0.5) / ppc - 0.5 - c0
            a = torch.floor(u)
            t = (u - a).to(x.dtype)
            a = a.long()
            lo, hi = x.index_select(axis, a), x.index_select(axis, a + 1)
            t = t.view(-1, 1) if axis == 0 else t.view(1, -1)
            return lo + t * (hi - lo)

        up = lambda x: lerp(lerp(x, i1, h, ci0, 0), j1, w, cj0, 1)
        return up(d), up(lk.float())

    def _flatten_swamps(self, heights: torch.Tensor, i1: int, j1: int) -> torch.Tensor:
        # river_field's first step on the native heights: the coastal shelf (+ lake-bed cap) goes in here for v2 worlds.
        return self.shelf(super()._flatten_swamps(heights, i1, j1), i1, j1, CP)

    def continent_land_cells(self, ci0: int, cj0: int, hc: int, wc: int, device) -> torch.Tensor:
        """Continent land weight that seeds the true sea (world/ocean.py). Height-curve v2 worlds (`sea_shelf`) use
        EXP-2's own (controls2.continent2: v4's field with the ocean basins pushed down) -- v4's field calls most of
        EXP-2's basin seas land, so their coasts were classified as LAKES (land biomes, a dirt-and-sand lake bed, no
        beach; 2026-11-19 round 2). v1 worlds keep v4's field (saved worlds stay seamless)."""
        if not getattr(self, "sea_shelf", False):
            return G.continent_land(ci0, cj0, hc, wc, self.seed, device)
        ci = torch.arange(ci0, ci0 + hc, device=device, dtype=torch.float32).view(-1, 1).expand(hc, wc)
        cj = torch.arange(cj0, cj0 + wc, device=device, dtype=torch.float32).view(1, -1).expand(hc, wc)
        land = C2.continent2(ci, cj, seed=self.seed)[1]
        # The layout's own sea counts too: its coast provinces drown land with the island noise (archipelagos, drowned
        # coastal plains) where even continent2 says land. Layout trend below SEA_SEED_TREND_M = open water.
        # The layout alone (review-python LOW-3): controls() = this + the climate node + gates, none of which writes
        # "trend", so the value is identical (tests/test_sea_shelf.py) without the climate work per sea-mask request.
        with self.lock:
            trend = self._layout(ci0, cj0, hc, wc)["trend"].to(device)
        drowned = trend < SEA_SEED_TREND_M
        if SEA_LINK:                                   # BV: closed basins are lakes, not sea seeds
            drowned = drowned & self.sea_link_cells(ci0, cj0, hc, wc, device)
        return torch.where(drowned, torch.zeros_like(land), land)

    def sea_link_cells(self, ci0: int, cj0: int, hc: int, wc: int, device) -> torch.Tensor:
        """(hc, wc) bool: cells whose super cell (SEA_LINK_CELLS) the layout links to the open ocean (see SEA_LINK).
        Exact for any window: the super grid is canonical and carries SEA_LINK_STEPS + 2 super cells of context."""
        K, S = SEA_LINK_CELLS, SEA_LINK_STEPS
        m = S + 2
        a0, b0 = ci0 // K - m, cj0 // K - m
        a1, b1 = (ci0 + hc - 1) // K + 1 + m, (cj0 + wc - 1) // K + 1 + m
        na, nb = a1 - a0, b1 - b0
        si = (torch.arange(a0, a1, device=device, dtype=torch.float32) * K + (K - 1) / 2).view(-1, 1).expand(na, nb)
        sj = (torch.arange(b0, b1, device=device, dtype=torch.float32) * K + (K - 1) / 2).view(1, -1).expand(na, nb)
        si, sj = si.contiguous(), sj.contiguous()
        with self.lock:
            trend = self._controls_at(si, sj)["trend"].to(device)
        from terrain_slm.world import ocean as O
        open_sea = C2.continent2(si, sj, seed=self.seed)[1] < O.SEA_LAND
        through = ((trend < SEA_LINK_TREND_M) | open_sea).float()[None, None]
        conn = open_sea.float()[None, None]
        for _ in range(S):
            conn = F.max_pool2d(conn, 3, 1, 1) * through
        conn = F.max_pool2d(conn, 3, 1, 1)[0, 0] > 0.5                  # one super cell of slack
        ii = torch.div(torch.arange(ci0, ci0 + hc, device=device), K, rounding_mode="floor") - a0
        jj = torch.div(torch.arange(cj0, cj0 + wc, device=device), K, rounding_mode="floor") - b0
        return conn[ii][:, jj]

    def overview_cells(self, ci0: int, cj0: int, hc: int, wc: int) -> dict:
        c = super().overview_cells(ci0, cj0, hc, wc)
        if getattr(self, "sea_shelf", False):                    # far previews of v2 worlds: the same shelf
            c = {**c, "height": self.shelf(c["height"], ci0, cj0, 1)}
        return c

    def use_climate_layout(self, layout: str) -> None:
        """Switch the world's climate layout (C2.CLIMATE_LAYOUTS); clears every cache that depends on the controls."""
        if layout not in C2.CLIMATE_LAYOUTS:
            raise ValueError(f"climate layout {layout!r} not in {C2.CLIMATE_LAYOUTS}")
        if layout != self.climate_layout:
            self.climate_layout = layout
            for v in list(vars(self).values()):          # every LRU is keyed by seed, not layout: start clean
                if isinstance(v, G._LRU):
                    v.clear()
            self._refresh_model_id()

    # ------------------------------------------------------------------ controls + climate
    def _controls_at(self, ci: torch.Tensor, cj: torch.Tensor) -> dict:
        """controls2_at (the climate node's sparse grids) on the native kernel when it can (bit-identical,
        tests/test_cuda_controls2.py; it leaves out v4's climate-archetype keys, which nothing here reads)."""
        from terrain_slm import kernels as K
        if NATIVE_CONTROLS and str(self.device).startswith("cuda") and K.available(self.device):
            from terrain_slm.kernels import cuda_controls2 as KC2
            return KC2.controls2_at_native(ci, cj, self.seed, self.climate_layout)
        return C2.controls2_at(ci, cj, self.seed, self.climate_layout)

    def _layout(self, ci0: int, cj0: int, hc: int, wc: int) -> dict:
        """The EXP-2 layout (controls2) alone, without the climate node: native kernel, CUDA graphs or eager torch."""
        from terrain_slm import kernels as K
        if NATIVE_CONTROLS and str(self.device).startswith("cuda") and K.available(self.device):
            from terrain_slm.kernels import cuda_controls2 as KC2
            return KC2.controls2(ci0, cj0, hc, wc, self.seed, self.device, self.climate_layout)
        if self._graphed is not None:
            return self._graphed(ci0, cj0, hc, wc, self.seed, self.climate_layout)
        return C2.controls2(ci0, cj0, hc, wc, self.seed, self.device, self.climate_layout)

    def trend(self, ci0: int, cj0: int, hc: int, wc: int) -> torch.Tensor:
        """v4's trend (layout + the relief node) plus the layout's FINE lowland landforms (controls2.landforms, 1-5 km
        hills, moraines, plateau valleys, cone karst): the relief node blurs its input by ~1 km and would erase them.
        Pointwise, so request-independent; the planner reads it, and the hill transfer copies its sub-km band."""
        t = super().trend(ci0, cj0, hc, wc)
        if C2.LANDFORM_GAIN == 0:
            return t
        return t + self._layout(ci0, cj0, hc, wc)["landform"]

    def controls(self, ci0: int, cj0: int, hc: int, wc: int) -> dict:
        c = self._layout(ci0, cj0, hc, wc)
        if self.climate_net is not None:
            c.update(self.climate_cells(ci0, cj0, hc, wc))
            gates = c["gates"].clone()
            gates[D2.STYLES.index("aeolian")] = C2.aeolian_gate(c["aridity"], c["trend"])
            c["gates"] = gates
        if self.experts_enabled is not None:                 # expert pruning: a disabled expert's gate is zero
            g = c["gates"].clone()
            for i, style in enumerate(D2.STYLES):
                if style not in self.experts_enabled:
                    g[i] = 0.0
            c = {**c, "gates": g}
        return c

    def _detail_window(self, wi: int, wj: int) -> torch.Tensor:
        """The canonical detail window with the lowland micro-relief boost applied inside it (before the cross-fade, so
        neighbouring tiles agree)."""
        win = super()._detail_window(wi, wj)
        if self.look_build and self.look_hill_gain > 0:   # FX4 hill band (height-curve v2 worlds, block look)
            win = _hill_band(win, self.look_hill_gain)
        if self.lowland_boost <= 0:
            return win
        regional = D.blur(win[None, None], LOWLAND_SIGMA_SAMPLES)[0, 0]
        d = win - regional
        low = 1 - C2._ss((regional - LOWLAND_TOP_M[0]) / (LOWLAND_TOP_M[1] - LOWLAND_TOP_M[0]))
        land = C2._ss(regional / 5.0)
        gain = 1 + self.lowland_boost * torch.exp(-(d / LOWLAND_D0_M) ** 2) * low * land
        return regional + d * gain

    def climate_native(self, i1: int, j1: int, i2: int, j2: int, elev: torch.Tensor) -> torch.Tensor:
        """(4 or 7, H, W) climate at native pixels (temperature lapse-adjusted to `elev`): with the climate node, also
        aridity, soil water and snow (biomes.has_extras), which the biome rules and the extras biome sidecar read."""
        clim = super().climate_native(i1, j1, i2, j2, elev)
        if self.climate_net is None:
            return clim
        ci0, cj0 = i1 // G.CP - 1, j1 // G.CP - 1
        hc, wc = (i2 - 1) // G.CP + 2 - ci0, (j2 - 1) // G.CP + 2 - cj0
        with self.lock:
            ctrl = self.controls(ci0, cj0, hc, wc)
        stack = torch.stack([ctrl["aridity"], ctrl["soil"], ctrl["snow"]])[None]
        up = F.interpolate(stack, size=(hc * G.CP, wc * G.CP), mode="bilinear", align_corners=False)[0]
        oi, oj = i1 - ci0 * G.CP, j1 - cj0 * G.CP
        return torch.cat([clim, up[:, oi : oi + (i2 - i1), oj : oj + (j2 - j1)]])

    def climate_cells(self, ci0: int, cj0: int, hc: int, wc: int) -> dict[str, torch.Tensor]:
        """The climate node's fields at cells [ci0, ci0+hc) x [cj0, cj0+wc), in climate_era5's units."""
        g = CLIMATE_GRID_CELLS
        gi0, gj0 = math.floor(ci0 / g) - 1, math.floor(cj0 / g) - 1
        gi1, gj1 = math.floor((ci0 + hc) / g) + 2, math.floor((cj0 + wc) / g) + 2
        y = self._climate_grid(gi0, gj0, gi1 - gi0, gj1 - gj0)                    # (7, gh, gw) normalised
        if not CANONICAL_CLIMATE:                       # the pre-2026-11-19 sampling (A/B only; request-dependent)
            ci = (torch.arange(ci0, ci0 + hc, device=self.device, dtype=torch.float32) + 0.5) / g - 0.5 - gi0
            cj = (torch.arange(cj0, cj0 + wc, device=self.device, dtype=torch.float32) + 0.5) / g - 0.5 - gj0
            gh, gw = y.shape[-2:]
            grid = torch.stack([(cj / (gw - 1)) * 2 - 1, torch.zeros_like(cj)], -1)   # x = j, y = i (normalised)
            gy = (ci / (gh - 1)) * 2 - 1
            sample = torch.stack(torch.broadcast_tensors(grid[None, :, 0], gy[:, None]), -1)
            out = F.grid_sample(y[None], sample[None], mode="bilinear", align_corners=True)[0]
            return {k: v[0] for k, v in CN.denorm(out[None]).items()}
        # Bilinear at each cell centre from its ABSOLUTE position on the climate grid (fp64, a function of the cell
        # alone): grid_sample's normalised coordinates depended on the request's grid extent, so the same cell came
        # out a few ulps apart in different requests -- and tiles depended on the order they were asked for.
        def axis(c0: int, n: int, k0: int):
            p = (torch.arange(c0, c0 + n, device=self.device, dtype=torch.float64) + 0.5) / g - 0.5
            k = torch.floor(p)
            return (k - k0).long(), (p - k).float()
        ki, fi = axis(ci0, hc, gi0)
        kj, fj = axis(cj0, wc, gj0)
        rows = (y.index_select(1, ki), y.index_select(1, ki + 1))                  # (7, hc, gw) each
        v00, v01 = rows[0].index_select(2, kj), rows[0].index_select(2, kj + 1)
        v10, v11 = rows[1].index_select(2, kj), rows[1].index_select(2, kj + 1)
        fy, fx = fi[:, None], fj[None, :]
        out = (v00 * ((1 - fx) * (1 - fy)) + v01 * (fx * (1 - fy))) + (v10 * ((1 - fx) * fy) + v11 * (fx * fy))
        return {k: v[0] for k, v in CN.denorm(out[None]).items()}

    def _climate_grid(self, gi0: int, gj0: int, gh: int, gw: int) -> torch.Tensor:
        items = []
        ramp = 2 * CLIMATE_APRON
        t = (torch.arange(ramp, device=self.device, dtype=torch.float32) + 0.5) / ramp
        w1 = torch.ones(CLIMATE_WIN, device=self.device)
        w1[:ramp] = torch.sin(0.5 * math.pi * t) ** 2
        w1[-ramp:] = torch.cos(0.5 * math.pi * t) ** 2
        w2 = w1[:, None] * w1[None, :]
        for wi in range(math.floor((gi0 - CLIMATE_WIN + CLIMATE_APRON) / CLIMATE_CORE),
                        math.floor((gi0 + gh - 1 + CLIMATE_APRON) / CLIMATE_CORE) + 1):
            for wj in range(math.floor((gj0 - CLIMATE_WIN + CLIMATE_APRON) / CLIMATE_CORE),
                            math.floor((gj0 + gw - 1 + CLIMATE_APRON) / CLIMATE_CORE) + 1):
                a0, b0 = wi * CLIMATE_CORE - CLIMATE_APRON, wj * CLIMATE_CORE - CLIMATE_APRON
                r0, r1 = max(gi0, a0), min(gi0 + gh, a0 + CLIMATE_WIN)
                s0, s1 = max(gj0, b0), min(gj0 + gw, b0 + CLIMATE_WIN)
                if r0 >= r1 or s0 >= s1:
                    continue
                items.append((self._climate_window(wi, wj), a0 - gi0, b0 - gj0))
        return KB.overlap_add((len(CN.OUT_NAMES), gh, gw), items, w2, self.device)   # bit-identical cross-fade

    def _land_lattice(self, gi0: int, gj0: int, n: int, step: int) -> torch.Tensor:
        """Land fraction on a lattice of `step` grid cells, n x n points starting at grid (gi0, gj0) (pointwise)."""
        g = CLIMATE_GRID_CELLS
        pi = (gi0 + (torch.arange(n, device=self.device, dtype=torch.float32) + 0.5) * step) * g
        pj = (gj0 + (torch.arange(n, device=self.device, dtype=torch.float32) + 0.5) * step) * g
        c = self._controls_at(pi.view(-1, 1).expand(n, n).contiguous(), pj.view(1, -1).expand(n, n).contiguous())
        return (c["trend"] > 0).float()

    def _continentality(self, a0: int, b0: int, km: float) -> torch.Tensor:
        """Land fraction blurred at `km` over the window [a0, a0+WIN) x [b0, b0+WIN) of grid cells, from a coarse
        lattice reaching 3 sigma beyond it (the window alone is too small for 1000-2500 km blurs)."""
        sigma = km / GRID_KM
        step = max(1, int(sigma // 3))
        reach = int(math.ceil(3 * sigma / step)) + 1
        n = CLIMATE_WIN // step + 2 * reach + 1
        land = self._land_lattice(a0 - reach * step, b0 - reach * step, n, step)
        blurred = D.blur(land[None, None], sigma / step)[0, 0]
        # lattice point k sits at grid a0 - reach*step + (k + 0.5) * step; window cell i at a0 + i + 0.5
        idx = (torch.arange(CLIMATE_WIN, device=self.device, dtype=torch.float32) + 0.5) / step + reach - 0.5
        gy = (idx / (n - 1)) * 2 - 1
        grid = torch.stack(torch.broadcast_tensors(gy[None, :], gy[:, None]), -1)
        return F.grid_sample(blurred[None, None], grid[None], mode="bilinear", align_corners=True)[0, 0]

    def _climate_window(self, wi: int, wj: int) -> torch.Tensor:
        def run():
            from scipy import ndimage

            a0, b0 = wi * CLIMATE_CORE - CLIMATE_APRON, wj * CLIMATE_CORE - CLIMATE_APRON
            n, k, g, e = CLIMATE_WIN, CLIMATE_SUB, CLIMATE_GRID_CELLS, CLIMATE_FETCH_CELLS
            # Controls over the window plus a fetch margin, pooled to the node's grid exactly like its training inputs
            # (0.25 deg block means): the upwind fields sample THIS smooth grid, as training sampled ERA5's -- point
            # samples of the raw trend read every sharp ridge as a km-high barrier (t0 fell ~25 C at spawn).
            m = n + 2 * e
            sub = (torch.arange(m * k, device=self.device, dtype=torch.float32) + 0.5) / k
            pi = ((a0 - e + sub) * g).view(-1, 1).expand(m * k, m * k).contiguous()
            pj = ((b0 - e + sub) * g).view(1, -1).expand(m * k, m * k).contiguous()
            c = self._controls_at(pi, pj)
            pool = lambda x: F.avg_pool2d(x[None, None], k)[0, 0]
            trend = c["trend"]
            land_x = pool((trend > 0).float())
            elev_x = pool(trend.clamp_min(0)) / land_x.clamp_min(1e-3) / 1000.0 * (land_x > 0)
            inner = lambda x: x[e : e + n, e : e + n]
            land, elev = inner(land_x), inner(elev_x)
            sea = (land < 0.5).cpu().numpy()
            d_land = ndimage.distance_transform_edt(sea) * GRID_KM
            d_sea = ndimage.distance_transform_edt(~sea) * GRID_KM
            coast = torch.from_numpy(((d_sea - d_land) / 50.0)).to(self.device).float().asinh()
            belt = inner(pool(c["belt"]))
            l250, l1000 = self._continentality(a0, b0, 250.0), self._continentality(a0, b0, 1000.0)
            # The node saw ERA5's 10 m annual VECTOR-mean wind: weak over land (roughness + changing directions; land
            # 30-55 deg: u ~0.3-0.5 m/s vs 3.45 at sea). The procedural belt wind is a free wind, 5x too strong over
            # land, which put spawn at -6 C; damp it by the land share around to the distribution the node knows.
            damp = WIND_SCALE * (LAND_WIND[0] + LAND_WIND[1] * (1.0 - l250) ** 2)
            u, v = inner(pool(c["wind_u"])) * damp, inner(pool(c["wind_v"])) * damp
            grid = torch.stack([land_x, elev_x])[None]
            ii = (torch.arange(n, device=self.device, dtype=torch.float32) + e).view(-1, 1).expand(n, n)
            jj = (torch.arange(n, device=self.device, dtype=torch.float32) + e).view(1, -1).expand(n, n)

            def fetch(drow, dcol):            # bilinear on the pooled grid (positions beyond it clamp at its edge)
                gy = ((ii + drow).clamp(0, m - 1) / (m - 1)) * 2 - 1
                gx = ((jj + dcol).clamp(0, m - 1) / (m - 1)) * 2 - 1
                out = F.grid_sample(grid, torch.stack([gx, gy], -1)[None], mode="bilinear", align_corners=True)[0]
                return out[0], out[1]

            up = CN.upwind_features(fetch, u, v, GRID_KM, GRID_KM, elev, l250, l1000)
            x = torch.stack([elev, land, l250, l1000, self._continentality(a0, b0, 2500.0), coast, belt, belt.abs(),
                             u / 5.0, v / 5.0, *up,
                             torch.full_like(u, self.climate_forcing[0] / CN.FORCING_DT_SCALE),
                             torch.full_like(u, self.climate_forcing[1] / CN.FORCING_AGE_SCALE)])
            with torch.no_grad():
                return self.climate_net(x[None])[0].float()
        return self._climate_windows.get_or((self.seed, wi, wj), run)

    # ------------------------------------------------------------------ detail experts
    def detail_gates(self, ci0: int, cj0: int, nc: int, margin_samples: int) -> torch.Tensor | None:
        if not isinstance(self.detail, D2.Detail2) or not len(self.detail.adapters):
            return None
        gates = self.controls(ci0, cj0, nc, nc)["gates"]                           # (S, nc, nc) cells
        up = DT.up_cells(gates[None])[0]                                           # samples
        m = margin_samples
        return up[:, m : m + G.DETAIL_EXT, m : m + G.DETAIL_EXT][None]

    # ------------------------------------------------------------------ SR at 1:2
    def fine_detail(self, i1: int, j1: int, i2: int, j2: int) -> torch.Tensor | None:
        if self.sr30 is None or self.scale.px_per_block >= DT.DS:
            return None
        return self._sr_blend(i1, j1, i2, j2, self._sr_window)

    def native_fine(self, k1: int, l1: int, k2: int, l2: int) -> torch.Tensor:
        """1:1 blocks (15 m half pixels) from SR-15 over the 30 m terrain: canonical windows cross-faded with weights
        constant inside each 2x2 block, so every 2x2 block averages EXACTLY to its 30 m pixel (as SR-30 to its 60 m
        sample). Without sr15.pt: the bilinear stand-in."""
        if self.sr15 is None:
            return super().native_fine(k1, l1, k2, l2)
        return self._sr_blend(k1, l1, k2, l2, self._sr15_window)

    def _sr15_window(self, wi: int, wj: int) -> torch.Tensor:
        def run():
            ext = SR_CORE + 2 * SR_APRON
            a0, b0 = wi * SR_CORE - SR_APRON, wj * SR_CORE - SR_APRON           # 15 m px, even
            m = 4                                                                # 30 m pixels of bicubic context
            with self.lock:
                coarse = self.native(a0 // 2 - m, b0 // 2 - m, (a0 + ext) // 2 + m, (b0 + ext) // 2 + m)
            up = SRM.upsample(coarse[None, None])
            cond = SRM.build_cond(up, 15.0)[..., 2 * m : 2 * m + ext, 2 * m : 2 * m + ext]
            noise = S.white_noise(a0, b0, ext, ext, self.seed, SR15_NOISE_STREAM, self.device)[None, None]
            look = self.look_build
            g = 1.0 if look else self.sr15_gain
            if self._sr15_native:
                x = self._sr_sample(self.sr15, cond, noise, SR15_STEPS) * g
            else:
                x = SRM.sample(self.sr15, cond.contiguous(memory_format=torch.channels_last), noise, SR15_STEPS) * g
            c0 = coarse[None, None][..., m:-m, m:-m]
            fine = SRM.assemble(c0, x)
            return (_sr_gain_look(fine, c0, self.sr15_gain) if look else fine)[0, 0]
        return self._sr15_windows.get_or((self.seed, wi, wj), run)

    def _sr_blend(self, i1: int, j1: int, i2: int, j2: int, window) -> torch.Tensor:
        """Fine pixels [i1, i2) x [j1, j2) cross-faded from canonical SR windows (`window(wi, wj)`, SR_CORE + 2 x
        SR_APRON fine pixels each); the fade weights are constant inside each 2x2 block: exact means."""
        h, w = i2 - i1, j2 - j1
        items = []
        ext = SR_CORE + 2 * SR_APRON
        ramp = 2 * SR_APRON
        t = (torch.arange(ramp // 2, device=self.device, dtype=torch.float32) + 0.5) / (ramp // 2)
        w1 = torch.ones(ext // 2, device=self.device)                              # per 2x2 block: exact means
        w1[: ramp // 2] = torch.sin(0.5 * math.pi * t) ** 2
        w1[-(ramp // 2):] = torch.cos(0.5 * math.pi * t) ** 2
        w1 = w1.repeat_interleave(2)
        w2 = w1[:, None] * w1[None, :]
        for wi in range(math.floor((i1 - ext + SR_APRON) / SR_CORE), math.floor((i2 - 1 + SR_APRON) / SR_CORE) + 1):
            for wj in range(math.floor((j1 - ext + SR_APRON) / SR_CORE), math.floor((j2 - 1 + SR_APRON) / SR_CORE) + 1):
                a0, b0 = wi * SR_CORE - SR_APRON, wj * SR_CORE - SR_APRON
                r0, r1 = max(i1, a0), min(i2, a0 + ext)
                s0, s1 = max(j1, b0), min(j2, b0 + ext)
                if r0 >= r1 or s0 >= s1:
                    continue
                items.append((window(wi, wj), a0 - i1, b0 - j1))
        return KB.overlap_add((1, h, w), items, w2, self.device)[0]                # bit-identical cross-fade

    def _sr_window(self, wi: int, wj: int) -> torch.Tensor:
        def run():
            ext = SR_CORE + 2 * SR_APRON
            a0, b0 = wi * SR_CORE - SR_APRON, wj * SR_CORE - SR_APRON           # fine px, even
            m = 4                                                                # samples of bicubic context
            coarse = self._detail_samples(a0 // 2 - m, b0 // 2 - m, (a0 + ext) // 2 + m, (b0 + ext) // 2 + m)
            up = SRM.upsample(coarse[None, None])
            cond = SRM.build_cond(up, 30.0)[..., 2 * m : 2 * m + ext, 2 * m : 2 * m + ext]
            noise = S.white_noise(a0, b0, ext, ext, self.seed, SR_NOISE_STREAM, self.device)[None, None]
            look = self.look_build
            x = self._sr_sample(self.sr30, cond, noise, SR_STEPS) * (1.0 if look else SR_GAIN)
            c0 = coarse[None, None][..., m:-m, m:-m]
            fine = SRM.assemble(c0, x)
            return (_sr_gain_look(fine, c0, SR_GAIN) if look else fine)[0, 0]
        return self._sr_windows.get_or((self.seed, wi, wj), run)
