"""Make a self-contained, shippable DaedalusTGM model folder from a model directory of symlinks.

    .venv/bin/python scripts/make_release.py checkpoints/exp2/world_exp2f checkpoints/exp2-r3
    .venv/bin/python scripts/make_release.py <src> <out> --verify-tiles 12 --device cuda:1     (default: 9 tiles)
    .venv/bin/python scripts/make_release.py <src> <out> --check-only                          (licence gate only)

Paths are relative to Models/DaedalusTGM-Exp (or absolute). For every *.pt in <src> (symlinks resolved):

1. **Licence gate.** The checkpoint's training run (its directory relative to checkpoints/) is looked up in
   scripts/release_provenance.json, its whole init chain is walked, and every dataset source on the way must be
   allowed (public domain / CC0 / CC BY / open-with-attribution). An unknown run, or one whose chain touches
   WorldClim, FABDEM, EEA-10 or the unconfirmed CHELSA-TraCE21k, stops the release (exit 2): add the run to the
   provenance file with its evidence, or retrain.
2. **Inference-only copy.** Real files (no symlinks). Only what the generators read is kept: model (state dict,
   each tensor cloned to its own compact storage, dtype unchanged), config, step, norms, quant. Optimiser / EMA
   bookkeeping is dropped; small metadata (eval, class_ids, ema_std, objective) moves into the manifest.
3. **Manifest** `release.json`: per file sha256, bytes, parameters, dtypes, step, source checkpoint (+ its
   sha256), training run, init chain, datasets, sources and licences; plus a `content_id` (hash of every file's
   name + sha256). The generator adds `:R<content_id[:12]>` to its model_id when a release.json is present, so
   TGMPipe's tile caches can never mix two releases written to the same folder name. ATTRIBUTION.md is copied in.
4. **Verification.** The same tiles are built from <src> and from the new folder, each in its own process, the
   way the TGMPipe service builds them (WorldGenerator2 when climate.pt is present, layout 'belts'), at 1:4 and
   at 1:2 (SR-30), for EXP-2 models also on the v2 height curve at 1:4 and (sr15.pt present) at 1:1 (SR-15), and
   every plane must be bit-identical. The result is written into the manifest.

The new folder is assembled next to <out> and renamed into place at the end. An existing <out> is replaced only
with --force and only if it is itself a release (has release.json).
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SLM = HERE.parent                                     # Models/DaedalusTGM-Exp
CHECKPOINTS = SLM / "checkpoints"
PROVENANCE = HERE / "release_provenance.json"
#: Model files the generators load (world/generator.py, world/generator2.py). Others are copied with a warning.
KNOWN = ("planner", "refiner", "relief", "hydro", "bank", "descgit", "detail", "detail_fp8", "biome", "climate", "sr30",
         "sr15", "surface", "veg")   # surface: the surface-detail node (agent L, generator2.surfacenet); veg: the vegetation node (agent V, generator2.vegnet)
#: Checkpoint keys the generators read (model, config, step everywhere; norms from planner.pt) + quant (an FP8
#: student's format: changes what the weights mean, so it stays with them).
KEEP = ("model", "config", "step", "norms", "quant")
MANIFEST = "release.json"
SCHEMA = 1


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 22), b""):
            h.update(block)
    return h.hexdigest()


def _abs(p: str | Path) -> Path:
    p = Path(p)
    return p if p.is_absolute() else SLM / p


def _rel(p: Path) -> str:
    try:
        return str(p.relative_to(SLM))
    except ValueError:
        return str(p)


def _jsonable(v):
    """Small metadata as JSON (tensors and the like become their repr)."""
    try:
        json.dumps(v)
        return v
    except TypeError:
        if isinstance(v, dict):
            return {str(k): _jsonable(x) for k, x in v.items()}
        if isinstance(v, (list, tuple)):
            return [_jsonable(x) for x in v]
        return repr(v)


# ------------------------------------------------------------------------------------------------ provenance

class LicenceError(Exception):
    pass


def run_key(real: Path) -> str:
    """The training run a checkpoint file belongs to: its directory relative to checkpoints/ (a loose .pt directly
    in a run folder's parent, like exp2/d2_mf_plus_experts_init.pt, is its own run)."""
    try:
        rel = real.relative_to(CHECKPOINTS)
    except ValueError:
        raise LicenceError(f"{real} is outside {CHECKPOINTS}: no provenance can be looked up")
    parent = rel.parent.as_posix()
    prov = load_provenance()
    if rel.as_posix() in prov["runs"]:
        return rel.as_posix()
    return parent


_PROV = None


def load_provenance() -> dict:
    global _PROV
    if _PROV is None:
        _PROV = json.loads(PROVENANCE.read_text())
    return _PROV


def lineage(key: str, seen=None) -> tuple[list[str], list[str], list[str]]:
    """(init chain incl. key, datasets, sources) of a run; raises LicenceError on unknown runs or cycles."""
    prov = load_provenance()
    seen = seen or []
    if key in seen:
        raise LicenceError(f"init cycle through {key}")
    run = prov["runs"].get(key)
    if run is None:
        raise LicenceError(f"run '{key}' is not in {PROVENANCE.name}: add it (data, init, evidence) after checking "
                           f"its training command, or do not ship it")
    chain, datasets = [key], list(run["data"])
    for parent in run["init"]:
        c, d, _ = lineage(parent, seen + [key])
        chain += [x for x in c if x not in chain]
        datasets += [x for x in d if x not in datasets]
    sources = []
    for d in datasets:
        if d not in prov["datasets"]:
            raise LicenceError(f"dataset '{d}' (run {key}) is not described in {PROVENANCE.name}")
        sources += [s for s in prov["datasets"][d]["sources"] if s not in sources]
    return chain, datasets, sources


def gate(real: Path) -> dict:
    """Provenance record of one checkpoint file; raises LicenceError if it may not ship."""
    prov = load_provenance()
    key = run_key(real)
    chain, datasets, sources = lineage(key)
    bad = [s for s in sources if not prov["sources"][s]["allowed"]]
    if bad:
        why = "; ".join(f"{prov['sources'][s]['name']}: {prov['sources'][s].get('why', prov['sources'][s]['licence'])}"
                        for s in bad)
        raise LicenceError(f"{_rel(real)} (run {key}, chain {' <- '.join(chain)}) was trained on forbidden data: {why}")
    return {"run": key, "init_chain": chain, "datasets": datasets, "sources": sources,
            "licences": {s: prov["sources"][s]["licence"] for s in sources},
            "evidence": {k: prov["runs"][k].get("evidence", "") for k in chain}}


# ------------------------------------------------------------------------------------------------ copy

def inference_copy(src_real: Path, dst: Path) -> dict:
    import torch

    ck = torch.load(src_real, map_location="cpu", weights_only=False)
    if not isinstance(ck, dict) or "model" not in ck or "config" not in ck:
        raise SystemExit(f"{src_real}: not a terrain_slm checkpoint (needs 'model' and 'config')")
    sd = ck["model"]
    if not all(isinstance(v, torch.Tensor) for v in sd.values()):
        raise SystemExit(f"{src_real}: 'model' is not a plain state dict")
    out = {k: ck[k] for k in KEEP if k in ck}
    out["model"] = {k: v.detach().clone().contiguous() for k, v in sd.items()}
    torch.save(out, dst)
    dropped = sorted(k for k in ck if k not in KEEP)
    meta = {k: _jsonable(ck[k]) for k in dropped if k not in ("optimizer", "opt", "optim", "scaler", "sched",
                                                                  "scheduler", "ema", "state", "rng")}
    return {
        "params": int(sum(v.numel() for v in sd.values())),
        "tensors": len(sd),
        "dtypes": sorted({str(v.dtype).replace("torch.", "") for v in sd.values()}),
        "step": ck.get("step"),
        "config": _jsonable(ck["config"]),
        "kept_keys": [k for k in KEEP if k in out],
        "dropped_keys": dropped,
        "meta": meta,
    }


# ------------------------------------------------------------------------------------------------ verification

def _worlds(model_dir: Path | None = None, exp2: bool = False):
    """The game's 1:4 handshake world (eval.tile_parity) and its 1:2 twin (30 m blocks, no downscale -> SR-30). For an
    EXP-2 model also a v2-curve 1:4 world (what new worlds get) and, when the folder carries sr15.pt, the 1:1 world
    (15 m blocks, SR-15; review-python LOW-2: the v1 1:4 / 1:2 tiles alone never showed that SR-15 loaded)."""
    from dataclasses import replace

    from terrain_slm.eval.tile_parity import game_world
    from terrain_slm.world.world_config import ONE_TO_ONE_CURVE, WorldConfig, curve_v2_keys
    w4 = game_world()
    worlds = {"1:4": w4, "1:2": replace(w4, horizontal_m=30.0, downscale=1)}
    if exp2:
        worlds["1:4v2"] = replace(w4, climate_layout="belts", **curve_v2_keys("1:4"))
        if model_dir is not None and (Path(model_dir) / "sr15.pt").exists():
            r, k = ONE_TO_ONE_CURVE      # TerrainScale.worldConfigJson(ONE_TO_ONE, BELTS, V2), as tests/test_one_to_one
            d = dict(world_height=640, min_y=-384, sea_level=64, horizontal_m=15.0, downscale=0.5, tile_size=256,
                     ocean_m_per_block=r[0], lowland_m_per_block=r[1], midland_m_per_block=r[2],
                     highland_m_per_block=r[3], lowland_top_m=k[0], highland_base_m=k[1], shore_blend_m=k[2],
                     midland_blend_m=k[3], highland_blend_m=k[4], climate_layout="belts")
            d.update(curve_v2_keys("1:1"))           # the v2 sea bands override the v1 ocean rate and shore blend
            worlds["1:1v2"] = WorldConfig(**d)
    return worlds


#: Tiles per seed of each verification world, as a function of --verify-tiles (1:4 v1 gets them all).
def _tiles_for(world: str, tiles: int) -> int:
    if world == "1:4":
        return tiles
    if world == "1:1v2":
        return 1                   # the slowest scale: one tile proves SR-15 loads and matches
    return max(1, tiles // 3)


def _default_device() -> str:
    """The less-loaded CUDA card (lowest utilisation, then least memory in use, by nvidia-smi; review-python LOW-2:
    the old default cuda:0 is often a training card), in PCI order (the children get CUDA_DEVICE_ORDER=PCI_BUS_ID so
    the index means the same card). cuda:0 when CUDA_VISIBLE_DEVICES narrows the view or nvidia-smi is missing."""
    if os.environ.get("CUDA_VISIBLE_DEVICES"):
        return "cuda:0"
    try:
        rows = subprocess.run(["nvidia-smi", "--query-gpu=index,utilization.gpu,memory.used",
                               "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=20,
                              check=True).stdout.strip().splitlines()
        cards = [tuple(int(x) for x in r.split(",")) for r in rows if r.strip()]
    except (OSError, subprocess.SubprocessError, ValueError):
        return "cuda:0"
    if not cards:
        return "cuda:0"
    best = min(cards, key=lambda c: (c[1], c[2], c[0]))
    return f"cuda:{best[0]}"


def dump_tiles(model_dir: Path, out: Path, device: str, tiles: int, seeds: list[int]) -> None:
    """Child process: build the tiles from `model_dir` exactly like the TGMPipe service and save them."""
    import numpy as np
    import torch

    from terrain_slm.eval.vram_audit import spiral
    from terrain_slm.model_files import require_exp2_model
    from terrain_slm.world.generator import GenConfig
    from terrain_slm.world.generator2 import WorldGenerator2
    from terrain_slm.world.tiles import TileBuilder

    if device.startswith("cuda"):
        torch.cuda.set_device(torch.device(device))
    require_exp2_model(model_dir)       # EXP-2 only (EXP-1 removed 2026-11-19); no Git LFS pointers
    # tgmpipe/service.py defaults
    gen = WorldGenerator2(model_dir, 0, device, GenConfig(t_start=0.6, steps=8, amp_scale=1.0, river_log_threshold=None))
    gen.use_climate_layout("belts")
    gen.warm_up()
    res = {"_model_id": np.array(gen.model_id), "_generator": np.array(type(gen).__name__),
           "_detail_fp8": np.array(":kd8-" in gen.model_id)}
    if (model_dir / "detail_fp8.pt").exists():
        from terrain_slm.world import generator2 as G2
        if G2.DETAIL_FP8 and ":kd8-" not in gen.model_id:
            raise SystemExit(f"verify: {model_dir} carries detail_fp8.pt but the FP8 detail sampler did not engage on "
                             f"{device} (needs sm_89+ and the native kernels); verify on an FP8-capable GPU")
    for wname, world in _worlds(model_dir, True).items():
        builder = TileBuilder(gen, world)
        n = _tiles_for(wname, tiles)
        for seed in seeds:
            for tx, tz in spiral(n):
                res[f"{wname}_s{seed}_x{tx}_z{tz}"] = np.asarray(builder.build(seed, tx, tz, 1))
    np.savez(out, **res)


def verify(src: Path, out: Path, device: str, tiles: int, seeds: list[int]) -> dict:
    import numpy as np

    env = dict(os.environ)
    env.setdefault("TGM_KERNEL_CACHE", str(SLM.parent / "kernel_cache"))   # the game's kernel cache
    env.setdefault("CUDA_DEVICE_ORDER", "PCI_BUS_ID")                      # cuda:N = nvidia-smi's index N
    dumps = {}
    with tempfile.TemporaryDirectory(prefix="make_release_") as tmp:
        for tag, d in (("src", src), ("release", out)):
            f = Path(tmp) / f"{tag}.npz"
            cmd = [sys.executable, str(Path(__file__).resolve()), "--_dump", str(d), str(f), "--device", device,
                   "--verify-tiles", str(tiles), "--seeds", *map(str, seeds)]
            print(f"verify: building tiles from {_rel(d)} ...", flush=True)
            subprocess.run(cmd, cwd=SLM, env=env, check=True)
            z = np.load(f)
            dumps[tag] = {k: z[k] for k in z.files}
    a, b = dumps["src"], dumps["release"]
    keys = sorted(k for k in a if not k.startswith("_"))
    diff = [k for k in keys if k not in b or a[k].shape != b[k].shape or not np.array_equal(a[k], b[k])]
    n_vals = int(sum(a[k].size for k in keys))
    result = {"tiles": len(keys), "bit_identical": len(keys) - len(diff), "differing": diff,
              "planes_per_tile": int(a[keys[0]].shape[0]) if keys else 0, "values_compared": n_vals,
              "worlds": sorted({k.split("_")[0] for k in keys}), "seeds": seeds, "device": device,
              "generator": str(a["_generator"]), "model_id_src": str(a["_model_id"]),
              "detail_fp8": bool(a["_detail_fp8"]) if "_detail_fp8" in a else False,
              "model_id_release": str(b["_model_id"])}
    print(f"verify: {result['bit_identical']}/{result['tiles']} tiles bit-identical "
          f"({n_vals:,} values; {result['generator']})", flush=True)
    return result


# ------------------------------------------------------------------------------------------------ main

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="scripts/make_release.py", description=__doc__.split("\n\n")[0])
    ap.add_argument("src", nargs="?", help="model dir (symlinks allowed), relative to Models/DaedalusTGM-Exp or absolute")
    ap.add_argument("out", nargs="?", help="release dir to create")
    ap.add_argument("--name", help="release name for the manifest (default: the out dir's name)")
    ap.add_argument("--device", default="auto",
                    help="device for the tile verification (default auto: the less-loaded card by nvidia-smi)")
    ap.add_argument("--verify-tiles", type=int, default=9, help="1:4 tiles per seed (1:2 gets a third); 0 = skip")
    ap.add_argument("--seeds", type=int, nargs="+", default=[1, 7])
    ap.add_argument("--force", action="store_true", help="replace an existing release at <out>")
    ap.add_argument("--check-only", action="store_true", help="run the licence gate and stop")
    ap.add_argument("--_dump", nargs=2, metavar=("MODEL_DIR", "NPZ"), help=argparse.SUPPRESS)
    args = ap.parse_args(argv)

    if args.device == "auto":
        args.device = _default_device()
    if args._dump:
        dump_tiles(Path(args._dump[0]), Path(args._dump[1]), args.device, args.verify_tiles, args.seeds)
        return 0
    if not args.src or not args.out:
        ap.error("src and out are required")

    src, out = _abs(args.src), _abs(args.out)
    if not src.is_dir():
        raise SystemExit(f"{src}: not a directory")
    files = sorted(p for p in src.iterdir() if p.suffix == ".pt")
    if not files:
        raise SystemExit(f"{src}: no .pt files")
    from terrain_slm.model_files import ModelFilesError, check_weights
    try:
        check_weights(src)                           # Git LFS pointers instead of weights: never torch.load one
    except ModelFilesError as e:
        raise SystemExit(str(e))
    others = sorted(p.name for p in src.iterdir() if p.suffix != ".pt")
    if others:
        print(f"note: not copied (not .pt): {', '.join(others)}")

    # 1. licence gate, before anything is written
    records, failures = {}, []
    for p in files:
        real = p.resolve(strict=True)
        try:
            records[p.name] = {"source": _rel(real), **gate(real)}
        except LicenceError as e:
            failures.append(str(e))
        if p.stem not in KNOWN:
            print(f"warning: {p.name} is not a file the generators load ({', '.join(KNOWN)}); copied anyway")
    for p in files:
        if p.name in records:
            r = records[p.name]
            print(f"  {p.name:12s} {r['run']:34s} <- {' <- '.join(r['init_chain'][1:]) or '(from scratch)'}"
                  f"  [{', '.join(r['sources'])}]")
    if failures:
        print("LICENCE GATE FAILED:", *failures, sep="\n  ")
        return 2
    print(f"licence gate: {len(files)} checkpoints, every source allowed")
    if args.check_only:
        return 0

    # 2. copy into a staging folder beside out
    if out.exists():
        if not args.force:
            raise SystemExit(f"{out} exists (use --force to replace a release)")
        if not (out / MANIFEST).exists():
            raise SystemExit(f"{out} exists and is not a release (no {MANIFEST}); refusing to replace it")
    out.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=f".{out.name}.staging-", dir=out.parent))
    try:
        for p in files:
            real = p.resolve(strict=True)
            print(f"copy {p.name} <- {_rel(real)}", flush=True)
            info = inference_copy(real, stage / p.name)
            records[p.name].update(info)
            records[p.name]["source_sha256"] = _sha256(real)
            records[p.name]["source_bytes"] = real.stat().st_size
            records[p.name]["sha256"] = _sha256(stage / p.name)
            records[p.name]["bytes"] = (stage / p.name).stat().st_size
        attribution = SLM / "ATTRIBUTION.md"
        if attribution.exists():
            shutil.copy2(attribution, stage / "ATTRIBUTION.md")
        content_id = hashlib.sha256("".join(f"{n}:{r['sha256']}\n" for n, r in sorted(records.items()))
                                    .encode()).hexdigest()
        prov = load_provenance()
        used = sorted({s for r in records.values() for s in r["sources"]})
        manifest = {
            "schema": SCHEMA,
            "name": args.name or out.name,
            "model": "DaedalusTGM-Exp-2" if (src / "climate.pt").exists() else "DaedalusTGM-Exp",
            "generator": "WorldGenerator2" if (src / "climate.pt").exists() else "WorldGenerator",
            "content_id": content_id,
            "created_utc": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "source_dir": _rel(src),
            "made_by": "scripts/make_release.py",
            "attribution": "ATTRIBUTION.md (copied into this folder)",
            "sources": {s: {k: prov["sources"][s][k] for k in ("name", "licence")} for s in used},
            "datasets": {d: prov["datasets"][d] for d in sorted({d for r in records.values() for d in r["datasets"]})},
            "total_bytes": sum(r["bytes"] for r in records.values()),
            "files": records,
            "verification": None,
        }
        (stage / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n")

        # 4. verify from the staged folder (its own name is part of model_id only, never of the numbers)
        if args.verify_tiles > 0:
            res = verify(src, stage, args.device, args.verify_tiles, args.seeds)
            manifest["verification"] = res
            (stage / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n")
            if res["differing"]:
                print("VERIFICATION FAILED: tiles differ:", *res["differing"][:20], sep="\n  ")
                return 3
        else:
            print("verification skipped (--verify-tiles 0)")

        # 3. into place
        if out.exists():
            old = out.with_name(f".{out.name}.old-{os.getpid()}")
            out.rename(old)
            stage.rename(out)
            shutil.rmtree(old)
        else:
            stage.rename(out)
        stage = None
    finally:
        if stage is not None and stage.exists():
            shutil.rmtree(stage)
    print(f"release {manifest['name']} -> {_rel(out)}: {len(records)} files, {manifest['total_bytes'] / 1e6:.1f} MB, "
          f"content_id {content_id[:12]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
