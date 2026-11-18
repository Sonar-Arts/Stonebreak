#!/usr/bin/env bash
#
# Compile check for agents working in parallel on stonebreak-game: compiles the WHOLE game module with plain
# javac into a PRIVATE output dir (never target/), so several agents can check their work at the same time
# without racing Maven, and prints only the errors in the path prefixes you own.
#
#   Testing/agent-javac.sh <name> [path-prefix ...]
#       <name>          your agent name (private dir: target/agent-javac/<name>, gitignored)
#       path-prefix     e.g. com/stonebreak/rendering  com/stonebreak/world/fastlod   (default: show all)
#   Env: AGENT_JAVAC_REFRESH=1 re-resolves the dependency classpath (after an OpenMason reinstall).
#
# Exit status: 0 when YOUR prefixes have no errors (other packages may still be broken mid-edit by other
# agents), 1 otherwise. Prints "total errors in module: N" so you can see the overall state.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="${1:?usage: agent-javac.sh <name> [path-prefix ...]}"; shift || true
BASE="$ROOT/target/agent-javac"
OUT="$BASE/$NAME/classes"
CP_FILE="$BASE/classpath.txt"
mkdir -p "$BASE" "$OUT"

# JDK 25 the same way the harness finds it.
JDK="${STONEBREAK_JDK:-}"
[[ -z "$JDK" && -x "$HOME/.jdks/ms-25.0.3/bin/javac" ]] && JDK="$HOME/.jdks/ms-25.0.3"
[[ -z "$JDK" && -n "${JAVA_HOME:-}" ]] && JDK="$JAVA_HOME"
JAVAC="${JDK:+$JDK/bin/}javac"

# Dependency classpath (resolved once, shared by all agents; refresh after an engine reinstall).
if [[ ! -s "$CP_FILE" || "${AGENT_JAVAC_REFRESH:-0}" == "1" ]]; then
    # -am resolves the reactor sibling stonebreak-formats to its target/classes; compilation is skipped
    # (maven.main.skip) so a half-edited game never fails this step.
    ( cd "$ROOT" && mvn -q -o compile dependency:build-classpath -pl stonebreak-game -am \
        -Dmaven.main.skip=true -Dmdep.outputFile="$CP_FILE.tmp" -Dmdep.includeScope=compile >"$BASE/cp.log" 2>&1 ) \
      || { echo "classpath resolution failed, see $BASE/cp.log"; exit 2; }
    mv "$CP_FILE.tmp" "$CP_FILE"
fi
CP="$(cat "$CP_FILE")"

SRC="$ROOT/stonebreak-game/src/main/java"
LIST="$BASE/$NAME/sources.txt"
# javac @argfiles split on whitespace: quote every path (the checkout path contains spaces).
find "$SRC" -name '*.java' | sed 's/\\/\\\\/g; s/.*/"&"/' > "$LIST"
LOG="$BASE/$NAME/javac.log"
rm -rf "$OUT" && mkdir -p "$OUT"
set +e
"$JAVAC" -d "$OUT" --module-path "$CP" -Xmaxerrs 100000 -Xlint:none -nowarn -proc:none @"$LIST" > "$LOG" 2>&1
RC=$?
set -e
if [[ $RC -ne 0 ]] && ! grep -q ': error:' "$LOG"; then
    echo "javac failed without source errors:"; head -20 "$LOG"; exit 2
fi

TOTAL=$(grep -c ': error:' "$LOG" || true)
if [[ $# -eq 0 ]]; then
    grep -A3 ': error:' "$LOG" | head -400 || true
    MINE=$TOTAL
else
    PAT=$(printf '%s|' "$@"); PAT="${PAT%|}"
    awk -v pat="$PAT" '
        /: error:/ { show = ($0 ~ pat); }
        show { print }
    ' "$LOG" | head -600
    MINE=$(grep ': error:' "$LOG" | grep -Ec "$PAT" || true)
fi
echo "---- errors in your prefixes: $MINE   total errors in module: $TOTAL   (full log: $LOG)"
[[ "$MINE" -eq 0 ]]
