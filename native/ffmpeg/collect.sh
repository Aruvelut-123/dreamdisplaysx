#!/usr/bin/env bash
#
# Collects the official bytedeco FFmpeg runtime for one target platform.
#
# Why this exists
# ---------------
# The mod no longer builds FFmpeg itself: org.bytedeco publishes prebuilt
# classifier jars on Maven Central, one per platform, and every one of them is
# built with the flags this project needs. Verified against
# ffmpeg-8.1.2-1.5.14-android-arm64.jar (the Android variant):
#
#   --enable-mediacodec  --enable-jni
#   h264_mediacodec / hevc_mediacodec / amediacodec_ndk registration names
#   AMediaCodec_* (NDK) symbols present in libavcodec.so
#   -lc++_static, i.e. libc++ is linked in and nothing external is required
#
# So "building the natives" means downloading the right classifier, verifying
# that the decoder this project actually calls is still registered in it, and
# repacking the shared objects into the tarball layout the in-mod downloader
# already knows how to consume.
#
# Usage:
#   collect.sh <platform>            e.g. linux-x86_64, android-arm64
#   FFPKG_VER=8.1.2-1.5.14 collect.sh android-arm64
#
# Environment:
#   OUT_DIR     output directory (default $PWD/out)
#   FFPKG_VER   org.bytedeco:ffmpeg version (default 8.1.2-1.5.14)
#
# Output layout (matches what NativesDownloader.flattenPlatformDir expects):
#   $OUT_DIR/<os>/<arch>/*.so | *.dylib | *.dll
#
set -euo pipefail

PLATFORM="${1:-}"
FFPKG_VER="${FFPKG_VER:-8.1.2-1.5.14}"

if [[ -z "$PLATFORM" ]]; then
  echo "usage: collect.sh <platform> [ffmpeg-version-env]" >&2
  echo "platforms: linux-x86_64 linux-arm64 macosx-x86_64 macosx-arm64" >&2
  echo "           windows-x86_64 android-arm64 android-x86_64" >&2
  exit 2
fi

# Map the Maven classifier to the <os>/<arch> directory the mod already uses.
# Android keeps the existing "android-<arch>" platform key; the native dir name
# follows the same OsInfo spelling so the downloader's flattening stays valid.
case "$PLATFORM" in
  linux-x86_64)    OS_DIR=Linux;   ARCH_DIR=x86_64  ;;
  linux-arm64)     OS_DIR=Linux;   ARCH_DIR=aarch64 ;;
  macosx-x86_64)   OS_DIR=Mac;     ARCH_DIR=x86_64  ;;
  macosx-arm64)    OS_DIR=Mac;     ARCH_DIR=aarch64 ;;
  windows-x86_64)  OS_DIR=Windows; ARCH_DIR=x86_64  ;;
  android-arm64)   OS_DIR=Android; ARCH_DIR=aarch64 ;;
  android-x86_64)  OS_DIR=Android; ARCH_DIR=x86_64  ;;
  *)
    echo "ERROR: unsupported platform '$PLATFORM'" >&2
    exit 2
    ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$PWD/out}"
OUT_LIBDIR="$OUT_DIR/$OS_DIR/$ARCH_DIR"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

JAR_URL="https://repo1.maven.org/maven2/org/bytedeco/ffmpeg/${FFPKG_VER}/ffmpeg-${FFPKG_VER}-${PLATFORM}.jar"
JAR_FILE="$WORK/ffmpeg.jar"

echo ">>> Fetching bytedeco FFmpeg $FFPKG_VER for $PLATFORM"
curl -fL --retry 4 --retry-all-errors --retry-delay 3 -o "$JAR_FILE" "$JAR_URL"

