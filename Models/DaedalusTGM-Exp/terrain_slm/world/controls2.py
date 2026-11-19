"""EXP-2 world controls (plan §3a, §5, §5b): v4's shape controls plus what the new nodes read.

    c = controls2(ci0, cj0, hc, wc, seed, device)      # cells [ci0, ci0+hc) x [cj0, cj0+wc), 240 m each

Pointwise in cell coordinates like v4's (any window is exact, so tiles stay request-independent). v4's
`procedural_controls` is untouched (v4 worlds stay bit-exact); this wraps it:

  trend, wild       v4's continents / ranges / hills, drowned where OCEAN BASINS open (2026-11-18: a ~2,400 km field
                    pushes v4's continent field into deep ocean, Atlantic/Pacific-sized, never near spawn), then ISLANDS: in coast-complexity provinces the ground
                    near sea level is broken up by island noise (archipelagos, skerries), and sparse hotspots raise
                    volcanic islands out of the sea
  lat, belt         effective latitude by CLIMATE LAYOUT (latitude_deg): 'belts' (default) = a per-seed random pole axis,
                    strongly warped, periodic over BELT_PERIOD_CELLS; 'zones' = ~600 km climate zones, no axis. Spawn is
                    pulled to PHI_HOME_DEG under both; belt = sin(phi)
  wind_u, wind_v    prevailing 10 m wind from the belt (trades, westerlies, polar easterlies) + a little noise:
                    the climate node casts rain shadows downwind of ranges with it
  gates             (len(detail2.STYLES), H, W) in [0, 1]: the style experts' weights. glacial_fjord at cold
                    mountainous coasts, volcanic around hotspots, drowned_archipelago / skerries in coast-complexity
                    provinces (warm / cold), lagoon_barrier on low gentle coasts; aeolian needs the climate node's
                    aridity, so the generator fills it after climate pass B (`aeolian_gate`)
  coast             the coast-complexity province itself (0..1)
  karst             limestone provinces (0..1, land only): the carve node's karst / tower-karst gate (tiles._carve)
  trench, ridge,    (2026-11-18) ocean tectonics on the sea floor, 0..1 each: trenches along a share of continental
  canyon            margins (a furrow ~30 km wide beyond the slope, to ~5.5 km below the abyss), mid-ocean ridges far
                    from land (a broad ~2 km rise with an axial rift valley, abyssal-hill fabric around it), and
                    branching canyons cut into continental slopes. Pointwise like everything else: a contour's distance
                    is the field over its finite-difference gradient. Trained against the deep-ocean regions
                    (mariana_trench, puerto_rico, mid_atlantic_ridge, atlantic_canyons).

All constants below are knobs for the world's look; the defaults are a first proposal.
"""
from __future__ import annotations

import math

import torch

from terrain_slm.models.detail2 import STYLES
from terrain_slm.world import generator as G

BELT_PERIOD_CELLS = 50_000.0     # one full cycle (0 -> +90 -> 0 -> -90 deg): ~12,000 km (Earth 40,000)
BELT_WARP_CELLS = 300.0          # belts wander, not straight lines
PHI_HOME_DEG = 37.0              # spawn latitude: temperate
COAST_PERIOD_CELLS = 500.0       # coast-complexity provinces (~120 km)
COAST_SHARE_BIAS = -0.6          # lower = fewer provinces
ISLAND_PERIOD_CELLS = 40.0       # archipelago island scale (~10 km)
SKERRY_PERIOD_CELLS = 12.0       # skerry scale (~3 km), cold provinces
ISLAND_M = 650.0                 # island noise amplitude around sea level
COAST_BAND_M = 450.0             # islands only where the trend is within this of sea level
HOTSPOT_PERIOD_CELLS = 300.0     # hotspot lattice (one candidate per lattice cell, jittered)
HOTSPOT_CHANCE = 0.12            # share of lattice cells that hold a volcano
VOLCANO_M = 2600.0
VOLCANO_RADIUS_CELLS = 45.0
VOLCANO_RISE_M = 4500.0          # a cone rises this far above the floor it stands on (capped at VOLCANO_M): from the
                                 # abyss most stay seamounts, a few breach the surface (Hawaii-style), on land 2,600 m
