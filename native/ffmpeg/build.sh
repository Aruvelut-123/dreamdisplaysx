#!/usr/bin/env bash
#
# Builds FFmpeg from upstream source for one target platform.
#
# Why this exists
# ---------------
# org.bytedeco's last published ffmpeg preset is 8.1.2-1.5.14 and it ships no
# windows-aarch64 classifier, so FFmpeg 9 is not reachable through Maven.
# Building from source gives us the current stable release and every platform
# the mod ships on.
#
# Usage:
#   build.sh <platform>
#   FFMPEG_VER=9.0.2 build.sh android-arm64
#
# Environment:
#   OUT_DIR     output directory (default $PWD/out)
#   FFMPEG_VER  upstream FFmpeg version (default 9.0.2)
#   N_JOBS      parallel make jobs (default: detected cpu count)
#
# Output layout (what NativesDownloader.flattenPlatformDir expects):
#   $OUT_DIR/<os>/<arch>/*.so* | *.dylib | *.dll
#
# NOTE: this produces only the FFmpeg libraries. The JavaCPP JNI shims
# (libjniavcodec.so, ...) that the Java bindings load are a separate artifact;
# see FfmpegNatives for how the runtime directory is registered.
#
set -euo pipefail

PLATFORM="${1:-}"
FFMPEG_VER="${FFMPEG_VER:-9.0.2}"
N_JOBS="${N_JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}"

if [[ -z "$PLATFORM" ]]; then
  echo "usage: build.sh <platform>" >&2
  echo "platforms:" >&2
  echo "  linux-x86_64    linux-aarch64" >&2
  echo "  macos-x86_64    macos-aarch64" >&2
  echo "  windows-x86_64  windows-aarch64" >&2
  echo "  android-arm64   android-x86_64" >&2
  exit 2
fi

# Map the platform key to <os>/<arch> (the spelling OsInfo/NativesDownloader
# uses) plus the configure target triple.
case "$PLATFORM" in
  linux-x86_64)    OS_DIR=Linux;   ARCH_DIR=x86_64  ; TARGET_OS=linux   ; TARGET_ARCH=x86_64  ; HOST_TRIPLE=x86_64-linux-gnu      ;;
  linux-aarch64)   OS_DIR=Linux;   ARCH_DIR=aarch64 ; TARGET_OS=linux   ; TARGET_ARCH=aarch64 ; HOST_TRIPLE=aarch64-linux-gnu     ;;
  macos-x86_64)    OS_DIR=Mac;     ARCH_DIR=x86_64  ; TARGET_OS=darwin  ; TARGET_ARCH=x86_64  ; HOST_TRIPLE=x86_64-apple-darwin   ;;
  macos-aarch64)   OS_DIR=Mac;     ARCH_DIR=aarch64 ; TARGET_OS=darwin  ; TARGET_ARCH=arm64   ; HOST_TRIPLE=arm64-apple-darwin    ;;
  windows-x86)     OS_DIR=Windows; ARCH_DIR=x86     ; TARGET_OS=mingw32 ; TARGET_ARCH=x86     ; HOST_TRIPLE=i686-w64-mingw32      ;;
  windows-x86_64)  OS_DIR=Windows; ARCH_DIR=x86_64  ; TARGET_OS=mingw32 ; TARGET_ARCH=x86_64  ; HOST_TRIPLE=x86_64-w64-mingw32    ;;
  windows-aarch64) OS_DIR=Windows; ARCH_DIR=aarch64 ; TARGET_OS=mingw32 ; TARGET_ARCH=aarch64 ; HOST_TRIPLE=aarch64-w64-mingw32   ;;
  android-arm64)   OS_DIR=Android; ARCH_DIR=aarch64 ; TARGET_OS=android ; TARGET_ARCH=aarch64 ; HOST_TRIPLE=aarch64-linux-android ;;
  android-x86_64)  OS_DIR=Android; ARCH_DIR=x86_64  ; TARGET_OS=android ; TARGET_ARCH=x86_64  ; HOST_TRIPLE=x86_64-linux-android  ;;
  *)
    echo "ERROR: unsupported platform '$PLATFORM'" >&2
    exit 2
    ;;
esac

OUT_DIR="${OUT_DIR:-$PWD/out}"
OUT_LIBDIR="$OUT_DIR/$OS_DIR/$ARCH_DIR"
PREFIX="$OUT_DIR/build-prefix/$OS_DIR/$ARCH_DIR"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT_LIBDIR" "$PREFIX"

# ── Fetch and unpack the release tarball ────────────────────────────────────
SRC_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VER}.tar.xz"
SRC_ARCHIVE="$WORK/ffmpeg-${FFMPEG_VER}.tar.xz"
SRC_DIR="$WORK/ffmpeg-${FFMPEG_VER}"

