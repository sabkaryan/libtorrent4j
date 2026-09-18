#!/bin/bash
# Builds, verifies and publishes a fork release: the Java library plus the
# native libraries for the requested platforms, as assets of a GitHub release
# tagged v<version>. Consumers resolve them with an Ivy repository over the
# release download URL, pattern "[revision]/[module]-[revision].[ext]".
#
#   release/publish.sh [--platforms "android-arm android-arm64 android-x86_64 macos"]
#   release/publish.sh --publish
#
# Two stages. Without --publish everything is built, verified and written to
# release/out/ (jars, SHA256SUMS, provenance.json); nothing leaves the machine.
# With --publish nothing is built: the files already in release/out/ are
# re-verified against SHA256SUMS and provenance.json, the tree must be clean at
# the commit recorded in provenance.json, and exactly those bytes are published
# — the release carries what was inspected, not a fresh rebuild of it.
# Requirements:
#   - Docker (Android platforms are built in the image from swig/android-build)
#   - a JDK for Gradle
#   - for macos: a macOS host with DEVELOPMENT_ROOT pointing at boost_1_89_0
#     (bootstrapped, b2 present) and openssl-macos (include/ + lib/*.a),
#     as swig/build-macos-arm64.sh expects, plus cmake on PATH (libdatachannel
#     builds libjuice and usrsctp with it; with CMake >= 4 set
#     CMAKE_POLICY_VERSION_MINIMUM=3.5 for their old cmake_minimum_required)
#   - gh (GitHub CLI) authenticated as the fork owner, for --publish
#
# Every native library is verified before it is packaged: ELF/Mach-O
# architecture, the exported JNI entry points (identical across platforms and
# including the fork's additions), and the absence of test-only entry points.
# A build step that reports success but produces a library of another
# architecture, or a stale one, fails here rather than at the consumer.
set -euo pipefail

PUBLISH=0
PLATFORMS="android-arm android-arm64 android-x86_64 macos"
while [ $# -gt 0 ]; do
    case "$1" in
        --publish) PUBLISH=1 ;;
        --platforms) PLATFORMS="$2"; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
VERSION=$(sed -n 's/^version = "\(.*\)"$/\1/p' build.gradle.kts)
[ -n "$VERSION" ] || { echo "cannot read version from build.gradle.kts" >&2; exit 2; }
TAG="v$VERSION"
OUT="$ROOT/release/out"
IMAGE=lt4j:latest

if [ -n "$(git status --porcelain --ignore-submodules=dirty)" ]; then
    echo "working tree is not clean; commit or stash first" >&2
    git status --short --ignore-submodules=dirty >&2
    exit 2
fi
FORK_COMMIT=$(git rev-parse HEAD)
LT_COMMIT=$(git -C swig/deps/libtorrent rev-parse HEAD)

if [ "$PUBLISH" = 1 ]; then
    [ -f "$OUT/provenance.json" ] && [ -f "$OUT/SHA256SUMS" ] \
        || { echo "nothing to publish: run without --publish first" >&2; exit 2; }
    built_at_commit=$(sed -n 's/^  "repository_commit": "\(.*\)",$/\1/p' "$OUT/provenance.json")
    [ "$built_at_commit" = "$FORK_COMMIT" ] \
        || { echo "release/out was built at $built_at_commit, HEAD is $FORK_COMMIT; rebuild" >&2; exit 2; }
    grep -q "^  \"version\": \"$VERSION\",$" "$OUT/provenance.json" \
        || { echo "provenance.json is not for version $VERSION" >&2; exit 2; }
    (cd "$OUT" && shasum -a 256 -c SHA256SUMS)
    # the checksums in provenance.json must be exactly those of SHA256SUMS
    diff <(sed -n 's/^    "\(.*\)": "\([0-9a-f]\{64\}\)",\{0,1\}$/\2  \1/p' "$OUT/provenance.json" | sort) \
         <(sort "$OUT/SHA256SUMS") \
        || { echo "provenance.json checksums differ from SHA256SUMS" >&2; exit 1; }
    NOTES="libtorrent4j $VERSION: upstream 2.1.0-38 (libtorrent $LT_COMMIT) plus torrent_handle::forget_piece(), see provenance.json.
