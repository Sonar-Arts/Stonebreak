"""Carve-node calibration: world/carve_node.py's rule on REAL terrain, scored against data/carve_labels.py.

    python -m terrain_slm.eval.carve_calibration [--data DIR] [--region R ...] [--tune] [--json OUT]

Per region, sampled windows (CORE x CORE 60 m samples + the node's margin, cell-aligned): the region's 30 m DEM
block-averaged 2x2 to 60 m (as carve_labels does), real aridity (cells.npz P/PET) and real rivers (3x3-dilated
drainage accumulation >= planner.RIVER_MIN_UPSLOPE_CELLS, the hydro sidecar's training target -- what the game's
`drainage()["river"]` predicts), both nearest-upsampled 4x like tiles._carve. Train windows avoid the region's
held-out val tiles (meta.json `val_tiles`); val windows lie inside them.

Scored per 240 m label cell with land >= LAND_MIN:
  hit      P(node carves the cell | label shows strong incision: vd600 >= POS_VD600_M and vd150 >= POS_VD150_M)
  fp       P(node carves the cell | label is gentle: vd600 < NEG_VD600_M)
           "carves" = at least half of the cell's samples have density > 0; cells touched by sea_stacks are left out
           of both (the coast rule's density is cliff height, not incision, and the labels mask the sea)
  flat     share of all land samples carved, in the flat regions (FLAT_REGIONS; should be ~0)
  agree    share of carved land samples whose style is plausible for the region's landform (WRONG_STYLE rules)
  sig      share of carved land samples in the region's signature style (SIGNATURE), where it has one
  rho_d    Spearman(cell mean density, label vd600) over non-gentle cells (vd600 >= NEG_VD600_M)
  rho_z    Spearman(cell mean depth class, label vd2000) over land cells

Caveat built into the method: the node measures valley depth exactly as the labels do (same closing, same 60 m
grid), so detection is close to a threshold choice on the same quantity -- the labels' value is their per-region
distributions (what "incised" and "flat" look like on real ground), not independent truth. Style is scored against
the regions' documented landforms (glo30.REGIONS notes), which is coarse.

The tuner (--tune) searches the rule constants on the TRAIN windows only (coordinate grid per rule group); the
report shows v1 (the first proposals, V1_CONSTANTS) vs the module's current constants on train and val.
"""
from __future__ import annotations

import argparse
import itertools
import json
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

from terrain_slm.data import carve_labels as CL
from terrain_slm.models.detail2 import STYLE_REGIONS
from terrain_slm.models.planner import RIVER_MIN_UPSLOPE_CELLS
from terrain_slm.world import carve_node as CN

DATA = Path("/mnt/extrastorage/terrain-slm-data/v2-gedtm30")
CELL = CL.CELL                       # 60 m samples per 240 m label cell
CORE = 192                           # core samples per window side (48 cells, 11.5 km)
M = CN.MARGIN_SAMPLES
MC = M // CELL                       # margin in cells (the margin is cell-aligned)
LAND_MIN = 0.9
# Reference label classes (cell means). Chosen from the labels' per-region distributions: every flat region's p99
# vd600 is <= 30 m (sahara_erg 30, great_plains 20, baltic 20, outer_banks 12, wadden 12) and vd150 p99 <= 4.2 m,
# so "strong incision" starts just above what any flat region reaches, and "gentle" is below the flat regions' p99.
POS_VD600_M, POS_VD150_M = 35.0, 4.5     # strong incision: deep at 600 m AND already narrow at 150 m
NEG_VD600_M = 20.0                       # gentle
STYLE = {n: i for i, n in enumerate(CN.CARVE_STYLES)}

# Landform expectations (from glo30.REGIONS notes).
FLAT_REGIONS = ("great_plains", "sahara_erg", "wadden", "outer_banks", "baltic_skerries")
SIGNATURE = {
    "colorado_plateau": "slot_canyon",
    **{r: "gorge" for r in ("karakoram", "himalaya_nepal", "ethiopian_highlands", "caucasus", "andes_central",
                            "massif_central")},
}
HILL_REGIONS = ("appalachian_plateau", "ozarks", "mar_de_morros", "borneo", "carpathian_hills", "rhenish_massif",
                "tuscany_apennines", "scottish_highlands", "loess_plateau")   # rivers in hills: few gorges
