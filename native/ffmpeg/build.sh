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
# Scratch space deliberately lives under OUT_DIR rather than in `mktemp -d`.
# On MSYS2 `mktemp -d` returns a path under its own `/tmp` mount, and both
# `curl -o` and the tar extractor failed there with
#   Warning: Failed to open the file /tmp/tmp.XXXX/ffmpeg-8.1.2.tar.xz: No such file or directory
#   curl: (23) client returned ERROR on write
# OUT_DIR is a plain Windows path, so it is writable for every tool involved.
# A fixed name (not a random one) also lets a rerun reuse the tarball.
WORK="$OUT_DIR/.work"
rm -rf "$WORK"
mkdir -p "$WORK"
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
        # Use --cc with target+sysroot, and push include paths via extra-cflags
        # so they are not mangled by make's word-splitting of CC.
        CC_TOOL="clang --target=${HOST_TRIPLE} --sysroot=${SYSROOT}"
        CXX_TOOL="clang++ --target=${HOST_TRIPLE} --sysroot=${SYSROOT}"
        EXTRA_CFLAGS="-isystem ${SYSROOT}/include/c++/v1 -isystem ${SYSROOT}/include"
        EXTRA_CXXFLAGS="-isystem ${SYSROOT}/include/c++/v1 -isystem ${SYSROOT}/include"
      fi
      echo ">>> Toolchain cc: $CC_TOOL"
      echo ">>> Toolchain cxx: $CXX_TOOL"
      echo ">>> Toolchain extra-cflags: $EXTRA_CFLAGS"
      CONFIGURE_FLAGS+=(
        --cc="$CC_TOOL"
        --cxx="$CXX_TOOL"
        --extra-cflags="$EXTRA_CFLAGS"
        --extra-cxxflags="$EXTRA_CXXFLAGS"
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
    #
    # gfxcapture is a Windows screen-capture *source* filter; the mod only
    # decodes, so it is dead weight. It is also the one translation unit that
    # includes <version> from libc++ through the WinRT wrl headers, and on
    # CLANGARM64 the FFmpeg source tree is on the include path, so the tree's
    # own `./version` file (plain text "8.1.2") shadows the C++20 header:
    #
    #   ./version:1:1: error: expected unqualified-id
    #       1 | 8.1.2
    #
    # -isystem cannot win against FFmpeg's own `-I.`. Disabling the filter
    # removes the only include of <version> and is a no-op for playback.
    CONFIGURE_FLAGS+=(--disable-filter=gfxcapture)
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
  # The import libraries (libavcodec.dll.a, ...) are needed to link the JNI
  # shims below. They are produced next to the DLLs by mingw's `--out-implib`.
  # Without them the shim link fails with "cannot find -lavcodec".
  find . -maxdepth 2 -name '*.dll.a' -exec cp -f {} "$PREFIX/lib/" \;

  # ── JavaCPP JNI shim source generation (windows-x86 / windows-aarch64) ─────
  # org.bytedeco does not publish windows-x86 / windows-aarch64 classifiers, so
  # there is no ready-made jniavcodec.dll for these two platforms. We generate
  # the shim sources ourselves with JavaCPP's Builder: it reads the
  # org.bytedeco.ffmpeg.global.* classes inside ffmpeg.jar (which carry the
  # JavaCPP annotations describing every FFmpeg function the mod needs) and
  # emits one .cpp per library. The .cpp files are architecture-agnostic; the
  # MSYS2 CI runner compiles them with the same mingw target triple used for
  # FFmpeg itself, producing jniavcodec.dll … jniswscale.dll.
  #
  # javacpp-1.5.14.jar and ffmpeg-8.1.2-1.5.14.jar are version-pinned in
  # natives.yml; they must match FFMPEG_VER/gradle's ffmpeg preset so the ABI
  # (avcodec-62.dll ...) lines up exactly.
  JNI_DIR="$WORK/jni-src"
  mkdir -p "$JNI_DIR"
  # Jar cache lives under OUT_DIR (a real Windows path) rather than $WORK:
  # MSYS2's /tmp is a mount inside the MSYS root and curl's writes there were
  # observed to fail with "curl: (23) client returned ERROR on write".
  JAR_CACHE="$OUT_DIR/.jars"
  mkdir -p "$JAR_CACHE"
  JAVACPP_JAR="$JAR_CACHE/javacpp-1.5.14.jar"
  FFMPEG_JAR="$JAR_CACHE/ffmpeg-8.1.2-1.5.14.jar"
  if [[ ! -s "$JAVACPP_JAR" || ! -s "$FFMPEG_JAR" ]]; then
    echo ">>> Downloading javacpp + ffmpeg preset jars for JNI shim generation"
    curl -fL --retry 4 --retry-all-errors --retry-delay 3 \
      -o "$JAVACPP_JAR" "https://repo1.maven.org/maven2/org/bytedeco/javacpp/1.5.14/javacpp-1.5.14.jar"
    curl -fL --retry 4 --retry-all-errors --retry-delay 3 \
      -o "$FFMPEG_JAR" "https://repo1.maven.org/maven2/org/bytedeco/ffmpeg/8.1.2-1.5.14/ffmpeg-8.1.2-1.5.14.jar"
  fi
  ls -la "$JAR_CACHE"
  # The JavaCPP Builder needs a JDK. On MSYS2, JAVA_HOME comes from the host as a
  # Windows path (e.g. C:\Program Files\Eclipse Adoptium\jdk-17.x). Convert it to
  # an MSYS2 path (/c/Program Files/...) so `java` resolves correctly.
  JAVA_BIN=""
  if [[ -n "${JAVA_HOME:-}" ]]; then
    # Step 1: turn backslashes into forward slashes (for %PATH% etc)
    JAVA_HOME_MSYS2="${JAVA_HOME//\\//}"
    # Step 2: strip the Windows drive prefix C: -> /c
    JAVA_HOME_MSYS2="${JAVA_HOME_MSYS2#C:/}"
    # Step 3: prepend /c to make MSYS2 resolve it as a drive root
    JAVA_HOME_MSYS2="/${JAVA_HOME_MSYS2}"
    JAVA_BIN="${JAVA_HOME_MSYS2}/bin/java"
  fi
  if [[ -z "$JAVA_BIN" || ! -x "$JAVA_BIN" ]]; then
    JAVA_BIN="$(command -v java || true)"
  fi
  if [[ -z "$JAVA_BIN" ]]; then
    echo "ERROR: no java found; the JNI shim generation stage needs a JDK" >&2
    exit 1
  fi
  echo "    java = $JAVA_BIN"
  #
  # Which classes must be passed: BOTH the `global.<lib>` function bindings AND
  # every class of the `org.bytedeco.ffmpeg.<lib>` target package. Feeding only
  # `global.*` produces 191 symbols for avcodec where the official DLL has 919 --
  # the missing 728 are the target-package Pointer subclasses (AVCodecContext,
  # AVPacket, ...). The official jniavcodec.dll symbol count is reproduced
  # exactly (avcodec 919, avutil 2183, avformat 767, avfilter 336, swscale 120,
  # swresample 35, avdevice 75) only when both sets are passed. Those classes
  # already exist inside ffmpeg.jar, so no C header parsing is needed (passing
  # the `presets.*` classes instead would require the full FFmpeg source tree
  # on the include path, plus a configure-generated libavutil/ffversion.h and
  # upstream's log_callback.h).
  echo ">>> Generating JavaCPP JNI shim sources for $PLATFORM"
  SHIM_CLASSES=()
  # The JDK's `jar` tool enumerates the jar without needing `unzip`, which the
  # MSYS2 shell does not ship by default.
  JAR_BIN="$(dirname "$JAVA_BIN")/jar"
  if [[ ! -x "$JAR_BIN" ]]; then
    JAR_BIN="$(command -v jar || true)"
  fi
  if [[ -z "$JAR_BIN" ]]; then
    echo "ERROR: no jar tool found; cannot enumerate $FFMPEG_JAR" >&2
    exit 1
  fi
  for lib in avcodec avdevice avfilter avformat avutil swresample swscale; do
    SHIM_CLASSES+=("org.bytedeco.ffmpeg.global.$lib")
    # Every non-nested class of the target package, listed straight from the jar.
    while IFS= read -r entry; do
      [[ -z "$entry" ]] && continue
      SHIM_CLASSES+=("${entry%.class}")
    done < <("$JAR_BIN" tf "$FFMPEG_JAR" \
               | grep "^org/bytedeco/ffmpeg/$lib/" \
               | grep '\.class$' \
               | grep -v '\$' \
               | sed 's#/#.#g; s#\.class$##')
  done
  echo "    ${#SHIM_CLASSES[@]} classes -> $JNI_DIR"
  "$JAVA_BIN" -cp "$JAVACPP_JAR:$FFMPEG_JAR" \
    org.bytedeco.javacpp.tools.Builder \
    -d "$JNI_DIR" \
    -classpath "$FFMPEG_JAR" \
    -nocompile \
    "${SHIM_CLASSES[@]}"
  # Builder also emits a jnijavacpp.cpp helper; keep it alongside so the
  # compile step can link it into each shim DLL.
  cp -f "$JNI_DIR"/*.cpp "$PREFIX/lib/"

  # ── Compile JavaCPP JNI shim sources into DLLs ──────────────────────────────
  # The generated .cpp files (jniavcodec.cpp ... jniswscale.cpp + jnijavacpp.cpp)
  # must be compiled with the SAME mingw target as FFmpeg itself, and linked
  # against the just-built avcodec-62.dll etc. in $PREFIX/lib.
  echo ">>> Compiling JavaCPP JNI shims for $PLATFORM"
  pushd "$PREFIX/lib" >/dev/null

  # Reuse the exact CC/CXX/flags resolved during FFmpeg configure so ABI matches.
  SHIM_CC="${CC_TOOL:-gcc}"
  SHIM_CXX="${CXX_TOOL:-g++}"
  SHIM_CXXFLAGS="${EXTRA_CXXFLAGS:-}"

  # Headers: FFmpeg's own source tree + the bytedeco log_callback.h shim header
  # (which is NOT inside ffmpeg.jar, it comes from javacpp-presets resources).
  FFmpeg_SRC="$WORK/ffmpeg-${FFMPEG_VER}"
  JNI_HDR="$PREFIX/lib/jni-headers"
  mkdir -p "$JNI_HDR"
  # log_callback.h is part of javacpp-presets ffmpeg module resources; embed it
  # inline here so we don't depend on an extra network round-trip.
  cat > "$JNI_HDR/log_callback.h" <<'EOF'
/*
 * Copyright (C) 2015 Samuel Audet
 * Licensed under Apache-2.0 or GPLv2+ (Classpath exception).
 */
#include <libavutil/log.h>
typedef void (*LogCallback)(int level, const char* msg);
static LogCallback logCallback;
void log_callback(void* ptr, int level, const char* fmt, va_list vl) {
    static int print_prefix = 1;
    char line[1024];
    if ((level & 0xff) > av_log_get_level()) {
        return;
    }
    av_log_format_line(ptr, level, fmt, vl, line, sizeof(line), &print_prefix);
    logCallback(level, line);
}

void setLogCallback(LogCallback lc) {
    av_log_set_callback(log_callback);
    logCallback = lc;
}
EOF

  # Linker must see the FFmpeg import libraries (.dll.a) we just built.
  # Those are named libavcodec.dll.a, libavutil.dll.a, ... (no version suffix).
  # -Wl,--enable-auto-image-base avoids the default image base clash of 7 DLLs.
  # -Wl,--out-implib produces the import lib for each shim (not strictly needed).
  #
  # -static-libgcc / -static-libstdc++ are GCC driver options. CLANGARM64 is a
  # clang + libc++ toolchain and rejects `-static-libstdc++` outright
  # ("argument unused during compilation"), so they are only added for the gcc
  # branch. clang links compiler-rt statically by default anyway.
  SHIM_LDFLAGS=()
  if [[ "$PLATFORM" != "windows-aarch64" ]]; then
    SHIM_LDFLAGS+=(-static-libgcc -static-libstdc++)
  fi

  for lib in avcodec avdevice avfilter avformat avutil swresample swscale; do
    echo "  Building jni$lib.dll ..."
    $SHIM_CXX -shared -O2 -std=c++17 \
      -I"$FFmpeg_SRC" \
      -I"$JNI_HDR" \
      $SHIM_CXXFLAGS \
      -o "jni$lib.dll" \
      "jni$lib.cpp" "jnijavacpp.cpp" \
      -L. -l${lib} \
      -Wl,--enable-auto-image-base \
      -Wl,--out-implib,libjni$lib.dll.a \
      "${SHIM_LDFLAGS[@]}" \
      2>&1 | tail -3
    if [[ ! -f "jni$lib.dll" ]]; then
      echo "ERROR: jni$lib.dll not produced" >&2
      exit 1
    fi
  done

  popd >/dev/null
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

# Windows builds must also include the JavaCPP JNI shims (jniavcodec.dll etc.),
# otherwise NativesDownloader.hasFfmpeg() will return false and the mod falls
# back to libvlc silently.
if [[ "$PLATFORM" == windows-* ]]; then
  MISSING_SHIM=0
  for shim in jniavcodec jniavdevice jniavfilter jniavformat jniavutil jniswresample jniswscale; do
    if ! find . -maxdepth 1 -type f -name "${shim}*" | grep -q .; then
      echo "ERROR: ${shim}.dll missing from $OUT_LIBDIR" >&2
      MISSING_SHIM=1
    fi
  done
  if [[ $MISSING_SHIM -ne 0 ]]; then
    exit 1
  fi
  echo "  ✓ JavaCPP JNI shims present"
fi

echo ">>> Done: FFmpeg $FFMPEG_VER for $PLATFORM -> $OUT_LIBDIR"