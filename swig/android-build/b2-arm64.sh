#!/bin/bash

rm -rf /libtorrent4j/swig/deps/libtorrent/deps/libdatachannel/deps/libjuice/build-*
rm -rf /libtorrent4j/swig/deps/libtorrent/deps/libdatachannel/deps/usrsctp/build-*

alias ranlib='/android-ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ranlib'
export ANDROID_TOOLCHAIN=/android-ndk/toolchains/llvm/prebuilt/linux-x86_64
export BOOST_ROOT=/boost
export OPENSSL_ROOT=/openssl-arm64
export LIBTORRENT_ROOT=/libtorrent4j/swig/deps/libtorrent
export CXX=${ANDROID_TOOLCHAIN}/bin/aarch64-linux-android24-clang++
export CC=${ANDROID_TOOLCHAIN}/bin/aarch64-linux-android24-clang
export AR=${ANDROID_TOOLCHAIN}/bin/llvm-ar
export LD=${ANDROID_TOOLCHAIN}/bin/ld
export RANLIB=${ANDROID_TOOLCHAIN}/bin/llvm-ranlib

cd /libtorrent4j/swig

# LT4J_CHECKED=1 builds a "checked" library: libtorrent's own asserts and
# invariant checks are compiled in (TORRENT_USE_ASSERTS, TORRENT_USE_INVARIANT_CHECKS),
# symbols are kept, and the output goes to bin/release-checked/. It is meant for
# test rigs only: a violated internal assumption aborts the process instead of
# silently corrupting state. The release build is unchanged.
B2_EXTRA=""
OUT_DIR=bin/release/android/arm64-v8a
if [ -n "${LT4J_CHECKED:-}" ]; then
    B2_EXTRA="asserts=on invariant-checks=on"
    OUT_DIR=bin/release-checked/android/arm64-v8a
fi

${BOOST_ROOT}/b2 -j${LT4J_JOBS:-2} --user-config=config/android-arm64-config.jam variant=release toolset=clang-arm64 target-os=android ${B2_EXTRA} location=${OUT_DIR}
if [ -z "${LT4J_CHECKED:-}" ]; then
    ${ANDROID_TOOLCHAIN}/bin/llvm-objcopy --only-keep-debug ${OUT_DIR}/libtorrent4j.so ${OUT_DIR}/libtorrent4j.so.debug
    ${ANDROID_TOOLCHAIN}/bin/llvm-strip --strip-unneeded -x ${OUT_DIR}/libtorrent4j.so
fi
${ANDROID_TOOLCHAIN}/bin/llvm-readelf -d ${OUT_DIR}/libtorrent4j.so
