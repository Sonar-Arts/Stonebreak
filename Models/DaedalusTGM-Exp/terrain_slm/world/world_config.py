"""The game world's scale: block grid, tile grid and the metres -> blocks height curve.

The game owns these numbers (`TerrainScale.worldConfig()` in stonebreak-game) and sends them in
the service handshake, so the model never guesses them. `WorldConfig.GAME` mirrors the game's
values for offline work -- training, evaluation, tests -- that runs without the game.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import MISSING, asdict, dataclass, fields


@dataclass(frozen=True)
class WorldConfig:
    #: Top of the block column, EXCLUSIVE (the game sends its max y, e.g. 640 for [-384, 640)); heights are
    #: absolute block y in [min_y, world_height).
    world_height: int
    sea_level: int
    #: Metres of real terrain per block, horizontally (60 = the 1:4 world).
    horizontal_m: float
    #: Model pixels (30 m) averaged into one block along each axis: 2 at 1:4, 1 at 1:2, 0.5 at 1:1 (15 m blocks, two
    #: blocks per pixel: the SR-15 node draws them). Integral values are stored as int, so 1:4 / 1:2 fingerprints keep.
    downscale: float
    #: Blocks per tile side; tiles are the unit of request and caching.
    tile_size: int
    # Height curve (see height_curve.HeightCurve): metres per block per band, and the knots.
    ocean_m_per_block: float
    lowland_m_per_block: float
    midland_m_per_block: float
    highland_m_per_block: float
    lowland_top_m: float
    highland_base_m: float
    shore_blend_m: float
    midland_blend_m: float
    highland_blend_m: float
    #: Bottom of the block column, inclusive (the cubic world: -384). Optional in the handshake; 0 = the old
    #: [0, world_height) column. Heights are clamped to [min_y, world_height).
    min_y: int = 0
    #: EXP-2 climate layout of this world ('belts' | 'zones' | 'latitude'); '' = the service default. Like the seed,
    #: part of the world's identity (2026-11-19); ignored by generators without layouts (v4).
    climate_layout: str = ""
    #: Height-curve version (2026-11-19), part of a saved world's identity like the seed: 1 = the original curve (deep
    #: ocean rate right up to the shore; tiles floor the sea bed at -4 km), 2 = shelf band (finer metres per block near
    #: sea level, `shelf_*`) and the sea bed down to the trenches. Optional in the handshake (absent = 1).
    curve_version: int = 1
    #: v2 shelf band (`height_curve.HeightCurve.shelf_*`): metres per block from the shore down to `shelf_base_m`,
    #: blended into the ocean rate over +-`shelf_blend_m`. v1 requires `shelf_m_per_block == 0`.
    shelf_m_per_block: float = 0.0
    shelf_base_m: float = -200.0
    shelf_blend_m: float = 100.0
    #: v2 shallows band (`height_curve.HeightCurve.shallow_*`, round 2): an even finer rate for the first metres below
    #: the waterline. 0 = none (v1 requires 0).
    shallow_m_per_block: float = 0.0
    shallow_base_m: float = -16.0
    shallow_blend_m: float = 8.0

    def __post_init__(self) -> None:
        ds = float(self.downscale)
        if ds >= 1 and ds == int(ds):
            object.__setattr__(self, "downscale", int(ds))
        elif ds == 0.5:                       # 1:1 (2026-11-19): the only sub-pixel block size
            object.__setattr__(self, "downscale", 0.5)
        else:
            raise ValueError(f"downscale must be an integer >= 1 or 0.5, got {self.downscale}")
        if self.tile_size < 1:
            raise ValueError(f"tile_size must be >= 1, got {self.tile_size}")
        if self.min_y >= self.world_height:
            raise ValueError(f"min_y {self.min_y} must be below world_height {self.world_height}")
        if not self.min_y <= self.sea_level < self.world_height:
            raise ValueError(f"sea_level {self.sea_level} outside the world [{self.min_y}, {self.world_height})")
        if self.curve_version not in CURVE_VERSIONS:
            raise ValueError(f"curve_version must be one of {CURVE_VERSIONS}, got {self.curve_version}")
        if (self.curve_version >= 2) != (self.shelf_m_per_block > 0.0):
            raise ValueError(f"curve_version {self.curve_version} with shelf_m_per_block {self.shelf_m_per_block}: "
                             "v1 has no shelf band (0), v2 needs one (> 0)")
        if self.curve_version < 2 and self.shallow_m_per_block != 0.0:
            raise ValueError(f"curve_version 1 has no shallows band, got shallow_m_per_block {self.shallow_m_per_block}")

    @staticmethod
    def from_dict(d: dict) -> "WorldConfig":
        """Strict: every required field present, nothing extra (a typo or a stale game fails loudly).
        Fields with a default (`min_y`) may be absent: an older game sends no `min_y`."""
        names = {f.name for f in fields(WorldConfig)}
        required = {f.name for f in fields(WorldConfig) if f.default is MISSING and f.default_factory is MISSING}
        missing, extra = required - d.keys(), d.keys() - names
        if missing or extra:
            raise ValueError(f"world config mismatch: missing {sorted(missing)}, unknown {sorted(extra)}")
        types = {f.name: f.type for f in fields(WorldConfig)}
        conv = {"int": int, "float": float, "str": str}
        return WorldConfig(**{k: conv[types[k]](v) for k, v in d.items()})

    def to_dict(self) -> dict:
        return asdict(self)

    def fingerprint(self) -> str:
        d = self.to_dict()
        if d["min_y"] == 0:
            del d["min_y"]   # the default: same fingerprint (cache namespace) as before the field existed
        if not d["climate_layout"]:
            del d["climate_layout"]   # likewise: worlds without a layout keep their caches
        # An explicit "belts" stays IN the fingerprint (decided 2026-11-19, FX1 on review-java LOW 2): the game sends the
        # layout of every world since 2026-11-19 (TerrainScale.worldConfigJson), so "" now only comes from an older
        # game and "belts" from this one. Dropping "belts" too would give v1 belts worlds their pre-change namespace
        # back, but the model_id moved the same day (every cache rebuilt once already): it would only rebuild them a
        # second time. Tiles are the same either way (the service's own default layout is belts).
        if d["curve_version"] == 1:
            for k in CURVE_V2_KEYS:
                del d[k]              # v1 worlds keep their caches (the shelf knobs mean nothing without the band)
        return hashlib.sha1(json.dumps(d, sort_keys=True).encode()).hexdigest()[:12]

    def curve(self):
        from terrain_slm.world.height_curve import HeightCurve
        return HeightCurve.from_world(self)

    def sea_bed_floor_m(self) -> float:
        """Deepest elevation (metres) the tile stages convert to blocks; deeper ground is clamped to it. v1: -4 km
        (historical: the abyss and trenches of v1 worlds sit flat there, kept so saved worlds stay seamless); v2: the
        elevation of `min_y` (nothing is clamped above the bottom of the column)."""
        if self.curve_version < 2:
            return -4000.0
        import numpy as np
        e = float(self.curve().to_elevation(np.array([float(self.min_y)]))[0])
        return 2.0 * np.floor(min(e, -4000.0) / 2.0)   # on the even-metre grid BlockScale samples


#: Height-curve versions the service understands (`WorldConfig.curve_version`).
CURVE_VERSIONS = (1, 2)
#: WorldConfig keys added with curve v2 (an older game sends none of them; v1 worlds' fingerprints ignore them).
CURVE_V2_KEYS = ("curve_version", "shelf_m_per_block", "shelf_base_m", "shelf_blend_m", "shallow_m_per_block",
                 "shallow_base_m", "shallow_blend_m")
#: The game's v2 sea bands (`NativeCubeGenerator.TerrainScalePreset.heightCurve` in stonebreak-game sends these; offline
#: work and tests mirror them here), per scale. Land rates and knots are v1's except the shore blend (narrow, so the
#: shallows hold their rate up to the waterline). Round 2 (2026-11-19, sweep vs 16 real coasts): shallows 4 m/block to
#: ~-24 m, shelf 6 m/block to ~-300 m, deep ocean 32 at 1:4; 1:2 / 1:1 = half / a quarter of the shallows and shelf
#: rates (same exaggeration near shore), their deep rates (32 / 48) chosen so the -9.7 km trench stays well above the
#: -384 floor (1:4 y -275, 1:2 -312, 1:1 -277).
CURVE_V2 = {
    "1:4": dict(ocean=32.0, shore_blend=10.0, shallow=4.0, shallow_base=-24.0, shallow_blend=12.0,
                shelf=6.0, shelf_base=-300.0, shelf_blend=150.0),
    "1:2": dict(ocean=32.0, shore_blend=10.0, shallow=2.0, shallow_base=-24.0, shallow_blend=12.0,
                shelf=3.0, shelf_base=-300.0, shelf_blend=150.0),
    "1:1": dict(ocean=48.0, shore_blend=10.0, shallow=1.0, shallow_base=-24.0, shallow_blend=12.0,
                shelf=1.5, shelf_base=-300.0, shelf_blend=150.0),
}


def curve_v2_keys(scale: str) -> dict:
    """The WorldConfig keys the game sends for a v2 world at `scale` ('1:4' | '1:2' | '1:1') on top of the v1 keys."""
    b = CURVE_V2[scale]
    return dict(curve_version=2, ocean_m_per_block=b["ocean"], shore_blend_m=b["shore_blend"],
                shallow_m_per_block=b["shallow"], shallow_base_m=b["shallow_base"], shallow_blend_m=b["shallow_blend"],
                shelf_m_per_block=b["shelf"], shelf_base_m=b["shelf_base"], shelf_blend_m=b["shelf_blend"])


#: The 1:1 preset's own land curve (15 m blocks, 2026-11-19; user decision 2026-11-18: CAP, not less exaggeration):
#: ((ocean v1, lowland, midland, highland) m/block, (lowland top, highland base, shore / midland / highland blend) m).
#: 1:4's exaggeration (quarter rates) holds to ~2 km; above, the rate climbs smoothly (highland blend +-1 km around
#: 2.6 km) to 32 m/block, so the generator's soft-capped summits (< 5150 m) end at y ~631. Its v2 sea: CURVE_V2["1:1"].
#: `NativeCubeGenerator.TerrainScalePreset.ONE_TO_ONE`.
ONE_TO_ONE_CURVE = ((12.0, 4.0, 6.85, 32.0), (600.0, 2600.0, 60.0, 150.0, 1000.0))


WorldConfig.GAME = WorldConfig(
    world_height=256, sea_level=64, horizontal_m=60.0, downscale=2, tile_size=256,
    ocean_m_per_block=48.0, lowland_m_per_block=16.0, midland_m_per_block=24.0, highland_m_per_block=38.0,
    lowland_top_m=600.0, highland_base_m=2000.0,
    shore_blend_m=60.0, midland_blend_m=150.0, highland_blend_m=300.0,
)
