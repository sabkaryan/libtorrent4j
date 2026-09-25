#!/bin/bash
# Builds, verifies and publishes a fork release: the Java library plus the
# native libraries for the requested platforms, as assets of a GitHub release
# tagged v<version>. Consumers resolve them with an Ivy repository over the
# release download URL, pattern "[revision]/[module]-[revision].[ext]".
#
#   release/publish.sh [--platforms "android-arm android-arm64 android-x86_64 macos"]
#   release/publish.sh --from DIR --built-at COMMIT [--platforms "..."]
#   release/publish.sh --publish [--notes FILE] [--dry-run]
#
# Two stages. Without --publish everything is built, verified and written to
# release/out/ (jars, SHA256SUMS, provenance.json); nothing leaves the machine.
# With --from, nothing is built either: the jars are taken from DIR, where a
# build of COMMIT left them (for example a set that has already been tested),
# and HEAD may differ from COMMIT only in release/. They are verified exactly
# like built ones.
# With --publish nothing is built: the files already in release/out/ are
# verified again (checksums, provenance, the native libraries inside the jars,
# and everything that must not be published), the tree must be clean at the
# commit recorded in provenance.json, and exactly those bytes are published
# — the release carries what was inspected, not a fresh rebuild of it.
# --dry-run runs every check of --publish and stops before anything is pushed.
# Requirements:
#   - Docker (Android platforms are built in the image from swig/android-build)
#   - a JDK for Gradle
#   - for macos: a macOS host with DEVELOPMENT_ROOT pointing at boost_1_89_0
#     (bootstrapped, b2 present) and openssl-macos (include/ + lib/*.a),
#     as swig/build-macos-arm64.sh expects, plus cmake on PATH (libdatachannel
#     builds libjuice and usrsctp with it; with CMake >= 4 set
#     CMAKE_POLICY_VERSION_MINIMUM=3.5 for their old cmake_minimum_required)
#   - gh (GitHub CLI) authenticated as the fork owner, for --publish
#   - for --publish: a GitHub noreply identity for the tag, in
#     GIT_COMMITTER_NAME/GIT_COMMITTER_EMAIL or user.name/user.email. Without
#     one git makes up user@host, and the tag would publish it.
# Optional environment:
#   RELEASE_DENYLIST        a file of strings (one per line, # for comments)
#                           that must not appear in anything the release
#                           publishes. Keep it outside the repository: a list
#                           committed here would publish what it protects.
#   RELEASE_ALLOWED_EMAILS  addresses, besides GitHub noreply ones, that the
#                           commits this fork adds on top of upstream may carry
#   RELEASE_UPSTREAM        the upstream ref those commits are counted from
#                           (default upstream/master)
#
# Every native library is verified before it is packaged: ELF/Mach-O
# architecture, the exported JNI entry points (identical across platforms and
# including the fork's additions), and the absence of test-only entry points.
# A build step that reports success but produces a library of another
# architecture, or a stale one, fails here rather than at the consumer.
# Nothing is published that carries a path of the build host, the account or
# machine name, an address that is not a noreply one, or a deny-listed string:
# every such check fails the release, it does not warn.
set -euo pipefail

PUBLISH=0
DRY_RUN=0
PLATFORMS="android-arm android-arm64 android-x86_64 macos"
FROM=""
BUILT_AT=""
NOTES_FILE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --publish) PUBLISH=1 ;;
        --dry-run) DRY_RUN=1 ;;
        --platforms) PLATFORMS="$2"; shift ;;
        --from) FROM="$2"; shift ;;
        --built-at) BUILT_AT="$2"; shift ;;
        --notes) NOTES_FILE="$2"; shift ;;
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
# scratch space for the checks, removed however the script ends
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

if [ -n "$(git status --porcelain --ignore-submodules=dirty)" ]; then
    echo "working tree is not clean; commit or stash first" >&2
    git status --short --ignore-submodules=dirty >&2
    exit 2
fi
# the libraries report the submodule's revision: it has to describe the
# sources they are built from (untracked build files do not matter)
if ! git -C swig/deps/libtorrent diff --quiet HEAD --; then
    echo "swig/deps/libtorrent has uncommitted changes; commit them first" >&2
    git -C swig/deps/libtorrent status --short --untracked-files=no >&2
    exit 2
