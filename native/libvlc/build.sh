#!/usr/bin/env bash
#
# Fetches the official LibVLC runtime (libvlc, libvlccore, plugins) from
# VideoLAN's pre-built distribution archives for all 6 target platforms.
#
# This replaces the earlier "build from source" approach — cross-compiling
# VLC against modern FFmpeg (7.x/8.x) was causing API incompatibilities.
# Instead, we use the same strategy as VideoPlayer-Library: download the
# official VideoLAN binaries and verify their completeness.
#
# Usage:
#   build.sh <Linux|Windows|Mac> <x86_64|aarch64> [vlc_version]
#
# Source of truth per platform:
#   Linux x86_64 / aarch64  → Flathub flatpak org.videolan.VLC //stable
#   Windows x86_64          → get.videolan.org win64 .zip
#   Windows aarch64         → MSYS2 mingw-w64-clang-aarch64-vlc
#   Mac x86_64              → get.videolan.org intel64 .dmg
#   Mac aarch64             → get.videolan.org arm64 .dmg
#
# Environment:
#   OUT_DIR      output directory (default $PWD/out)
set -euo pipefail

OS_NAME="${1:-}"
OS_ARCH="${2:-}"
VLC_VER="${3:-3.0.21}"

if [[ -z "$OS_NAME" || -z "$OS_ARCH" ]]; then
  echo "usage: build.sh <Linux|Windows|Mac> <x86_64|aarch64> [vlc_version]" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$PWD/out}"
OUT_LIBDIR="$OUT_DIR/$OS_NAME/$OS_ARCH"
mkdir -p "$OUT_LIBDIR"

echo ">>> Fetching LibVLC $VLC_VER for $OS_NAME/$OS_ARCH"

# Helper: require a file exists in the output dir
require_file() {
  local label="$1"
  local pattern="$2"
  local dir="${3:-$OUT_LIBDIR}"
  if ! find "$dir" -maxdepth 1 -type f -iname "$pattern" -print -quit | grep -q .; then
    echo "ERROR: missing $label (pattern $pattern) in $dir" >&2
    exit 1
  fi
  echo "  ✓ $label: $(find "$dir" -maxdepth 1 -type f -iname "$pattern" -exec basename {} \;)"
}

# Helper: require a plugin exists
require_plugin() {
  local label="$1"
  local pattern="$2"
  if ! find "$OUT_LIBDIR/plugins" -type f -iname "$pattern" -print -quit | grep -q .; then
    echo "ERROR: missing plugin $label (pattern $pattern)" >&2
    exit 1
  fi
  echo "  ✓ plugin $label"
}

