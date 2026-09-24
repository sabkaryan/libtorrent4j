#!/bin/bash

export LT4J_LIBTORRENT_REVISION=${LT4J_LIBTORRENT_REVISION:-$(git -C ../deps/libtorrent rev-parse HEAD)}
docker run --rm -i -e LT4J_CHECKED -e LT4J_JOBS -e LT4J_LIBTORRENT_REVISION -v "$PWD/../../":/libtorrent4j lt4j:latest "/b2-arm.sh"

# the checked build is for test rigs, it is not packaged into jars
[ -n "${LT4J_CHECKED:-}" ] && exit 0

pushd ../../
./gradlew clean
./gradlew jar
./gradlew nativeAndroidArmJar
popd