fi
FORK_COMMIT=$(git rev-parse HEAD)
LT_COMMIT=$(git -C swig/deps/libtorrent rev-parse HEAD)
LT_VERSION=$(sed -n 's/^#define LIBTORRENT_VERSION "\(.*\)"$/\1/p' swig/deps/libtorrent/include/libtorrent/version.hpp)
[ -n "$LT_VERSION" ] || { echo "cannot read LIBTORRENT_VERSION" >&2; exit 2; }
# libtorrent commits on top of the upstream merge-base of this fork
# (upstream RC_2_1 if the submodule has an "upstream" remote, else the 2.1.2
# commit the fork's patches were put on)
LT_UPSTREAM=$(git -C swig/deps/libtorrent rev-parse --verify -q upstream/RC_2_1 \
    || echo 2fbfc51973b52414d4583bc7b1275f2367797e9c)
LT_BASE=$(git -C swig/deps/libtorrent merge-base HEAD "$LT_UPSTREAM")
# and the commits of this repository on top of upstream
RELEASE_UPSTREAM=${RELEASE_UPSTREAM:-upstream/master}
git rev-parse --verify -q "$RELEASE_UPSTREAM" >/dev/null \
    || { echo "cannot resolve $RELEASE_UPSTREAM; fetch the upstream remote or set RELEASE_UPSTREAM" >&2; exit 2; }
FORK_BASE=$(git merge-base HEAD "$RELEASE_UPSTREAM")
# baked into the native libraries and generated into the jar, so a client
# can tell a native library from another build (LibTorrent.nativeBuild())
export LT4J_LIBTORRENT_REVISION=$LT_COMMIT
swig/write-revision-header.sh

# ---- what must never be published ----------------------------------------------

# Paths of the build host, the account and machine names, the address git
# would use here, and the local deny-list. Matched case-insensitively against
# the content of every jar, provenance.json, SHA256SUMS, the release notes and
# the tag message. A deny-list entry is reported by its number, never echoed,
# so that the report itself does not print it into a build log.
LEAK_PATTERNS=()
LEAK_LABELS=()
add_leak_pattern() { # <pattern> [<label>]
    # an empty or very short pattern would match everything
    [ "${#1}" -ge 4 ] || return 0
    LEAK_PATTERNS+=("$1")
    LEAK_LABELS+=("${2:-$1}")
}
for p in "$ROOT" "$(pwd -P)" "$HOME" /Users/ /home/ /root/ /private/ /var/folders/ /tmp/ 'C:\Users\'; do
    add_leak_pattern "$p"
done
add_leak_pattern "/$(id -un)/"
add_leak_pattern "$(id -un)@"
[ "$(hostname -s)" = localhost ] || add_leak_pattern "$(hostname -s)"
[ -n "${TMPDIR:-}" ] && add_leak_pattern "${TMPDIR%/}"
if [ -n "${DEVELOPMENT_ROOT:-}" ]; then
    add_leak_pattern "$DEVELOPMENT_ROOT"
    add_leak_pattern "$(cd "$DEVELOPMENT_ROOT" && pwd -P)"
fi
# the address git would put into the tag (see check_tagger for its rule)
add_leak_pattern "$(git var GIT_COMMITTER_IDENT | sed -n 's/.*<\(.*\)>.*/\1/p')"
if [ -n "${RELEASE_DENYLIST:-}" ]; then
    [ -f "$RELEASE_DENYLIST" ] || { echo "RELEASE_DENYLIST: no such file" >&2; exit 2; }
    n=0
    while IFS= read -r line || [ -n "$line" ]; do
        line=${line%$'\r'}
        n=$((n + 1))
        case "$line" in ''|'#'*) continue ;; esac
        add_leak_pattern "$line" "deny-list line $n"
    done < "$RELEASE_DENYLIST"
    echo "deny-list: $(grep -cvE '^(#|$)' "$RELEASE_DENYLIST" || true) entries"
else
    echo "no RELEASE_DENYLIST: checking host paths and identities only"
fi

