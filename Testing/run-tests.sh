#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
HARNESS="$HERE/harness.py"

# ── Argument parsing ────────────────────────────────────────────────
LIST=0
UPDATE_BASELINE=0
VERBOSE=0
SCOPE_WORDS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      cat <<'EOF'
Usage: Testing/run-tests.sh [--list] [--update-baseline] [--verbose]
                                 all | integration | regression | <system> [<system>...]
EOF
      exit 2
      ;;
    --list)           LIST=1; shift ;;
    --update-baseline) UPDATE_BASELINE=1; shift ;;
    --verbose)        VERBOSE=1; shift ;;
    -*)
      echo "Unknown flag: $1" >&2
      cat >&2 <<'EOF'
Usage: Testing/run-tests.sh [--list] [--update-baseline] [--verbose]
                                 all | integration | regression | <system> [<system>...]
EOF
      exit 2
      ;;
    *) SCOPE_WORDS+=("$1"); shift ;;
  esac
done

if [[ "$LIST" -eq 0 && ${#SCOPE_WORDS[@]} -eq 0 ]]; then
  cat <<'EOF'
Usage: Testing/run-tests.sh [--list] [--update-baseline] [--verbose]
                                 all | integration | regression | <system> [<system>...]
EOF
  exit 2
fi

# ── --list short-circuit ────────────────────────────────────────────
if [[ "$LIST" -eq 1 ]]; then
  exec python3 "$HARNESS" list
fi

# Join scope words with commas
IFS=','
SCOPE="${SCOPE_WORDS[*]}"
unset IFS

# ── JDK resolution ──────────────────────────────────────────────────
CAND=""

if [[ -n "${STONEBREAK_JDK:-}" ]]; then
  CAND="$STONEBREAK_JDK"
elif [[ -d "$HOME/.jdks/ms-25.0.3" ]]; then
  CAND="$HOME/.jdks/ms-25.0.3"
elif [[ -n "${JAVA_HOME:-}" ]]; then
  CAND="$JAVA_HOME"
fi

if [[ -z "${CAND:-}" ]] || ! "$CAND/bin/java" -version 2>&1 | grep -q 'version "25'; then
  echo "No JDK 25 found. Set STONEBREAK_JDK to a JDK 25 installation." >&2
  exit 2
fi

export JAVA_HOME="$CAND"
export PATH="$JAVA_HOME/bin:$PATH"

# ── Resolve scope ───────────────────────────────────────────────────
RESOLVED="$(python3 "$HARNESS" resolve --scope "$SCOPE")"
MODULES_LINE="$(echo "$RESOLVED" | head -n1)"
SECOND_LINE="$(echo "$RESOLVED" | sed -n '2p')"

# Strip leading keyword from each line
MODULES_STR="${MODULES_LINE#MODULES }"
LINE_KIND="${SECOND_LINE%% *}"
LINE_VALUE="${SECOND_LINE#* }"

read -r -a MODULES <<<"$MODULES_STR"

# ── Clean stale reports ─────────────────────────────────────────────
for m in "${MODULES[@]}"; do
  rm -rf "$ROOT/$m/target/surefire-reports"
done

# ── Run Maven ───────────────────────────────────────────────────────
cd "$ROOT"
MVN_LOG="$(mktemp /tmp/stonebreak-tests-XXXXXX.log)"
MVN_EXIT=0

# Determine if all three modules are present (full reactor needed)
ALL_THREE=false
for required_module in openmason-engine stonebreak-game openmason-tool; do
  found=false
  for m in "${MODULES[@]}"; do
    if [[ "$m" == "$required_module" ]]; then
      found=true
      break
    fi
  done
  if [[ "$found" == false ]]; then
    ALL_THREE=false
    break
  fi
  ALL_THREE=true
done

if [[ "$LINE_KIND" == "GROUPS" ]]; then
  if [[ "$VERBOSE" -eq 1 ]]; then
    mvn -q -Dmaven.test.failure.ignore=true test -Dgroups="$LINE_VALUE" 2>&1 | tee "$MVN_LOG" || MVN_EXIT=$?
  else
    mvn -q -Dmaven.test.failure.ignore=true test -Dgroups="$LINE_VALUE" >"$MVN_LOG" 2>&1 || MVN_EXIT=$?
  fi
elif [[ "$LINE_KIND" == "TESTS" ]]; then
  if [[ "$ALL_THREE" == true ]]; then
    # Full reactor — no -pl
    if [[ "$VERBOSE" -eq 1 ]]; then
      mvn -q -Dmaven.test.failure.ignore=true test -Dtest="$LINE_VALUE" -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$MVN_LOG" || MVN_EXIT=$?
    else
      mvn -q -Dmaven.test.failure.ignore=true test -Dtest="$LINE_VALUE" -Dsurefire.failIfNoSpecifiedTests=false >"$MVN_LOG" 2>&1 || MVN_EXIT=$?
    fi
  else
    # Subset of modules — use -pl -am
    PL_LIST="$(IFS=','; echo "${MODULES[*]}")"
    if [[ "$VERBOSE" -eq 1 ]]; then
      mvn -pl "$PL_LIST" -am -q -Dmaven.test.failure.ignore=true test -Dtest="$LINE_VALUE" -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$MVN_LOG" || MVN_EXIT=$?
    else
      mvn -pl "$PL_LIST" -am -q -Dmaven.test.failure.ignore=true test -Dtest="$LINE_VALUE" -Dsurefire.failIfNoSpecifiedTests=false >"$MVN_LOG" 2>&1 || MVN_EXIT=$?
    fi
  fi
fi

# ── Post-Maven: check for reports ───────────────────────────────────
if [[ "$MVN_EXIT" -ne 0 ]]; then
  FOUND_REPORT=false
  for m in "${MODULES[@]}"; do
    if [[ -d "$ROOT/$m/target/surefire-reports" ]] && ls "$ROOT/$m/target/surefire-reports/TEST-"*.xml &>/dev/null; then
      FOUND_REPORT=true
      break
    fi
  done

  if [[ "$FOUND_REPORT" == false ]]; then
    tail -n 40 "$MVN_LOG"
    echo "build/infra failure — full log: $MVN_LOG"
    exit 2
  fi
fi

# ── Hand off to summarizer ──────────────────────────────────────────
echo "mvn log: $MVN_LOG"

if [[ "$UPDATE_BASELINE" -eq 1 ]]; then
  exec python3 "$HARNESS" summarize --scope "$SCOPE" --update-baseline
else
  exec python3 "$HARNESS" summarize --scope "$SCOPE"
fi