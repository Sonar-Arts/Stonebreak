"""Which model folder the game ships and whether a folder can be loaded (2026-11-19, v4 removal + Git LFS).

* `DEFAULT_MODEL`: the release the game runs (`TGMPipe.DEFAULT_MODEL` on the Java side); relative to the package root.
* The release weights (`checkpoints/<release>/*.pt`) are stored with **Git LFS**. A clone made without git-lfs (or
  with `GIT_LFS_SKIP_SMUDGE=1`) holds ~130-byte text pointers instead; `torch.load` on one fails with an unpickling
  error that says nothing useful. `check_weights` finds them first and names the fix (`git lfs pull`).
* EXP-1 (v2 / v3 / v4, WorldClim-trained) was removed on 2026-11-19: only EXP-2 folders (they carry the climate node,
  `climate.pt`) are served (`require_exp2_model`).
"""
from __future__ import annotations

from pathlib import Path

DEFAULT_MODEL = "checkpoints/exp2-r3"

#: First bytes of every Git LFS pointer file (git-lfs docs/spec.md); pointers are < 1024 bytes.
LFS_POINTER_PREFIX = b"version https://git-lfs.github.com/spec/"
LFS_POINTER_MAX_BYTES = 1024

#: Retired model folders (relative to the package root): a request for one gets a clear error, never a load.
RETIRED_MODELS = ("checkpoints/v2", "checkpoints/v3", "checkpoints/v4")

FETCH_HINT = ("The DaedalusTGM-Exp model weights are stored with Git LFS: install git-lfs, then run "
              "'git lfs install' and 'git lfs pull' in the Stonebreak checkout")


class ModelFilesError(RuntimeError):
    """A model folder that cannot be loaded: missing, retired, not EXP-2, or Git LFS pointers instead of weights."""


def is_lfs_pointer(path: Path) -> bool:
    """True when `path` is a Git LFS pointer file rather than the real file."""
    try:
        if not path.is_file() or path.stat().st_size >= LFS_POINTER_MAX_BYTES:
            return False
        with open(path, "rb") as f:
            return f.read(len(LFS_POINTER_PREFIX)) == LFS_POINTER_PREFIX
    except OSError:
        return False


def lfs_pointers(model_dir: Path) -> list[str]:
    """Names of the folder's `*.pt` files that are still Git LFS pointers (sorted)."""
    return sorted(p.name for p in Path(model_dir).glob("*.pt") if is_lfs_pointer(p))


def check_weights(model_dir: Path) -> None:
    """Raise ModelFilesError when any `*.pt` in `model_dir` is a Git LFS pointer: never hand one to torch.load."""
    pointers = lfs_pointers(model_dir)
    if pointers:
        raise ModelFilesError(f"model folder {model_dir} has Git LFS pointer files instead of weights "
                              f"({', '.join(pointers)}). {FETCH_HINT}.")


def _retired(model_dir: Path) -> str | None:
    from terrain_slm.paths import MODEL_DIR
    p = Path(model_dir)
    try:
        rel = p.resolve().relative_to(MODEL_DIR.resolve()).as_posix() if p.is_absolute() else p.as_posix()
    except ValueError:
        return None
    rel = rel.rstrip("/")
    return rel if rel in RETIRED_MODELS else None


def require_exp2_model(model_dir: Path) -> None:
    """Raise ModelFilesError unless `model_dir` is a loadable EXP-2 model folder (the only kind the service, the
    warm-up and make_release serve since EXP-1 was removed): present, not retired, climate node present, real
    weights (no Git LFS pointers)."""
    model_dir = Path(model_dir)
    retired = _retired(model_dir)
    if retired is not None:
        raise ModelFilesError(f"model {retired} was retired (EXP-1 models were removed on 2026-11-19); "
                              f"use the release {DEFAULT_MODEL}")
    if not (model_dir / "planner.pt").is_file():
        raise ModelFilesError(f"model checkpoints not found in {model_dir} (planner.pt is missing). {FETCH_HINT}.")
    check_weights(model_dir)
    if not (model_dir / "climate.pt").is_file():
        raise ModelFilesError(f"{model_dir} is not a DaedalusTGM-Exp-2 model folder (no climate.pt): EXP-1 models "
                              f"(v2 / v3 / v4) were removed on 2026-11-19 and are no longer served")
