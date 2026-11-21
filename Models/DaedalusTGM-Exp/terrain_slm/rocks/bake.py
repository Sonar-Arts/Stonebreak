"""Bake an SBRB rock bank from a RockVAE checkpoint - deterministic from (checkpoint, seed, counts).

For every size class the bank provides (2 medium, 3 large, 4 erratic) it draws conditions (style, size bin,
grounded), decodes latents z ~ N(0, tau^2) ON THE CPU (fixed threads, deterministic algorithms), and keeps a shape
only if
- plausible at model resolution: one 6-connected solid (>= 99 % of the voxels in the main component), no cavity;
- novel: nearest-neighbour IoU to every training scan in 48 orientations < `max_nn` (default 0.80);
- not a repeat inside the bank: block-level IoU < 0.9 to every kept shape of the same dims;
- valid SBRB after resampling to block dims (one component, bottom row, dims caps).

    python -m terrain_slm.rocks.bake --ckpt checkpoints/rocks/rockvae_v1/last.pt \
        --out ../../stonebreak-game/src/main/resources/worldgen/rocks/rock_bank_v1.sbrb --bank-id 1
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import torch

from . import dataset as D
from . import evaluate as E
from . import sbrb
from . import shapes as S
from .train import sha256

# per class: style mix (rounded, angular, slab, tor), size-bin mix (stone, rock, boulder), grounded share
CLASS_COND = {
    2: ([0.40, 0.32, 0.18, 0.10], [0.25, 0.60, 0.15], 0.30),
    3: ([0.36, 0.30, 0.20, 0.14], [0.05, 0.55, 0.40], 0.30),
    4: ([0.30, 0.32, 0.22, 0.16], [0.00, 0.35, 0.65], 0.25),
}
DEFAULT_COUNTS = {2: 1024, 3: 1024, 4: 512}


def bake(ckpt: Path, seed: int, counts: dict, tau: float = 1.0, max_nn: float = 0.80, hints: bool = True,
         log=print) -> tuple[list[sbrb.Shape], dict]:
    torch.set_num_threads(4)
    torch.use_deterministic_algorithms(True)
    model, ck = E.load_model(ckpt, "cpu")
    refs, assets = E.load_refs()
    shapes: list[sbrb.Shape] = []
    stats = {}
    for cls, want in counts.items():
        styles_p, size_p, g_share = CLASS_COND[cls]
        rng = np.random.default_rng([seed, cls])
        gen = torch.Generator().manual_seed(seed * 1000 + cls)
        kept: list[sbrb.Shape] = []
        by_dims: dict = {}
        tried = rej_plaus = rej_nov = rej_dup = rej_fmt = 0
        nn_kept = []
        while len(kept) < want and tried < want * 8:
            n = 256
            style = rng.choice(4, n, p=styles_p)
            size_bin = rng.choice(3, n, p=size_p)
            grounded = (rng.random(n) < g_share).astype(int)
            dxz = rng.integers(S.CLASS_DXZ[cls][0], S.CLASS_DXZ[cls][1] + 1, n)
            flat_base = rng.random(n) < 0.35
            hint_seeds = rng.integers(0, 2 ** 63, n)
            sdf = E.sample(model, style, size_bin, grounded, tau, gen, "cpu")
            nov, _ = E.nn_iou(sdf < 0, refs, "cpu")
            for i in range(n):
                if len(kept) >= want:
                    break
                tried += 1
                p = S.plausibility(sdf[i])
                if p["solid"] < 200 or p["main_frac"] < 0.99 or p["cavity_frac"] > 0.0:
                    rej_plaus += 1
                    continue
                if nov[i] >= max_nn:
                    rej_nov += 1
                    continue
                occ = S.resample_blocks(sdf[i], int(dxz[i]), flat_base=bool(flat_base[i] or grounded[i]))
                if occ is None:
                    rej_fmt += 1
                    continue
                hmax = max(occ.shape[0], occ.shape[2])
                if not (S.CLASS_DXZ[cls][0] <= hmax <= S.CLASS_DXZ[cls][1]) or occ.shape[1] < 2:
                    rej_fmt += 1  # thin parts lost in the resampling took the shape out of its size class
                    continue
                if min(occ.shape[0], occ.shape[2]) < max(2, round(0.3 * hmax)):  # 1-block walls / thin fins
                    rej_fmt += 1
                    continue
                if occ.shape[1] > 1.6 * hmax:  # spires read as pillars, not rocks
                    rej_fmt += 1
                    continue
                key = occ.shape
                if any((occ & o).sum() / (occ | o).sum() >= 0.9 for o in by_dims.get(key, [])):
                    rej_dup += 1
                    continue
                srng = np.random.default_rng(int(hint_seeds[i]))
                sink = S.sink_rows(occ, srng, flat_base=bool(flat_base[i] or grounded[i]))
                hint = S.material_hints(occ, srng) if hints else None
                sh = sbrb.Shape(cls, occ, sink, S.shape_flags(occ), hint,
                                {"style": int(style[i]), "size_bin": int(size_bin[i]), "grounded": int(grounded[i]),
                                 "nn_iou": float(nov[i])})
                if sbrb.validate_shape(sh) is not None:
                    rej_fmt += 1
                    continue
                kept.append(sh)
                by_dims.setdefault(key, []).append(occ)
                nn_kept.append(float(nov[i]))
        stats[sbrb.CLASS_NAMES[cls]] = {
            "kept": len(kept), "tried": tried, "rejected_plausibility": rej_plaus, "rejected_novelty": rej_nov,
            "rejected_duplicate": rej_dup, "rejected_format": rej_fmt,
            "nn_iou_kept_mean": float(np.mean(nn_kept)) if nn_kept else None,
            "nn_iou_kept_max": float(np.max(nn_kept)) if nn_kept else None,
            "dims_mean": np.mean([s.dims for s in kept], 0).round(2).tolist() if kept else None,
            "dims_max": np.max([s.dims for s in kept], 0).tolist() if kept else None,
        }
        log(f"[bake] class {sbrb.CLASS_NAMES[cls]}: {json.dumps(stats[sbrb.CLASS_NAMES[cls]])}")
        shapes += kept
    return shapes, {"classes": stats, "ckpt_step": ck["step"], "ckpt_meta": ck["meta"]}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ckpt", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--bank-id", type=int, default=1)
    ap.add_argument("--seed", type=int, default=20261119)
    ap.add_argument("--tau", type=float, default=1.0)
    ap.add_argument("--max-nn", type=float, default=0.80)
    ap.add_argument("--counts", default="2:1024,3:1024,4:512")
    ap.add_argument("--no-hints", action="store_true")
    a = ap.parse_args(argv)
    counts = {int(k): int(v) for k, v in (kv.split(":") for kv in a.counts.split(","))}
    shapes, info = bake(a.ckpt, a.seed, counts, a.tau, a.max_nn, not a.no_hints)
    data = sbrb.write_bank(shapes, a.bank_id)
    sbrb.read_bank(data)  # round trip through the validating reader
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_bytes(data)
    src = json.loads((D.RAW_DIR / "sources.json").read_text())
    manifest = {
        "format": "SBRB v1 (stonebreak-game/native/src/rocks.hpp)", "bank_id": a.bank_id, "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(), "shape_count": len(shapes),
        "bake": {"seed": a.seed, "tau": a.tau, "max_nn_iou": a.max_nn, "counts": counts, "hints": not a.no_hints,
                 "command": "python -m terrain_slm.rocks.bake " + " ".join(sys.argv[1:] if argv is None else argv)},
        "model": {"name": "RockVAE (terrain_slm/rocks/model.py)", "checkpoint": str(a.ckpt),
                  "checkpoint_sha256": sha256(a.ckpt), "step": info["ckpt_step"],
                  "train_data_sha256": info["ckpt_meta"]["data_sha256"]},
        "classes": info["classes"],
        "data_sources": [{"source": s["name"], "licence": s["licence"], "licence_url": s["licence_url"],
                          "attribution_required": s["attribution_required"]} for s in src["sources"].values()],
        "scans_used": sorted({m["asset"] for m in json.loads(D.OUT_DEFAULT.with_suffix(".json").read_text())["shapes"]}),
    }
    a.out.with_suffix(".json").write_text(json.dumps(manifest, indent=1))
    print(f"[bake] wrote {a.out} ({len(data)} bytes, {len(shapes)} shapes)")


if __name__ == "__main__":
    main()
