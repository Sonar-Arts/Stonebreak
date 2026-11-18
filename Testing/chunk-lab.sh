#!/usr/bin/env bash
# Cube Footprint Lab (cubic runtime, 3B-R) — stream a disc of cube columns through the real native
# pipeline (generator, population, cv_gpu_mesh) headless and diff the run against the radius baseline.
#
#   Testing/chunk-lab.sh <radius> <label> [--baseline] [-Dkey=value ...]
#
#   radius    1 | 3 | 8   (horizontal stream radius in cubes; 8 = one region wide)
#   label     ledger name for this iteration (baseline, plan-v2, ...)
#   --baseline  copy this run's ledger to r<N>/baseline.json afterwards
#   -D...     forwarded to the JVM (e.g. -Dlab.seed=..., -Dlab.scale=ONE_TO_TWO,
#             -Dlab.timeoutSeconds=300, -Dstonebreak.cearl=/path/plan.CEARL)
#
# Measures stream throughput to READY, cv_cube_encode bytes per cube, mesh bytes/quads per layer and the
# planned VRAM of the renderer's 3D region arenas (MmsArenaSim under the CEARL plan). The old column lab
# (ChunkFootprintLab, --gl) is gone with the column pipeline.
# Ledgers land in "Dev Working/bench/chunk-lab/cubes/r<N>/<label>.json" (gitignored).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=python.sh
source "$ROOT/Testing/python.sh"

TIER="${1:-}"; LABEL="${2:-}"
if [[ -z "$TIER" || -z "$LABEL" ]]; then
  sed -n '2,14p' "$0"; exit 2
fi
shift 2
GL=false; MAKE_BASELINE=false; EXTRA=()
for arg in "$@"; do
  case "$arg" in
    --gl) echo "--gl is gone with the column lab (GL proof: MmsChunkRegionGlTest in OpenMason)" >&2; exit 2 ;;
    --baseline) MAKE_BASELINE=true ;;
    -D*) EXTRA+=("$arg") ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

# ── JDK resolution (same rule as run-tests.sh) ──
CAND=""
if [[ -n "${STONEBREAK_JDK:-}" ]]; then CAND="$STONEBREAK_JDK"
elif [[ -d "$HOME/.jdks/ms-25.0.3" ]]; then CAND="$HOME/.jdks/ms-25.0.3"
elif [[ -n "${JAVA_HOME:-}" ]]; then CAND="$JAVA_HOME"; fi
if [[ -z "$CAND" ]] || ! "$CAND/bin/java" -version 2>&1 | grep -q 'version "25'; then
  echo "No JDK 25 found. Set STONEBREAK_JDK." >&2; exit 2
fi
export JAVA_HOME="$CAND"; export PATH="$JAVA_HOME/bin:$PATH"

OUT="$ROOT/Dev Working/bench/chunk-lab"
LEDGER="$OUT/cubes/r$TIER/$LABEL.json"
mkdir -p "$OUT/cubes/r$TIER"
case "$TIER" in 1|3|8) ;; *) echo "radius must be 1, 3 or 8" >&2; exit 2 ;; esac

echo "[chunk-lab] radius $TIER cubes, label '$LABEL' ${EXTRA[*]:-}"
( cd "$ROOT" && mvn -q -pl stonebreak-game -am test \
    -Dtest=CubeFootprintLabTest -Dsurefire.failIfNoSpecifiedTests=false \
    -DfailIfNoTests=false -Dstonebreak.bench=true \
    -Dlab.tier="$TIER" -Dlab.label="$LABEL" -Dlab.out="$OUT" \
    "${EXTRA[@]}" ) 2>&1 | grep -v '^\[WARNING\]' | grep -E '^\[chunk-lab\]|ERROR|Tests run|FAIL|Exception' || true

if [[ ! -f "$LEDGER" ]]; then
  echo "[chunk-lab] no ledger written — see output above" >&2; exit 1
fi
"${PY[@]}" "$ROOT/Testing/chunk-lab-diff.py" "$LEDGER" "$OUT/cubes/r$TIER/baseline.json"
if $MAKE_BASELINE; then
  cp "$LEDGER" "$OUT/cubes/r$TIER/baseline.json"
  echo "[chunk-lab] baseline for radius $TIER := $LABEL"
fi
