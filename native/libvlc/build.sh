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
#   build.sh <Linux|Windows|Mac|Android> <x86_64|aarch64> [vlc_version]
#
# Source of truth per platform:
#   Linux x86_64 / aarch64  → Flathub flatpak org.videolan.VLC //stable
#   Windows x86_64          → get.videolan.org win64 .zip
#   Windows aarch64         → MSYS2 mingw-w64-clang-aarch64-vlc
#   Mac x86_64              → get.videolan.org intel64 .dmg
#   Mac aarch64             → get.videolan.org arm64 .dmg
#   Android aarch64 / x86_64→ Maven org.videolan.android:libvlc-all AAR
#                             (the official library-form LibVLC for Android,
#                             monolithic libvlc.so with statically linked
#                             plugins, shipped with libvlcjni.so +
#                             libc++_shared.so). The vlc-android 3.x line is
#                             built from VLC 3.0 like the desktop runtimes.
#
# Environment:
#   OUT_DIR      output directory (default $PWD/out)
#   LIBVLC_ALL_VER  org.videolan.android:libvlc-all version (default 3.7.5)
set -euo pipefail

OS_NAME="${1:-}"
OS_ARCH="${2:-}"
VLC_VER="${3:-3.0.22}"
LIBVLC_ALL_VER="${LIBVLC_ALL_VER:-3.7.5}"
# Unique SONAME for the bundled libc++ so the Android linker can never
# deduplicate it against a same-named libc++_shared.so that a Pojav-style
# launcher (Zalith/FCL) already loaded from its own runtime dir. libvlc.so's
# DT_NEEDED is rewritten to this name during packaging (patchelf below).
ANDROID_CXX_SONAME="libc++_dreamdisplayx.so"

