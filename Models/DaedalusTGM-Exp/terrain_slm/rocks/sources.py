"""Licence-checked rock scan sources + downloader.

Only sources whose licence was verified on the provider's own page are listed here (no NC / ND licences).
Every download writes `<raw>/<source>/<asset>/provenance.json` (asset id, authors, licence, URLs, md5) and the
source-level `<raw>/sources.json` collects them, so the bank manifest can cite exactly what trained the model.

    python -m terrain_slm.rocks.sources --download          # Poly Haven rocks (geometry only, ~340 MB)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import time
import urllib.request
from pathlib import Path

RAW_DIR = Path("/mnt/extrastorage/terrain-slm-data/raw/rocks")
USER_AGENT = "daedalus-rock-dataset/1.0"

# Licence checks (verified 2026-11-19 on the provider's pages; quotes in the N report).
SOURCES = {
    "polyhaven": {
        "name": "Poly Haven (3D models, category 'rocks')",
        "licence": "CC0 1.0",
        "licence_url": "https://polyhaven.com/license",
        "verified": "2026-11-19: 'All assets (HDRIs, textures and 3D models)' are 'licensed as CC0'; "
                    "'You do not need to give credit'; 'You can use our assets for any purpose'.",
        "api": "https://api.polyhaven.com (public API: 'Free to use for any purpose, personal or commercial'; "
               "a 'Powered by Poly Haven' credit is asked only of apps building on the LIVE API - we do not)",
        "allowed": True,
        "attribution_required": False,
    },
}
# Checked and NOT used: ambientCG (CC0, verified https://docs.ambientcg.com/license/) no longer lists 3D models in
# its API (2026-11-19: every 'rock' asset is a 2D PBR material), so it adds no shapes.


def _get(url: str, timeout: float = 60.0) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception:  # noqa: BLE001 - network retries
            if attempt == 3:
                raise
            time.sleep(2.0 * (attempt + 1))
    raise RuntimeError(url)


def _get_json(url: str):
    return json.loads(_get(url))


def download_polyhaven(raw_dir: Path = RAW_DIR, category: str = "rocks") -> list[dict]:
    """Download the glTF geometry (.gltf + .bin, no textures) of every Poly Haven model in `category`."""
    out = []
    assets = _get_json(f"https://api.polyhaven.com/assets?t=models&c={category}")
    for aid in sorted(assets):
        info = _get_json(f"https://api.polyhaven.com/info/{aid}")
        files = _get_json(f"https://api.polyhaven.com/files/{aid}")
        g = files["gltf"]["1k"]["gltf"]
        d = raw_dir / "polyhaven" / aid
        d.mkdir(parents=True, exist_ok=True)
        entries = [(f"{aid}.gltf", g["url"], g["md5"])]
        entries += [(name, v["url"], v["md5"]) for name, v in g["include"].items() if name.endswith(".bin")]
        for name, url, md5 in entries:
            p = d / Path(name).name
            if p.exists() and hashlib.md5(p.read_bytes()).hexdigest() == md5:
                continue
            data = _get(url)
            if hashlib.md5(data).hexdigest() != md5:
                raise RuntimeError(f"md5 mismatch for {url}")
            p.write_bytes(data)
        prov = {
            "source": "polyhaven", "asset": aid, "name": info.get("name"),
            "authors": sorted((info.get("authors") or {}).keys()),
            "licence": SOURCES["polyhaven"]["licence"], "licence_url": SOURCES["polyhaven"]["licence_url"],
            "page": f"https://polyhaven.com/a/{aid}",
            "categories": info.get("categories"), "tags": info.get("tags"),
            "dimensions_mm": info.get("dimensions"), "polycount": info.get("polycount"),
            "files": [{"name": Path(n).name, "url": u, "md5": m} for n, u, m in entries],
            "files_hash": info.get("files_hash"),
        }
        (d / "provenance.json").write_text(json.dumps(prov, indent=1))
        out.append(prov)
        print(f"[sources] {aid}: {sum((d / Path(n).name).stat().st_size for n, _, _ in entries) / 1e6:.1f} MB")
    return out


def collect_manifest(raw_dir: Path = RAW_DIR) -> dict:
    assets = [json.loads(p.read_text()) for p in sorted(raw_dir.glob("*/*/provenance.json"))]
    man = {"sources": SOURCES, "assets": assets}
    (raw_dir / "sources.json").write_text(json.dumps(man, indent=1))
    return man


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--download", action="store_true")
    ap.add_argument("--raw-dir", type=Path, default=RAW_DIR)
    a = ap.parse_args(argv)
    if a.download:
        download_polyhaven(a.raw_dir)
    man = collect_manifest(a.raw_dir)
    print(f"[sources] {len(man['assets'])} assets in {a.raw_dir}")


if __name__ == "__main__":
    main()