# ── Fetch and extract per platform ────────────────────────────────────────
case "$OS_NAME" in
  Linux)
    echo ">>> Installing flatpak (if needed)"
    sudo apt-get update -qq
    sudo apt-get install -y -qq flatpak 2>/dev/null || true

    echo ">>> Adding Flathub remote"
    flatpak --user remote-add --if-not-exists flathub \
      https://flathub.org/repo/flathub.flatpakrepo

    echo ">>> Installing VLC from Flathub (stable)"
    flatpak --user install -y --noninteractive flathub org.videolan.VLC//stable

    location="$(flatpak --user info --show-location org.videolan.VLC)"
    echo "  Flatpak location: $location"

    # Copy libvlc.so* and libvlccore.so* from the bundle
    shopt -s nullglob
    cp -a "$location/files/lib"/libvlc*.so* "$OUT_LIBDIR/"
    shopt -u nullglob

    # Copy plugins
    if [[ -d "$location/files/lib/vlc/plugins" ]]; then
      cp -a "$location/files/lib/vlc/plugins" "$OUT_LIBDIR/plugins"
    elif [[ -d "$location/files/plugins" ]]; then
      cp -a "$location/files/plugins" "$OUT_LIBDIR/plugins"
    else
      echo "ERROR: no VLC plugins directory found in flatpak" >&2
      exit 1
    fi
    ;;

  Windows)
    echo ">>> Installing MSYS2 VLC package ($OS_ARCH)"
    if [[ "$OS_ARCH" == "aarch64" ]]; then
      MINGW_PREFIX="mingw-w64-clang-aarch64"
      VLC_LIBDIR="/clangarm64"
    else
      MINGW_PREFIX="mingw-w64-x86_64"
      VLC_LIBDIR="/mingw64"
    fi

    # Install the official MSYS2 VLC package (pre-built, already compiled
    # against a compatible FFmpeg version for the mingw environment).
    pacman -Sy --noconfirm --needed
    pacman -S --noconfirm --needed ${MINGW_PREFIX}-vlc

    # Copy runtime DLLs
    cp -a "$VLC_LIBDIR/bin"/libvlc*.dll "$OUT_LIBDIR/" 2>/dev/null || true

    # Copy plugins
    if [[ -d "$VLC_LIBDIR/lib/vlc/plugins" ]]; then
      cp -a "$VLC_LIBDIR/lib/vlc/plugins" "$OUT_LIBDIR/plugins"
    elif [[ -d "$VLC_LIBDIR/plugins" ]]; then
      cp -a "$VLC_LIBDIR/plugins" "$OUT_LIBDIR/plugins"
    else
      echo "ERROR: no VLC plugins directory found at $VLC_LIBDIR" >&2
      ls -la "$VLC_LIBDIR/lib/vlc/" 2>/dev/null || true
      exit 1
    fi
    ;;

  Mac)
    # macOS: download the appropriate architecture-specific DMG
    if [[ "$OS_ARCH" == "aarch64" ]]; then
      DMG_URL="https://get.videolan.org/vlc/$VLC_VER/macosx/vlc-$VLC_VER-arm64.dmg"
    else
      DMG_URL="https://get.videolan.org/vlc/$VLC_VER/macosx/vlc-$VLC_VER-intel64.dmg"
    fi
    DMG_FILE="vlc-$VLC_VER.dmg"
    echo ">>> Downloading $DMG_URL"
    curl -fL --retry 3 --retry-all-errors -o "$DMG_FILE" "$DMG_URL"

    echo ">>> Mounting DMG"
    MOUNT_POINT="/Volumes/vlc-install"
    hdiutil attach "$DMG_FILE" -nobrowse -mountpoint "$MOUNT_POINT"

    # VLC.app is at the root of the DMG
    VLC_APP="$MOUNT_POINT/VLC.app/Contents/MacOS"
    if [[ ! -d "$VLC_APP/lib" ]]; then
      # Alternative path: VLC.app/Contents/Frameworks
      VLC_APP="$MOUNT_POINT/VLC.app/Contents/Frameworks"
    fi

    cp -a "$VLC_APP/lib"/libvlc*.dylib "$OUT_LIBDIR/" 2>/dev/null || true
    if [[ -d "$VLC_APP/plugins" ]]; then
      cp -a "$VLC_APP/plugins" "$OUT_LIBDIR/plugins"
    else
      # Trying the MacOS/lib path as fallback
      cp -a "$MOUNT_POINT/VLC.app/Contents/MacOS/plugins" "$OUT_LIBDIR/plugins" 2>/dev/null || true
    fi
    hdiutil detach "$MOUNT_POINT" -quiet 2>/dev/null || true
    rm -f "$DMG_FILE"
    ;;

  *)
    echo "ERROR: unsupported os $OS_NAME" >&2
    exit 2
    ;;
esac

# ── Verify completeness (inspired by VideoPlayer-Library) ──────────────────
echo ">>> Verifying LibVLC runtime completeness"

# Core libraries
case "$OS_NAME" in
  Linux)
    require_file "libvlc.so"     "libvlc.so*"
    require_file "libvlccore.so" "libvlccore.so*"
    ;;
  Windows)
    require_file "libvlc.dll"     "libvlc*.dll"
    require_file "libvlccore.dll" "libvlccore*.dll"
    ;;
  Mac)
    require_file "libvlc.dylib"     "libvlc*.dylib"
    require_file "libvlccore.dylib" "libvlccore*.dylib"
    ;;
esac

# Plugin directory
PLUGIN_COUNT="$(find "$OUT_LIBDIR/plugins" -type f 2>/dev/null | wc -l)"
echo "  Plugin count: $PLUGIN_COUNT"
if [[ "$PLUGIN_COUNT" -lt 250 ]]; then
  echo "WARNING: low plugin count ($PLUGIN_COUNT); expected ≥250" >&2
fi

# Key playback plugins (video player mod requirements)
require_plugin "avcodec"         "*avcodec*plugin*"
require_plugin "mkv"             "*mkv*plugin*"
require_plugin "mp4"             "*mp4*plugin*"
require_plugin "packetizer_h264" "*packetizer_h264*plugin*"
require_plugin "packetizer_hevc" "*packetizer_hevc*plugin*"
require_plugin "http"            "*http*plugin*"
require_plugin "freetype"        "*freetype*plugin*"

echo ">>> Collected files:"
find "$OUT_LIBDIR" -maxdepth 1 -type f | sort
echo "  Plugins: $(find "$OUT_LIBDIR/plugins" -type f 2>/dev/null | wc -l) files"
echo ">>> Done fetching LibVLC $VLC_VER for $OS_NAME/$OS_ARCH"