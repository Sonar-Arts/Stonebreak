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
# missing or fails its ABI handshake. So this script NEVER fails the build: a
# missing toolchain or a compile error prints a warning and exits 0, leaving you
# with a (slower) working game rather than a blocked launch.

set -uo pipefail

PRESET="${1:-release}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$HERE/build/$PRESET"
LIB="$BUILD_DIR/native/kernels/$( [[ "$(uname -s)" == "Darwin" ]] && echo libcenda_kernels.dylib || echo libcenda_kernels.so )"

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
  warn "re-run '$0 $PRESET' without redirection, or 'cmake --build --preset $PRESET', to see the errors."
  exit 0
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

echo "[cenda] kernels ready: $LIB"
