# Test rig for `torrent_handle::forget_piece()`

`forget_piece()` lets a client stop considering a piece as downloaded — so it is
picked and fetched again — without dropping peers or re-checking the torrent.
A client that needs to free disk space behind its read position can forget a
piece and then release its bytes (e.g. punch a hole in the file); the piece is
re-downloaded when it is wanted again. This directory checks that behaviour on
a real Android runtime.

## Build the library

The Android build runs in Docker, see `swig/android-build/`:

    docker build --platform linux/amd64 -t lt4j:latest swig/android-build
    cd swig/android-build && ./build-arm64.sh          # release build + jars
    cd swig/android-build && LT4J_CHECKED=1 ./build-arm64.sh   # checked build

`LT4J_CHECKED=1` compiles libtorrent with its own asserts and invariant checks
(`asserts=on invariant-checks=on`) into `swig/bin/release-checked/`. Use it for
the rig only: a violated internal assumption aborts the process instead of
silently corrupting state. `LT4J_JOBS` sets the b2 parallelism (default 2).

## Run

    ./gradlew jar
    stand/run-android.sh swig/bin/release-checked/android/arm64-v8a/libtorrent4j.so offthread
    stand/run-android.sh swig/bin/release-checked/android/arm64-v8a/libtorrent4j.so breakpick
    stand/run-android.sh swig/bin/release-checked/android/arm64-v8a/libtorrent4j.so forget
    stand/run-android.sh swig/bin/release-checked/android/arm64-v8a/libtorrent4j.so redownload
    stand/run-android.sh swig/bin/release-checked/android/arm64-v8a/libtorrent4j.so resume
    stand/run-android.sh swig/bin/release/android/arm64-v8a/libtorrent4j.so forget

Order matters:

1. `offthread` and `breakpick` on the **checked** build must abort with an
   assertion (`is_single_thread()` and `torrent::check_invariant()`
   respectively). Until they do, silence from the checked build proves nothing.
2. `control` on a library built **without** the patch must pass, and `forget`
   on it must fail with `UnsatisfiedLinkError`: the observations see the patch,
   not something the library does by itself.
3. Only then `forget`, `redownload` and `resume` on the checked build, and then
   on the release build, are evidence.

To compare a release and a checked build on one device, run
`bench [pieces] [kib] [posix|default]` with each library, several times each,
alternating the order; `bench` prints one line with the hash-check time, the
time to the first piece and the full download time from a local seed. Keep the
piece count realistic (thousands): the checked build's invariant checks scale
with it, and a small fixture would show no slowdown whatever the real cost.

What the rig does not cover: a peer requesting from us a piece we have just
forgotten (the stale-read guard in `peer_connection::on_disk_read_complete`
and its `ses.num_stale_piece_rejects` counter), whether forget_piece itself
flags the resume data as dirty (it is already dirty after checking), and torrents that have already released their piece picker (`forget_piece`
returns 2 for those).
