#!/usr/bin/env bash
#
# Builds Open Mason (Sonar-Arts/OpenMason: the engine modules, the tool and the Cenda SDK) on this
# machine and installs it into the local Maven repository (~/.m2), so Stonebreak builds without a
# GitHub Packages token and runs against natives built here (#388).
#
#   scripts/openmason-local.sh
#       What the root pom.xml's <openmason.version> asks for. A -SNAPSHOT (Stonebreak follows
#       OpenMason main for now) builds the newest OpenMason main; a release X.Y.Z builds tag vX.Y.Z.
#       The source is kept in ~/.cache/stonebreak/openmason/ and installed at exactly that version,
#       Cenda (release preset) included. Stonebreak then builds as usual.
#
#   scripts/openmason-local.sh --checkout <dir> [--use]
#       The cross-repo dev loop: builds and installs YOUR OpenMason checkout, natives included, as
#       version 0.0.0-local-SNAPSHOT, a version nothing on GitHub Packages publishes, so a downloaded
#       snapshot can never replace your build. --use also writes -Dopenmason.version=0.0.0-local-SNAPSHOT
#       to .mvn/maven.config (gitignored), which both the Maven CLI and IntelliJ read, so Stonebreak
#       builds against it. Re-run after every engine change you want the game to see (Java, natives,
#       or the SDK's FastNoise2 pin / noise_nodes.hpp).
#
#   scripts/openmason-local.sh --release
#       Removes the .mvn/maven.config override: back to the version in pom.xml.
#
# Options: --skip-natives (Java only; the engine-core jar then carries no Cenda library, so UI
# scripting and layout fail loudly), --tests (run OpenMason's own tests while installing).
# Env: OPENMASON_GIT_URL (default https://github.com/Sonar-Arts/OpenMason.git).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CONFIG="$ROOT/.mvn/maven.config"
LOCAL_VERSION="0.0.0-local-SNAPSHOT"  # JPMS module versions must start with a digit

usage() { sed -n '3,27p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2; }
die() { echo "[openmason] ERROR: $*" >&2; exit 1; }
say() { echo "[openmason] $*"; }

CHECKOUT=""
USE=0
RELEASE=0
NATIVES=1
TESTS=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --checkout) [[ $# -ge 2 ]] || usage; CHECKOUT="$2"; shift 2 ;;
    --checkout=*) CHECKOUT="${1#--checkout=}"; shift ;;
    --use) USE=1; shift ;;
    --release) RELEASE=1; shift ;;
    --skip-natives) NATIVES=0; shift ;;
    --tests) TESTS=1; shift ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done

# The <tag>value</tag> of the first match in a POM (no Maven call: this runs before anything resolves).
pom_value() { sed -n "s:.*<$2>\([^<]*\)</$2>.*:\1:p" "$1" | head -n 1; }

PINNED="$(pom_value "$ROOT/pom.xml" openmason.version)"
[[ -n "$PINNED" ]] || die "no <openmason.version> in $ROOT/pom.xml"

if [[ "$RELEASE" -eq 1 ]]; then
  [[ -z "$CHECKOUT" && "$USE" -eq 0 ]] || die "--release takes no other options"
  if [[ -f "$CONFIG" ]]; then
    rm -f "$CONFIG"
    say "removed .mvn/maven.config: Stonebreak builds against $PINNED (pom.xml) again (reload Maven in IntelliJ)."
  else
    say "no .mvn/maven.config override present."
  fi
  exit 0
fi
[[ "$USE" -eq 0 || -n "$CHECKOUT" ]] || die "--use needs --checkout <dir>"

# A JDK 25 for Maven (same resolution order as Testing/run-tests.sh).
for cand in "${STONEBREAK_JDK:-}" "$HOME/.jdks/ms-25.0.3" "${JAVA_HOME:-}"; do
  if [[ -n "$cand" && -x "$cand/bin/java" ]] && "$cand/bin/java" -version 2>&1 | grep -q 'version "25'; then
    export JAVA_HOME="$cand" PATH="$cand/bin:$PATH"
    break
  fi
done
command -v mvn >/dev/null || die "mvn not found"

if [[ -n "$CHECKOUT" ]]; then
  SRC="$(cd "$CHECKOUT" && pwd)" || die "no such directory: $CHECKOUT"
  [[ -f "$SRC/openmason-engine/cenda/CMakeLists.txt" ]] || die "$SRC is not an OpenMason checkout"
  VERSION="$LOCAL_VERSION"
  # The checkout's own presets: its build/ and deps cache are the developer's, kept across runs.
  CMAKE_DEPS_ARGS=()
