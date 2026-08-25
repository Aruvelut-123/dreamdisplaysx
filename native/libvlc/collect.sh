#!/usr/bin/env bash
#
# Collects the official LibVLC runtime libraries (libvlc, libvlccore, plugins)
# from the system package manager or official VideoLAN distribution and packages
# them into the native bundle format used by the shadow jar build.
#
# The output mirrors the sqlite-natives bundle structure:
#   $OUT_DIR/<os>/<arch>/
#     libvlc.dll / libvlc.so / libvlc.dylib
#     libvlccore.dll / libvlccore.so / libvlccore.dylib
#     plugins/            (codec/access/... modules)
#     plugins.dat
#
# Usage:
#   collect.sh <Linux|Windows|Mac> <x86_64|aarch64> [vlc_version]
#     os   : Linux | Windows | Mac
#     arch : x86_64 | aarch64
#   Environment:
#     OUT_DIR  output directory (default $PWD/out)
#     VLC_DIR  optional path to an existing VLC installation (bypasses auto-install)
#
# On Linux:  installs libvlc-dev from apt (official Ubuntu repos)
# On Windows: downloads official VLC from videolan.org (and extracts libvlc DLLs)
# On Mac:     installs VLC from Homebrew (official formula)
set -euo pipefail

OS_NAME="${1:-}"
OS_ARCH="${2:-}"
VLC_VER="${3:-3.0.21}"

if [[ -z "$OS_NAME" || -z "$OS_ARCH" ]]; then
  echo "usage: collect.sh <Linux|Windows|Mac> <x86_64|aarch64> [vlc_version]" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$PWD/out}"
VLC_DIR="${VLC_DIR:-}"

OUT_LIBDIR="$OUT_DIR/$OS_NAME/$OS_ARCH"
mkdir -p "$OUT_LIBDIR"

echo ">> Collecting LibVLC $VLC_VER for $OS_NAME/$OS_ARCH"

case "$OS_NAME" in
  Linux)
    if [[ -z "$VLC_DIR" ]]; then
      echo ">> Installing libvlc-dev from apt (official Ubuntu repos)"
      apt-get update -qq
      apt-get install -y -qq libvlc-dev libvlccore-dev vlc-plugin-base
      # libvlc.so / libvlccore.so are installed to /usr/lib/<triple>/
      VLC_DIR="$(dpkg -L libvlc-dev 2>/dev/null \
        | grep -E 'libvlc\.so$' | head -1 | xargs dirname)"
      if [[ -z "$VLC_DIR" ]]; then
        VLC_DIR="/usr/lib/$(dpkg-architecture -q DEB_HOST_MULTIARCH 2>/dev/null || echo x86_64-linux-gnu)"
      fi
      echo ">> VLC libraries found in $VLC_DIR"
    fi

    # Copy libvlc and libvlccore
    find "$VLC_DIR" -maxdepth 1 -name 'libvlc.so*' -exec cp -L {} "$OUT_LIBDIR/" \;
    find "$VLC_DIR" -maxdepth 1 -name 'libvlccore.so*' -exec cp -L {} "$OUT_LIBDIR/" \;

    # Copy plugins (from the vlc-plugin-base package)
    PLUGIN_DIRS=(
      "$VLC_DIR/vlc/plugins"
      "/usr/lib/vlc/plugins"
      "/usr/lib64/vlc/plugins"
    )
    for pdir in "${PLUGIN_DIRS[@]}"; do
      if [[ -d "$pdir" ]]; then
        echo ">> Copying plugins from $pdir"
        mkdir -p "$OUT_LIBDIR/plugins"
        cp -rL "$pdir/"* "$OUT_LIBDIR/plugins/"
        break
      fi
    done
    ;;

  Windows)
    if [[ -z "$VLC_DIR" ]]; then
      # Download official VLC from VideoLAN's download server
      ARCH_SUFFIX="win64"
      if [[ "$OS_ARCH" == "aarch64" ]]; then
        ARCH_SUFFIX="win64-aarch64"
      fi
      VLC_ZIP_URL="https://get.videolan.org/vlc/$VLC_VER/$ARCH_SUFFIX/vlc-$VLC_VER-$ARCH_SUFFIX.7z"
      echo ">> Downloading official VLC from $VLC_ZIP_URL"
      # Use curl with retries; fallback to download.videolan.org if get.videolan.org fails
      TMP_7Z="$(mktemp -d)/vlc.7z"
      if ! curl -fL --retry 3 --retry-all-errors -o "$TMP_7Z" "$VLC_ZIP_URL"; then
        VLC_ZIP_URL="https://download.videolan.org/pub/videolan/vlc/$VLC_VER/$ARCH_SUFFIX/vlc-$VLC_VER-$ARCH_SUFFIX.7z"
        echo ">> Retrying with $VLC_ZIP_URL"
        curl -fL --retry 3 --retry-all-errors -o "$TMP_7Z" "$VLC_ZIP_URL"
      fi
      TMP_EXTRACT="$(mktemp -d)"
      # 7z is available on Windows GitHub runners; use python3 as fallback
      if command -v 7z &>/dev/null; then
        7z x "$TMP_7Z" -o"$TMP_EXTRACT" -y >/dev/null
      elif command -v 7za &>/dev/null; then
        7za x "$TMP_7Z" -o"$TMP_EXTRACT" -y >/dev/null
      else
        python3 -c "