check_no_leaks() { # <what> <file or dir>...
    local what=$1 i hits found=0
    shift
    for i in "${!LEAK_PATTERNS[@]}"; do
        hits=$(LC_ALL=C grep -r -a -l -i -F -- "${LEAK_PATTERNS[$i]}" "$@" || true)
        if [ -n "$hits" ]; then
            echo "LEAK in $what: ${LEAK_LABELS[$i]} found in:" >&2
            echo "$hits" >&2
            found=1
        fi
    done
    [ "$found" = 0 ] || exit 1
    echo "no host paths, identities or deny-listed strings in $what (${#LEAK_PATTERNS[@]} patterns)"
}

# ---- identities a release makes public ---------------------------------------------

email_allowed() { # <address>
    grep -qiE '@users\.noreply\.github\.com$' <<< "$1" && return 0
    local a
    for a in ${RELEASE_ALLOWED_EMAILS:-}; do
        [ "$1" = "$a" ] && return 0
    done
    return 1
}

# the tag publishes the commits this fork adds on top of upstream, with the
# author and committer addresses they carry
check_commit_identities() { # <repository> <base> <head>
    local bad
    bad=$(git -C "$1" log --format='%h %ae %ce' "$2..$3" | while read -r h a c; do
        if ! email_allowed "$a" || ! email_allowed "$c"; then echo "$h"; fi
    done)
    if [ -n "$bad" ]; then
        echo "IDENTITY: commits in $1 carry an address that is not a GitHub noreply one:" $bad >&2
        exit 1
    fi
    echo "commit identities in $1: $(git -C "$1" rev-list --count "$2..$3") commits, all noreply"
}

# git takes the tagger from GIT_COMMITTER_* or user.*, and without either
# makes one up from the account and host name
check_tagger() {
    local email
    email=$(git var GIT_COMMITTER_IDENT | sed -n 's/.*<\(.*\)>.*/\1/p')
    if ! email_allowed "$email"; then
        echo "IDENTITY: the tag would carry <$email>; set GIT_COMMITTER_NAME and" \
             "GIT_COMMITTER_EMAIL (or user.name and user.email) to a GitHub noreply identity" >&2
        exit 1
    fi
}

check_commit_identities "$ROOT" "$FORK_BASE" HEAD
check_commit_identities "$ROOT/swig/deps/libtorrent" "$LT_BASE" "$LT_COMMIT"

# ---- verification of a native library -------------------------------------------

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
    grep -q '_libtorrent_1ext_native_1build$' <<< "$ex" \
        || { echo "MISSING EXPORT: $1 has no libtorrent_ext.native_build" >&2; exit 1; }
    # the build it reports (libtorrent_ext.nativeBuild()) must be this one
    LC_ALL=C grep -a -q -F "$LT_VERSION $LT_COMMIT $VERSION" "$1" \
        || { echo "WRONG BUILD: $1 does not report '$LT_VERSION $LT_COMMIT $VERSION'" >&2; exit 1; }
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

# the native library in the jar of a platform, and the architecture its
# `file` description must match
platform_lib() { # <platform>
    case "$1" in
        android-arm)    echo 'lib/armeabi-v7a/libtorrent4j.so|ELF 32-bit.*ARM' ;;
        android-arm64)  echo 'lib/arm64-v8a/libtorrent4j.so|ELF 64-bit.*(aarch64|ARM aarch64)' ;;
        android-x86_64) echo 'lib/x86_64/libtorrent4j.so|ELF 64-bit.*x86-64' ;;
        macos)          echo 'lib/arm64/libtorrent4j.dylib|Mach-O 64-bit.*arm64' ;;
        *) echo "unknown platform $1" >&2; exit 2 ;;
    esac
}

expected_jars() {
    echo "libtorrent4j-$VERSION.jar"
    for p in $PLATFORMS; do echo "libtorrent4j-$p-$VERSION.jar"; done
}