TOWER_REGIONS = ("guilin_karst", "zhangjiajie", "halong")        # gated pass: towers expected
SINKHOLE_REGIONS = ("aegean", "dalmatia")                       # gated pass: Mediterranean karst, no towers
RIFT_REGIONS = ("east_africa", "aegean", "mid_atlantic_ridge")   # rifts / grabens plausible
SLOT_REGIONS = ("colorado_plateau", "canaries", "zagros", "andes_aconcagua", "andes_central", "rockies_colorado")
KARST_GATED = tuple(STYLE_REGIONS["drowned_archipelago"]) + ("guilin_karst", "zhangjiajie")
FJORD_GATED = tuple(STYLE_REGIONS["glacial_fjord"])

# The rule constants as first proposed (before this calibration); gorges then shared DENSITY_VD600_M[0] and had no
# 2 km depth test (GORGE_VD2000_M = 0 reproduces that exactly: vd2000 >= vd600 > 25 m wherever the v1 rule fired).
V1_CONSTANTS = {"DENSITY_VD600_M": (25.0, 160.0), "SLOT_VD150_M": 12.0, "ARID": 0.2, "GORGE_VD600_M": 25.0,
                "GORGE_VD2000_M": 0.0, "RIFT_VD2000_M": 300.0, "RIFT_COHERENCE": 0.6, "SEA_CLIFF_DENSITY_M": 20.0,
                "TOWER_HUMID": 0.75, "TOWER_RELIEF_M": 100.0}


def current_constants() -> dict:
    legacy = {"GORGE_VD600_M": CN.DENSITY_VD600_M[0], "GORGE_VD2000_M": 0.0}
    return {k: getattr(CN, k) if hasattr(CN, k) else legacy[k] for k in V1_CONSTANTS}


# ----------------------------------------------------------------------------------------------- windows
@dataclass
class Window:
    region: str
    split: str                  # "train" | "val"
    ci: int                     # core top-left, in cells
    cj: int
    fields: dict                # core (CORE, CORE) tensors at 60 m
    labels: dict                # core (CORE/4, CORE/4) numpy label cells


def _val_boxes(meta: dict) -> list[tuple[int, int, int, int]]:
    th, tw = meta["tile_px"][0] // 8, meta["tile_px"][1] // 8
    lat1, lon0 = meta["lat"][1], meta["lon"][0]
    return [((lat1 - 1 - la) * th, (lo - lon0) * tw, th, tw) for la, lo in meta["val_tiles"]]


