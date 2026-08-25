#!/usr/bin/env bash
#
# Builds LibVLC runtime (libvlc, libvlccore, plugins) from the official
# VideoLAN source for all 6 target platforms.
#
# Usage:
#   build.sh <Linux|Windows|Mac> <x86_64|aarch64> [vlc_version]
#
# Environment:
#   OUT_DIR      output directory (default $PWD/out)
#   MAKE_JOBS    parallel make jobs (default: nproc)
#   VLC_SRC_DIR  cache directory holding the VLC source (optional)
#
# Each platform installs system-level build dependencies, then runs the
# standard VLC bootstrap/configure/make chain.  Only libvlc, libvlccore
# and the built-in plugins are built (no GUI, no Qt, no skins2).
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
MAKE_JOBS="${MAKE_JOBS:-$(nproc 2>/dev/null || echo 4)}"
OUT_LIBDIR="$OUT_DIR/$OS_NAME/$OS_ARCH"
mkdir -p "$OUT_LIBDIR"

echo ">>> Building LibVLC $VLC_VER for $OS_NAME/$OS_ARCH (jobs=$MAKE_JOBS)"

# ── 1. Download VLC source tarball ──────────────────────────────────────────
VLC_SRC_DIR="${VLC_SRC_DIR:-$ROOT/.build-cache/vlc-$VLC_VER}"
if [[ ! -d "$VLC_SRC_DIR" ]]; then
  TARBALL="vlc-$VLC_VER.tar.xz"
  TARBALL_URL="https://get.videolan.org/vlc/$VLC_VER/vlc-$VLC_VER.tar.xz"
  echo ">>> Downloading $TARBALL_URL"
  mkdir -p "$(dirname "$VLC_SRC_DIR")"
  cd "$(dirname "$VLC_SRC_DIR")"
  curl -fL --retry 3 --retry-all-errors -o "$TARBALL" "$TARBALL_URL" \
    || curl -fL --retry 3 --retry-all-errors -o "$TARBALL" \
      "https://download.videolan.org/pub/videolan/vlc/$VLC_VER/$TARBALL"
  tar xJf "$TARBALL"
  cd "$ROOT"
fi
cd "$VLC_SRC_DIR"

