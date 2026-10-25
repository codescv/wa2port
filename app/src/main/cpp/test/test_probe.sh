#!/usr/bin/env bash
# Builds wa2movie_probe for an ABI, pushes it with the FFmpeg libs to /data/local/tmp/wa2movie and
# decodes each given on-device movie path. Usage: test_probe.sh <adb-serial> <abi> <device-movie>...
set -euo pipefail
SERIAL="$1"; ABI="$2"; shift 2
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CPP="$HERE/.."
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.0.12077973}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
case "$ABI" in arm64-v8a) CC="$TC/aarch64-linux-android26-clang" ;; x86_64) CC="$TC/x86_64-linux-android26-clang" ;; esac
FF="$CPP/ffmpeg/$ABI"
OUT="${TMPDIR:-/tmp}/wa2movie_probe_$ABI"
"$CC" -O2 -Wall -I"$FF/include" -o "$OUT" "$HERE/wa2movie_probe.c" -L"$FF/lib" \
  -lavformat -lavcodec -lswscale -lswresample -lavutil -ljnigraphics -llog
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb} -s $SERIAL"
$ADB shell mkdir -p /data/local/tmp/wa2movie
$ADB push "$OUT" /data/local/tmp/wa2movie/probe >/dev/null
$ADB push "$FF"/lib/*.so /data/local/tmp/wa2movie/ >/dev/null
for mv in "$@"; do
  echo "== $mv"
  $ADB shell "cd /data/local/tmp/wa2movie && LD_LIBRARY_PATH=. ./probe '$mv'"
done