echo ">>> Extracting shared objects"
mkdir -p "$OUT_LIBDIR"
# The classifier jars carry the payload in one of two layouts:
#   desktop : org/bytedeco/ffmpeg/<classifier>/*  (libav*.so.N / .dylib / .dll)
#   android : lib/<abi>/*.so
# Both are flattened to the same destination.
if unzip -l "$JAR_FILE" | grep -q "org/bytedeco/ffmpeg/${PLATFORM}/"; then
  unzip -j -q "$JAR_FILE" "org/bytedeco/ffmpeg/${PLATFORM}/*" -d "$OUT_LIBDIR"
else
  case "$PLATFORM" in
    android-arm64)  ABI=arm64-v8a ;;
    android-x86_64) ABI=x86_64    ;;
    *) echo "ERROR: no payload directory inside $PLATFORM jar" >&2; exit 1 ;;
  esac
  unzip -j -q "$JAR_FILE" "lib/${ABI}/*" -d "$OUT_LIBDIR"
fi

# The jars also carry the ffmpeg/ffprobe command-line tools. They are useless to
# a JVM playback path and only inflate the artifact, so drop executables while
# keeping every shared object.
find "$OUT_LIBDIR" -maxdepth 1 -type f ! -name '*.so*' ! -name '*.dylib' ! -name '*.dll' -delete

# ── Verify the runtime is actually the one this project calls ────────────────
#
# A decoder that silently disappears from a newer build would not fail the
# download: the mod would load FFmpeg fine and then report "no suitable decoder"
# at playback time, on a device, in a log we cannot reproduce. So the symbols
# this project depends on are asserted here, at packaging time.

SO_FILES=$(find "$OUT_LIBDIR" -maxdepth 1 -type f \( -name 'libavcodec*' -o -name 'avcodec-*' \) | head -1)
if [[ -z "$SO_FILES" ]]; then
  echo "ERROR: no libavcodec found for $PLATFORM" >&2
  ls -la "$OUT_LIBDIR" >&2
  exit 1
fi

if ! command -v strings >/dev/null 2>&1; then
  echo "WARNING: 'strings' not available; skipping symbol verification" >&2
else
  for decoder in h264 hevc; do
    if ! strings "$SO_FILES" | grep -qx "${decoder}_mediacodec"; then
      case "$PLATFORM" in
        android-*)
          echo "ERROR: ${decoder}_mediacodec missing from $PLATFORM libavcodec" >&2
          exit 1
          ;;
        *)
          # Desktop builds ship no mediacodec at all: there is no Android
          # MediaCodec to talk to. That is expected, not a packaging failure.
          echo "  (desktop build: ${decoder}_mediacodec absent, as expected)"
          ;;
      esac
    fi
  done
  # Mach-O prefixes every C symbol with an underscore (_avcodec_open2), so an
  # anchored ^avcodec_ match only works on ELF. Anchor on the underscore too and
  # accept both spellings.
  if ! strings "$SO_FILES" | grep -qE '_?avcodec_(open2|alloc_context3|find_decoder)'; then
    echo "ERROR: libavcodec does not export avcodec_* symbols" >&2
    exit 1
  fi
  echo "  ✓ decoder registration verified"
fi

# dylibs/dlls are not covered by the .so guard above; make sure every platform
# still ends up with the core codec library it needs.
CORE_COUNT=$(find "$OUT_LIBDIR" -maxdepth 1 -type f \
  \( -name 'libavcodec*' -o -name 'avcodec-*' \) | wc -l)
if [[ "$CORE_COUNT" -lt 1 ]]; then
  echo "ERROR: libavcodec missing after cleanup" >&2
  exit 1
fi

echo ">>> Files collected for $PLATFORM:"
find "$OUT_LIBDIR" -maxdepth 1 -type f | sed 's|.*/||' | sort
echo ">>> Total: $(find "$OUT_LIBDIR" -maxdepth 1 -type f | wc -l) files, $(du -sh "$OUT_LIBDIR" | cut -f1)"
echo ">>> Done collecting FFmpeg $FFPKG_VER for $PLATFORM"
