"""Thin client for upstream's `minecraft_api.py` `/terrain` contract.

Contract (confirmed against source, see LibAlex collection
`stonebreak-terrain-generation` and plan.md section 2):
  GET /terrain?i1&j1&i2&j2&scale&noise&format
  -> binary body: elevation int16 LE (H*W*2 bytes) followed by
     biome id int16 LE (H*W*2 bytes), headers X-Height / X-Width / X-Dtype.

Deliberately never sends a `seed` query param on routine fetches: passing one
that differs from upstream's current seed clears its tile cache and rebuilds
the whole pipeline in place, which would corrupt every other tile in flight.
Seed is pinned once, out of band, by starting the upstream process itself
with a matching `--seed` (see terrain-bridge/README.md) — this client can't
verify that match, because minecraft_api.py (unlike the generic api.py) has
no GET /seed probe to check it against.
"""
from __future__ import annotations

import numpy as np
import requests

from .config import BridgeConfig


class UpstreamError(RuntimeError):
    pass


class UpstreamClient:
    def __init__(self, cfg: BridgeConfig):
        self._cfg = cfg
        self._session = requests.Session()

    def health(self) -> dict:
        r = self._session.get(f"{self._cfg.upstream_url}/health", timeout=self._cfg.upstream_timeout_s)
        r.raise_for_status()
        return r.json()

    def fetch_tile(self, i1: int, j1: int, i2: int, j2: int, lod: int = 1) -> tuple[np.ndarray, np.ndarray]:
        """Fetch one canonical-shape tile. Returns (elev_m int16 HxW, biome_id int16 HxW)."""
        elev, biome, _ = self._fetch_planes(self._tile_params(i1, j1, i2, j2, lod), water=False)
        return elev, biome

    def fetch_tile_with_water(
        self, i1: int, j1: int, i2: int, j2: int, lod: int = 1
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray, tuple[np.ndarray, ...] | None]:
        """Like `fetch_tile`, plus the model's river water-surface plane (int16 metres,
        WATER_NONE where dry) and, with `river3d`, its river tunnel floor / roof / flow-octant
        planes (int16 BLOCKS, -1 where none) -- or None. Only upstreams that speak `water=1`
        (DaedalusTGM-Exp) do."""
        params = self._tile_params(i1, j1, i2, j2, lod)
        params["water"] = 1
        if self._cfg.river3d:
            params["river3d"] = 1
        planes = self._fetch_raw(params, timeout=self._cfg.upstream_timeout_s)
        if len(planes) < 3:
            raise UpstreamError("upstream returned no water plane (does it speak water=1?)")
        river = tuple(planes[3:6]) if self._cfg.river3d and len(planes) >= 6 else None
        return planes[0], planes[1], planes[2], river

    def _tile_params(self, i1: int, j1: int, i2: int, j2: int, lod: int = 1) -> dict:
        params = {
            "i1": i1,
            "j1": j1,
            "i2": i2,
            "j2": j2,
            "scale": self._cfg.scale,
            "noise": self._cfg.noise_scale,
        }
        if self._cfg.downscale > 1:
            # A coarser level of detail just averages more model pixels per sample; the
            # coordinates are already in sample units.
            params["downscale"] = self._cfg.downscale * lod
        return params

    def _fetch_planes(self, params: dict, water: bool):
        planes = self._fetch_raw(params, timeout=self._cfg.upstream_timeout_s)
        if len(planes) < 2:
            raise UpstreamError("upstream returned no biome plane")
        if water and len(planes) < 3:
            raise UpstreamError("upstream returned no water plane (does it speak water=1?)")
        return planes[0], planes[1], (planes[2] if water else None)

    def fetch_native(
        self, i1: int, j1: int, i2: int, j2: int, timeout_s: float | None = None
    ) -> np.ndarray:
        """Fetch elevation at the model's native resolution. Returns int16 metres, HxW.

        Coordinates are in *native pixels*, not blocks: upstream's `/terrain` takes its
        bounding box in target-resolution units and derives native ones by `i1 // scale`,
        so at `scale=1` the two coincide. One native pixel is `native_resolution` metres
        (30 m for the current model) against `native_resolution / scale` for a block.

        Used for bulk regions rather than tiles, which is a different cost regime
        entirely -- `Dev Working/Rivers and lakes plan.md` section 13.3 measures
        contiguous native generation at ~480k px/s over HTTP against ~20k px/s through
        the per-tile path, because a region pays the per-request overheads once.
        `timeout_s` therefore defaults to a multiple of the tile timeout: a region strip
        legitimately takes tens of seconds where a tile taking that long is a fault.

        No `noise` parameter is sent, and none would do anything. Upstream adds its
        slope-scaled Perlin inside `_get_upsampled`, to restore detail lost to the
        bilinear upsample; `scale=1` routes to `_handle_1x`, which never calls it. So
        native output *is* the smooth field section 4.3 wants to route on, for free.

        `elev_only=1` asks a patched upstream to skip the climate/biome work this caller
        discards anyway (the padded second generation pass, climate derivation and the
        biome classifier -- roughly half the request cost, measured 2026-08-01). The
        elevation bytes are identical either way, and an unpatched upstream simply
        ignores the parameter and returns both planes, which `_fetch` already handles.
        """
        params = {"i1": i1, "j1": j1, "i2": i2, "j2": j2, "scale": 1, "elev_only": 1}
        elev, _ = self._fetch(params, timeout=timeout_s or self._cfg.upstream_timeout_s * 20.0)
        return elev

    def _fetch(self, params: dict, timeout: float) -> tuple[np.ndarray, np.ndarray | None]:
        planes = self._fetch_raw(params, timeout)
        return planes[0], (planes[1] if len(planes) > 1 else None)

    def _fetch_raw(self, params: dict, timeout: float) -> list[np.ndarray]:
        try:
            r = self._session.get(
                f"{self._cfg.upstream_url}/terrain", params=params, timeout=timeout
            )
        except requests.RequestException as e:
            raise UpstreamError(f"could not reach upstream at {self._cfg.upstream_url}: {e}") from e
        if r.status_code != 200:
            raise UpstreamError(f"upstream /terrain returned {r.status_code}: {r.text[:200]}")

        dtype = r.headers.get("X-Dtype", "int16-le")
        if dtype != "int16-le":
            # The body is two bare concatenated planes with no header bytes, so a
            # width change would be sliced into garbage rather than rejected.
            raise UpstreamError(f"upstream sent X-Dtype {dtype!r}, expected 'int16-le'")
        h = int(r.headers["X-Height"])
        w = int(r.headers["X-Width"])
        body = r.content
        plane = h * w * 2
        # 1 (elev_only), 2 (elev + biome), 3 (+ water surface) or 6 (+ river tunnel floor,
        # roof and flow octant) planes.
        if len(body) not in (plane, plane * 2, plane * 3, plane * 6):
            raise UpstreamError(
                f"unexpected payload size for {h}x{w}: got {len(body)}, "
                f"expected {plane} x 1, 2, 3 or 6 planes"
            )
        return [
            np.frombuffer(body[k : k + plane], dtype="<i2").reshape(h, w).copy()
            for k in range(0, len(body), plane)
        ]