if [[ -z "$OS_NAME" || -z "$OS_ARCH" ]]; then
  echo "usage: build.sh <Linux|Windows|Mac|Android> <x86_64|aarch64> [vlc_version]" >&2
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
    if [[ "$OS_ARCH" == "aarch64" ]]; then
      # Windows ARM64: no official VLC build exists, so use the MSYS2
      # clangarm64 package (the only pre-built source for this platform).
      echo ">>> Installing MSYS2 VLC package (aarch64)"
      # Official mirror auto-redirects to a fast mirror; default list includes
      # slow/blocked mirrors (e.g. ftp2.osuosl.org) that time out on runners.
      echo 'Server = https://mirror.msys2.org/msys/$arch' > /etc/pacman.d/mirrorlist.msys
      echo 'Server = https://mirror.msys2.org/mingw/$repo' > /etc/pacman.d/mirrorlist.mingw
      pacman -Sy --noconfirm --needed
      pacman -S --noconfirm --needed mingw-w64-clang-aarch64-vlc

      VLC_LIBDIR="/clangarm64"
      cp -a "$VLC_LIBDIR/bin"/libvlc*.dll "$OUT_LIBDIR/" 2>/dev/null || true
      if [[ -d "$VLC_LIBDIR/lib/vlc/plugins" ]]; then
        cp -a "$VLC_LIBDIR/lib/vlc/plugins" "$OUT_LIBDIR/plugins"
      elif [[ -d "$VLC_LIBDIR/plugins" ]]; then
        cp -a "$VLC_LIBDIR/plugins" "$OUT_LIBDIR/plugins"
      else
        echo "ERROR: no VLC plugins directory found at $VLC_LIBDIR" >&2
        ls -la "$VLC_LIBDIR/lib/vlc/" 2>/dev/null || true
        exit 1
      fi
    else
      # Windows x86_64 or x86: download official VideoLAN archive
      if [[ "$OS_ARCH" == "x86" ]]; then
        WIN_ARCH="win32"
      else
        WIN_ARCH="win64"
      fi
      echo ">>> Downloading official VLC $VLC_VER $WIN_ARCH zip"
      ZIP_URL="https://get.videolan.org/vlc/$VLC_VER/$WIN_ARCH/vlc-$VLC_VER-$WIN_ARCH.zip"
      ZIP_FILE="vlc-$VLC_VER-$WIN_ARCH.zip"
      curl -fL --retry 3 --retry-all-errors -o "$ZIP_FILE" "$ZIP_URL"

      echo ">>> Extracting"
      # MSYS2 shell may not ship unzip; install it or use busybox/tar fallback
      if ! command -v unzip &>/dev/null; then
        echo 'Server = https://mirror.msys2.org/msys/$arch' > /etc/pacman.d/mirrorlist.msys
        echo 'Server = https://mirror.msys2.org/mingw/$repo' > /etc/pacman.d/mirrorlist.mingw
        pacman -Sy --noconfirm --needed unzip
      fi
      unzip -q "$ZIP_FILE" -d vlc-extract

      # The zip contains a top-level directory vlc-<ver>/
      SRC="vlc-extract/vlc-$VLC_VER"
      if [[ ! -d "$SRC" ]]; then
        SRC="$(find vlc-extract -mindepth 1 -maxdepth 1 -type d | head -1)"
      fi
      cp -a "$SRC/libvlc.dll" "$OUT_LIBDIR/" 2>/dev/null || true
      cp -a "$SRC/libvlccore.dll" "$OUT_LIBDIR/" 2>/dev/null || true
      if [[ -d "$SRC/plugins" ]]; then
        cp -a "$SRC/plugins" "$OUT_LIBDIR/plugins"
      else
        echo "ERROR: no plugins directory in $WIN_ARCH zip at $SRC" >&2
        ls -la "$SRC/" 2>/dev/null || true
        exit 1
      fi
      rm -rf vlc-extract "$ZIP_FILE"
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

  Android)
    # Official org.videolan.android:libvlc-all Maven AAR: monolithic libvlc.so
    # (all plugins statically linked, libvlccore merged in), shipped as a
    # library-form runtime alongside libvlcjni.so and the full libc++_shared.so.
    # This is the same source squi2rel/VideoPlayer uses for Android.
    #
    # SONAME hardening: Pojav-style launchers (Zalith/FCL) already load their own
    # libc++_shared.so into the process before our JVM code runs. The Android
    # linker deduplicates by SONAME, so `System.load(<our libc++>)` silently
    # binds to that pre-existing library — whose NDK version often lacks the
    # `_ZTTNSt6__ndk118basic_stringstream...` vtable symbol libvlc.so needs
    # (dlopen dies with `cannot locate symbol`). We therefore rename our libc++
    # to a unique SONAME AND rewrite libvlc.so's DT_NEEDED to that name, so the
    # linker can only bind to the copy we ship.
    case "$OS_ARCH" in
      aarch64) ANDROID_ABI="arm64-v8a" ;;
      x86_64)  ANDROID_ABI="x86_64" ;;
      *)
        echo "ERROR: unsupported Android arch $OS_ARCH" >&2
        exit 2
        ;;
    esac
    AAR_URL="https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/${LIBVLC_ALL_VER}/libvlc-all-${LIBVLC_ALL_VER}.aar"
    AAR_FILE="libvlc-all-${LIBVLC_ALL_VER}.aar"
    echo ">>> Downloading $AAR_URL"
    curl -fL --retry 3 --retry-all-errors -o "$AAR_FILE" "$AAR_URL"

    echo ">>> Extracting $ANDROID_ABI libs from the AAR"
    EXTRACT_DIR="vlc-aar-extract"
    rm -rf "$EXTRACT_DIR"
    mkdir -p "$EXTRACT_DIR"
    if command -v unzip &>/dev/null; then
      unzip -q "$AAR_FILE" "jni/${ANDROID_ABI}/*" -d "$EXTRACT_DIR"
    else
      # MSYS2-less environments: use the JDK's jar tool (a plain zip reader).
      JDK_BIN="$(dirname "$(readlink -f "$(command -v java)")")"
      "$JDK_BIN/jar" xf "$AAR_FILE" "jni/${ANDROID_ABI}/libc++_shared.so" \
        "jni/${ANDROID_ABI}/libvlc.so" \
        "jni/${ANDROID_ABI}/libvlcjni.so"
      mkdir -p "$EXTRACT_DIR/jni/${ANDROID_ABI}"
      mv "jni/${ANDROID_ABI}/"*.so "$EXTRACT_DIR/jni/${ANDROID_ABI}/"
    fi

    echo ">>> Renaming libc++ to unique SONAME $ANDROID_CXX_SONAME"
    CXX_SRC="$EXTRACT_DIR/jni/${ANDROID_ABI}/libc++_shared.so"
    CXX_DST="$EXTRACT_DIR/jni/${ANDROID_ABI}/$ANDROID_CXX_SONAME"
    if ! command -v patchelf &>/dev/null; then
      echo ">>> Installing patchelf (SONAME rewrite requires it)"
      sudo apt-get update -qq && sudo apt-get install -y -qq patchelf
    fi
    mv "$CXX_SRC" "$CXX_DST"
    patchelf --set-soname "$ANDROID_CXX_SONAME" "$CXX_DST"
    # Point libvlc.so (and libvlcjni.so, if it carries the dep) at the renamed
    # libc++. --replace-needed fails when the original name is absent, so guard
    # with --print-needed first.
    for SO in "$EXTRACT_DIR/jni/${ANDROID_ABI}/libvlc.so" "$EXTRACT_DIR/jni/${ANDROID_ABI}/libvlcjni.so"; do
      if patchelf --print-needed "$SO" | grep -qx "libc++_shared.so"; then
        patchelf --replace-needed libc++_shared.so "$ANDROID_CXX_SONAME" "$SO"
        echo "  rewrote DT_NEEDED in $(basename "$SO") -> $ANDROID_CXX_SONAME"
      else
        echo "  $(basename "$SO") does not depend on libc++_shared.so; left as-is"
      fi
    done

    cp -a "$EXTRACT_DIR/jni/${ANDROID_ABI}/"*.so "$OUT_LIBDIR/"
    rm -rf "$EXTRACT_DIR" "$AAR_FILE"
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
  Android)
    # Monolithic build: libvlc.so embeds libvlccore + all plugins, so only the
    # three .so files are required (libc++ carries a unique SONAME after the
    # patchelf step above).
    require_file "libvlc.so"         "libvlc.so"
    require_file "libvlcjni.so"      "libvlcjni.so"
    require_file "$ANDROID_CXX_SONAME" "$ANDROID_CXX_SONAME"
    ;;