def _pick_windows(meta: dict, land: np.ndarray, n_train: int, n_val: int, rng) -> list[tuple[str, int, int]]:
    hc, wc = land.shape
    k = CORE // CELL
    boxes = _val_boxes(meta)
    lo_i, hi_i, lo_j, hi_j = MC, hc - MC - k, MC, wc - MC - k
    if hi_i <= lo_i or hi_j <= lo_j:
        return []
    cs = np.cumsum(np.cumsum(np.pad(land, ((1, 0), (1, 0))), 0), 1)
    landfrac = lambda i, j: (cs[i + k, j + k] - cs[i, j + k] - cs[i + k, j] + cs[i, j]) / (k * k)
    overlaps = lambda i, j, b: i < b[0] + b[2] and i + k > b[0] and j < b[1] + b[3] and j + k > b[1]
    inside = lambda i, j, b: i >= b[0] and i + k <= b[0] + b[2] and j >= b[1] and j + k <= b[1] + b[3]

    def choose(cands, n):
        cands = [c for c in cands]
        if not cands:
            return []
        lf = np.array([landfrac(i, j) for i, j in cands])
        good = [c for c, f in zip(cands, lf) if f >= 0.3]
        pool = good if len(good) >= n else [cands[t] for t in np.argsort(-lf)[: max(n, len(good))]]
        idx = rng.choice(len(pool), size=min(n, len(pool)), replace=False)
        return [pool[t] for t in idx]

    tr = [(int(i), int(j)) for i, j in zip(rng.integers(lo_i, hi_i, 4000), rng.integers(lo_j, hi_j, 4000))]
    tr = [c for c in tr if not any(overlaps(*c, b) for b in boxes)]
    out = [("train", i, j) for i, j in choose(tr, n_train)]
    per = max(1, -(-n_val // max(1, len(boxes))))
    for b in boxes:
        va = [(int(i), int(j)) for i, j in zip(rng.integers(b[0], b[0] + b[2] - k + 1, 1000),
                                               rng.integers(b[1], b[1] + b[3] - k + 1, 1000))]
        va = [c for c in va if lo_i <= c[0] <= hi_i and lo_j <= c[1] <= hi_j and inside(*c, b)]
        out += [("val", i, j) for i, j in choose(va, per)]
    return out


def window_inputs(dem, cells: dict, ci: int, cj: int):
    """(h60, aridity, river) over the core at cell (ci, cj) plus the node's margin, like tiles._carve."""
    k = CORE // CELL + 2 * MC
    r0, c0 = (ci - MC) * CELL * 2, (cj - MC) * CELL * 2          # 30 m pixels
    n = k * CELL * 2
    z30 = np.asarray(dem[r0 : r0 + n, c0 : c0 + n], np.float32)
    h60 = torch.from_numpy(z30.reshape(n // 2, 2, n // 2, 2).mean((1, 3)))
    up = lambda a: torch.from_numpy(np.ascontiguousarray(a[ci - MC : ci - MC + k, cj - MC : cj - MC + k])).float() \
        .repeat_interleave(CELL, 0).repeat_interleave(CELL, 1)
    return h60, up(cells["aridity"]), up(cells["river"])


def node_fields(h60: torch.Tensor, aridity: torch.Tensor, river: torch.Tensor, margin: int = M) -> dict:
    """carve_codes' intermediate fields (everything its rule constants act on), cropped to the core. Mirrors
    carve_node.carve_codes line for line; `rules` turns them into codes (parity-checked against carve_codes)."""
    pool = lambda x, r: F.max_pool2d(x, 2 * r + 1, stride=1, padding=r)
    z = h60[None, None].float()
    vd150, vd600, vd2000 = (CL.valley_depth(z, s)[0, 0] for s in (150.0, 600.0, 2000.0))
    c2, s2 = CL.strike(vd600[None, None])
    coh = torch.sqrt(c2 ** 2 + s2 ** 2)
    angle = 0.5 * torch.atan2(s2, c2)
    strike = torch.round(((angle % math.pi) / math.pi) * 16).long() % 16
    r = CN.TOWER_RELIEF_SAMPLES
    relief = (pool(z, r) + pool(-z, r))[0, 0]
    sea = (h60 < 0.0).float()[None, None]
    shore = (pool(sea, 1) > 0.5) & (sea < 0.5)
    cliff = pool(z.clamp_min(0.0), CN.SEA_CLIFF_SAMPLES)
    steep = shore & (cliff >= CN.SEA_CLIFF_M)
    reach = CN.SEA_STACK_REACH_SAMPLES
    near = (pool(steep.float(), reach) > 0.5)[0, 0] & (h60 > 0.0)
    exposure = pool(torch.where(steep, cliff, torch.zeros_like(cliff)), reach)[0, 0]
    m = margin
    crop = lambda x: x[m:-m, m:-m]
    return {"h": crop(h60), "vd150": crop(vd150), "vd600": crop(vd600), "vd2000": crop(vd2000), "coh": crop(coh),
            "strike": crop(strike), "relief": crop(relief), "near": crop(near), "exposure": crop(exposure),
            "aridity": crop(aridity), "river": crop(river)}


def rules(f: dict, c: dict, gates: dict | None = None) -> dict:
    """The node's rule over precomputed fields with constants `c` -> unpacked (density, style, strike, depth).
    Same order and arithmetic as carve_codes (gorge's depth threshold is c["GORGE_VD600_M"])."""
    lo, hi = c["DENSITY_VD600_M"]
    v6 = f["vd600"]
    density = ((v6 - lo) / (hi - lo) * 15).clamp(0, 15)
    style = torch.zeros_like(v6, dtype=torch.long)
    style = torch.where((f["vd150"] > c["SLOT_VD150_M"]) & (f["aridity"] < c["ARID"]), torch.full_like(style, 1), style)
    gorge = (f["river"] > 0.5) & (v6 > c["GORGE_VD600_M"]) & (f["vd2000"] > c["GORGE_VD2000_M"])
    style = torch.where(gorge, torch.full_like(style, 2), style)
    style = torch.where((f["vd2000"] > c["RIFT_VD2000_M"]) & (f["coh"] > c["RIFT_COHERENCE"]),
                        torch.full_like(style, 3), style)
    if gates is not None:
        if "drowned_archipelago" in gates:
            karst = gates["drowned_archipelago"] > 0.5
            style = torch.where(karst, torch.full_like(style, 4), style)
            towers = karst & (f["aridity"] >= c["TOWER_HUMID"]) & (f["relief"] >= c["TOWER_RELIEF_M"])
            style = torch.where(towers, torch.full_like(style, 7), style)
        if "glacial_fjord" in gates:
            density = density * (1 - 0.8 * gates["glacial_fjord"])
    style = torch.where(f["near"], torch.full_like(style, 6), style)
    density = torch.where(f["near"], (f["exposure"] / c["SEA_CLIFF_DENSITY_M"]).clamp(1, 15), density)
    density = torch.where(f["h"] <= 0.0, torch.zeros_like(density), density)
    depth = f["vd2000"].clamp(max=1000.0) / 32.0
    return {"density": density.round().clamp(0, 15).long(), "style": style.clamp(0, 7),
            "strike": f["strike"].clamp(0, 15), "depth": depth.round().clamp(0, 31).long()}


def region_gates(region: str, shape) -> dict | None:
    g = {}
    if region in KARST_GATED:
        g["drowned_archipelago"] = torch.ones(shape)
    if region in FJORD_GATED:
        g["glacial_fjord"] = torch.ones(shape)
    return g or None


def load_region(rdir: Path, n_train: int, n_val: int, seed: int = 0) -> list[Window]:
    meta = json.loads((rdir / "meta.json").read_text())
    lab = np.load(rdir / "cells_carve.npz")
    c = np.load(rdir / "cells.npz")
    acc = torch.from_numpy(c["acc"]).float()[None, None]
    cells = {"aridity": c["aridity"],
             "river": (F.max_pool2d(acc, 3, stride=1, padding=1)[0, 0] >= RIVER_MIN_UPSLOPE_CELLS).float().numpy()}
    dem = np.load(rdir / "dem.npy", mmap_mode="r")
    land = lab["land"]
    rng = np.random.default_rng(seed + sum(map(ord, rdir.name)))
    k = CORE // CELL
    out = []
    for split, ci, cj in _pick_windows(meta, land, n_train, n_val, rng):
        h60, ar, rv = window_inputs(dem, cells, ci, cj)
        f = node_fields(h60, ar, rv)
        f["_inputs"] = (h60, ar, rv)
        labels = {n: np.asarray(lab[n][ci : ci + k, cj : cj + k]) for n in ("vd150", "vd600", "vd2000", "land")}
        out.append(Window(rdir.name, split, ci, cj, f, labels))
    return out


def parity_check(windows: list[Window], n: int = 4) -> None:
    """The fast `rules` must reproduce carve_codes exactly with the module's current constants."""
    c = current_constants()
    for w in windows[:n]:
        h60, ar, rv = w.fields["_inputs"]
        g = region_gates(w.region, h60.shape)
        ref = CN.unpack(CN.carve_codes(h60, ar, rv, g))
        gc = None if g is None else {k: v[M:-M, M:-M] for k, v in g.items()}
        got = rules(w.fields, c, gc)
        for key in ("density", "style", "strike", "depth"):
            bad = int((ref[key].long() != got[key]).sum())
            assert bad == 0, f"rules() drifted from carve_codes ({w.region} {key}: {bad} samples)"


# ----------------------------------------------------------------------------------------------- scoring
def _cells(x: torch.Tensor) -> np.ndarray:
    return F.avg_pool2d(x.float()[None, None], CELL)[0, 0].numpy()


def _spearman(a: np.ndarray, b: np.ndarray) -> float:
    if a.size < 10 or np.std(a) == 0 or np.std(b) == 0:
        return float("nan")
    ra, rb = np.argsort(np.argsort(a)), np.argsort(np.argsort(b))
    return float(np.corrcoef(ra, rb)[0, 1])


def wrong_style(region: str, gated: bool) -> np.ndarray:
    """Styles (bool per style id) implausible for the region's landform."""
    w = np.zeros(8, bool)
    if region not in RIFT_REGIONS:
        w[STYLE["rift"]] = True
    if region not in SLOT_REGIONS:
        w[STYLE["slot_canyon"]] = True
    if not gated or region not in KARST_GATED:
        w[STYLE["karst"]] = w[STYLE["towers"]] = True
    if region in FLAT_REGIONS:
        w[:] = True
        w[STYLE["sea_stacks"]] = False
    return w


def score(windows: list[Window], c: dict, gated: bool = False) -> dict:
    """Per-region metrics for the given constants. Returns {region: {...}}."""
    acc: dict[str, dict] = {}
    for w in windows:
        g = region_gates(w.region, w.fields["h"].shape) if gated else None
        u = rules(w.fields, c, g)
        carved = (u["density"] > 0)
        landm = w.fields["h"] > 0.0
        cl = w.labels
        ok = cl["land"] >= LAND_MIN
        cc = _cells(carved.float()) >= 0.5
        ok = ok & (_cells((u["style"] == STYLE["sea_stacks"]).float()) == 0)
        pos = ok & (cl["vd600"] >= POS_VD600_M) & (cl["vd150"] >= POS_VD150_M)
        neg = ok & (cl["vd600"] < NEG_VD600_M)
        a = acc.setdefault(w.region, {"pos": 0, "hit": 0, "neg": 0, "fp": 0, "land": 0, "carved": 0,
                                      "styles": np.zeros(8, np.int64), "dens": [], "v6": [], "dep": [], "v20": [],
                                      "sat": 0, "stacks": 0})
        a["pos"] += int(pos.sum()); a["hit"] += int((pos & cc).sum())
        a["neg"] += int(neg.sum()); a["fp"] += int((neg & cc).sum())
        a["land"] += int(landm.sum())
        cm = carved & landm
        a["carved"] += int(cm.sum())
        a["sat"] += int((cm & (u["density"] == 15) & (u["style"] != STYLE["sea_stacks"])).sum())
        a["stacks"] += int((landm & (u["style"] == STYLE["sea_stacks"])).sum())
        a["styles"] += np.bincount(u["style"][cm].numpy(), minlength=8)
        nz = ok & (cl["vd600"] >= NEG_VD600_M)
        a["dens"].append(_cells(u["density"])[nz]); a["v6"].append(cl["vd600"][nz])
        a["dep"].append(_cells(u["depth"])[ok]); a["v20"].append(cl["vd2000"][ok])
    out = {}
    for r, a in acc.items():
        st = a["styles"]
        n = max(1, int(st.sum()))
        wr = wrong_style(r, gated)
        res = {"pos": a["pos"], "neg": a["neg"],
               "hit": a["hit"] / a["pos"] if a["pos"] else float("nan"),
               "fp": a["fp"] / a["neg"] if a["neg"] else float("nan"),
               "carved": a["carved"] / max(1, a["land"]),
               "agree": 1.0 - float(st[wr].sum()) / n if st.sum() else float("nan"),
               "styles": {CN.CARVE_STYLES[i]: float(st[i]) / n for i in range(8) if st[i]},
               "sat": a["sat"] / n, "stacks": a["stacks"] / max(1, a["land"]),
               "rho_d": _spearman(np.concatenate(a["dens"]), np.concatenate(a["v6"])),
               "rho_z": _spearman(np.concatenate(a["dep"]), np.concatenate(a["v20"]))}
        sig = SIGNATURE.get(r)
        if sig:
            res["sig"] = float(st[STYLE[sig]]) / n if st.sum() else 0.0
        if gated and r in TOWER_REGIONS + SINKHOLE_REGIONS:   # towers where expected / sinkhole karst where not
            want = STYLE["towers"] if r in TOWER_REGIONS else STYLE["karst"]
            karstic = int(st[STYLE["towers"]] + st[STYLE["karst"]])
            res["sig"] = float(st[want]) / karstic if karstic else 0.0
        out[r] = res
    return out


def objective(per: dict) -> float:
    """One number to maximise over the train windows: detection (hit - fp, regions with enough cells), flat ground
    left alone, signature styles where documented, few gorges on hill rivers, implausible styles avoided.
    Region-weighted."""
    det = [p["hit"] - p["fp"] for r, p in per.items() if p["pos"] >= 50 and p["neg"] >= 50]
    flat = [p["carved"] for r, p in per.items() if r in FLAT_REGIONS]
    sig = [p["sig"] for r, p in per.items() if "sig" in p]
    agree = [p["agree"] for r, p in per.items() if not math.isnan(p["agree"]) and r not in FLAT_REGIONS]
    hills = [p["styles"].get("gorge", 0.0) for r, p in per.items() if r in HILL_REGIONS]
    return (float(np.mean(det)) - 4.0 * float(np.mean(flat) if flat else 0.0)
            + 0.5 * float(np.mean(sig) if sig else 0.0) - 0.5 * float(np.mean(hills) if hills else 0.0)
            + 1.0 * float(np.mean(agree) if agree else 0.0))


def tune(train: list[Window], start: dict, passes: int = 2) -> dict:
    """Coordinate grid search per rule group on the train windows (ungated pass for density/slot/gorge/rift, gated
    pass for towers). Groups are nearly independent (style overrides are ordered, density is separate)."""
    best = dict(start)
    everything = {}
    grids = [
        ("DENSITY_VD600_M", [(lo, hi) for lo in (20.0, 25.0, 30.0, 35.0, 40.0, 45.0, 50.0)
                             for hi in (120.0, 160.0, 200.0, 250.0)]),
        ("SLOT", [(v, a) for v in (3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 15.0) for a in (0.15, 0.2, 0.25, 0.3, 0.35, 0.4)]),
        ("GORGE", [(v6, v20) for v6 in (25.0, 40.0, 60.0) for v20 in (0.0, 100.0, 150.0, 200.0, 250.0, 300.0)]),
        ("RIFT", [(v, k) for v in (300.0, 400.0, 500.0, 600.0, 800.0) for k in (0.6, 0.7, 0.8, 0.9)]),
    ]
    for name, grid in grids * passes:
        res = []
        for val in grid:
            c = dict(best)
            if name == "SLOT":
                c["SLOT_VD150_M"], c["ARID"] = val
            elif name == "RIFT":
                c["RIFT_VD2000_M"], c["RIFT_COHERENCE"] = val
            elif name == "GORGE":
                c["GORGE_VD600_M"], c["GORGE_VD2000_M"] = val
            else:
                c[name] = val
            res.append((objective(score(train, c)), val, c))
        res.sort(key=lambda t: -t[0])
        everything[name] = [(round(o, 4), v) for o, v, _ in res]
        print(f"  {name}: best {res[0][1]} (obj {res[0][0]:.4f}); top-3 {[ (round(o, 4), v) for o, v, _ in res[:3]]}",
              flush=True)
        best = res[0][2]
    # Towers: gated pass, the tower regions (towers) vs Mediterranean karst (sinkholes). Gated whole-region like the
    # style experts' training gates; seto_inland / visayas are left out (granite islands / mixed cone karst).
    tw = [w for w in train if w.region in TOWER_REGIONS + SINKHOLE_REGIONS]
    if tw:
        res = []
        for hum, rel in itertools.product((0.6, 0.75, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.6),
                                         (20.0, 40.0, 60.0, 80.0, 100.0, 150.0)):
            c = dict(best, TOWER_HUMID=hum, TOWER_RELIEF_M=rel)
            per = score(tw, c, gated=True)
            res.append((float(np.mean([per[r]["sig"] for r in per])), (hum, rel), c))
        res.sort(key=lambda t: -t[0])
        print(f"  TOWER: best {res[0][1]} (mean karst-style agreement {res[0][0]:.3f}); top-3 "
              f"{[(round(o, 3), v) for o, v, _ in res[:3]]}", flush=True)
        best = res[0][2]
    return best


# ----------------------------------------------------------------------------------------------- report
def _fmt(x, pct=True):
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return "   -"
    return f"{100 * x:4.0f}" if pct else f"{x:4.2f}"


def table(before: dict, after: dict, title: str) -> str:
    lines = [f"### {title}", "", "| region | pos/neg cells | hit v1 -> now | fp v1 -> now | carved% v1 -> now "
             "| agree v1 -> now | sig v1 -> now | rho_d | rho_z | styles now (% of carved) |", "|" + "---|" * 10]
    for r in sorted(after):
        b, a = before[r], after[r]
        st = " ".join(f"{k}:{100 * v:.0f}" for k, v in sorted(a["styles"].items(), key=lambda t: -t[1]) if v >= 0.005)
        lines.append(f"| {r} | {a['pos']}/{a['neg']} | {_fmt(b['hit'])} -> {_fmt(a['hit'])} | {_fmt(b['fp'])} -> "
                     f"{_fmt(a['fp'])} | {_fmt(b['carved'])} -> {_fmt(a['carved'])} | {_fmt(b['agree'])} -> "
                     f"{_fmt(a['agree'])} | {_fmt(b.get('sig'))} -> {_fmt(a.get('sig'))} | {_fmt(a['rho_d'], False)} "
                     f"| {_fmt(a['rho_z'], False)} | {st} |")
    return "\n".join(lines)


def summary(per: dict) -> dict:
    det = [p for p in per.values() if p["pos"] >= 50 and p["neg"] >= 50]
    m = lambda xs: float(np.nanmean(xs)) if xs else float("nan")
    return {"objective": objective(per), "hit": m([p["hit"] for p in det]), "fp": m([p["fp"] for p in det]),
            "flat_carved": m([per[r]["carved"] for r in FLAT_REGIONS if r in per]),
            "sig": m([p["sig"] for p in per.values() if "sig" in p]),
            "agree": m([p["agree"] for r, p in per.items() if r not in FLAT_REGIONS]),
            "rift_share": m([p["styles"].get("rift", 0.0) for r, p in per.items() if r not in RIFT_REGIONS]),
            "saturated": m([p["sat"] for p in per.values()])}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=DATA)
    ap.add_argument("--region", action="append")
    ap.add_argument("--train-windows", type=int, default=10)
    ap.add_argument("--val-windows", type=int, default=5)
    ap.add_argument("--threads", type=int, default=12)
    ap.add_argument("--tune", action="store_true", help="search constants on the train windows and print them")
    ap.add_argument("--json", type=Path)
    ap.add_argument("--cache", type=Path, help="torch.save the windows' fields here / load them if present")
    args = ap.parse_args()
    torch.set_num_threads(args.threads)
    dirs = [args.data / r for r in args.region] if args.region else sorted(p.parent for p in args.data.glob("*/meta.json"))
    windows: list[Window] = []
    if args.cache and args.cache.exists():
        windows = [Window(**d) for d in torch.load(args.cache, weights_only=False)]
        dirs = []
    for d in dirs:
        ws = load_region(d, args.train_windows, args.val_windows)
        windows += ws
        print(f"{d.name}: {sum(w.split == 'train' for w in ws)} train / {sum(w.split == 'val' for w in ws)} val windows",
              flush=True)
    if args.cache and not args.cache.exists():
        torch.save([vars(w) for w in windows], args.cache)
    parity_check(windows)
    train = [w for w in windows if w.split == "train"]
    val = [w for w in windows if w.split == "val"]
    now = current_constants()
    if args.tune:
        print("tuning on train windows from the v1 constants ...", flush=True)
        tuned = tune(train, V1_CONSTANTS)
        print("tuned:", json.dumps(tuned), flush=True)
    report = {}
    for split, ws in (("train", train), ("val", val)):
        b, a = score(ws, V1_CONSTANTS), score(ws, now)
        print(table(b, a, f"{split} (ungated, as the game runs today)"), "\n", flush=True)
        report[split] = {"v1": summary(b), "now": summary(a), "regions_v1": b, "regions_now": a}
        print(f"summary {split}: v1 {json.dumps({k: round(v, 3) for k, v in summary(b).items()})}")
        print(f"summary {split}: now {json.dumps({k: round(v, 3) for k, v in summary(a).items()})}\n", flush=True)
        gw = [w for w in ws if w.region in KARST_GATED]
        if gw:
            gb, ga = score(gw, V1_CONSTANTS, gated=True), score(gw, now, gated=True)
            print(table(gb, ga, f"{split} (gated: drowned_archipelago on karst regions)"), "\n", flush=True)
            report[split]["gated_v1"], report[split]["gated_now"] = gb, ga
    if args.json:
        args.json.write_text(json.dumps({"constants_v1": V1_CONSTANTS, "constants_now": now, **report}, indent=1,
                                        default=lambda o: o.tolist() if hasattr(o, "tolist") else str(o)))


if __name__ == "__main__":
    main()