echo ">>> Fetching FFmpeg $FFMPEG_VER"
curl -fL --retry 4 --retry-all-errors --retry-delay 3 -o "$SRC_ARCHIVE" "$SRC_URL"
echo ">>> Extracting"
tar -xf "$SRC_ARCHIVE" -C "$WORK"

cd "$SRC_DIR"

# ── Base flags ──────────────────────────────────────────────────────────────
#
# Shared libraries are mandatory: JavaCPP dlopens libavcodec.so.<N> at runtime,
# so a static-only build would produce .a files nothing can load.
#
# No external codec libraries are enabled on purpose (no libx264/libvpx/...).
# Each would add a cross-compiled dependency per platform, and the playback path
# only ever decodes, which FFmpeg's own decoders cover.
#
CONFIGURE_FLAGS=(
  --prefix="$PREFIX"
  --enable-shared
  --disable-static
  --disable-programs
  --disable-doc
  --disable-debug
  --disable-stripping
  --enable-gpl
  --enable-version3
  --enable-pic
  --enable-runtime-cpudetect
)

case "$PLATFORM" in
  linux-x86_64|linux-aarch64)
    CONFIGURE_FLAGS+=(
      --target-os=linux
      --arch="$TARGET_ARCH"
      --enable-pthreads
      # Explicitly disable VA-API/VDPAU/X11 on cross-compile runners where headers
      # exist but runtime libs (libGL, libva, libvdpau) are missing, causing
      # configure to fail. Native builds on actual target hardware would detect
      # them automatically if present; we keep it simple and safe.
      --disable-vaapi
      --disable-vdpau
      --disable-xlib
    )
    if [[ "$PLATFORM" == "linux-aarch64" && "$(uname -m)" != "aarch64" ]]; then
      CONFIGURE_FLAGS+=(--cross-prefix="${HOST_TRIPLE}-")
    fi
    ;;

  macos-x86_64|macos-aarch64)
    CONFIGURE_FLAGS+=(
      --target-os=darwin
      --arch="$TARGET_ARCH"
      --enable-videotoolbox
      --enable-audiotoolbox
    )
    if [[ "$PLATFORM" == "macos-aarch64" && "$(uname -m)" != "arm64" ]]; then
      CONFIGURE_FLAGS+=(--cross-prefix="${HOST_TRIPLE}-")
    fi
    ;;
 
  windows-x86|windows-x86_64|windows-aarch64)
    CONFIGURE_FLAGS+=(
      --target-os=mingw32
      --arch="$TARGET_ARCH"
    )

    # probe_tool <candidate>...: first candidate on PATH, else the last one.
    probe_tool() {
      local c last=""
      for c in "$@"; do
        last="$c"
        if command -v "$c" >/dev/null 2>&1; then
          echo "$c"
          return
        fi
      done
      echo "$last"
    }

    # MSYS2's binutils installs the *unprefixed* `ar`/`nm`/`ranlib`/`strip` (or
    # LLVM's `llvm-*`), not `${TRIPLE}-ar`. That matters because FFmpeg's
    # compat/windows/makedef is invoked from the makefile with `AR=$(AR)`, and a
    # name that does not exist kills the build with:
    #
    #   ./compat/windows/makedef: line 49: x86_64-w64-mingw32-ar: command not found
    #   Could not create temporary library.
    #
    # configure never validates `--ar`, so this only surfaces mid-build. Probe
    # for the prefixed spelling first (real cross toolchains usually have it)
    # and fall back to the bare name that MSYS2 actually ships.
    TOOL_AR="$(probe_tool "${HOST_TRIPLE}-ar" llvm-ar ar)"
    TOOL_NM="$(probe_tool "${HOST_TRIPLE}-nm" llvm-nm nm)"
    TOOL_RANLIB="$(probe_tool "${HOST_TRIPLE}-ranlib" llvm-ranlib ranlib)"
    TOOL_STRIP="$(probe_tool "${HOST_TRIPLE}-strip" llvm-strip strip)"
    echo ">>> Toolchain binutils: ar=$TOOL_AR nm=$TOOL_NM ranlib=$TOOL_RANLIB strip=$TOOL_STRIP"

    CONFIGURE_FLAGS+=(
      --ar="$TOOL_AR"
      --nm="$TOOL_NM"
      --ranlib="$TOOL_RANLIB"
      --strip="$TOOL_STRIP"
    )

    if [[ "$PLATFORM" == "windows-aarch64" ]]; then
      # CLANGARM64 ships only the clang-based aarch64 mingw toolchain; there is
      # no aarch64 mingw-w64 gcc at all. Its wrapper
      # `${TRIPLE}-clang` already bakes in the target triple AND the
      # /clangarm64 sysroot. Plain `clang` has neither, and the missing sysroot
      # is what makes FFmpeg die with the very misleading
      #
      #   Host compiler lacks C11 support
      #
      # (the probe never reaches the C11 feature test: it cannot find stdio.h).
      # Do not pass --cross-prefix here either; it would make configure hunt
      # for ${TRIPLE}-{as,ld,dlltool,...} that CLANGARM64 does not install, and
      # every explicit tool is already pinned above.
      CC_TOOL="$(probe_tool "${HOST_TRIPLE}-clang" clang)"
      CXX_TOOL="$(probe_tool "${HOST_TRIPLE}-clang++" clang++)"
      if [[ "$CC_TOOL" == *clang ]]; then
        SYSROOT="${MSYSTEM_PREFIX:-/clangarm64}"
        CC_TOOL="clang --target=${HOST_TRIPLE} --sysroot=${SYSROOT} -isystem ${SYSROOT}/include/c++/v1 -isystem ${SYSROOT}/include"
        CXX_TOOL="clang++ --target=${HOST_TRIPLE} --sysroot=${SYSROOT} -isystem ${SYSROOT}/include/c++/v1 -isystem ${SYSROOT}/include"
      fi
      echo ">>> Toolchain cc: $CC_TOOL"
      echo ">>> Toolchain cxx: $CXX_TOOL"
      CONFIGURE_FLAGS+=(
        --cc="$CC_TOOL"
        --cxx="$CXX_TOOL"
      )
      # clang links compiler-rt statically by default, so -static-libgcc is
      # neither needed nor understood here (that flag is a GCC driver option).
    else
      CONFIGURE_FLAGS+=(
        --cross-prefix="${HOST_TRIPLE}-"
        --cc="$(probe_tool "${HOST_TRIPLE}-gcc" gcc)"
        --cxx="$(probe_tool "${HOST_TRIPLE}-g++" g++)"
      )
      # Static gcc runtime: a mingw-w64 build otherwise emits DLLs that need
      # libgcc_s_seh-1.dll / libwinpthread-1.dll at load time, which an end user
      # does not have. `-static-libgcc` is enough here because the C runtime is
      # msvcrt (imported from Windows itself).
      CONFIGURE_FLAGS+=(--extra-ldflags="-static-libgcc")
    fi

    # d3d11va/dxva2 are autodetected from the mingw headers and back the
    # Windows hardware-decoder rungs.
    ;;

  android-arm64|android-x86_64)
    if [[ -z "${ANDROID_NDK:-}" ]]; then
      echo "ERROR: ANDROID_NDK is not set; the Android targets need the NDK" >&2
      exit 1
    fi
    # The LLVM toolchain inside the NDK is per host OS; pick whichever exists so
    # this also runs on a macOS or Windows runner.
    TOOLCHAIN=""
    for host in linux-x86_64 darwin-x86_64 darwin-arm64 windows-x86_64; do
      if [[ -d "$ANDROID_NDK/toolchains/llvm/prebuilt/$host" ]]; then
        TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$host"
        break
      fi
    done
    if [[ -z "$TOOLCHAIN" ]]; then
      echo "ERROR: no LLVM prebuilt host toolchain inside $ANDROID_NDK" >&2
      exit 1
    fi
    API_LEVEL="${ANDROID_API_LEVEL:-24}"
    export PATH="$TOOLCHAIN/bin:$PATH"
    # NDK clang wrappers are named <triple><api>-clang.
    export CC="${HOST_TRIPLE}${API_LEVEL}-clang"
    export CXX="${HOST_TRIPLE}${API_LEVEL}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export NM="$TOOLCHAIN/bin/llvm-nm"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export STRIP="$TOOLCHAIN/bin/llvm-strip"

    CONFIGURE_FLAGS+=(
      --target-os=android
      --arch="$TARGET_ARCH"
      --enable-cross-compile
      --sysroot="$TOOLCHAIN/sysroot"
      --cc="$CC"
      --cxx="$CXX"
      # MediaCodec + the NDK backend. --enable-jni is what makes the mediacodec
      # hwaccel available; without it avcodec_find_decoder_by_name(
      # "h264_mediacodec") returns null and every Android start falls back.
      --enable-mediacodec
      --enable-jni
      # Nothing external: the NDK sysroot does not carry zlib/bzlib/libxml2
      # headers on every API level, and the playback path does not need them.
      --disable-zlib
      --disable-bzlib
      --disable-lzma
      --disable-iconv
      --disable-libxml2
    )
    ;;

  *)
    echo "ERROR: unsupported platform '$PLATFORM'" >&2
    exit 2
    ;;
