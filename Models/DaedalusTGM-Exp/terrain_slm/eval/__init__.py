"""Evaluation tools. `DEFAULT_EXP2_MODEL`: the EXP-2 model folder the censuses measure when no `--model` is given
(2026-11-19: the release candidate; bump it with each new candidate so the tools never silently measure an old
model). Relative paths resolve against the package root (Models/DaedalusTGM-Exp)."""
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_EXP2_MODEL = PACKAGE_ROOT / "checkpoints" / "exp2-r3"


def model_path(p) -> Path:
    """A model folder argument: absolute, or relative to the cwd if it exists there, else to the package root."""
    p = Path(p)
    if p.is_absolute() or p.exists():
        return p
    return PACKAGE_ROOT / p
