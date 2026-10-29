#!/usr/bin/env bash
# Cross-compile a static ffmpeg CLI binary for Android with the NDK.
#
# WHY: the engine shells out to an ffmpeg *executable*. python-for-android's
# ffmpeg recipe builds libraries for ffpyplayer, not a CLI binary, so we build
# our own and ship it as an APK asset (android/main.py unpacks it and exports
# FFMPEG_PATH).
#
# Output: android/assets/ffmpeg/<arch>/ffmpeg
#
# Usage:
#   ANDROID_NDK=/path/to/ndk bash android/ffmpeg_ndk_build.sh [arm64|armv7|both]
#
# On GitHub Actions the NDK is preinstalled under $ANDROID_HOME/ndk/<ver> and the
# workflow locates it automatically.
#
# NOTE ON NETWORKS: ffmpeg.org and code.videolan.org are unreachable from
# restricted sandboxes. x264 is fetched from its official GitHub mirror (an
# allow-listed host); the ffmpeg tarball needs ffmpeg.org, so run this on CI or
# a normal dev machine.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_ROOT="$REPO/android/assets/ffmpeg"
JOBS="$(nproc 2>/dev/null || echo 4)"

FFMPEG_VERSION="${FFMPEG_VERSION:-7.0.2}"
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
# Official mirror of x264 (code.videolan.org/videolan/x264), on an allow-listed host.
X264_URL="https://codeload.github.com/mirror/x264/tar.gz/refs/heads/stable"

NDK="${ANDROID_NDK:-}"
if [ -z "$NDK" ] && [ -n "${ANDROID_HOME:-}" ]; then
  NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "ERROR: set ANDROID_NDK to your NDK root (or ANDROID_HOME with an NDK installed)" >&2
  exit 1
fi
echo "==> NDK: $NDK"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() { # fetch <url> <dest>
  if command -v curl > /dev/null; then curl -fsSL -o "$2" "$1"
  else wget -qO "$2" "$1"; fi
}

build_one() {
  local arch="$1"
  # ffarch is ffmpeg's --arch: an architecture FAMILY (aarch64 / arm), not a CPU
  # name. Passing "armv8-a" there makes configure reject the target outright -
  # that bug cost a CI run. CPU tuning belongs in --cpu, and is omitted here so
  # the clang triple's own defaults apply.
  local triple ffarch api
  case "$arch" in
    arm64)  triple="aarch64-linux-android";    ffarch="aarch64"; api=21 ;;
    armv7)  triple="armv7a-linux-androideabi"; ffarch="arm";     api=21 ;;
    *) echo "unknown arch: $arch" >&2; return 1 ;;
  esac

  local toolchain="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
  local cc="$toolchain/bin/${triple}${api}-clang"
  [ -x "$cc" ] || { echo "ERROR: no compiler at $cc" >&2; return 1; }

  local dest="$OUT_ROOT/$arch"
  local build="$WORK/build-$arch"
  local x264_prefix="$build/x264-prefix"
  mkdir -p "$dest" "$build" "$x264_prefix"

  # ---- x264 (static lib) -------------------------------------------------
  # ffmpeg has no native H.264 *encoder*, and compatibility mode must be able to
  # encode H.264 on device, so libx264 is not optional here.
  echo "==> [$arch] fetching x264"
  fetch "$X264_URL" "$WORK/x264-$arch.tar.gz"
  tar -xf "$WORK/x264-$arch.tar.gz" -C "$build"
  local x264_src
  x264_src="$(find "$build" -maxdepth 1 -type d -name 'x264-*' | head -1)"

  echo "==> [$arch] building x264"
  ( cd "$x264_src" && \
    CC="$cc" ./configure \
      --host="$triple" \
      --sysroot="$toolchain/sysroot" \
      --prefix="$x264_prefix" \
      --enable-static --disable-cli --disable-opencl \
      --enable-pic \
      --cross-prefix="" \
      > x264-configure.log 2>&1 && \
    make -j"$JOBS" > x264-make.log 2>&1 && \
    make install > x264-install.log 2>&1 ) \
    || { echo "x264 build failed:" >&2; tail -25 "$x264_src"/x264-*.log >&2; return 1; }

  # ---- ffmpeg ------------------------------------------------------------
  if [ ! -f "$WORK/ffmpeg.tar.xz" ]; then
    echo "==> fetching $FFMPEG_URL"
    fetch "$FFMPEG_URL" "$WORK/ffmpeg.tar.xz"
  fi
  if [ ! -d "$WORK/ffmpeg-$FFMPEG_VERSION" ]; then
    tar -xf "$WORK/ffmpeg.tar.xz" -C "$WORK"
  fi

  echo "==> [$arch] configuring ffmpeg"
  # Deliberately NOT using --disable-everything: a hand-picked component list
  # silently omits bitstream filters and parsers the mp4/webm muxers need
  # (h264_mp4toannexb, aac_adtstoasc, vp9 superframe split), which only fail at
  # runtime on a real video. Default components + --enable-small is the safe
  # size/robustness tradeoff for an APK asset.
  ( cd "$build" && \
    "$WORK/ffmpeg-$FFMPEG_VERSION/configure" \
      --target-os=android \
      --arch="$ffarch" \
      --cc="$cc" \
      --cxx="${cc}++" \
      --ar="$toolchain/bin/llvm-ar" \
      --ranlib="$toolchain/bin/llvm-ranlib" \
      --strip="$toolchain/bin/llvm-strip" \
      --sysroot="$toolchain/sysroot" \
      --enable-cross-compile \
      --enable-pic \
      --enable-gpl \
      --enable-libx264 \
      --enable-small \
      --disable-doc --disable-debug --disable-ffplay \
      --disable-autodetect \
      --extra-cflags="-I$x264_prefix/include -O3" \
      --extra-ldflags="-L$x264_prefix/lib -static-libstdc++" \
      > configure.log 2>&1 && \
    make -j"$JOBS" > make.log 2>&1 ) \
    || { echo "ffmpeg build failed:" >&2; tail -25 "$build"/configure.log "$build"/make.log >&2; return 1; }

  local bin="$build/ffmpeg"
  [ -x "$bin" ] || { echo "ERROR: ffmpeg binary not produced for $arch" >&2; return 1; }

  "$toolchain/bin/llvm-strip" "$bin" || true
  cp "$bin" "$dest/ffmpeg"
  chmod 755 "$dest/ffmpeg"
  echo "    -> $dest/ffmpeg ($(du -h "$dest/ffmpeg" | cut -f1))"
}

case "${1:-both}" in
  both) build_one arm64; build_one armv7 ;;
  *)    build_one "$1" ;;
esac

echo "==> done"
