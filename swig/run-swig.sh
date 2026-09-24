#!/bin/bash

BOOST_ROOT=${BOOST_ROOT:-$DEVELOPMENT_ROOT/boost_1_89_0}
LIBTORRENT_ROOT=deps/libtorrent

# in-place sed that works with both BSD (macOS) and GNU sed
function sedi() {
    sed -i.bak "$@" && rm -f "${@: -1}.bak"
}

function fixCode() {
    sedi 's/) &;/)  ;/g' ${LIBTORRENT_ROOT}/include/libtorrent/file_storage.hpp
    sedi 's/) & noexcept;/)   noexcept;/g' ${LIBTORRENT_ROOT}/include/libtorrent/file_storage.hpp
    sedi 's/(time_point32::min)();/time_point32::min();/g' ${LIBTORRENT_ROOT}/include/libtorrent/announce_entry.hpp
    sedi 's/userdata = client_data_t{});/userdata);/g' ${LIBTORRENT_ROOT}/include/libtorrent/torrent_handle.hpp
    sedi 's/(std::min)(/std::min(/g' ${LIBTORRENT_ROOT}/include/libtorrent/file_storage.hpp
}

function refixCode() {
    sedi 's/)  ;/) \&;/g' ${LIBTORRENT_ROOT}/include/libtorrent/file_storage.hpp
    sedi 's/)   noexcept;/) \& noexcept;/g' ${LIBTORRENT_ROOT}/include/libtorrent/file_storage.hpp
    sedi 's/time_point32::min();/(time_point32::min)();/g' ${LIBTORRENT_ROOT}/include/libtorrent/announce_entry.hpp
    sedi 's/userdata);/userdata = client_data_t{});/g' ${LIBTORRENT_ROOT}/include/libtorrent/torrent_handle.hpp
    sedi 's/std::min(/(std::min)(/g' ${LIBTORRENT_ROOT}/include/libtorrent/file_storage.hpp
}

JAVA_SRC_OUTPUT=../src/main/java/org/libtorrent4j/swig

# remove the previously generated sources, but keep the hand-written ones
# (libtorrent_ext.java, see ext_jni.cpp)
mkdir -p ${JAVA_SRC_OUTPUT}
find ${JAVA_SRC_OUTPUT} -type f ! -name libtorrent_ext.java -delete

fixCode

swig -c++ -java -o libtorrent_jni.cpp \
    -outdir ${JAVA_SRC_OUTPUT} \
    -package org.libtorrent4j.swig \
    -I${BOOST_ROOT} \
    -I${LIBTORRENT_ROOT}/include \
    -DBOOST_ASIO_DECL="" \
    -DBOOST_NO_TYPEID=1 \
    -DBOOST_NO_EXCEPTIONS \
    -DBOOST_POSIX_API=1 \
    -DBOOST_SYSTEM_NOEXCEPT="" \
    -DBOOST_SYSTEM_DECL="" \
    -DBOOST_SYSTEM_NO_DEPRECATED=1 \
    -DBOOST_SYSTEM_CONSTEXPR \
    -DBOOST_NO_IOSTREAM \
    -DBOOST_SYMBOL_VISIBLE \
    -DBOOST_NOEXCEPT="" \
    -DBOOST_NOEXCEPT_OR_NOTHROW="" \
    -DTORRENT_ABI_VERSION=4 \
    -DTORRENT_VERSION_NAMESPACE_2="" \
    -DTORRENT_VERSION_NAMESPACE_2_END="" \
    -DTORRENT_VERSION_NAMESPACE_3="" \
    -DTORRENT_VERSION_NAMESPACE_3_END="" \
    -DTORRENT_VERSION_NAMESPACE_4="" \
    -DTORRENT_VERSION_NAMESPACE_4_END="" \
    -DTORRENT_IPV6_NAMESPACE="" \
    -DTORRENT_IPV6_NAMESPACE_END="" \
    -DTORRENT_CFG="TORRENT_CFG" \
    -DTORRENT_NO_DEPRECATE \
    -DTORRENT_DEPRECATED_EXPORT="" \
    -DTORRENT_DEPRECATED_MEMBER="" \
    -DTORRENT_DEPRECATED_ENUM="" \
    -DTORRENT_DEPRECATED="" \
    -DTORRENT_EXPORT="" \
    -DTORRENT_UNEXPORT="" \
    -DTORRENT_EXTRA_EXPORT="" \
    -DTORRENT_FORMAT\(x,y\)="" \
    -DNDEBUG=1 \
    -D_bit="" \
    -Dfinal="" \
    libtorrent.i

refixCode