esac

# Plugin directory (desktop layouts only — the Android monolithic libvlc.so
# carries its plugins inside the library itself)
if [[ "$OS_NAME" != "Android" ]]; then
  PLUGIN_COUNT="$(find "$OUT_LIBDIR/plugins" -type f 2>/dev/null | wc -l)"
  echo "  Plugin count: $PLUGIN_COUNT"
  if [[ "$PLUGIN_COUNT" -lt 250 ]]; then
    echo "WARNING: low plugin count ($PLUGIN_COUNT); expected ≥250" >&2
  fi
fi

# Key playback plugins (video player mod requirements) — desktop only; the
# Android monolithic libvlc.so carries every module inside the library.
if [[ "$OS_NAME" != "Android" ]]; then
  require_plugin "avcodec"         "*avcodec*plugin*"
  require_plugin "mkv"             "*mkv*plugin*"
  require_plugin "mp4"             "*mp4*plugin*"
  require_plugin "packetizer_h264" "*packetizer_h264*plugin*"
  require_plugin "packetizer_hevc" "*packetizer_hevc*plugin*"
  require_plugin "http"            "*http*plugin*"
  require_plugin "freetype"        "*freetype*plugin*"
fi

echo ">>> Collected files:"
find "$OUT_LIBDIR" -maxdepth 1 -type f | sort
echo "  Plugins: $(find "$OUT_LIBDIR/plugins" -type f 2>/dev/null | wc -l) files"
echo ">>> Done fetching LibVLC $VLC_VER for $OS_NAME/$OS_ARCH"