Verify downloads against SHA256SUMS."
    git push origin "HEAD:refs/heads/$(git branch --show-current)"   # fast-forward only
    git tag -a "$TAG" -m "libtorrent4j $VERSION" "$FORK_COMMIT"
    git push origin "$TAG"
    gh release create "$TAG" --verify-tag --title "libtorrent4j $VERSION" --notes "$NOTES" \
        "$OUT"/*.jar "$OUT/SHA256SUMS" "$OUT/provenance.json"
    echo "published $TAG"
    exit 0
fi

rm -rf "$OUT" && mkdir -p "$OUT"

# ---- native libraries ------------------------------------------------------

# libdatachannel builds libjuice and usrsctp with cmake inside the source tree
# (deps/*/build-<variant>), keyed by variant only, not by target. A cache left
# by the previous platform hands back an archive of the wrong architecture
# while reporting success, so every platform starts from a clean cache, and
# the copies b2 keeps in the output directory (make targets without sources
# are never considered stale) are removed too.
clean_datachannel() { # <output dir>
    rm -rf swig/deps/libtorrent/deps/libdatachannel/deps/libjuice/build-* \
           swig/deps/libtorrent/deps/libdatachannel/deps/usrsctp/build-*
    # the whole output directory goes too: a release is built from this commit
    # only, never from objects left by an earlier build
    rm -rf "$1"
}

build_android() { # <abi-script-suffix> <abi-dir>
    local suffix=$1 abi=$2
    if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
        docker build --platform linux/amd64 -t "$IMAGE" swig/android-build
    fi
    clean_datachannel "swig/bin/release/android/$abi"
    # run the script from the repository, not the copy baked into the image
    docker run --rm -i -e LT4J_JOBS="${LT4J_JOBS:-4}" -v "$ROOT":/libtorrent4j "$IMAGE" \
        bash "/libtorrent4j/swig/android-build/b2-$suffix.sh"
    [ -f "swig/bin/release/android/$abi/libtorrent4j.so" ]
}

build_macos() {
    [ "$(uname -s)" = Darwin ] || { echo "macos artifacts can only be built on macOS" >&2; exit 2; }
    [ -n "${DEVELOPMENT_ROOT:-}" ] || { echo "set DEVELOPMENT_ROOT (boost_1_89_0 + openssl-macos)" >&2; exit 2; }
    command -v cmake >/dev/null || { echo "cmake is required on PATH for the macOS build" >&2; exit 2; }
    export CMAKE_POLICY_VERSION_MINIMUM=${CMAKE_POLICY_VERSION_MINIMUM:-3.5}
    # __FILE__ and boost's source_location embed absolute source paths. The
    # Android builds run in a container at /libtorrent4j and /boost; map the
    # host paths to the same neutral prefixes. CCC_OVERRIDE_OPTIONS reaches
    # every clang invocation, including the cmake builds of libjuice and
    # usrsctp, whose CMAKE_C_FLAGS are fixed by libdatachannel's Jamfile.
    local maps="" d
    for d in "$ROOT" "$(cd "$ROOT" && pwd -P)"; do maps="$maps +-ffile-prefix-map=$d=/libtorrent4j"; done
    for d in "$DEVELOPMENT_ROOT" "$(cd "$DEVELOPMENT_ROOT" && pwd -P)"; do
        maps="$maps +-ffile-prefix-map=$d/boost_1_89_0=/boost +-ffile-prefix-map=$d/openssl-macos=/openssl +-ffile-prefix-map=$d=/dev"
    done
    export CCC_OVERRIDE_OPTIONS="#$maps"
    clean_datachannel swig/bin/release/macos/arm64
    (cd swig && ./build-macos-arm64.sh)
    unset CCC_OVERRIDE_OPTIONS
    [ -f swig/bin/release/macos/arm64/libtorrent4j.dylib ]
}