esac

# ── Build ───────────────────────────────────────────────────────────────────
echo ">>> Configuring FFmpeg $FFMPEG_VER for $PLATFORM"

# Print what the toolchain actually resolved to. A cross-toolchain that is
# present but wrong (modular clang with no sysroot, a host compiler shadowing
# the cross one on PATH) produces the same opaque one-liners inside configure
# ("C compiler test failed", "Host compiler lacks C11 support") as a missing
# one, and the resolved paths are what tell them apart.
echo ">>> Toolchain probe:"
for t in cc cxx ar nm ranlib strip; do
  case "$t" in
    cc)  v="$(printf '%s' "${CONFIGURE_FLAGS[*]}" | sed -n 's/.*--cc=\([^ ]*\).*/\1/p')" ;;
    cxx) v="$(printf '%s' "${CONFIGURE_FLAGS[*]}" | sed -n 's/.*--cxx=\([^ ]*\).*/\1/p')" ;;
    *)   v="$(printf '%s' "${CONFIGURE_FLAGS[*]}" | sed -n "s/.*--$t=\([^ ]*\).*/\1/p")" ;;
  esac
  echo "    $t = ${v:-<default>}"
done
for t in clang clang++ gcc g++ cc; do
  if command -v "$t" >/dev/null 2>&1; then
    echo "    $(command -v "$t") : $($t --version 2>&1 | head -1)"
  fi
