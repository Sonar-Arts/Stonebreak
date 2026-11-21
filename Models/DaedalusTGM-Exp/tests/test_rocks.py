"""terrain_slm.rocks: SBRB format, mesh/voxel tools, block resampling, model shapes (agent N, 2026-11-19)."""
import struct

import numpy as np
import pytest
import torch

from terrain_slm.rocks import mesh as M
from terrain_slm.rocks import sbrb
from terrain_slm.rocks import shapes as S
from terrain_slm.rocks import voxelize as V
from terrain_slm.rocks.model import COND_DIM, RockVAE, cond_vector


def _cube_mesh(lo=0.0, hi=1.0):
    v = np.array([[x, y, z] for x in (lo, hi) for y in (lo, hi) for z in (lo, hi)], float)
    f = np.array([[0, 1, 3], [0, 3, 2], [4, 6, 7], [4, 7, 5], [0, 4, 5], [0, 5, 1],
                  [2, 3, 7], [2, 7, 6], [0, 2, 6], [0, 6, 4], [1, 5, 7], [1, 7, 3]])
    return M.Mesh("cube", v, f)


def _blob(dx=7, dy=5, dz=6, seed=0):
    x, y, z = np.meshgrid(*(np.linspace(-1, 1, n) for n in (dx, dy, dz)), indexing="ij")
    occ = x * x + (y + 0.3) ** 2 + z * z < 1.1
    occ[:, 0, :] |= occ[:, 1, :]
    return occ


def test_winding_inside_outside():
    m = _cube_mesh()
    w = V.winding_numbers(m.v, m.f, np.array([[0.5, 0.5, 0.5], [2.0, 0.5, 0.5], [0.1, 0.9, 0.2]]))
    assert w[0] == pytest.approx(1.0, abs=1e-4) and abs(w[1]) < 1e-4 and w[2] == pytest.approx(1.0, abs=1e-3)


def test_winding_open_base_still_inside():
    m = _cube_mesh()
    keep = ~np.all(m.v[m.f][:, :, 1] == 0.0, axis=1)  # remove the bottom face (scan never saw the base)
    w = V.winding_numbers(m.v, m.f[keep], np.array([[0.5, 0.6, 0.5]]))
    assert w[0] > 0.5


def test_components_and_weld():
    a, b = _cube_mesh(0, 1), _cube_mesh(3, 4)
    m = M.Mesh("two", np.concatenate([a.v, b.v]), np.concatenate([a.f, b.f + 8]))
    comps = M.components(M.weld(m, 1e-6))
    assert len(comps) == 2 and all(len(c.f) == 12 for c in comps)
    assert M.boundary_edge_fraction(comps[0]) == 0.0


def test_sbrb_roundtrip_and_layout():
    occ = _blob()
    hint = S.material_hints(occ, np.random.default_rng(0))
    shapes = [sbrb.Shape(2, occ, S.sink_rows(occ), S.shape_flags(occ), hint), sbrb.Shape(3, _blob(9, 6, 8), 1)]
    data = sbrb.write_bank(shapes, 7)
    magic, ver, hs, count, flags, bank_id, _, _ = struct.unpack_from("<4sHHIIIIQ", data)
    assert (magic, ver, hs, count, flags, bank_id) == (b"SBRB", 1, 32, 2, 1, 7)
    bid, back = sbrb.read_bank(data)
    assert bid == 7 and len(back) == 2
    assert np.array_equal(back[0].occ, occ) and np.array_equal(back[0].hint, np.where(occ, hint, 0))
    assert back[1].hint is None and back[1].sink == 1
    # bit order: voxel i = (y*dz + z)*dx + x, LSB first
    _, _, _, _, _, _, _, off, _ = struct.unpack_from("<BBBBBBHII", data, 32)
    dx, dy, dz = occ.shape
    i = (2 * dz + 3) * dx + 4
    assert ((data[off + (i >> 3)] >> (i & 7)) & 1) == int(occ[4, 2, 3])


