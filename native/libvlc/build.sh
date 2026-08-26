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
# Configure strategy: this is a **video player mod**, so every module that
# contributes to playback is enabled — codecs (avcodec, x264/x265, vpx,
# a52/dca/faad/twolame DTS/AC-3/MPEG audio), demuxers (mkv/mp4/ts/ps),
# streaming protocols (live555 RTSP, HTTP(S), SMB, FTP), subtitle renderers
# (freetype/fontconfig/fribidi/harfbuzz), DVD/Blu-ray navigation. Missing
# system libraries are installed (apt/brew/pacman). Only modules useless for
# a headless video player are disabled: GUI (qt/skins2), lua scripting,
# ncurses.
#
# Windows builds MUST run inside a MSYS2 shell (MINGW64 or CLANGARM64).
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

# Shared playback-relevant build options. Everything else is left to
# configure auto-detection (it enables a module when the dependency is
# present and safely skips it otherwise).
#
# Deliberately NOT disabled: avcodec/avformat/swscale, live555 (RTSP),
# a52/dts/faad/twolame audio, dvdnav/dvdread/bluray, freetype/fontconfig,
# fribidi/harfbuzz, vorbis/ogg/theora/mad/mpeg2, vpx/x264/x265, opus/flac,
# sdl2, chromaprint, dcadec, etc. — all are needed for a complete player.
VC_OPTS_BASE=(
  --disable-qt --disable-skins2
  --disable-lua --disable-ncurses
  --disable-fluidsynth
  --prefix="$OUT_LIBDIR"
)

# Sparkle is VLC's auto-update framework; useless for a bundled player lib
VC_OPTS_MACOS=(
  --disable-sparkle
)