COLD_DEG = (48.0, 62.0)          # glacial / skerry styles fade in over this latitude range
FJORD_MOUNTAIN_M = 900.0         # trend that counts as mountains for fjords
SHELF_M, ABYSS_M = -150.0, -4200.0   # ocean floor: shelf near coasts, abyssal plain far out (v4: flat -300)
ABYSS_CONTINENT = (-0.45, -0.85)     # v4 continent field values where the slope starts / the abyss is reached
# Ocean tectonics.
TRENCH_LEVEL = -0.7              # continent-field contour the trench follows (beyond the slope, before the abyss)
TRENCH_M = 5500.0                # extra depth at the axis (abyss -4200 -> ~-9700; Challenger Deep is -10,900)
TRENCH_SIGMA_CELLS = 55.0        # ~13 km Gaussian half-width: a furrow ~30 km across
TRENCH_PERIOD_CELLS = 3000.0     # subduction provinces (~700 km): which margins get a trench
TRENCH_BIAS = -0.35              # lower = fewer trench margins (~1/3)
RIDGE_PERIOD_CELLS = 9000.0      # spreading-ridge network: zero contour of a very slow, single-octave field (straight-ish)
RIDGE_M, RIDGE_SIGMA_CELLS = 2200.0, 160.0    # broad thermal rise (~2 km over ~40 km)
RIFT_M, RIFT_SIGMA_CELLS = 900.0, 12.0        # axial rift valley (slow-spreading, Mid-Atlantic style)
HILLS_M, HILLS_PERIOD_CELLS = 220.0, 14.0     # abyssal-hill fabric, strongest near ridges
CANYON_M, CANYON_PERIOD_CELLS = 600.0, 70.0   # slope canyons: branching incisions on the continental slope, ~17 km apart
CANYON_WIDTH = 0.30                            # share of the noise range that is canyon: ~4-8 km across. (700 m / 22 cells /
                                               # 0.18 cut 1-2-cell slots: 400 m steps between 240 m cells, a blocky floor)
# Ocean basins: v4's continent field (216 km features) gives ~70% land and seas a few hundred km across; real worlds
# need oceans. A very slow field opens basins by pushing the continent field down; coasts keep v4's own rule.
BASIN_PERIOD_CELLS = 10_000.0    # ~2,400 km features
BASIN_BIAS = 0.05                # higher = more ocean (target: about half the world ocean)
BASIN_SOFT = 0.35                # width of the continental margin zone in field units
BASIN_PUSH = 1.6                 # how far a full basin pushes the continent field (well past the abyss level)
BASIN_HOME_KEEP_CELLS = 4500.0   # ~1,100 km around spawn stays continental (1500 left spawn on a mid-ocean island)
KARST_PERIOD_CELLS = 600.0       # karst provinces (limestone country, ~150 km): sinkholes, or tower karst where humid
KARST_BIAS = -0.9                # lower = fewer provinces (~1 in 8 of land)
# Lowland texture (2026-11-18): the relief / detail nodes draw roughness from `wild` (log band-3 amplitude). v4's plains
# (wild ~ log 2 m) drew dead-flat lowland at block scale; a gentle floor keeps meadows calm but never a table, and a
# little more roll in v4's hilly provinces (same province field) varies the lowlands without touching landmasses.
WILD_FLOOR_M = 3.5               # plains floor (real plains: band-3 ~2-3 m, gently rolling lowland ~5 m)
ROLLING_BOOST = 0.25             # extra log-amplitude in rolling / hill provinces (x1.28)
BIG_OCEAN_RADIUS_CELLS = 250.0   # trenches / ridges only in sizeable seas: deep at most points of this ring (~60 km)


def _ss(x):
    return G._smoothstep(x)