# ── 2. Install build dependencies & configure ───────────────────────────────
case "$OS_NAME" in
  Linux)
    echo ">>> Installing Linux build dependencies"
    apt-get update -qq
    apt-get install -y -qq \
      autoconf automake autopoint libtool pkg-config gettext \
      yasm ragel \
      libxcb-shm0-dev libxcb-xv0-dev libxcb-keysyms1-dev \
      libx11-xcb-dev libxcb-randr0-dev libxcb-composite0-dev \
      libxcb-shape0-dev libxcb-xfixes0-dev libxcb-render0-dev \
      libasound2-dev libpulse-dev libdbus-1-dev \
      libfreetype6-dev libfontconfig1-dev libxml2-dev \
      libavcodec-dev libavformat-dev libswscale-dev \
      libchromaprint-dev libbluray-dev libgstreamer1.0-dev \
      libgstreamer-plugins-base1.0-dev \
      libgnutls28-dev libgcrypt20-dev \
      liblua5.2-dev libmad0-dev libogg-dev libvorbis-dev \
      libtheora-dev libdvdnav-dev libdvdread-dev \
      libsamplerate0-dev liba52-0.7.4-dev libmpeg2-4-dev \
      libdca-dev libfaad-dev libtwolame-dev libmpcdec-dev \
      libvpx-dev libx264-dev libx265-dev

    CONFIGURE_HOST=""
    VLC_BUILD_OPTS=(
      --enable-libvlc --enable-libvlccore
      --disable-gui --disable-qt --disable-skins2
      --disable-lua --disable-ncurses
      --disable-avcodec --disable-avformat --disable-swscale
      --disable-chromaprint --disable-bluray
      --enable-dbus --enable-pulse --enable-alsa
      --enable-xcb --enable-xvideo
      --enable-freetype --enable-fontconfig
      --enable-dvdnav --enable-dvdread
      --enable-vorbis --enable-ogg --enable-mad --enable-mpeg2
      --enable-dca --enable-faad --enable-twolame --enable-mpc
      --enable-vpx --enable-x264 --enable-x265
      --enable-realrtsp --enable-live555
      --enable-opengl --enable-glx
      --prefix="$OUT_LIBDIR"
    )
    ;;

  Mac)
    echo ">>> Installing macOS build dependencies"
    brew install autoconf automake libtool pkg-config gettext ragel yasm \
      freetype fontconfig libxml2 dbus gnutls \
      lua mad libogg libvorbis libtheora \
      x264 x265 libvpx

    # Homebrew installs gettext + libtool keg-only; force-link them
    brew link --overwrite gettext libtool 2>/dev/null || true

    CONFIGURE_HOST=""
    VLC_BUILD_OPTS=(
      --enable-libvlc --enable-libvlccore
      --disable-gui --disable-qt --disable-skins2
      --disable-lua --disable-ncurses
      --disable-avcodec --disable-avformat --disable-swscale
      --disable-chromaprint --disable-bluray
      --enable-dbus --enable-freetype --enable-fontconfig
      --enable-vorbis --enable-ogg --enable-mad
      --enable-vpx --enable-x264 --enable-x265
      --enable-realrtsp --enable-live555
      --prefix="$OUT_LIBDIR"
    )
    ;;

  Windows)
    echo ">>> Installing Windows (MSYS2/MinGW) build dependencies"
    # GitHub Actions windows-latest has MSYS2 pre-installed at C:\msys64
    MSYS2="C:/msys64/usr/bin/bash.exe"
    if [[ ! -f "$MSYS2" ]]; then
      # Fallback: try to find msys2
      MSYS2="$(command -v msys2 2>/dev/null || echo "")"
      if [[ -z "$MSYS2" ]]; then
        echo "ERROR: MSYS2 not found; install it first (choco install msys2)"
        exit 1
      fi
    fi

    # Determine MinGW arch prefix
    MINGW_ARCH="x86_64"
    MINGW_PREFIX="mingw-w64-x86_64"
    if [[ "$OS_ARCH" == "aarch64" ]]; then
      MINGW_ARCH="aarch64"
      MINGW_PREFIX="mingw-w64-clang-aarch64"
    fi

    # Install mingw-w64 toolchain + VLC dependencies via MSYS2 pacman
    "$MSYS2" -lc "
      pacman -Syu --noconfirm --needed
      pacman -S --noconfirm --needed \
        ${MINGW_PREFIX}-toolchain \
        ${MINGW_PREFIX}-autotools \
        ${MINGW_PREFIX}-gettext \
        ${MINGW_PREFIX}-pkg-config \
        ${MINGW_PREFIX}-libmad \
        ${MINGW_PREFIX}-libogg \
        ${MINGW_PREFIX}-libvorbis \
        ${MINGW_PREFIX}-libtheora \
        ${MINGW_PREFIX}-freetype \
        ${MINGW_PREFIX}-fontconfig \
        ${MINGW_PREFIX}-libxml2 \
        ${MINGW_PREFIX}-gnutls \
        ${MINGW_PREFIX}-lua \
        ${MINGW_PREFIX}-dbus \
        ${MINGW_PREFIX}-libsamplerate \
        ${MINGW_PREFIX}-libbluray \
        ${MINGW_PREFIX}-libdvdnav \
        ${MINGW_PREFIX}-libdvdread \
        ${MINGW_PREFIX}-x264 \
        ${MINGW_PREFIX}-x265 \
        ${MINGW_PREFIX}-libvpx \
        make gettext
    "

    CONFIGURE_HOST="--host=${MINGW_ARCH}-w64-mingw32"
    export CC="${MINGW_ARCH}-w64-mingw32-gcc"
    export CXX="${MINGW_ARCH}-w64-mingw32-g++"
    # For Clang-based aarch64, mingw-w64-clang-aarch64 uses clang
    if [[ "$MINGW_ARCH" == "aarch64" ]]; then
      export CC="${MINGW_ARCH}-w64-mingw32-clang"
      export CXX="${MINGW_ARCH}-w64-mingw32-clang++"
    fi
    export PKG_CONFIG_PATH="/mingw64/lib/pkgconfig:/mingw64/share/pkgconfig"
    if [[ "$OS_ARCH" == "aarch64" ]]; then
      export PKG_CONFIG_PATH="/clangarm64/lib/pkgconfig:/clangarm64/share/pkgconfig"
    fi

    VLC_BUILD_OPTS=(
      --enable-libvlc --enable-libvlccore
      --disable-gui --disable-qt --disable-skins2
      --disable-lua --disable-ncurses
      --disable-avcodec --disable-avformat --disable-swscale
      --disable-chromaprint --disable-bluray
      --enable-freetype --enable-fontconfig
      --enable-vorbis --enable-ogg --enable-mad
      --enable-vpx --enable-x264 --enable-x265
      --enable-realrtsp --enable-live555
      --prefix="$OUT_LIBDIR"
    )
    ;;

  *)
    echo "ERROR: unsupported os $OS_NAME" >&2
    exit 2
    ;;
esac

# ── 3. Bootstrap + Configure + Make ────────────────────────────────────────
echo ">>> Running bootstrap"
./bootstrap 2>&1 | tail -5

echo ">>> Running configure"
mkdir -p "$VLC_SRC_DIR/build"
cd "$VLC_SRC_DIR/build"
../configure \
  $CONFIGURE_HOST \
  "${VLC_BUILD_OPTS[@]}" \
  2>&1 | tail -20

echo ">>> Building (make -j${MAKE_JOBS})"
make -j"${MAKE_JOBS}" 2>&1 | tail -20

echo ">>> Installing to $OUT_LIBDIR"
make install 2>&1 | tail -10

# ── 4. Collect & verify ─────────────────────────────────────────────────────
cd "$ROOT"
echo ">>> Collected files in $OUT_LIBDIR:"
ls -la "$OUT_LIBDIR/lib/"
if [[ -d "$OUT_LIBDIR/lib/vlc/plugins" ]]; then
  mv "$OUT_LIBDIR/lib/vlc/plugins" "$OUT_LIBDIR/plugins"
  rm -rf "$OUT_LIBDIR/lib/vlc"
fi
if [[ -d "$OUT_LIBDIR/plugins" ]]; then
  echo "  Plugins: $(find "$OUT_LIBDIR/plugins" -type f | wc -l) files"
fi
echo ">>> Done building LibVLC $VLC_VER for $OS_NAME/$OS_ARCH"