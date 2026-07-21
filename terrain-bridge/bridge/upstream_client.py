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

    def fetch_tile(self, i1: int, j1: int, i2: int, j2: int) -> tuple[np.ndarray, np.ndarray]:
        """Fetch one canonical-shape tile. Returns (elev_m int16 HxW, biome_id int16 HxW)."""
        params = {
            "i1": i1,
            "j1": j1,
            "i2": i2,
            "j2": j2,
            "scale": self._cfg.scale,
            "noise": self._cfg.noise_scale,
        }
        try:
            r = self._session.get(
                f"{self._cfg.upstream_url}/terrain", params=params, timeout=self._cfg.upstream_timeout_s
            )
        except requests.RequestException as e:
            raise UpstreamError(f"could not reach upstream at {self._cfg.upstream_url}: {e}") from e
        if r.status_code != 200:
            raise UpstreamError(f"upstream /terrain returned {r.status_code}: {r.text[:200]}")

        h = int(r.headers["X-Height"])
        w = int(r.headers["X-Width"])
        body = r.content
        expected = h * w * 2 * 2  # elevation int16 + biome int16
        if len(body) != expected:
            raise UpstreamError(f"unexpected payload size for {h}x{w}: got {len(body)}, expected {expected}")

        elev = np.frombuffer(body[: h * w * 2], dtype="<i2").reshape(h, w)
        biome = np.frombuffer(body[h * w * 2 :], dtype="<i2").reshape(h, w)
        return elev.copy(), biome.copy()