for p in $PLATFORMS; do
    echo "### building $p"
    case "$p" in
        android-arm)    build_android arm armeabi-v7a ;;
        android-arm64)  build_android arm64 arm64-v8a ;;
        android-x86_64) build_android x86_64 x86_64 ;;
        macos)          build_macos ;;
        *) echo "unknown platform $p" >&2; exit 2 ;;
    esac
done

# ---- verification of the native libraries ------------------------------------

# exported JNI entry points of a library, one per line, sorted
jni_exports() {
    case "$1" in
        *.dylib) nm -gU "$1" | awk '{print $3}' | sed 's/^_//' ;;
        *)       nm -D --defined-only "$1" | awk '{print $3}' ;;
    esac | grep '^Java_' | sort
}

expect_arch() { # <file> <regex the `file` output must match>
    local desc
    desc=$(file -b "$1")
    if ! grep -qE "$2" <<< "$desc"; then
        echo "ARCH MISMATCH: $1: '$desc' does not match /$2/" >&2
        exit 1
    fi
}

REF_EXPORTS=""
verify_lib() { # <file> <arch regex>
    expect_arch "$1" "$2"
    local ex
    ex=$(jni_exports "$1")
    # here-strings, not `echo | grep -q`: with pipefail, grep -q exiting on the
    # first match kills echo with SIGPIPE and the pipeline reports failure —
    # a found symbol reads as missing, and a present test export as absent
    grep -q '_libtorrent_1ext_forget_1piece$' <<< "$ex" \
        || { echo "MISSING EXPORT: $1 has no libtorrent_ext.forget_piece" >&2; exit 1; }
    if grep -q 'for_1test$' <<< "$ex"; then
        echo "TEST-ONLY EXPORTS in a release library: $1" >&2; exit 1
    fi
    if [ -z "$REF_EXPORTS" ]; then
        REF_EXPORTS=$ex
    elif [ "$ex" != "$REF_EXPORTS" ]; then
        echo "EXPORT SET DIFFERS: $1 vs the first verified library" >&2
        diff <(echo "$REF_EXPORTS") <(echo "$ex") >&2 || true
        exit 1
    fi
    echo "verified $1: $(wc -l <<< "$ex" | tr -d ' ') JNI exports"
}

for p in $PLATFORMS; do
    case "$p" in
        android-arm)    verify_lib swig/bin/release/android/armeabi-v7a/libtorrent4j.so 'ELF 32-bit.*ARM' ;;
        android-arm64)  verify_lib swig/bin/release/android/arm64-v8a/libtorrent4j.so 'ELF 64-bit.*(aarch64|ARM aarch64)' ;;
        android-x86_64) verify_lib swig/bin/release/android/x86_64/libtorrent4j.so 'ELF 64-bit.*x86-64' ;;
        macos)          verify_lib swig/bin/release/macos/arm64/libtorrent4j.dylib 'Mach-O 64-bit.*arm64' ;;
    esac
done

# ---- jars ----------------------------------------------------------------------

TASKS="jar"
for p in $PLATFORMS; do
    case "$p" in
        android-arm)    TASKS="$TASKS nativeAndroidArmJar" ;;
        android-arm64)  TASKS="$TASKS nativeAndroidArm64Jar" ;;
        android-x86_64) TASKS="$TASKS nativeAndroidX64Jar" ;;
        macos)          TASKS="$TASKS nativeMacOSJar" ;;
    esac
done
./gradlew -q clean $TASKS

ASSETS="build/libs/libtorrent4j-$VERSION.jar"
for p in $PLATFORMS; do
    ASSETS="$ASSETS build/libs/libtorrent4j-$p-$VERSION.jar"