def test_sbrb_rejects_bad_banks():
    occ = _blob()
    data = bytearray(sbrb.write_bank([sbrb.Shape(2, occ)], 1))
    data[-1] ^= 0xFF
    with pytest.raises(ValueError, match="hash"):
        sbrb.read_bank(bytes(data))
    two = np.zeros((4, 3, 4), bool)
    two[0, 0, 0] = two[3, 0, 3] = True
    with pytest.raises(ValueError, match="6-connected"):
        sbrb.write_bank([sbrb.Shape(2, two)], 1)
    floating = np.zeros((3, 3, 3), bool)
    floating[1, 1:, 1] = True
    with pytest.raises(ValueError, match="bottom"):
        sbrb.write_bank([sbrb.Shape(2, floating)], 1)
    with pytest.raises(ValueError, match="dims"):
        sbrb.write_bank([sbrb.Shape(2, np.ones((16, 2, 2), bool))], 1)


def test_fnv_matches_reference():
    assert sbrb.fnv1a64(b"") == 0xCBF29CE484222325
    assert sbrb.fnv1a64(b"a") == 0xAF63DC4C8601EC8C


def test_resample_blocks_valid_for_every_class():
    g = np.indices((32, 32, 32)).astype(float) - 15.5
    sdf = np.sqrt((g[0] / 12) ** 2 + (g[1] / 8) ** 2 + (g[2] / 10) ** 2) * 9 - 9  # an ellipsoid
    for cls, (lo, hi) in S.CLASS_DXZ.items():
        for dxz in (lo, hi):
            occ = S.resample_blocks(sdf, dxz, flat_base=True)
            assert max(occ.shape[0], occ.shape[2]) == dxz
            sh = sbrb.Shape(cls, occ, S.sink_rows(occ), S.shape_flags(occ))
            assert sbrb.validate_shape(sh) is None


def test_plausibility_flags_floating_voxels():
    sdf = np.ones((32, 32, 32))
    sdf[5:15, 2:10, 5:15] = -1
    assert S.plausibility(sdf)["main_frac"] == 1.0
    sdf[25, 25, 25] = -1
    p = S.plausibility(sdf)
    assert p["n_components"] == 2 and p["main_frac"] < 1.0


def test_model_shapes():
    m = RockVAE(latent=16, width=8)
    x = torch.zeros(2, 32, 32, 32)
    c = cond_vector([0, 3], [1, 2], [0, 1])
    assert c.shape == (2, COND_DIM)
    y, mu, lv = m(x, c)
    assert y.shape == (2, 32, 32, 32) and mu.shape == (2, 16) and float(y.detach().abs().max()) <= 1.0


_REPO = __import__("pathlib").Path(__file__).resolve().parents[3]
_BANK = _REPO / "stonebreak-game/src/main/resources/worldgen/rocks/rock_bank_v1.sbrb"
_LIB = _REPO / "stonebreak-game/native/build/release/libstonebreak_worldgen.so"


@pytest.mark.skipif(not _BANK.exists(), reason="no shipped rock bank")
def test_shipped_bank_valid_python_and_native():
    data = _BANK.read_bytes()
    bank_id, shapes = sbrb.read_bank(data)
    assert bank_id == 1
    for cls in (2, 3, 4):  # P's rule: >= 16 shapes for every class the bank provides
        assert sum(s.cls == cls for s in shapes) >= 16
    if _LIB.exists():
        import ctypes
        lib = ctypes.CDLL(str(_LIB))
        if hasattr(lib, "sbw_register_rock_bank"):
            f = lib.sbw_register_rock_bank
            f.restype, f.argtypes = ctypes.c_int32, [ctypes.c_uint32, ctypes.c_char_p, ctypes.c_int64]
            assert f(bank_id, data, len(data)) == 0
            assert f(bank_id + 100, data, len(data)) == -1  # header id must match the registered id
