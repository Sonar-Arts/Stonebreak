#!/usr/bin/env bash
#
# Builds Stonebreak's own native library (libstonebreak_worldgen: the worm carver and the fused
# chunk generator, #386) from the CURRENT sources.
#
# Wired into the "Stonebreak" IntelliJ run configuration as a before-launch step (the game POM's
# exec:exec@worldgen-natives), next to the engine's cenda/build-kernels.sh, so launching the game
# always runs against a lib built from the checked-out tree. Also safe to run by hand:
#
#     stonebreak-game/native/build-worldgen.sh [debug|release|asan]
#
# The library is OPTIONAL — WorldgenNatives falls back to the Java carver and the legacy chunk
# path when it is missing or fails its ABI handshake. So a missing toolchain, a compile error or
# an ABI mismatch prints a warning and exits 0: a (slower) working game, never a blocked launch.

set -uo pipefail

PRESET="${1:-release}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$HERE/build/$PRESET"
case "$(uname -s)" in
  Darwin) LIB_NAME=libstonebreak_worldgen.dylib ;;
  MINGW*|MSYS*|CYGWIN*) LIB_NAME=stonebreak_worldgen.dll ;;
  *) LIB_NAME=libstonebreak_worldgen.so ;;
esac
LIB="$BUILD_DIR/$LIB_NAME"

warn() { echo "[worldgen] WARNING: $*" >&2; }

# `cmake --preset` resolves CMakePresets.json against the CWD.
cd "$HERE" || { warn "cannot enter $HERE — skipping worldgen natives."; exit 0; }

CMAKE="${CMAKE_BIN:-}"
if [[ -z "$CMAKE" ]]; then
  for c in "$(command -v cmake 2>/dev/null || true)" \
           "/c/Program Files/CMake/bin/cmake.exe" \
           "/opt/homebrew/bin/cmake" "/usr/local/bin/cmake" "/snap/bin/cmake"; do
    [[ -n "$c" && -x "$c" ]] && CMAKE="$c" && break
  done
fi
if [[ -z "$CMAKE" ]]; then
  warn "cmake not found — skipping worldgen natives; chunks generate on the Java path."
  warn "run 'openmason-engine/cenda/setup-cenda.sh' to install the toolchain."
  exit 0
fi

if [[ ! -f "$BUILD_DIR/CMakeCache.txt" ]]; then
  echo "[worldgen] configuring '$PRESET' preset..."
  if ! "$CMAKE" --preset "$PRESET" >/dev/null; then
    warn "cmake configure failed — skipping worldgen natives; chunks generate on the Java path."
    exit 0
  fi
fi

# Incremental: a no-op in well under a second when nothing changed.
if ! "$CMAKE" --build --preset "$PRESET" --parallel >/dev/null; then
  warn "worldgen natives failed to build — chunks generate on the Java path."
  warn "re-run '$0 $PRESET' without redirection, or 'cmake --build --preset $PRESET', to see the errors."
  exit 0
fi

if [[ ! -f "$LIB" ]]; then
  for CONFIG in Release Debug; do
    [[ -f "$BUILD_DIR/$CONFIG/$LIB_NAME" ]] && LIB="$BUILD_DIR/$CONFIG/$LIB_NAME" && break
  done
fi
if [[ ! -f "$LIB" ]]; then
  warn "build reported success but $LIB is missing — chunks generate on the Java path."
  exit 0
fi

# The failure mode that silently disables the native generator: the ABI the sources export must
# match the one WorldgenNatives expects, or the library is rejected at load time.
HEADER="$HERE/include/stonebreak/worldgen.h"
BINDING="$HERE/../src/main/java/com/stonebreak/world/generation/WorldgenNatives.java"
if [[ -f "$HEADER" && -f "$BINDING" ]]; then
  NATIVE_ABI="$(sed -n 's/^#define SBW_ABI_VERSION[[:space:]]\+\([0-9]\+\).*/\1/p' "$HEADER" | head -1)"
  JAVA_ABI="$(sed -n 's/.*EXPECTED_ABI[[:space:]]*=[[:space:]]*\([0-9]\+\).*/\1/p' "$BINDING" | head -1)"
  if [[ -n "$NATIVE_ABI" && -n "$JAVA_ABI" && "$NATIVE_ABI" != "$JAVA_ABI" ]]; then
    warn "ABI mismatch: worldgen.h exports $NATIVE_ABI but WorldgenNatives.EXPECTED_ABI is $JAVA_ABI."
    warn "WorldgenNatives will REJECT the library; worm carving and chunk generation fall back to Java."
    exit 0
  fi
fi

echo "[worldgen] natives ready: $LIB"