done
for a in $ASSETS; do
    [ -f "$a" ] || { echo "missing asset $a" >&2; exit 1; }
    # the jar must contain exactly one native library (or, for the core jar, none)
    n=$(unzip -l "$a" | grep -cE 'libtorrent4j\.(so|dylib)$' || true)
    case "$a" in
        */libtorrent4j-$VERSION.jar) [ "$n" = 0 ] || { echo "core jar carries a native library" >&2; exit 1; } ;;
        *) [ "$n" = 1 ] || { echo "$a carries $n native libraries, expected 1" >&2; exit 1; } ;;
    esac
    cp "$a" "$OUT/"
done

# no artifact may carry a path of the build host: the checkout, the home
# directory or the dependency root would leak into every copy downloaded
HOST_PATHS=("$ROOT" "$(pwd -P)" "$HOME")
[ -n "${DEVELOPMENT_ROOT:-}" ] && HOST_PATHS+=("$DEVELOPMENT_ROOT" "$(cd "$DEVELOPMENT_ROOT" && pwd -P)")
SCAN=$(mktemp -d)
for a in "$OUT"/*.jar; do
    unzip -q -o "$a" -d "$SCAN/$(basename "$a" .jar)"
done
for hp in "${HOST_PATHS[@]}"; do
    hits=$(grep -r -a -l -F -- "$hp" "$SCAN" || true)
    if [ -n "$hits" ]; then
        echo "HOST PATH '$hp' embedded in:" >&2
        echo "$hits" | sed "s|$SCAN/||" >&2
        rm -rf "$SCAN"
        exit 1
    fi
done
rm -rf "$SCAN"
echo "no host paths in the artifacts (${#HOST_PATHS[@]} prefixes checked)"

# ---- checksums and provenance --------------------------------------------------

cd "$OUT"
shasum -a 256 *.jar > SHA256SUMS
IMAGE_ID=$(docker image inspect "$IMAGE" --format '{{.Id}}' 2>/dev/null || echo "not used")
# libtorrent commits on top of the upstream merge-base of this fork
LT_BASE=$(git -C "$ROOT/swig/deps/libtorrent" merge-base HEAD "$(git -C "$ROOT/swig/deps/libtorrent" rev-parse --verify -q upstream/master || echo a01469c8d1f88dd83bed458ffccffab2727b9d2a)")
PATCHES=$(git -C "$ROOT/swig/deps/libtorrent" log --reverse --format='%H %s' "$LT_BASE"..HEAD \
    | awk '{h=$1; $1=""; sub(/^ /,""); gsub(/"/,"\\\""); printf "%s    {\"commit\": \"%s\", \"subject\": \"%s\"}", (NR>1?",\n":""), h, $0}')
cat > provenance.json <<EOF
{
  "artifact": "libtorrent4j",
  "version": "$VERSION",
  "tag": "$TAG",
  "repository_commit": "$FORK_COMMIT",
  "libtorrent_commit": "$LT_COMMIT",
  "libtorrent_patches": [
$PATCHES
  ],
  "platforms": "$PLATFORMS",
  "android_build_image": "$IMAGE_ID",
  "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "sha256": {
$(awk -v n="$(wc -l < SHA256SUMS | tr -d ' ')" '{printf "    \"%s\": \"%s\"%s\n", $2, $1, (NR==n?"":",")}' SHA256SUMS)
  }
}
EOF
cat SHA256SUMS
echo "provenance: $OUT/provenance.json"

# ---- self-check of what was written ----------------------------------------------

n_sums=$(wc -l < SHA256SUMS | tr -d ' ')
n_prov=$(grep -cE '^    "[^"]+\.jar": "[0-9a-f]{64}",?$' provenance.json || true)
[ "$n_sums" = "$n_prov" ] && [ "$n_sums" -gt 0 ] \
    || { echo "provenance.json lists $n_prov checksums, SHA256SUMS has $n_sums" >&2; exit 1; }
if command -v python3 >/dev/null; then python3 -m json.tool provenance.json >/dev/null; fi

echo "built and verified into $OUT. Inspect it, then run: release/publish.sh --publish"
