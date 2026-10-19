"""The game world's scale: block grid, tile grid and the metres -> blocks height curve.

The game owns these numbers (`TerrainScale.worldConfig()` in stonebreak-game) and sends them in
the service handshake, so the model never guesses them. `WorldConfig.GAME` mirrors the game's
values for offline work -- training, evaluation, tests -- that runs without the game.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass, fields


@dataclass(frozen=True)
class WorldConfig:
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

    def __post_init__(self) -> None:
        if self.downscale < 1:
            raise ValueError(f"downscale must be >= 1, got {self.downscale}")
        if self.tile_size < 1:
            raise ValueError(f"tile_size must be >= 1, got {self.tile_size}")
        if not 0 <= self.sea_level < self.world_height:
            raise ValueError(f"sea_level {self.sea_level} outside the world (height {self.world_height})")

    @staticmethod
    def from_dict(d: dict) -> "WorldConfig":
        """Strict: every field present, nothing extra (a typo or a stale game fails loudly)."""
        names = {f.name for f in fields(WorldConfig)}
        missing, extra = names - d.keys(), d.keys() - names
        if missing or extra:
            raise ValueError(f"world config mismatch: missing {sorted(missing)}, unknown {sorted(extra)}")
        types = {f.name: f.type for f in fields(WorldConfig)}
        return WorldConfig(**{k: (int(v) if types[k] == "int" else float(v)) for k, v in d.items()})

    def to_dict(self) -> dict:
        return asdict(self)

    def fingerprint(self) -> str:
        return hashlib.sha1(json.dumps(self.to_dict(), sort_keys=True).encode()).hexdigest()[:12]

    def curve(self):
        from terrain_slm.world.height_curve import HeightCurve
        return HeightCurve.from_world(self)


WorldConfig.GAME = WorldConfig(
    world_height=256, sea_level=64, horizontal_m=60.0, downscale=2, tile_size=256,
    ocean_m_per_block=48.0, lowland_m_per_block=16.0, midland_m_per_block=24.0, highland_m_per_block=38.0,
    lowland_top_m=600.0, highland_base_m=2000.0,
    shore_blend_m=60.0, midland_blend_m=150.0, highland_blend_m=300.0,
)