CLIMATE_LAYOUTS = ("belts", "zones", "latitude")
BELT_WARP_STRONG_CELLS = 2500.0  # "belts": belts fold and meander (cold tongues, warm pockets) ...
BELT_WARP_PERIOD_CELLS = 6000.0  # ... over ~1,400 km
ZONE_PERIOD_CELLS = 2500.0       # "zones": climate zones ~600 km across, each its own effective latitude
ZONE_SPREAD = 0.6                # tanh gain: how far zones push toward tropics / poles (1.6 made most zones polar or
                                 # tropical, with snow butting onto jungle; 1.0 left 42% polar; 0.6 ~18% polar vs Earth 13%)
HOME_CLIMATE_CELLS = 1500.0      # spawn region pulled to PHI_HOME_DEG (~360 km) under 'belts' / 'latitude'
HOME_ZONE_CELLS = 4000.0         # ... and a wider, gentler pull under 'zones' (1500 drew a ring around spawn)


def _seed_angle(seed: int) -> float:
    """Per-seed direction of the pole axis (belts): no world has a fixed compass direction to the snow."""
    import random

    return random.Random(seed * 0x9E3779B1 + 47).uniform(0.0, 2 * math.pi)


def latitude_deg(ci: torch.Tensor, cj: torch.Tensor, seed: int, layout: str = "belts") -> torch.Tensor:
    """Effective latitude (deg, -90..90), pointwise. The climate node only ever reads it locally (belt = sin phi) and
    through the wind it implies (wind(): belts relative to the local poleward direction), so any smooth field works:
      belts   latitude along a per-seed random axis, strongly warped: travel far enough and it gets colder, but the
              belts fold and wander and no world puts the snow in the same direction (option B, 2026-11-18)
      zones   no global axis: climate zones ~600 km across, each with its own effective latitude (option C)
      latitude  a true global latitude: -X (lower rows) is north, no warp beyond a gentle wiggle; periodic over
                BELT_PERIOD_CELLS like a globe (option A: one compass north for sun path / day length / seasons)
    All are pulled to PHI_HOME_DEG around spawn."""
    if layout not in CLIMATE_LAYOUTS:
        raise ValueError(f"climate layout {layout!r} not in {CLIMATE_LAYOUTS}")
    r = HOME_ZONE_CELLS if layout == "zones" else HOME_CLIMATE_CELLS
    home = torch.exp(-(ci ** 2 + cj ** 2) / (2 * r ** 2))
    if layout == "latitude":
        off = math.asin(PHI_HOME_DEG / 90.0) / (2 * math.pi) * BELT_PERIOD_CELLS
        warp = BELT_WARP_CELLS * G._fbm(ci, cj, 1200.0, seed, 30, octaves=2) / G._FBM2_STD
        return 90.0 * torch.sin(2 * math.pi * (-ci + off + warp) / BELT_PERIOD_CELLS) * (1 - home) + PHI_HOME_DEG * home
    if layout == "zones":
        z = G._fbm(ci, cj, ZONE_PERIOD_CELLS, seed, 46, octaves=3) / G._FBM3_STD
        phi = 90.0 * torch.tanh(ZONE_SPREAD * z)
    else:
        th = _seed_angle(seed)
        s = ci * math.cos(th) + cj * math.sin(th)
        warp = BELT_WARP_STRONG_CELLS * G._fbm(ci, cj, BELT_WARP_PERIOD_CELLS, seed, 30, octaves=2) / G._FBM2_STD
        warp0 = BELT_WARP_STRONG_CELLS * G._fbm(torch.zeros(1, device=ci.device), torch.zeros(1, device=ci.device),
                                                BELT_WARP_PERIOD_CELLS, seed, 30, octaves=2) / G._FBM2_STD
        off = math.asin(PHI_HOME_DEG / 90.0) / (2 * math.pi) * BELT_PERIOD_CELLS
        phi = 90.0 * torch.sin(2 * math.pi * (s + warp - warp0 + off) / BELT_PERIOD_CELLS)
    return phi * (1 - home) + PHI_HOME_DEG * home