else
  VERSION="$PINNED"
  CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/stonebreak/openmason"
  URL="${OPENMASON_GIT_URL:-https://github.com/Sonar-Arts/OpenMason.git}"
  if [[ "$VERSION" == *-SNAPSHOT ]]; then
    # Following main: refresh to its newest commit on every run.
    SRC="$CACHE/main"
    if [[ ! -d "$SRC/.git" ]]; then
      say "cloning $URL (main) into $SRC"
      mkdir -p "$CACHE"
      git clone --quiet --depth 1 --branch main "$URL" "$SRC" || die "could not clone main from $URL"
    else
      say "updating $SRC to the newest main"
      git -C "$SRC" fetch --quiet --depth 1 origin main || die "could not fetch main from $URL"
      git -C "$SRC" reset --quiet --hard FETCH_HEAD
    fi
    MAIN_REVISION="$(pom_value "$SRC/pom.xml" revision)"
    if [[ "$MAIN_REVISION" != "$VERSION" ]]; then
      say "note: OpenMason main is $MAIN_REVISION now; installing it as $VERSION (what pom.xml asks for)."
      say "      update <openmason.version> in pom.xml to $MAIN_REVISION to match what CI downloads."
    fi
    DEPS_KEY="main"
  else
    SRC="$CACHE/$VERSION"
    if [[ ! -d "$SRC/.git" ]]; then
      say "cloning $URL at v$VERSION into $SRC"
      mkdir -p "$CACHE"
      git clone --quiet --depth 1 --branch "v$VERSION" "$URL" "$SRC" \
        || die "could not clone tag v$VERSION from $URL"
    fi
    DEPS_KEY="$VERSION"
  fi
  # Own deps dir: FetchContent build trees remember their source path, so sharing them with another
  # checkout breaks whichever one built first (see the Cenda presets).
  CMAKE_DEPS_ARGS=("-DFETCHCONTENT_BASE_DIR=$CACHE/cenda-deps-$DEPS_KEY" -DBUILD_TESTING=OFF)
fi

say "Open Mason $VERSION from $SRC ($(git -C "$SRC" rev-parse --short HEAD 2>/dev/null || echo 'not a git checkout'))"

if [[ "$NATIVES" -eq 1 ]]; then
  command -v cmake >/dev/null || die "cmake not found (run $SRC/openmason-engine/cenda/setup-cenda.sh, or pass --skip-natives)"
  say "building Cenda (release)..."
  (
    cd "$SRC/openmason-engine/cenda"
    cmake --preset release ${CMAKE_DEPS_ARGS[@]+"${CMAKE_DEPS_ARGS[@]}"} >/dev/null
    cmake --build --preset release --parallel >/dev/null
  ) || die "Cenda failed to build (re-run cmake --build --preset release in $SRC/openmason-engine/cenda to see why)"
fi

MVN_ARGS=(-B -q -ntp install "-Drevision=$VERSION")
[[ "$TESTS" -eq 1 ]] || MVN_ARGS+=(-DskipTests)
say "installing into the local Maven repository (mvn ${MVN_ARGS[*]})..."
(cd "$SRC" && mvn "${MVN_ARGS[@]}") || die "the Open Mason build failed"

if [[ "$NATIVES" -eq 1 ]]; then
  jar="$HOME/.m2/repository/com/openmason/openmason-engine-core/$VERSION/openmason-engine-core-$VERSION.jar"
  if [[ -f "$jar" ]] && ! jar tf "$jar" | grep -q '^natives/.*cenda_kernels'; then
    die "$jar carries no Cenda library; check the Cenda build output in $SRC/openmason-engine/cenda/build/release"
  fi
fi

if [[ "$USE" -eq 1 ]]; then
  printf -- '-Dopenmason.version=%s\n' "$VERSION" > "$CONFIG"
  say "wrote .mvn/maven.config: Stonebreak now builds against your checkout ($VERSION; reload Maven in IntelliJ)."
  say "back to $PINNED (pom.xml): scripts/openmason-local.sh --release"
elif [[ -n "$CHECKOUT" ]]; then
  say "installed your checkout as $VERSION. Add --use (or build with -Dopenmason.version=$VERSION) to use it."
else
  say "installed $VERSION (what pom.xml asks for)."
fi
