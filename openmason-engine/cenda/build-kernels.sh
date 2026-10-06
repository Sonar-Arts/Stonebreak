#!/usr/bin/env bash
#
# Builds the Cenda native kernels (libcenda_kernels.so) from the CURRENT sources.
#
# Wired into the "Stonebreak" IntelliJ run configuration as a before-launch step
# (.run/Cenda Kernels.run.xml), so launching the game always runs against a lib
# built from the checked-out tree. Also safe to run by hand:
#
#     openmason-engine/cenda/build-kernels.sh [debug|release|asan]
#
# The kernels are OPTIONAL — the Java side falls back to pure Java when the lib is
# missing or fails its ABI handshake. So a missing toolchain or a compile error
# prints a warning and exits 0, leaving you with a (slower) working game rather
# than a blocked launch.
#
# The Lua host (UI scripting, #292) and the Yoga flex host (UI layout, #287) share
# the library but have NO fallback. A Lua/Flex ABI mismatch between the C headers
# and the Java bindings is therefore a blocking error (exit 1): the launch stops
# with the diagnostic instead of migrated UI failing later.

set -uo pipefail

PRESET="${1:-release}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$HERE/build/$PRESET"
case "$(uname -s)" in
  Darwin) LIB_NAME=libcenda_kernels.dylib ;;
  MINGW*|MSYS*|CYGWIN*) LIB_NAME=cenda_kernels.dll ;;
  *) LIB_NAME=libcenda_kernels.so ;;
esac
LIB="$BUILD_DIR/native/kernels/$LIB_NAME"


warn() { echo "[cenda] WARNING: $*" >&2; }

# `cmake --preset` resolves CMakePresets.json against the CWD, so anchor to the
# cenda dir regardless of where the caller (IntelliJ, shell) invoked us from.
cd "$HERE" || { warn "cannot enter $HERE — skipping native kernels."; exit 0; }

if ! command -v cmake >/dev/null 2>&1; then
  warn "cmake not found — skipping native kernels; the game will use the Java fallback path."
  exit 0
fi

# Configure only when the cache is absent; cmake re-configures itself on preset changes.
if [[ ! -f "$BUILD_DIR/CMakeCache.txt" ]]; then
  echo "[cenda] configuring '$PRESET' preset..."
  if ! cmake --preset "$PRESET" >/dev/null; then
    warn "cmake configure failed — skipping native kernels; the game will use the Java fallback path."
    exit 0
  fi
fi

# Incremental: a no-op in well under a second when nothing changed.
if ! cmake --build --preset "$PRESET" --parallel >/dev/null; then
  warn "native kernels failed to build — the game will use the Java fallback path."
  warn "UI scripting and UI layout have no fallback: a stale library from an earlier build may now fail"
  warn "their ABI handshake, and scripted or migrated screens will refuse to open (loudly) until this builds."
  warn "re-run '$0 $PRESET' without redirection, or 'cmake --build --preset $PRESET', to see the errors."
  exit 0
fi

# Multi-config generators (Visual Studio, Xcode) put the library in a config sub-directory.
if [[ ! -f "$LIB" ]]; then
  for CONFIG in Release Debug; do
    [[ -f "$BUILD_DIR/native/kernels/$CONFIG/$LIB_NAME" ]] && LIB="$BUILD_DIR/native/kernels/$CONFIG/$LIB_NAME" && break
  done
fi

if [[ ! -f "$LIB" ]]; then
  warn "build reported success but $LIB is missing — the game will use the Java fallback path."
  exit 0
fi

# Guard the failure mode that silently disables the whole native stack: the C ABI
# version the sources export must match the one the Java FFM binding expects, or
# CendaKernels rejects the library at load time and every native path falls back.
HEADER="$HERE/native/kernels/include/cenda/kernels.h"
BINDING="$HERE/../src/main/java/com/openmason/engine/cenda/CendaKernels.java"
if [[ -f "$HEADER" && -f "$BINDING" ]]; then
  NATIVE_ABI="$(sed -n 's/^#define CK_ABI_VERSION[[:space:]]\+\([0-9]\+\).*/\1/p' "$HEADER" | head -1)"
  JAVA_ABI="$(sed -n 's/.*EXPECTED_ABI[[:space:]]*=[[:space:]]*\([0-9]\+\).*/\1/p' "$BINDING" | head -1)"
  if [[ -n "$NATIVE_ABI" && -n "$JAVA_ABI" && "$NATIVE_ABI" != "$JAVA_ABI" ]]; then
    warn "ABI mismatch: kernels.h exports $NATIVE_ABI but CendaKernels.EXPECTED_ABI is $JAVA_ABI."
    warn "CendaKernels will REJECT the library and every native path (noise, fused chunk gen,"
    warn "mesher, carver, zstd codec) will silently fall back to Java. Fix one side to match."
    exit 0
  fi
fi

# Same guard for the Lua host (#283/#292), which shares the library but has its own
# handshake. UI scripting has no Java fallback, so a mismatch here means scripted UI
# documents fail to load — CendaLua throws with this same diagnostic at startup.
LUA_HEADER="$HERE/native/kernels/include/cenda/lua_host.h"
LUA_BINDING="$HERE/../src/main/java/com/openmason/engine/cenda/CendaLua.java"
if [[ -f "$LUA_HEADER" && -f "$LUA_BINDING" ]]; then
  NATIVE_CL="$(sed -n 's/^#define CL_ABI_VERSION[[:space:]]\+\([0-9]\+\).*/\1/p' "$LUA_HEADER" | head -1)"
  JAVA_CL="$(sed -n 's/.*EXPECTED_ABI[[:space:]]*=[[:space:]]*\([0-9]\+\).*/\1/p' "$LUA_BINDING" | head -1)"
  if [[ -n "$NATIVE_CL" && -n "$JAVA_CL" && "$NATIVE_CL" != "$JAVA_CL" ]]; then
    echo "[cenda] ERROR: Lua host ABI mismatch: lua_host.h exports $NATIVE_CL but CendaLua.EXPECTED_ABI is $JAVA_CL." >&2
    echo "[cenda] ERROR: UI scripting has no fallback; fix one side to match. Launch stopped." >&2
    exit 1
  fi
fi

# And for the retained Yoga tree (#287): migrated UI has no Java layout fallback either.
FLEX_HEADER="$HERE/native/kernels/include/cenda/flex.h"
FLEX_BINDING="$HERE/../src/main/java/com/openmason/engine/cenda/CendaFlex.java"
if [[ -f "$FLEX_HEADER" && -f "$FLEX_BINDING" ]]; then
  NATIVE_CF="$(sed -n 's/^#define CF_ABI_VERSION[[:space:]]\+\([0-9]\+\).*/\1/p' "$FLEX_HEADER" | head -1)"
  JAVA_CF="$(sed -n 's/.*EXPECTED_ABI[[:space:]]*=[[:space:]]*\([0-9]\+\).*/\1/p' "$FLEX_BINDING" | head -1)"
  if [[ -n "$NATIVE_CF" && -n "$JAVA_CF" && "$NATIVE_CF" != "$JAVA_CF" ]]; then
    echo "[cenda] ERROR: Flex ABI mismatch: flex.h exports $NATIVE_CF but CendaFlex.EXPECTED_ABI is $JAVA_CF." >&2
    echo "[cenda] ERROR: UI layout has no fallback; fix one side to match. Launch stopped." >&2
    exit 1
  fi
fi

echo "[cenda] kernels ready: $LIB"