import zipfile, sys, os
# 7z is not a zip; try py7zr or fallback
try:
    import py7zr
    with py7zr.SevenZipFile(open(sys.argv[1], 'rb')) as z:
        z.extractall(sys.argv[2])
except ImportError:
    # Use subprocess to call 7z from PATH
    import subprocess
    subprocess.run(['7z', 'x', sys.argv[1], '-o' + sys.argv[2], '-y'], check=True)
" "$TMP_7Z" "$TMP_EXTRACT"
      fi
      # Find the VLC directory (usually vlc-<version>)
      VLC_DIR="$(find "$TMP_EXTRACT" -maxdepth 2 -name 'vlc.exe' -exec dirname {} \; | head -1)"
      if [[ -z "$VLC_DIR" ]]; then
        VLC_DIR="$TMP_EXTRACT"
      fi
      echo ">> VLC extracted to $VLC_DIR"
    fi

    # Copy libvlc.dll and libvlccore.dll
    cp -L "$VLC_DIR/libvlc.dll" "$OUT_LIBDIR/" 2>/dev/null || true
    cp -L "$VLC_DIR/libvlccore.dll" "$OUT_LIBDIR/" 2>/dev/null || true
    # Also try bin/ subdir (VLC portable layout)
    cp -L "$VLC_DIR/bin/libvlc.dll" "$OUT_LIBDIR/" 2>/dev/null || true
    cp -L "$VLC_DIR/bin/libvlccore.dll" "$OUT_LIBDIR/" 2>/dev/null || true

    # Copy plugins directory
    if [[ -d "$VLC_DIR/plugins" ]]; then
      mkdir -p "$OUT_LIBDIR/plugins"
      cp -rL "$VLC_DIR/plugins/"* "$OUT_LIBDIR/plugins/"
    elif [[ -d "$VLC_DIR/bin/plugins" ]]; then
      mkdir -p "$OUT_LIBDIR/plugins"
      cp -rL "$VLC_DIR/bin/plugins/"* "$OUT_LIBDIR/plugins/"
    fi
    ;;

  Mac)
    if [[ -z "$VLC_DIR" ]]; then
      echo ">> Installing VLC from Homebrew (official formula)"
      brew install vlc
      VLC_DIR="/Applications/VLC.app/Contents/MacOS"
    fi

    if [[ -d "$VLC_DIR" ]]; then
      # Copy libvlc and libvlccore
      cp -L "$VLC_DIR/lib/libvlc.dylib" "$OUT_LIBDIR/" 2>/dev/null || true
      cp -L "$VLC_DIR/lib/libvlccore.dylib" "$OUT_LIBDIR/" 2>/dev/null || true

      # Copy plugins
      if [[ -d "$VLC_DIR/plugins" ]]; then
        mkdir -p "$OUT_LIBDIR/plugins"
        cp -rL "$VLC_DIR/plugins/"* "$OUT_LIBDIR/plugins/"
      fi
    fi
    ;;

  *)
    echo "ERROR: unsupported os $OS_NAME" >&2
    exit 2
    ;;
esac

# Verify
echo ">> Collected files in $OUT_LIBDIR:"
ls -la "$OUT_LIBDIR"
if [[ -d "$OUT_LIBDIR/plugins" ]]; then
  echo "  Plugins: $(find "$OUT_LIBDIR/plugins" -type f | wc -l) files"
fi

echo ">> Done collecting LibVLC $VLC_VER for $OS_NAME/$OS_ARCH"