# the jars in release/out: exactly the expected set, each with the native
# library it should carry and nothing that must not be published. Run on the
# bytes that are published, both after the build and again before publishing.
verify_out() {
    local scan p entry lib n jar
    diff <(expected_jars | sort) <(cd "$OUT" && ls -1 -- *.jar | sort) >&2 \
        || { echo "release/out does not hold exactly the jars of $PLATFORMS" >&2; exit 1; }
    scan="$WORK/scan"
    rm -rf "$scan" && mkdir -p "$scan"
    REF_EXPORTS=""
    for jar in $(expected_jars); do
        # the jar must contain exactly one native library (or, for the core jar, none)
        n=$(unzip -Z1 "$OUT/$jar" | grep -cE 'libtorrent4j\.(so|dylib)$' || true)
        case "$jar" in
            "libtorrent4j-$VERSION.jar") [ "$n" = 0 ] || { echo "core jar carries a native library" >&2; exit 1; } ;;
            *) [ "$n" = 1 ] || { echo "$jar carries $n native libraries, expected 1" >&2; exit 1; } ;;
        esac
        unzip -q -o "$OUT/$jar" -d "$scan/${jar%.jar}"
    done
    for p in $PLATFORMS; do
        entry=$(platform_lib "$p")
        lib="$scan/libtorrent4j-$p-$VERSION/${entry%%|*}"
        [ -f "$lib" ] || { echo "libtorrent4j-$p-$VERSION.jar has no ${entry%%|*}" >&2; exit 1; }
        verify_lib "$lib" "${entry#*|}"
    done
    check_no_leaks "the jars" "$scan"
}

