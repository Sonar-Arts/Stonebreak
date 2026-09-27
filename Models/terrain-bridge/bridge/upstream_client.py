"""Thin client for the model server's `/terrain` contract (DaedalusTGM-Exp,
`terrain_slm.serve.upstream_api`).

  GET /terrain?i1&j1&i2&j2&scale&noise[&downscale]&water=1[&river3d=1]
  -> binary body of bare int16-LE planes: elevation (metres), biome id, water surface
     (metres, WATER_NONE where dry) and, with river3d, river tunnel floor / roof / flow
     octant (blocks, -1 where none). Headers X-Height / X-Width / X-Dtype.

Never sends a `seed`: the seed is pinned once, out of band, by starting the model
server with a matching `--seed` (see terrain-bridge/README.md).
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

    def fetch_tile_with_water(
        self, i1: int, j1: int, i2: int, j2: int, lod: int = 1
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray, tuple[np.ndarray, ...] | None]:
        """Fetch one canonical-shape tile: (elev_m, biome_id, water surface m, river planes).

        The river planes (tunnel floor / roof / flow octant, int16 BLOCKS, -1 where none) are
        None unless `river3d` is on."""
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
            # The body is bare concatenated planes with no header bytes, so a width
            # change would be sliced into garbage rather than rejected.
            raise UpstreamError(f"upstream sent X-Dtype {dtype!r}, expected 'int16-le'")
        h = int(r.headers["X-Height"])
        w = int(r.headers["X-Width"])
        body = r.content
        plane = h * w * 2
        # 3 (elev, biome, water surface) or 6 (+ river tunnel floor, roof and flow octant).
        if len(body) not in (plane * 3, plane * 6):
            raise UpstreamError(
                f"unexpected payload size for {h}x{w}: got {len(body)}, "
                f"expected {plane} x 3 or 6 planes"
            )
        return [
            np.frombuffer(body[k : k + plane], dtype="<i2").reshape(h, w).copy()
            for k in range(0, len(body), plane)
        ]