done

if ! ./configure "${CONFIGURE_FLAGS[@]}" > /tmp/configure.out 2>&1; then
  tail -40 /tmp/configure.out
  echo "=== ffbuild/config.log (compiler errors) ===" >&2
  grep -nE 'error|fatal|not found|cannot|unable|undefined' ffbuild/config.log 2>/dev/null | head -60 >&2 || true
  echo "=== last 60 lines of config.log ===" >&2
  tail -60 ffbuild/config.log >&2 || true
  exit 1
fi
tail -40 /tmp/configure.out

echo ">>> Building with $N_JOBS job(s)"
make -j"$N_JOBS"

echo ">>> Installing into $PREFIX"
if [[ "$PLATFORM" == windows-* ]]; then
  # `make install` on mingw tries `install libavdevice/avdevice.lib`, but a
  # mingw-w64 gcc build only produces the DLL (+ import .dll.a), never the
  # MSVC-style .lib, so the rule dies with:
  #
  #   install: cannot stat 'libavdevice/avdevice.lib': No such file or directory
  #   make: *** [ffbuild/library.mak:138: install-libavdevice-shared] Error 1
  #
  # The compiled DLLs are already complete, so collect them straight from the
  # build tree instead of running the install rules. `install-libs` also has to
  # be skipped for the same reason; headers/pc files are of no use to the mod.
  mkdir -p "$PREFIX/lib"
  find . -maxdepth 2 -name '*.dll' -exec cp -f {} "$PREFIX/lib/" \;
else
  make install
fi

# ── Flatten the install tree ────────────────────────────────────────────────
#
# `make install` writes <prefix>/lib and <prefix>/include; the in-mod
# downloader expects the libraries directly in the platform directory, so lift
# them one level and drop what the runtime never reads.
echo ">>> Flattening libraries into $OUT_LIBDIR"
find "$PREFIX/lib" -maxdepth 1 -type f \
  \( -name '*.so*' -o -name '*.dylib' -o -name '*.dll' \) \
  -exec cp -f {} "$OUT_LIBDIR/" \;
rm -rf "$PREFIX/include" "$PREFIX/share" "$PREFIX/bin" "$PREFIX/lib"

# ── Verify ──────────────────────────────────────────────────────────────────
cd "$OUT_LIBDIR"

echo ">>> Libraries produced for $PLATFORM:"
ls -1 | sort

MISSING=0
for lib in avcodec avformat avutil swscale swresample avfilter; do
  if ! find . -maxdepth 1 -type f -name "*${lib}*" | grep -q .; then
    echo "ERROR: lib${lib} missing from $OUT_LIBDIR" >&2
    MISSING=1
  fi
done
if [[ $MISSING -ne 0 ]]; then
  exit 1
fi

# The Android build must actually contain the MediaCodec decoders; a silent
# absence would only surface as "no suitable decoder" on a user's device.
if [[ "$PLATFORM" == android-* ]]; then
  AVCODEC_LIB=$(find . -maxdepth 1 -type f -name 'libavcodec*' | head -1)
  if command -v strings >/dev/null 2>&1; then
    for dec in h264_mediacodec hevc_mediacodec; do
      if ! strings "$AVCODEC_LIB" | grep -qx "$dec"; then
        echo "ERROR: $dec is not registered in $AVCODEC_LIB" >&2
        exit 1
      fi
    done
    echo "  ✓ mediacodec decoders registered"
  fi
fi

echo ">>> Done: FFmpeg $FFMPEG_VER for $PLATFORM -> $OUT_LIBDIR"