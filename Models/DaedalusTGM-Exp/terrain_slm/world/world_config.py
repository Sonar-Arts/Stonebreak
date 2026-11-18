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
    #: Model pixels (30 m) averaged into one block along each axis.
    downscale: int
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

    def __post_init__(self) -> None:
        if self.downscale < 1:
            raise ValueError(f"downscale must be >= 1, got {self.downscale}")
        if self.tile_size < 1:
            raise ValueError(f"tile_size must be >= 1, got {self.tile_size}")
        if self.min_y >= self.world_height:
            raise ValueError(f"min_y {self.min_y} must be below world_height {self.world_height}")
        if not self.min_y <= self.sea_level < self.world_height:
            raise ValueError(f"sea_level {self.sea_level} outside the world [{self.min_y}, {self.world_height})")

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
        return WorldConfig(**{k: (int(v) if types[k] == "int" else float(v)) for k, v in d.items()})

    def to_dict(self) -> dict:
        return asdict(self)

    def fingerprint(self) -> str:
        d = self.to_dict()
        if d["min_y"] == 0:
            del d["min_y"]   # the default: same fingerprint (cache namespace) as before the field existed
        return hashlib.sha1(json.dumps(d, sort_keys=True).encode()).hexdigest()[:12]

    def curve(self):
        from terrain_slm.world.height_curve import HeightCurve
        return HeightCurve.from_world(self)


WorldConfig.GAME = WorldConfig(
    world_height=256, sea_level=64, horizontal_m=60.0, downscale=2, tile_size=256,
    ocean_m_per_block=48.0, lowland_m_per_block=16.0, midland_m_per_block=24.0, highland_m_per_block=38.0,
    lowland_top_m=600.0, highland_base_m=2000.0,
    shore_blend_m=60.0, midland_blend_m=150.0, highland_blend_m=300.0,
)
