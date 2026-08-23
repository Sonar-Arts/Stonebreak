#!/usr/bin/env bash
# Chunk Footprint Lab — measure one iteration and diff it against the tier baseline.
#
#   Testing/chunk-lab.sh <tier> <label> [--gl] [--baseline] [--no-features] [-Dkey=value ...]
#
#   tier      1 | 3 | 8 | 16   (side of the measured chunk square; 8 = one region)
#   label     ledger name for this iteration (baseline, compact16, plan-v2, ...)
#   --gl      also open a hidden GL context and upload through the real region arenas
#   --baseline  copy this run's ledger to tier<N>/baseline.json afterwards
#   -D...     forwarded to the JVM (e.g. -Dstonebreak.mesh.vertexformat=compact16,
#             -Dlab.cearl=/path/plan.CEARL, -Dstonebreak.mesher.backend=java)
#
# Ledgers land in "Dev Working/bench/chunk-lab/tier<N>/<label>.json" (gitignored).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

TIER="${1:-}"; LABEL="${2:-}"
if [[ -z "$TIER" || -z "$LABEL" ]]; then
  sed -n '2,14p' "$0"; exit 2
fi
shift 2
GL=false; MAKE_BASELINE=false; EXTRA=()
for arg in "$@"; do
  case "$arg" in
    --gl) GL=true ;;
    --baseline) MAKE_BASELINE=true ;;
    --no-features) EXTRA+=("-Dlab.features=false") ;;
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
LEDGER="$OUT/tier$TIER/$LABEL.json"
mkdir -p "$OUT/tier$TIER"

echo "[chunk-lab] tier $TIER label '$LABEL' gl=$GL ${EXTRA[*]:-}"
( cd "$ROOT" && mvn -q -pl openmason-engine,stonebreak-game -am test \
    -Dtest=ChunkFootprintLabTest -Dsurefire.failIfNoSpecifiedTests=false \
    -DfailIfNoTests=false -Dstonebreak.bench=true \
    -Dlab.tier="$TIER" -Dlab.label="$LABEL" -Dlab.gl="$GL" -Dlab.out="$OUT" \
    "${EXTRA[@]}" ) 2>&1 | grep -v '^\[WARNING\]' | grep -E '^\[chunk-lab\]|ERROR|Tests run|FAIL|Exception' || true

if [[ ! -f "$LEDGER" ]]; then
  echo "[chunk-lab] no ledger written — see output above" >&2; exit 1
fi
python3 "$ROOT/Testing/chunk-lab-diff.py" "$LEDGER" "$OUT/tier$TIER/baseline.json"
if $MAKE_BASELINE; then
  cp "$LEDGER" "$OUT/tier$TIER/baseline.json"
  echo "[chunk-lab] baseline for tier $TIER := $LABEL"
fi
