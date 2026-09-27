"""Package built training data for training on another machine (docs/Remote-Training.md).

    .venv/bin/python scripts/package_training_data.py              # all regions, with DEMs (~6.5 GB)
    .venv/bin/python scripts/package_training_data.py --no-dem     # cells only (~1.2 GB): relief + planner

Allowlist only: each region's meta.json, cells.npz and (unless --no-dem) dem.npy. Nothing else
under data/ is ever packed: no logs, no raw downloads, no symlinks. The tar extracts to
data/<region>/... at the model root, with data/SHA256SUMS (check with `sha256sum -c data/SHA256SUMS`)
and data/MANIFEST.json (regions, sizes, source commit, data attribution).
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import subprocess
import tarfile
import time
from pathlib import Path

from terrain_slm.paths import MODEL_DIR

ALLOWED = ("meta.json", "cells.npz", "dem.npy")
ATTRIBUTION = {
    "elevation": "Produced using Copernicus WorldDEM-30 (c) DLR e.V. 2010-2014 and (c) Airbus Defence and "
                 "Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved.",
    "climate": "WorldClim 2.1 (Fick, S.E. and R.J. Hijmans, 2017. WorldClim 2: new 1km spatial resolution climate "
               "surfaces for global land areas. International Journal of Climatology 37: 4302-4315). "
               "Check WorldClim's terms before any redistribution beyond private research.",
}


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 22), b""):
            h.update(block)
    return h.hexdigest()


def _git_commit() -> str:
    try:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=MODEL_DIR, capture_output=True,
                              text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def _add_bytes(tar: tarfile.TarFile, name: str, data: bytes) -> None:
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mtime = int(time.time())
    info.mode = 0o644
    tar.addfile(info, io.BytesIO(data))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=MODEL_DIR / "data")
    ap.add_argument("--region", action="append", help="region name(s); default: every built region")
    ap.add_argument("--no-dem", action="store_true", help="omit dem.npy (only the refiner needs it)")
    ap.add_argument("--out", type=Path, default=MODEL_DIR / "dist")
    args = ap.parse_args()

    data = args.data.resolve()
    built = sorted(p.parent.name for p in data.glob("*/cells.npz") if (p.parent / "meta.json").is_file())
    regions = args.region or built
    missing = sorted(set(regions) - set(built))
    if missing:
        raise SystemExit(f"not built: {missing} (built: {built})")
    wanted = [n for n in ALLOWED if not (args.no_dem and n == "dem.npy")]

    files = []
    for r in regions:
        for n in wanted:
            p = data / r / n
            if p.is_symlink() or not p.is_file():
                raise SystemExit(f"refusing {p}: missing or a symlink")
            files.append((f"data/{r}/{n}", p))

    args.out.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d")
    out = args.out / f"DaedalusTGM-Exp-data-{stamp}{'-cells' if args.no_dem else ''}.tar"
    sums, manifest_files = [], []
    with tarfile.open(out, "w", format=tarfile.PAX_FORMAT) as tar:
        for arc, p in files:
            digest = _sha256(p)
            info = tar.gettarinfo(str(p), arcname=arc)
            info.uid = info.gid = 0
            info.uname = info.gname = ""  # no local user or group names in the archive
            with open(p, "rb") as f:
                tar.addfile(info, f)
            sums.append(f"{digest}  {arc}")
            manifest_files.append({"path": arc, "bytes": p.stat().st_size, "sha256": digest})
            print(f"  {arc}  {p.stat().st_size / 1e9:.2f} GB", flush=True)
        manifest = {"model": "DaedalusTGM-Exp", "source_commit": _git_commit(), "created": stamp,
                    "regions": regions, "includes_dem": not args.no_dem, "files": manifest_files,
                    "attribution": ATTRIBUTION}
        _add_bytes(tar, "data/SHA256SUMS", ("\n".join(sums) + "\n").encode())
        _add_bytes(tar, "data/MANIFEST.json", json.dumps(manifest, indent=2).encode())
    print(f"wrote {out} ({out.stat().st_size / 1e9:.2f} GB, {len(files)} files)")


if __name__ == "__main__":
    main()