def hotspots(ci: torch.Tensor, cj: torch.Tensor, seed: int) -> torch.Tensor:
    """Round volcanic cones (0..1): each lattice cell of HOTSPOT_PERIOD_CELLS holds a volcano with HOTSPOT_CHANCE at a
    hashed position; the field is the nearest volcano's Gaussian cone (3x3 lattice neighbourhood)."""
    from terrain_slm.synth import noise as S

    p = HOTSPOT_PERIOD_CELLS
    gi, gj = torch.floor(ci / p).long(), torch.floor(cj / p).long()
    base = S._hash32(torch.tensor((seed * 0x9E3779B1 + 36 * 0x632BE5AB) & S._MASK32, device=ci.device))
    out = torch.zeros_like(ci)
    for di in (-1, 0, 1):
        for dj in (-1, 0, 1):
            h = S._hash32(S._hash32((gi + di) ^ base) ^ (gj + dj))
            u = lambda k: (S._hash32(h ^ (0x51ED27 * (k + 1))).float() / 4294967296.0) % 1.0
            alive = (u(0) < HOTSPOT_CHANCE).float()
            pi, pj = (gi + di).float() * p + u(1) * p, (gj + dj).float() * p + u(2) * p
            d2 = (ci - pi) ** 2 + (cj - pj) ** 2
            out = torch.maximum(out, alive * torch.exp(-d2 / (2 * VOLCANO_RADIUS_CELLS ** 2)))
    return out


def wind(phi_deg: torch.Tensor, ci, cj, seed, layout: str = "belts") -> tuple[torch.Tensor, torch.Tensor]:
    """Mean 10 m wind (m/s) in the climate node's convention (ERA5's): u eastward = +cj, v northward = -ci. Belts relative
    to the LOCAL poleward direction (from the latitude field's gradient), so they follow however the layout bends them:
    trades from the east toward the equator below ~30 deg, westerlies (slightly poleward) 30-60, polar easterlies
    (equatorward) above. "East" is the latitude gradient turned a quarter left; on a straight +lat = -ci layout it is
    +cj, Earth's."""
    a = phi_deg.abs()
    w_trade = 1 - _ss((a - 25.0) / 10.0)
    w_polar = _ss((a - 58.0) / 8.0)
    w_west = (1 - w_trade) * (1 - w_polar)
    zonal = -6.0 * w_trade + 7.0 * w_west - 3.0 * w_polar                      # + = toward local east
    merid = -2.0 * w_trade + 1.0 * w_west - 1.0 * w_polar                      # + = poleward
    gi = (latitude_deg(ci + 1, cj, seed, layout) - latitude_deg(ci - 1, cj, seed, layout)) * 0.5
    gj = (latitude_deg(ci, cj + 1, seed, layout) - latitude_deg(ci, cj - 1, seed, layout)) * 0.5
    gn = torch.sqrt(gi * gi + gj * gj).clamp_min(1e-6)
    ni, nj = gi / gn, gj / gn                                                 # toward higher latitude ("north")
    ei, ej = nj, -ni                                                          # local east (north turned a quarter)
    pole = torch.sign(phi_deg)                                                # poleward = north in N, south in S
    wi = zonal * ei + merid * pole * ni
    wj = zonal * ej + merid * pole * nj
    u, v = wj, -wi                                                            # grid (i, j) -> ERA5 (east, north)
    u = u + 1.5 * G._fbm(ci, cj, 800.0, seed, 31, octaves=2) / G._FBM2_STD
    v = v + 1.5 * G._fbm(ci, cj, 800.0, seed, 32, octaves=2) / G._FBM2_STD
    return u, v


def basins(ci: torch.Tensor, cj: torch.Tensor, seed: int) -> torch.Tensor:
    """Ocean-basin weight 0..1 (pointwise): where the world opens into ocean, away from spawn."""
    b = _ss((G._fbm(ci, cj, BASIN_PERIOD_CELLS, seed, 44, octaves=2) / G._FBM2_STD + BASIN_BIAS) / BASIN_SOFT)
    home = torch.exp(-(ci ** 2 + cj ** 2) / (2 * BASIN_HOME_KEEP_CELLS ** 2))
    return b * (1 - home)


