#!/usr/bin/env bash
#
# Sets up the toolchain the Cenda native library needs, then builds it.
#
#     openmason-engine/cenda/setup-cenda.sh [options] [debug|release|asan]
#
#     --check     report what is installed and what is missing; install and build nothing
#     --yes       install missing tools without asking
#     --no-build  install missing tools but do not build
#
# Requirements: CMake >= 3.25 (presets schema v6), git (FetchContent clones FastNoise2, zstd, Lua
# and Yoga), and a C++23 compiler: Visual Studio 2022 Build Tools (MSVC 17+) on Windows, GCC or
# Clang elsewhere. Missing tools are installed with the platform's package manager (winget,
# Homebrew, apt/dnf/pacman), asking first unless --yes.
#
# Unlike build-kernels.sh (the IntelliJ before-launch step, which warns and lets the game fall
# back to Java), this script fails loudly: it exists to get a working library onto the machine.
# It builds with full compiler output, then runs build-kernels.sh for its ABI guards.

set -euo pipefail

MIN_CMAKE=3.25
PRESET=release
CHECK_ONLY=0
ASSUME_YES=0
BUILD=1

for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    --yes|-y) ASSUME_YES=1 ;;
    --no-build) BUILD=0 ;;
    debug|release|asan) PRESET="$arg" ;;
    -h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$(uname -s)" in
  Darwin) PLATFORM=macos ;;
  MINGW*|MSYS*|CYGWIN*) PLATFORM=windows ;;
  Linux) PLATFORM=linux ;;
  *) echo "[cenda] unsupported platform: $(uname -s)" >&2; exit 1 ;;
esac

info() { echo "[cenda] $*"; }
ok() { echo "[cenda]   ok       $*"; }
miss() { echo "[cenda]   MISSING  $*"; }
die() { echo "[cenda] ERROR: $*" >&2; exit 1; }

version_ge() { [[ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -1)" == "$2" ]]; }

confirm() {
  (( ASSUME_YES )) && return 0
  [[ -t 0 ]] || die "$1 needs confirmation; re-run with --yes from a non-interactive shell."
  read -r -p "[cenda] $1 [y/N] " reply
  [[ "$reply" =~ ^[Yy] ]]
}

# --- tool discovery --------------------------------------------------------------------------

# A fresh install is not on this shell's PATH yet, so also look where the installers put cmake.
find_cmake() {
  local c
  for c in "$(command -v cmake 2>/dev/null || true)" \
           "/c/Program Files/CMake/bin/cmake.exe" \
           "/opt/homebrew/bin/cmake" "/usr/local/bin/cmake" "/snap/bin/cmake"; do
    [[ -n "$c" && -x "$c" ]] || continue
    if version_ge "$("$c" --version | head -1 | sed 's/[^0-9.]*\([0-9][0-9.]*\).*/\1/')" "$MIN_CMAKE"; then
      echo "$c"; return 0
    fi
  done
  return 1
}

VSWHERE="/c/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"

# Visual Studio 2022+ with the x64 C++ tools; CMake's default generator picks the newest install.
find_msvc() {
  [[ -x "$VSWHERE" ]] || return 1
  local path
  path="$("$VSWHERE" -latest -products '*' -version '[17.0,' \
    -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | tr -d '\r')"
  [[ -n "$path" ]] && echo "$path"
}

find_cxx() {
  case "$PLATFORM" in
    windows) find_msvc ;;
    *) command -v c++ || command -v g++ || command -v clang++ ;;
  esac
}

LINUX_PM=""
if [[ "$PLATFORM" == linux ]]; then
  for pm in apt-get dnf pacman zypper; do
    command -v "$pm" >/dev/null 2>&1 && { LINUX_PM="$pm"; break; }
  done
fi

# --- installers ------------------------------------------------------------------------------

linux_install() {
  case "$LINUX_PM" in
    apt-get) sudo apt-get update -q && sudo apt-get install -y "$@" ;;
    dnf) sudo dnf install -y "$@" ;;
    pacman) sudo pacman -S --needed --noconfirm "$@" ;;
    zypper) sudo zypper install -y "$@" ;;
    *) die "no supported package manager; install manually: $*" ;;
  esac
}

need_brew() {
  command -v brew >/dev/null 2>&1 || die "Homebrew not found; install it from https://brew.sh, then re-run."
}

