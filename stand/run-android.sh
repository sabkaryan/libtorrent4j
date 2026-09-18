#!/bin/bash
# Runs StandTest on an Android device or emulator through app_process.
#
#   stand/run-android.sh <libtorrent4j.so> <mode> [adb serial]
#
# <libtorrent4j.so> is a library built by swig/android-build for the device's
# ABI, either the release one (swig/bin/release/android/<abi>/libtorrent4j.so)
# or the checked one (LT4J_CHECKED=1, swig/bin/release-checked/android/<abi>/).
# <mode> is one of the modes documented in StandTest.java.
#
# Needs: ANDROID_SDK_ROOT (or ANDROID_HOME) with platform-tools and a
# build-tools version providing d8, a JDK (javac), and the wrapper's Java
# classes in build/libs/libtorrent4j-*.jar (./gradlew jar).
set -euo pipefail

SO=${1:?path to libtorrent4j.so}
MODE=${2:?mode}
SERIAL=${3:-}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?set ANDROID_SDK_ROOT}}
ADB="$SDK/platform-tools/adb"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
D8=$(ls -d "$SDK"/build-tools/*/d8 | sort -V | tail -1)
VERSION=$(sed -n 's/^version = "\(.*\)"$/\1/p' "$ROOT/build.gradle.kts")
JAR="$ROOT/build/libs/libtorrent4j-$VERSION.jar"
[ -f "$JAR" ] || { echo "no $JAR; run ./gradlew jar first" >&2; exit 2; }

OUT="$HERE/build"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
javac -source 8 -target 8 -cp "$JAR" -d "$OUT/classes" "$HERE/StandTest.java"
"$D8" --min-api 26 --output "$OUT/stand.zip" "$OUT/classes/StandTest.class" "$JAR"

T=/data/local/tmp/lt4j-stand
$ADB shell "mkdir -p $T"
$ADB push "$OUT/stand.zip" "$T/" >/dev/null
$ADB push "$SO" "$T/libtorrent4j.so" >/dev/null
$ADB logcat -c || true
# stdout of a process killed by SIGABRT is lost; the assertion text is printed
# to stderr before abort and both are shown here
$ADB shell "cd $T && rm -rf w && CLASSPATH=$T/stand.zip app_process -Dlibtorrent4j.jni.path=$T/libtorrent4j.so $T StandTest $MODE $T/w" 2>&1 || true
echo "--- fatal signals in logcat (expected only for the positive controls on a checked build):"
$ADB logcat -d 2>/dev/null | grep -E "Fatal signal|assertion failed" | head -5 || true
