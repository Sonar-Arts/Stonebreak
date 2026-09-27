"""Where things live, relative to this checkout (repo layout: <repo>/Models/DaedalusTGM-Exp/).

One place for every cross-folder path, so moving the model is a one-file change.
"""
from pathlib import Path

MODEL_DIR = Path(__file__).resolve().parents[1]     # Models/DaedalusTGM-Exp
MODELS_DIR = MODEL_DIR.parent                        # Models/
REPO_DIR = MODELS_DIR.parent                         # repository root
BRIDGE_DIR = MODELS_DIR / "terrain-bridge"           # shared tile adapter (HeightCurve, hydrology)
