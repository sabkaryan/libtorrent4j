#!/bin/bash

../write-revision-header.sh
docker run --rm -i -e LT4J_CHECKED -e LT4J_JOBS -v "$PWD/../../":/libtorrent4j lt4j:latest "/b2-arm64.sh"

# the checked build is for test rigs, it is not packaged into jars
[ -n "${LT4J_CHECKED:-}" ] && exit 0

pushd ../../
./gradlew clean
./gradlew jar
./gradlew nativeAndroidArm64Jar
popd
