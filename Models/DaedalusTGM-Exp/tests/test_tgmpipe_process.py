"""The real service as the game runs it: a child process with the packaged model (the default release), spoken to
over its pipes. Skips without the release weights; the refusal tests (Git LFS pointers, retired / non-EXP-2 folders)
need no model."""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

from terrain_slm.tgmpipe import protocol as W
from terrain_slm.world.world_config import WorldConfig

from terrain_slm.model_files import DEFAULT_MODEL, lfs_pointers

ROOT = Path(__file__).resolve().parents[1]
needs_release = pytest.mark.skipif(not (ROOT / DEFAULT_MODEL / "planner.pt").exists() or bool(lfs_pointers(ROOT / DEFAULT_MODEL)),
                                   reason="no release weights (git lfs pull)")
POINTER = (b"version https://git-lfs.github.com/spec/v1\n"
           b"oid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393\nsize 19341689\n")


def _fatal_at_start(tmp_path, model) -> bytes:
    proc = subprocess.run([sys.executable, "-m", "terrain_slm.tgmpipe", str(model), "--device", "cpu",
                           "--disk-cache-dir", str(tmp_path / "cache")],
                          cwd=ROOT, input=b"", capture_output=True, timeout=300)
    kind, body = W.read_frame(__import__("io").BytesIO(proc.stdout))
    assert proc.returncode == 2 and kind == W.FATAL, (proc.returncode, kind, body)
    return body


def test_git_lfs_pointer_files_are_refused_with_the_fix(tmp_path):
    model = tmp_path / "exp2-rX"
    model.mkdir()
    for name in ("planner.pt", "climate.pt", "detail.pt"):
        (model / name).write_bytes(POINTER)
    body = _fatal_at_start(tmp_path, model)
    assert b"Git LFS pointer" in body and b"git lfs pull" in body and b"planner.pt" in body, body


def test_retired_and_non_exp2_models_are_refused(tmp_path):
    body = _fatal_at_start(tmp_path, "checkpoints/v4")
    assert b"retired" in body, body
    exp1 = tmp_path / "exp1"
    exp1.mkdir()
    (exp1 / "planner.pt").write_bytes(b"not a pointer, not EXP-2")
    body = _fatal_at_start(tmp_path, exp1)
    assert b"not a DaedalusTGM-Exp-2 model" in body, body
    body = _fatal_at_start(tmp_path, tmp_path / "missing")
    assert b"not found" in body and b"git lfs pull" in body, body


@needs_release
def test_service_speaks_only_frames_and_exits_with_the_game(tmp_path):
    proc = subprocess.Popen([sys.executable, "-m", "terrain_slm.tgmpipe", "--disk-cache-dir", str(tmp_path)],
                            cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=open(tmp_path / "service.log", "wb"))
    try:
        proc.stdin.write(W.hello(W.VERSION, {"worlds": [WorldConfig.GAME.to_dict()]}))
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


@needs_release
def test_a_bad_handshake_is_reported_not_hung(tmp_path):
    proc = subprocess.run([sys.executable, "-m", "terrain_slm.tgmpipe", "--disk-cache-dir", str(tmp_path)],
                          cwd=ROOT, input=W.hello(W.VERSION, {"worlds": [{"sea_level": 64}]}),
                          capture_output=True, timeout=300)
    kind, body = W.read_frame(__import__("io").BytesIO(proc.stdout))
    assert proc.returncode == 2 and kind == W.FATAL and b"world config mismatch" in body
