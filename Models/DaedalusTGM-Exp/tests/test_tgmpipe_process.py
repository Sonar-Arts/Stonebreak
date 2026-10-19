"""The real service as the game runs it: a child process with the packaged model, spoken to over
its pipes. Skips without a model."""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

from terrain_slm.tgmpipe import protocol as W
from terrain_slm.world.world_config import WorldConfig

ROOT = Path(__file__).resolve().parents[1]
pytestmark = pytest.mark.skipif(not (ROOT / "checkpoints/v3/planner.pt").exists(), reason="no packaged model")


def test_service_speaks_only_frames_and_exits_with_the_game(tmp_path):
    proc = subprocess.Popen([sys.executable, "-m", "terrain_slm.tgmpipe", "--disk-cache-dir", str(tmp_path)],
                            cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=open(tmp_path / "service.log", "wb"))
    try:
        proc.stdin.write(W.hello(W.VERSION, {"world": WorldConfig.GAME.to_dict()}))
        # Far-zoom overview tile: the cheapest real tile (cells only).
        proc.stdin.write(W.tile(1, 1234, 0, 0, 8, 0))
        proc.stdin.flush()
        kind, body = W.read_frame(proc.stdout)
        assert kind == W.READY, body
        ready = json.loads(body)
        assert ready["name"] == "DaedalusTGM-Exp" and ready["protocol"] == W.VERSION
        kind, body = W.read_frame(proc.stdout)
        assert kind == W.TILE_DATA, body
        req_id, cached, i1, j1, planes = W.parse_tile_data(body)
        n = WorldConfig.GAME.tile_size
        assert (req_id, cached, i1, j1, planes.shape) == (1, False, 0, 0, (W.PLANES, n, n))
        h = planes[0]
        assert 0 <= h.min() and h.max() < WorldConfig.GAME.world_height and np.ptp(h) > 0

        proc.stdin.write(W.tile(2, 1234, 0, 0, 8, 0))   # same tile again: from disk
        proc.stdin.flush()
        kind, body = W.read_frame(proc.stdout)
        assert kind == W.TILE_DATA and W.parse_tile_data(body)[:2] == (2, True)
        assert np.array_equal(W.parse_tile_data(body)[4], planes)
    finally:
        proc.stdin.close()                             # the game going away...
    assert proc.wait(timeout=30) == 0                  # ...ends the service
    assert proc.stdout.read() == b"", "the service wrote something other than frames"


def test_a_bad_handshake_is_reported_not_hung(tmp_path):
    proc = subprocess.run([sys.executable, "-m", "terrain_slm.tgmpipe", "--disk-cache-dir", str(tmp_path)],
                          cwd=ROOT, input=W.hello(W.VERSION, {"world": {"sea_level": 64}}),
                          capture_output=True, timeout=300)
    kind, body = W.read_frame(__import__("io").BytesIO(proc.stdout))
    assert proc.returncode == 2 and kind == W.FATAL and b"world config mismatch" in body
