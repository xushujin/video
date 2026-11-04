#!/usr/bin/env bash
#
# 自己编 FFmpeg（LGPL，--disable-gpl）+ media3 的 JNI 桥，产出 libffmpegJNI.so。
#
# 为什么不用现成的 AAR：nextlib / Jellyfin 的白名单里都没有 MP2
# （老 AVI 里最常见的音轨），只有自己编才能补上。
#
# 用法：
#   NDK=/path/to/ndk ./tools/build_ffmpeg.sh /path/to/ffmpeg-7.1.1
#
# 产物会直接写进 src/main/jniLibs/<abi>/libffmpegJNI.so，
# 所以 CI 不需要 NDK —— 只有想改解码器白名单时才需要重跑这个脚本。
set -euo pipefail

FFMPEG_SRC="${1:?用法: build_ffmpeg.sh <ffmpeg 源码目录>}"
NDK="${NDK:?请设置 NDK 环境变量指向 NDK 根目录}"
HOST_TAG="darwin-x86_64"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
API=26
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_ROOT="${BUILD_ROOT:-/tmp/qingying-ffmpeg}"

# 音轨解码器白名单。mp2 是这次的重点，其余保持与之前一致。
DECODERS="mp2,mp3,aac,ac3,eac3,dca,mlp,truehd,flac,alac,vorbis,opus,pcm_s16le,pcm_s16be,pcm_f32le,pcm_u8"
PARSERS="mpegaudio,aac,ac3,dca,flac,opus,vorbis"

build_abi() {
  local abi="$1" arch="$2" cpu="$3" cc_prefix="$4"
  local prefix="$BUILD_ROOT/$abi"
  echo "==> 编译 FFmpeg for $abi"
  rm -rf "$prefix"
  mkdir -p "$prefix"

  pushd "$FFMPEG_SRC" >/dev/null
  make distclean >/dev/null 2>&1 || true
  ./configure \
    --prefix="$prefix" \
    --target-os=android \
    --arch="$arch" \
    --cpu="$cpu" \
    --enable-cross-compile \
    --sysroot="$TOOLCHAIN/sysroot" \
    --cc="$TOOLCHAIN/bin/${cc_prefix}${API}-clang" \
    --cxx="$TOOLCHAIN/bin/${cc_prefix}${API}-clang++" \
    --ar="$TOOLCHAIN/bin/llvm-ar" \
    --nm="$TOOLCHAIN/bin/llvm-nm" \
    --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
    --strip="$TOOLCHAIN/bin/llvm-strip" \
    --enable-static --disable-shared --enable-pic \
    --disable-gpl --disable-nonfree \
    --disable-programs --disable-doc --disable-avdevice --disable-avfilter \
    --disable-postproc --disable-network --disable-encoders --disable-muxers \
    --disable-avformat --disable-swscale \
    --disable-devices --disable-filters --disable-bsfs --disable-protocols \
    --disable-zlib --disable-iconv \
    --disable-everything \
    --enable-decoder="$DECODERS" \
    --enable-parser="$PARSERS" \
    --enable-swresample \
    >"$BUILD_ROOT/$abi-configure.log" 2>&1

  make -j"$(sysctl -n hw.ncpu)" >"$BUILD_ROOT/$abi-make.log" 2>&1
  make install >>"$BUILD_ROOT/$abi-make.log" 2>&1
  popd >/dev/null
}

build_abi arm64-v8a aarch64 armv8-a aarch64-linux-android
build_abi armeabi-v7a arm armv7-a armv7a-linux-androideabi

echo "==> 编译 JNI 桥"
for abi in arm64-v8a armeabi-v7a; do
  prefix="$BUILD_ROOT/$abi"
  case "$abi" in
    arm64-v8a) cc="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++" ;;
    armeabi-v7a) cc="$TOOLCHAIN/bin/armv7a-linux-androideabi${API}-clang++" ;;
  esac
  mkdir -p "$OUT_DIR/src/main/jniLibs/$abi"
  "$cc" -shared -fPIC -O2 -std=c++11 -Wl,-Bsymbolic \
    -I"$prefix/include" \
    "$OUT_DIR/src/main/jni/ffmpeg_jni.cc" \
    "$prefix/lib/libavcodec.a" "$prefix/lib/libswresample.a" "$prefix/lib/libavutil.a" \
    -llog -landroid -lm -latomic \
    -o "$OUT_DIR/src/main/jniLibs/$abi/libffmpegJNI.so"
  ls -lh "$OUT_DIR/src/main/jniLibs/$abi/libffmpegJNI.so"
done

echo "完成：$OUT_DIR/src/main/jniLibs/<abi>/libffmpegJNI.so"
