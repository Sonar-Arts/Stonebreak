"""Where the native engine's shared libraries come from (review-native F1).

Fixed places only, never "the newest build of any kind": an experimental or sanitizer build that happens to be newer
must not win (an ASan library loaded into a plain Python aborts the process before ctypes can raise).

    find(env, [dir, ...], names, symbol)

1. `$env` (a file): taken as given, no other candidate is tried.
2. the given build dirs in order (release first, then the named dev dir); a dir whose CMakeCache.txt builds with
   `-fsanitize` is skipped, and every candidate is probed in a subprocess before this process loads it.
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

_PROBE = ("import ctypes, sys\n"
          "lib = ctypes.CDLL(sys.argv[1])\n"
          "getattr(lib, sys.argv[2])\n")


def sanitized(build_dir: Path) -> bool:
    """True when the CMake build in `build_dir` (or one of its parents up to 4 levels) uses a sanitizer."""
    d = build_dir
    for _ in range(5):
        cache = d / "CMakeCache.txt"
        if cache.exists():
            try:
                text = cache.read_text(errors="replace")
            except OSError:
                return True
            return "-fsanitize" in text
        if d.parent == d:
            break
        d = d.parent
    return False


def probe(path: Path, symbol: str) -> str | None:
    """None when `path` loads in a fresh interpreter and exports `symbol`, else the reason it does not."""
    try:
        r = subprocess.run([sys.executable, "-I", "-c", _PROBE, str(path), symbol], capture_output=True, text=True,
                           timeout=60)
    except (OSError, subprocess.SubprocessError) as e:
        return f"probe failed: {e}"
    if r.returncode != 0:
        tail = (r.stderr or r.stdout).strip().splitlines()
        return tail[-1] if tail else f"exit {r.returncode}"
    return None


def find(env: str, files: list[Path], symbol: str) -> tuple[list[Path], list[str]]:
    """Candidates to load, in order, and the reasons others were skipped. `files` = fixed library paths (in order)."""
    given = os.getenv(env)
    if given:
        return [Path(given)], []
    out, skipped = [], []
    for p in files:
        if not p.exists():
            continue
        if sanitized(p.parent):
            skipped.append(f"{p}: sanitizer build, skipped")
            continue
        why = probe(p, symbol)
        if why is not None:
            skipped.append(f"{p}: {why}")
            continue
        out.append(p)
    return out, skipped


def shared_names(stem: str) -> tuple[str, ...]:
    return (f"lib{stem}.so", f"lib{stem}.dylib", f"{stem}.dll")