need_winget() {
  command -v winget >/dev/null 2>&1 || command -v winget.exe >/dev/null 2>&1 \
    || die "winget not found; install 'App Installer' from the Microsoft Store, then re-run."
}

install_cmake() {
  case "$PLATFORM" in
    windows) need_winget; winget install --id Kitware.CMake -e --accept-source-agreements --accept-package-agreements ;;
    macos) need_brew; brew install cmake ;;
    linux)
      linux_install cmake
      # LTS distros can lag (Ubuntu 22.04 ships 3.22); Kitware's snap is always current.
      if ! find_cmake >/dev/null && command -v snap >/dev/null 2>&1; then
        info "distro cmake is older than $MIN_CMAKE; installing the Kitware snap."
        sudo snap install cmake --classic
      fi ;;
  esac
}

install_git() {
  case "$PLATFORM" in
    windows) need_winget; winget install --id Git.Git -e --accept-source-agreements --accept-package-agreements ;;
    macos) xcode-select --install || true; die "finish the Command Line Tools install dialog, then re-run." ;;
    linux) linux_install git ;;
  esac
}

install_cxx() {
  case "$PLATFORM" in
    windows)
      need_winget
      info "installing Visual Studio 2022 Build Tools (C++ workload, several GB; accept the UAC prompt)..."
      winget install --id Microsoft.VisualStudio.2022.BuildTools -e \
        --accept-source-agreements --accept-package-agreements \
        --override "--quiet --wait --norestart --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended" ;;
    macos) xcode-select --install || true; die "finish the Command Line Tools install dialog, then re-run." ;;
    linux)
      case "$LINUX_PM" in
        apt-get) linux_install build-essential ;;
        dnf) linux_install gcc-c++ make ;;
        pacman) linux_install base-devel ;;
        zypper) linux_install gcc-c++ make ;;
        *) die "no supported package manager; install a C++23 compiler (GCC 11+ / Clang 17+) manually." ;;
      esac ;;
  esac
}

# --- check -----------------------------------------------------------------------------------

describe() {
  local missing=0 v
  info "toolchain ($PLATFORM):"
  if CMAKE="$(find_cmake)"; then ok "cmake $("$CMAKE" --version | head -1 | awk '{print $3}') ($CMAKE)"
  else miss "cmake >= $MIN_CMAKE"; missing=1; fi
  if command -v git >/dev/null 2>&1; then ok "$(git --version)"
  else miss "git"; missing=1; fi
  if v="$(find_cxx)"; then ok "C++ compiler ($v)"
  elif [[ "$PLATFORM" == windows ]]; then miss "Visual Studio 2022 Build Tools with the C++ workload"; missing=1
  else miss "C++ compiler (GCC 11+ or Clang 17+)"; missing=1; fi
  return $missing
}

if describe; then
  TOOLS_OK=1
else
  TOOLS_OK=0
fi

if (( CHECK_ONLY )); then
  (( TOOLS_OK )) && info "everything needed to build Cenda is installed." && exit 0
  info "run without --check to install what is missing."
  exit 1
fi

# --- install ---------------------------------------------------------------------------------

if (( ! TOOLS_OK )); then
  find_cmake >/dev/null || { confirm "install CMake?" && install_cmake || die "CMake >= $MIN_CMAKE is required."; }
  command -v git >/dev/null 2>&1 || { confirm "install git?" && install_git || die "git is required."; }
  find_cxx >/dev/null || { confirm "install a C++ compiler?" && install_cxx || die "a C++23 compiler is required."; }
  echo
  describe || die "tools are still missing after install (a new shell may be needed for PATH); see above."
fi

CMAKE="$(find_cmake)"
(( BUILD )) || { info "toolchain ready; skipping build (--no-build)."; exit 0; }

# --- build -----------------------------------------------------------------------------------

cd "$HERE"
# The presets cache the dependency clones under $HOME/.cache, outside the checkout.
[[ -n "${HOME:-}" ]] || die "HOME is not set; the presets keep their dependency cache under it."

info "configuring '$PRESET'..."
"$CMAKE" --preset "$PRESET"
info "building '$PRESET'..."
"$CMAKE" --build --preset "$PRESET" --parallel

# build-kernels.sh is now an incremental no-op; run it for the ABI guards and the final path.
CMAKE_BIN="$CMAKE" "$HERE/build-kernels.sh" "$PRESET"