# ── 2. Install build dependencies & configure ───────────────────────────────
case "$OS_NAME" in
  Linux)
    echo ">>> Installing Linux build dependencies"
    sudo apt-get update -qq
    sudo apt-get install -y -qq \
      autoconf automake autopoint libtool pkg-config gettext \
      yasm ragel \
      libxcb-shm0-dev libxcb-xv0-dev libxcb-keysyms1-dev \
      libx11-xcb-dev libxcb-randr0-dev libxcb-composite0-dev \
      libxcb-shape0-dev libxcb-xfixes0-dev libxcb-render0-dev \
      libasound2-dev libpulse-dev libdbus-1-dev \
      libfreetype6-dev libfontconfig1-dev libfribidi-dev \
      libharfbuzz-dev libxml2-dev \
      libavcodec-dev libavformat-dev libswscale-dev libavutil-dev \
      libpostproc-dev \
      libchromaprint-dev libbluray-dev libgstreamer1.0-dev \
      libgstreamer-plugins-base1.0-dev \
      libgnutls28-dev libgcrypt20-dev \
      liblua5.2-dev libmad0-dev libogg-dev libvorbis-dev \
      libtheora-dev libdvdnav-dev libdvdread-dev \
      libsamplerate0-dev liba52-0.7.4-dev libmpeg2-4-dev \
      libdca-dev libfaad-dev libtwolame-dev libmpcdec-dev \
      libvpx-dev libx264-dev libx265-dev \
      libopus-dev libflac-dev libsdl2-dev \
      libjpeg-dev libpng-dev \
      zlib1g-dev libbz2-dev

    # live555 is not available in Ubuntu 24.04 repos, so build it from source
    echo ">>> Building live555 from source (RTSP support)"
    LIVE555_DIR="$ROOT/.build-cache/live555"
    if [[ ! -f "/usr/local/lib/pkgconfig/live555.pc" ]]; then
      mkdir -p "$(dirname "$LIVE555_DIR")"
      cd "$(dirname "$LIVE555_DIR")"
      if [[ ! -d "$LIVE555_DIR" ]]; then
        curl -fL --retry 3 --retry-all-errors -o live555.tar.gz \
          "https://www.live555.com/liveMedia/public/live555-latest.tar.gz"
        tar xzf live555.tar.gz
        mv live "$LIVE555_DIR"
      fi
      cd "$LIVE555_DIR"
      ./genMakefiles linux
      make -j"${MAKE_JOBS}"
      sudo mkdir -p /usr/local/include /usr/local/lib /usr/local/lib/pkgconfig
      sudo cp -r BasicUsageEnvironment/include/* /usr/local/include/
      sudo cp -r groupsock/include/* /usr/local/include/
      sudo cp -r liveMedia/include/* /usr/local/include/liveMedia/
      sudo cp -r UsageEnvironment/include/* /usr/local/include/
      sudo cp -r BasicUsageEnvironment/libBasicUsageEnvironment.a /usr/local/lib/
      sudo cp -r groupsock/libgroupsock.a /usr/local/lib/
      sudo cp -r liveMedia/libliveMedia.a /usr/local/lib/
      sudo cp -r UsageEnvironment/libUsageEnvironment.a /usr/local/lib/
      sudo tee /usr/local/lib/pkgconfig/live555.pc > /dev/null <<'PKGCONFIG'
prefix=/usr/local
libdir=${prefix}/lib
includedir=${prefix}/include

Name: live555
Description: LIVE555 Streaming Media
Version: 2023.11.30
Libs: -L${libdir} -lliveMedia -lBasicUsageEnvironment -lUsageEnvironment -lgroupsock
Cflags: -I${includedir} -I${includedir}/liveMedia
PKGCONFIG
    fi
    cd "$VLC_SRC_DIR"
    export PKG_CONFIG_PATH="/usr/local/lib/pkgconfig:$PKG_CONFIG_PATH"

    CONFIGURE_HOST=""
    VLC_BUILD_OPTS=("${VC_OPTS_BASE[@]}")
    ;;

  Mac)
    echo ">>> Installing macOS build dependencies"
    brew install autoconf automake libtool pkg-config gettext ragel yasm \
      freetype fontconfig fribidi harfbuzz libxml2 dbus gnutls \
      libogg libvorbis theora mad \
      x264 x265 libvpx ffmpeg \
      a52dec openrtsp libdvdnav libdvdread libbluray \
      libsamplerate opus flac chromaprint sdl2-compat

    # Homebrew installs gettext + libtool keg-only; force-link them
    brew link --overwrite gettext libtool 2>/dev/null || true

    CONFIGURE_HOST=""
    VLC_BUILD_OPTS=("${VC_OPTS_BASE[@]}" "${VC_OPTS_MACOS[@]}")
    ;;

  Windows)
    echo ">>> Installing Windows (MSYS2/MinGW) build dependencies"

    # Inside MSYS2 shell: detect which msystem we are running under
    MINGW_PREFIX="mingw-w64-x86_64"
    MINGW_ARCH="x86_64"
    if [[ "$MSYSTEM" == "CLANGARM64" ]]; then
      MINGW_PREFIX="mingw-w64-clang-aarch64"
      MINGW_ARCH="aarch64"
    fi

    # Install build dependencies (MSYS autotools + mingw compiler chain).
    # NOTE: never `pacman -Syu` (sysupgrade) here — upgrading msys2-runtime
    # forcibly kills the current MSYS2 terminal/process, aborting the build.
    pacman -Sy --noconfirm --needed
    pacman -S --noconfirm --needed \
      autoconf automake libtool make gettext \
      ${MINGW_PREFIX}-toolchain \
      ${MINGW_PREFIX}-pkgconf \
      ${MINGW_PREFIX}-libmad \
      ${MINGW_PREFIX}-a52dec \
      ${MINGW_PREFIX}-libogg \
      ${MINGW_PREFIX}-libvorbis \
      ${MINGW_PREFIX}-libtheora \
      ${MINGW_PREFIX}-freetype \
      ${MINGW_PREFIX}-fontconfig \
      ${MINGW_PREFIX}-fribidi \
      ${MINGW_PREFIX}-harfbuzz \
      ${MINGW_PREFIX}-libxml2 \
      ${MINGW_PREFIX}-gnutls \
      ${MINGW_PREFIX}-libsamplerate \
      ${MINGW_PREFIX}-libbluray \
      ${MINGW_PREFIX}-libdvdnav \
      ${MINGW_PREFIX}-libdvdread \
      ${MINGW_PREFIX}-x264 \
      ${MINGW_PREFIX}-x265 \
      ${MINGW_PREFIX}-libvpx \
      ${MINGW_PREFIX}-ffmpeg \
      ${MINGW_PREFIX}-opus \
      ${MINGW_PREFIX}-flac \
      ${MINGW_PREFIX}-chromaprint \
      ${MINGW_PREFIX}-live-media

    # Set PKG_CONFIG_PATH for the correct mingw prefix
    if [[ "$MSYSTEM" == "CLANGARM64" ]]; then
      export PKG_CONFIG_PATH="/clangarm64/lib/pkgconfig:/clangarm64/share/pkgconfig"
      export CC="clang"
      export CXX="clang++"
      export BUILDCC="clang"
      CONFIGURE_HOST="--host=${MINGW_ARCH}-w64-mingw32"
    else
      export PKG_CONFIG_PATH="/mingw64/lib/pkgconfig:/mingw64/share/pkgconfig"
      export CC="x86_64-w64-mingw32-gcc"
      export CXX="x86_64-w64-mingw32-g++"
      export BUILDCC="gcc"
      CONFIGURE_HOST="--host=${MINGW_ARCH}-w64-mingw32"
    fi

    VLC_BUILD_OPTS=("${VC_OPTS_BASE[@]}")
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

# ── 4. Normalize install layout ────────────────────────────────────────────
# `make install` scatters files under prefix/lib (SOs) and prefix/lib/vlc/plugins.
# Flatten to the loader's expected layout:
#   $OUT_LIBDIR/libvlc.so | libvlc.dll | libvlc.dylib
#   $OUT_LIBDIR/libvlccore.*
#   $OUT_LIBDIR/plugins/...
cd "$ROOT"
echo ">>> Normalizing install layout in $OUT_LIBDIR"
for sub in bin lib lib64 Lib Lib64; do
  if [[ -d "$OUT_LIBDIR/$sub" ]]; then
    # Lift library files up (DLLs install to bin/ on Windows, SOs to lib/ on Unix)
    find "$OUT_LIBDIR/$sub" -maxdepth 1 -type f \
      \( -name 'libvlc*' -o -name 'libvlccore*' \) -exec mv -f {} "$OUT_LIBDIR/" \;
    # Move plugins dir if present under this subdir
    if [[ -d "$OUT_LIBDIR/$sub/vlc/plugins" ]]; then
      rm -rf "$OUT_LIBDIR/plugins"
      mv "$OUT_LIBDIR/$sub/vlc/plugins" "$OUT_LIBDIR/plugins"
    elif [[ -d "$OUT_LIBDIR/$sub/plugins" ]]; then
      rm -rf "$OUT_LIBDIR/plugins"
      mv "$OUT_LIBDIR/$sub/plugins" "$OUT_LIBDIR/plugins"
    fi
    rm -rf "$OUT_LIBDIR/$sub"
  fi
done
# Any leftover nested vlc dir directly under output
if [[ -d "$OUT_LIBDIR/vlc/plugins" ]]; then
  rm -rf "$OUT_LIBDIR/plugins"
  mv "$OUT_LIBDIR/vlc/plugins" "$OUT_LIBDIR/plugins"
  rm -rf "$OUT_LIBDIR/vlc"
fi
# Drop unwanted share/include/etc dirs
rm -rf "$OUT_LIBDIR/share" "$OUT_LIBDIR/include" "$OUT_LIBDIR/etc"
echo ">>> Collected files:"
find "$OUT_LIBDIR" -maxdepth 1 -type f | sort
echo "  Plugins: $(find "$OUT_LIBDIR/plugins" -type f 2>/dev/null | wc -l) files"
echo ">>> Done building LibVLC $VLC_VER for $OS_NAME/$OS_ARCH"