def continent2(ci: torch.Tensor, cj: torch.Tensor, seed: int) -> tuple[torch.Tensor, torch.Tensor]:
    """(continent field, land weight) with ocean basins: v4's field pushed down by `basins`, land by v4's own coast rule
    (generator._continent), so coastlines keep v4's sharpness."""
    continent, _ = G._continent(ci, cj, seed)
    c2 = continent - BASIN_PUSH * basins(ci, cj, seed)
    return c2, G._smoothstep((c2 + 0.35) / 0.2)


def _contour_distance(f: torch.Tensor, df_dci: torch.Tensor, df_dcj: torch.Tensor) -> torch.Tensor:
    """Signed distance (cells) to the zero contour of a smooth field, first order: f / |grad f|."""
    return f / torch.sqrt(df_dci ** 2 + df_dcj ** 2).clamp_min(1e-6)


def ocean_tectonics(ci: torch.Tensor, cj: torch.Tensor, seed: int, continent: torch.Tensor, deep: torch.Tensor):
    """(depth offset m, trench 0..1, ridge 0..1, canyon 0..1) for the sea floor; offsets apply where (1 - land_w)."""
    # Big-ocean gate: share of 8 points on a ring of BIG_OCEAN_RADIUS_CELLS that are deep ocean. Without it trenches
    # followed the TRENCH_LEVEL contour around every small deep basin as closed rings; real trenches line big oceans.
    ring = 0.0
    for k in range(8):
        a = 2 * math.pi * k / 8
        ck = continent2(ci + BIG_OCEAN_RADIUS_CELLS * math.sin(a), cj + BIG_OCEAN_RADIUS_CELLS * math.cos(a), seed)[0]
        ring = ring + _ss((ABYSS_CONTINENT[0] - ck) / (ABYSS_CONTINENT[0] - ABYSS_CONTINENT[1])) / 8
    big = _ss((ring - 0.3) / 0.3)
    # Trench: a furrow along the TRENCH_LEVEL contour of the continent field, on the margins a slow province picks.
    c_i = (continent2(ci + 1, cj, seed)[0] - continent2(ci - 1, cj, seed)[0]) * 0.5
    c_j = (continent2(ci, cj + 1, seed)[0] - continent2(ci, cj - 1, seed)[0]) * 0.5
    d_trench = _contour_distance(continent - TRENCH_LEVEL, c_i, c_j)
    margin = _ss((G._fbm(ci, cj, TRENCH_PERIOD_CELLS, seed, 39, octaves=2) / G._FBM2_STD + TRENCH_BIAS) / 0.5)
    # Subduction polarity: a slowly turning direction; only the side of a basin whose margin faces it subducts, so a
    # contour loop around a sea becomes an ARC on one side (Mariana, Aleutians), not a ring.
    theta = math.pi * G._fbm(ci, cj, 2 * TRENCH_PERIOD_CELLS, seed, 43, octaves=2) / G._FBM2_STD
    g_n = torch.sqrt(c_i ** 2 + c_j ** 2).clamp_min(1e-6)
    facing = (c_i * torch.cos(theta) + c_j * torch.sin(theta)) / g_n
    side = _ss((facing - 0.25) / 0.5)
    trench = big * margin * side * torch.exp(-(d_trench / TRENCH_SIGMA_CELLS) ** 2) * _ss(deep * 3.0)
    # Ridges: the zero contour of a very slow field, only far out (deep ~ 1) so they never touch a continent.
    r = lambda a, b: G._fbm(a, b, RIDGE_PERIOD_CELLS, seed, 40, octaves=1)
    f = r(ci, cj)
    d_ridge = _contour_distance(f, (r(ci + 1, cj) - r(ci - 1, cj)) * 0.5, (r(ci, cj + 1) - r(ci, cj - 1)) * 0.5)
    far = big * _ss((deep - 0.7) / 0.2)
    rise = torch.exp(-(d_ridge / RIDGE_SIGMA_CELLS) ** 2)
    ridge = far * rise
    rift = far * torch.exp(-(d_ridge / RIFT_SIGMA_CELLS) ** 2)
    hills = HILLS_M * deep * (0.4 + 0.6 * rise) * G._fbm(ci, cj, HILLS_PERIOD_CELLS, seed, 41, octaves=3) / G._FBM3_STD
    # Canyons: thin branching lines (|noise| small) confined to the continental slope, deepening downslope.
    slope_zone = _ss(deep / 0.12) * (1 - _ss((deep - 0.75) / 0.2))
    n = G._fbm(ci, cj, CANYON_PERIOD_CELLS, seed, 42, octaves=3) / G._FBM3_STD
    canyon = slope_zone * torch.exp(-(n / CANYON_WIDTH) ** 2) * (1 - trench)
    offset = -TRENCH_M * trench + RIDGE_M * ridge - RIFT_M * rift + hills - CANYON_M * canyon * (0.4 + 0.6 * deep)
    return offset, trench, ridge, canyon


