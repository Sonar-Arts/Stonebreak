"""Where things live, relative to this checkout (repo layout: <repo>/Models/DaedalusTGM-Exp/).

One place for every cross-folder path, so moving the model is a one-file change.
"""
from pathlib import Path

MODEL_DIR = Path(__file__).resolve().parents[1]     # Models/DaedalusTGM-Exp
MODELS_DIR = MODEL_DIR.parent                        # Models/
REPO_DIR = MODELS_DIR.parent                         # repository root
TILE_CACHE_DIR = MODELS_DIR / "tile_cache"          # the service's on-disk tile cache (gitignored)