if [ "$PUBLISH" = 1 ]; then
    [ -f "$OUT/provenance.json" ] && [ -f "$OUT/SHA256SUMS" ] \
        || { echo "nothing to publish: run without --publish first" >&2; exit 2; }
    built_at_commit=$(sed -n 's/^  "repository_commit": "\(.*\)",$/\1/p' "$OUT/provenance.json")
    [ "$built_at_commit" = "$FORK_COMMIT" ] \
        || { echo "release/out was built at $built_at_commit, HEAD is $FORK_COMMIT; rebuild" >&2; exit 2; }
    grep -q "^  \"version\": \"$VERSION\",$" "$OUT/provenance.json" \
        || { echo "provenance.json is not for version $VERSION" >&2; exit 2; }
    # the platforms release/out was made for, whatever --platforms says now
    PLATFORMS=$(sed -n 's/^  "platforms": "\(.*\)",$/\1/p' "$OUT/provenance.json")
    [ -n "$PLATFORMS" ] || { echo "provenance.json names no platforms" >&2; exit 2; }
    (cd "$OUT" && shasum -a 256 -c SHA256SUMS)
    # the checksums in provenance.json must be exactly those of SHA256SUMS
    diff <(sed -n 's/^    "\(.*\)": "\([0-9a-f]\{64\}\)",\{0,1\}$/\2  \1/p' "$OUT/provenance.json" | sort) \
         <(sort "$OUT/SHA256SUMS") \
        || { echo "provenance.json checksums differ from SHA256SUMS" >&2; exit 1; }
    verify_out
    check_no_leaks "provenance.json and SHA256SUMS" "$OUT/provenance.json" "$OUT/SHA256SUMS"
    TITLE="libtorrent4j $VERSION"
    TAG_MESSAGE="libtorrent4j $VERSION"
    TEXTS="$WORK/texts"
    mkdir -p "$TEXTS"
    if [ -n "$NOTES_FILE" ]; then
        cp "$NOTES_FILE" "$TEXTS/notes"
    else
        printf '%s\n' "libtorrent4j $VERSION: libtorrent $LT_COMMIT (upstream plus the fork's patches listed in provenance.json)." \
            "Verify downloads against SHA256SUMS." > "$TEXTS/notes"
    fi
    printf '%s\n%s\n' "$TITLE" "$TAG_MESSAGE" > "$TEXTS/title-and-tag-message"
    check_no_leaks "the release notes, title and tag message" "$TEXTS"
    check_tagger
    if [ "$DRY_RUN" = 1 ]; then
        echo "dry run: every check passed; nothing was pushed or published"
        exit 0
    fi
    git push origin "HEAD:refs/heads/$(git branch --show-current)"   # fast-forward only
    git tag -a "$TAG" -m "$TAG_MESSAGE" "$FORK_COMMIT"
    # the tag object as created, before it leaves the machine
    tagger=$(git for-each-ref "refs/tags/$TAG" --format='%(taggeremail)' | tr -d '<>')
    if ! email_allowed "$tagger"; then
        git tag -d "$TAG" >/dev/null
        echo "IDENTITY: the tag was created with <$tagger>; deleted it, nothing was pushed" >&2
        exit 1
    fi
    git push origin "$TAG"
    # gh picks its base repository by remote name and prefers "upstream" over
    # "origin"; in a fork that would target the upstream project. Name the
    # repository explicitly, from origin.
    REPO=$(git remote get-url origin | sed -E 's#^(git@github\.com:|https://github\.com/)##; s#\.git$##')
    gh release create "$TAG" --repo "$REPO" --verify-tag --title "$TITLE" --notes-file "$TEXTS/notes" \
        "$OUT"/*.jar "$OUT/SHA256SUMS" "$OUT/provenance.json"
    echo "published $TAG"
    exit 0
fi

rm -rf "$OUT" && mkdir -p "$OUT"

if [ -n "$FROM" ]; then

# ---- jars of a build made elsewhere ------------------------------------------------

    [ -n "$BUILT_AT" ] || { echo "--from needs --built-at <the commit the jars were built from>" >&2; exit 2; }
    BUILT_AT=$(git rev-parse --verify "$BUILT_AT^{commit}")
    # the jars are of BUILT_AT; they may be published at HEAD only if the two
    # differ in nothing but this release machinery (the submodule pointer
    # included)
    if ! git diff --quiet "$BUILT_AT" HEAD -- . ':(exclude)release'; then
        echo "HEAD differs from $BUILT_AT outside release/; the jars in $FROM are not of this commit" >&2
        git diff --stat "$BUILT_AT" HEAD -- . ':(exclude)release' >&2
        exit 2
    fi
    for a in $(expected_jars); do
        [ -f "$FROM/$a" ] || { echo "missing $FROM/$a" >&2; exit 1; }
        cp "$FROM/$a" "$OUT/"
    done

else

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
    # run the script from the repository, not the copy baked into the image.
    # The sources are mounted at /libtorrent4j and the image keeps boost and
    # openssl at /boost and /openssl-*, so no path of the host can reach the
    # library; the leak check verifies that anyway
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
    # The home directory comes first as a catch-all for anything else under it;
    # the specific prefixes follow and win (clang applies the longest match, or
    # the last one, depending on the version: either way the specific one).
    local maps="" d
    for d in "$HOME" "$(cd "$HOME" && pwd -P)"; do maps="$maps +-ffile-prefix-map=$d=/src"; done
    for d in "$ROOT" "$(cd "$ROOT" && pwd -P)"; do maps="$maps +-ffile-prefix-map=$d=/libtorrent4j"; done
    for d in "$DEVELOPMENT_ROOT" "$(cd "$DEVELOPMENT_ROOT" && pwd -P)"; do
        maps="$maps +-ffile-prefix-map=$d=/dev +-ffile-prefix-map=$d/boost_1_89_0=/boost +-ffile-prefix-map=$d/openssl-macos=/openssl"
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

# fail fast on a wrong library, before packaging it (verify_out checks the
# libraries again as they are in the jars)
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

for a in $(expected_jars); do
    [ -f "build/libs/$a" ] || { echo "missing asset build/libs/$a" >&2; exit 1; }
    cp "build/libs/$a" "$OUT/"
done

fi

verify_out

# ---- checksums and provenance --------------------------------------------------

cd "$OUT"
shasum -a 256 *.jar > SHA256SUMS
if [ -n "$FROM" ]; then
    IMAGE_ID="not recorded (--from)"
    BUILT_AT_JSON="\"not recorded (--from)\",
  \"artifacts_built_at_commit\": \"$BUILT_AT\""
else
    IMAGE_ID=$(docker image inspect "$IMAGE" --format '{{.Id}}' 2>/dev/null || echo "not used")
    BUILT_AT_JSON="\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\""
fi
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
  "built_at": $BUILT_AT_JSON,
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
check_no_leaks "provenance.json and SHA256SUMS" provenance.json SHA256SUMS

echo "built and verified into $OUT. Inspect it, then run: release/publish.sh --publish"
