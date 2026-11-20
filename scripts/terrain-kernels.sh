#!/usr/bin/env bash
#
# Compiles every GPU kernel the DaedalusTGM-Exp terrain model needs, outside the game, into Models/kernel_cache/
# (where the game looks first), so the first world load never compiles while it already keeps the GPU busy.
#
#   scripts/terrain-kernels.sh [--device cuda:N] [--native-only | --python-only] [model_path]
#
# Covers both paths: the Python service's kernels (what the game's first-launch setup compiles) and the native
# in-process engine's kernels (model warm-up + one tile per level of detail at 1:4, 1:2 and 1:1). A second run finds
# everything cached and finishes in seconds. Run it after pulling a new model release or engine build.
# Logic: Models/DaedalusTGM-Exp/terrain_slm/tgmpipe/precompile.py (any OS: run it with the model's Python directly).
#
# Engine library (needs the inference runtime, ci_*): $TGM_CENDA_LIB, else the copy the game extracted last
# (~/.cache/openmason/natives/*/), else the one in the installed engine jar (~/.m2), else an OpenMason checkout
# next to this repo (../OpenMason, build/release or build/agent-infer).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SLM="$ROOT/Models/DaedalusTGM-Exp"
PY="$SLM/.venv/bin/python"

die() { echo "[kernels] ERROR: $*" >&2; exit 1; }

[[ -x "$PY" ]] || die "no Python environment at $SLM/.venv (launch the game once, or: cd \"$SLM\" && uv sync --frozen)"

export TGM_KERNEL_CACHE="${TGM_KERNEL_CACHE:-$ROOT/Models/kernel_cache}"

has_infer() { [[ -f "$1" ]] && grep -q "ci_abi_version" "$1"; }

if [[ -z "${TGM_CENDA_LIB:-}" ]]; then
  # 1. what the game extracted last (newest first)
  for lib in $(ls -t "$HOME"/.cache/openmason/natives/*/libcenda_kernels.so 2>/dev/null); do
    if has_infer "$lib"; then export TGM_CENDA_LIB="$lib"; break; fi
  done
fi
if [[ -z "${TGM_CENDA_LIB:-}" ]]; then
  # 2. the engine jar the build uses (.mvn/maven.config override, else pom.xml)
  version="$(sed -n 's/^-Dopenmason.version=//p' "$ROOT/.mvn/maven.config" 2>/dev/null | head -n 1)"
  [[ -n "$version" ]] || version="$(sed -n 's:.*<openmason.version>\([^<]*\)</openmason.version>.*:\1:p' "$ROOT/pom.xml" | head -n 1)"
  jar="$HOME/.m2/repository/com/openmason/openmason-engine-core/$version/openmason-engine-core-$version.jar"
  if [[ -f "$jar" ]]; then
    dest="$ROOT/Models/tools/cenda/$version"
    mkdir -p "$dest"
    if unzip -q -o -j "$jar" 'natives/linux-x86_64/libcenda_kernels.so' -d "$dest" 2>/dev/null \
        && has_infer "$dest/libcenda_kernels.so"; then
      export TGM_CENDA_LIB="$dest/libcenda_kernels.so"
    fi
  fi
fi
# 3. otherwise the Python side searches ../OpenMason itself (and says so if nothing is there)
[[ -n "${TGM_CENDA_LIB:-}" ]] && echo "[kernels] engine library: $TGM_CENDA_LIB"

cd "$SLM"
exec "$PY" -m terrain_slm.tgmpipe.precompile "$@"