def controls2(ci0: int, cj0: int, hc: int, wc: int, seed: int, device, layout: str = "belts") -> dict:
    base = G.procedural_controls(ci0, cj0, hc, wc, seed, device)
    ci = torch.arange(ci0, ci0 + hc, device=device, dtype=torch.float32).view(-1, 1).expand(hc, wc)
    cj = torch.arange(cj0, cj0 + wc, device=device, dtype=torch.float32).view(1, -1).expand(hc, wc)
    return _exp2(base, ci, cj, seed, layout)


def controls2_at(ci: torch.Tensor, cj: torch.Tensor, seed: int, layout: str = "belts") -> dict:
    """`controls2` at arbitrary float cell coordinates (sparse grids: the climate node's ~28 km windows)."""
    return _exp2(G.procedural_controls_at(ci, cj, seed), ci, cj, seed, layout)


def _exp2(base: dict, ci: torch.Tensor, cj: torch.Tensor, seed: int, layout: str = "belts") -> dict:
    hc, wc = ci.shape
    device = ci.device
    # Ocean basins: drown v4's land where a basin opens. v4's trend is -300 + land * (relief terms), so scaling its land
    # part by land2 / land keeps every relief term and coastline shape and only lowers drowned ground to the sea floor.
    _, land_v4 = G._continent(ci, cj, seed)
    continent, land_w = continent2(ci, cj, seed)
    r = land_w / land_v4.clamp_min(1e-6)
    trend0 = torch.where(land_v4 > 0, -300.0 + (base["trend"] + 300.0) * r, base["trend"])
    wild = base["wild"] * torch.where(land_v4 > 0, r, torch.ones_like(r)) + \
        math.log(2.0) * (1 - torch.where(land_v4 > 0, r, torch.ones_like(r)))
    rolling = _ss((G._fbm(ci, cj, G.HILL_PERIOD_CELLS, seed, 9, octaves=3) / G._FBM3_STD + G.HILL_BIAS)
                  / G.HILL_SOFTNESS)                                        # v4's own lowland-province field
    wild = torch.maximum(wild + ROLLING_BOOST * rolling * land_w, wild * 0 + math.log(WILD_FLOOR_M) * land_w
                         + wild * (1 - land_w))
    base = {**base, "wild": wild}
    # Real ocean floors (data v2 has bathymetry): v4's flat -300 m sea becomes shelf -> slope -> abyss, keyed to v4's
    # continent field so coasts stay where v4 put them.
    deep = _ss((continent - ABYSS_CONTINENT[0]) / (ABYSS_CONTINENT[1] - ABYSS_CONTINENT[0]))
    ocean_floor = SHELF_M + (ABYSS_M - SHELF_M) * deep
    tecto, trench, ridge, canyon = ocean_tectonics(ci, cj, seed, continent, deep)
    trend0 = torch.where(land_w < 1.0, trend0 + (1.0 - land_w) * (ocean_floor + 300.0 + tecto), trend0)
    phi = latitude_deg(ci, cj, seed, layout)
    cold = _ss((phi.abs() - COLD_DEG[0]) / (COLD_DEG[1] - COLD_DEG[0]))

    # Coast-complexity provinces: islands and drowned valleys near sea level (archipelagos when warm, skerries when cold).
    coast = _ss((G._fbm(ci, cj, COAST_PERIOD_CELLS, seed, 33, octaves=3) / G._FBM3_STD + COAST_SHARE_BIAS) / 0.8)
    band = torch.exp(-(trend0 / COAST_BAND_M) ** 2)
    isl = G._fbm(ci, cj, ISLAND_PERIOD_CELLS, seed, 34, octaves=3) / G._FBM3_STD
    sker = G._fbm(ci, cj, SKERRY_PERIOD_CELLS, seed, 35, octaves=3) / G._FBM3_STD
    noise = (1 - cold) * isl + cold * (0.6 * isl + 0.4 * sker)
    trend = trend0 + coast * band * ISLAND_M * noise

    # Hotspots: sparse round volcanoes (islands out of the sea, cones on land), one candidate per jittered lattice cell.
    spot = hotspots(ci, cj, seed)
    jitter = G._fbm(ci, cj, VOLCANO_RADIUS_CELLS, seed, 37, octaves=3) / G._FBM3_STD
    cone = spot * (1 + 0.2 * jitter).clamp(0.5, 1.5)
    peak = torch.minimum(torch.full_like(trend, VOLCANO_M), trend + VOLCANO_RISE_M * (1 + 0.35 * jitter))
    trend = torch.maximum(trend, trend + cone.clamp(0, 1) * (peak - trend))   # a cone rises from the floor it stands on

    u, v = wind(phi, ci, cj, seed, layout)
    land = (trend > 0).float()
    near_coast = torch.exp(-(trend / 1500.0) ** 2) + 0.0
    mountains = _ss((trend0 - FJORD_MOUNTAIN_M) / 800.0)
    gates = torch.zeros(len(STYLES), hc, wc, device=device)
    gates[STYLES.index("glacial_fjord")] = cold * _ss(mountains * 1.5) * _ss(near_coast * 2.0)
    gates[STYLES.index("volcanic")] = _ss(spot * 2.0)
    gates[STYLES.index("drowned_archipelago")] = coast * band * (1 - cold)
    gates[STYLES.index("skerries")] = coast * band * cold * (1 - mountains)
    # Only the coastal transition itself (v4's trend crosses sea level over a few hundred metres there).
    low_gentle = (1 - mountains) * torch.exp(-(trend0 / 40.0) ** 2) * (1 - coast)
    lag = _ss((G._fbm(ci, cj, 400.0, seed, 38, octaves=2) / G._FBM2_STD - 0.2) / 0.6)
    gates[STYLES.index("lagoon_barrier")] = low_gentle * lag * (1 - cold)
    # "aeolian": filled after climate pass B (aeolian_gate).
    # Undersea detail (CUDEM-trained): from the waterline, fully on by ~30 m depth (reef walls are shallow).
    gates[STYLES.index("undersea")] = _ss(-trend / 30.0)
    return {**base, "trend_v4": base["trend"], "trend_sea": trend0, "trend": trend, "lat": phi, "belt": torch.sin(torch.deg2rad(phi)),
            "wind_u": u, "wind_v": v, "gates": gates.clamp(0, 1), "coast": coast, "land": land,
            "trench": trench, "ridge": ridge, "canyon": canyon,
            "karst": _ss((G._fbm(ci, cj, KARST_PERIOD_CELLS, seed, 45, octaves=2) / G._FBM2_STD + KARST_BIAS) / 0.3) * land}


def aeolian_gate(aridity: torch.Tensor, relief_m: torch.Tensor) -> torch.Tensor:
    """Sand seas: hyper-arid (aridity < 0.05 -> 1, > 0.2 -> 0) and not mountainous."""
    return _ss((0.2 - aridity) / 0.15) * (1 - _ss((relief_m - 400.0) / 400.0))
