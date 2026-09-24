#!/bin/bash

export LT4J_LIBTORRENT_REVISION=${LT4J_LIBTORRENT_REVISION:-$(git -C ../deps/libtorrent rev-parse HEAD)}
docker run --rm -it -e LT4J_LIBTORRENT_REVISION -v "$PWD/../../":/libtorrent4j lt4j:latest "/b2-x86_64.sh"

pushd ../../
./gradlew clean
./gradlew jar
./gradlew nativeAndroidX64Jar
popd
