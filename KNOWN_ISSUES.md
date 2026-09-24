# Known issues (libtorrent 2.1 branch)

Failures seen when running the libtorrent test suite (`swig/deps/libtorrent/test`)
that come from the environment or from upstream, not from this branch.

## `test_checking` fails intermittently in debug builds with GCC (libstdc++)

A debug assert fails in `counters::inc_stats_counter()`, called from
`pread_disk_io::do_job(job::hash&)`:

    expression: value >= 0 || c >= num_stats_counters

Seen in about 2 of 3 runs, in different tests each time (for example
`read_only_corrupt_v2_posix`, `checking_v2_mmap`). pread_disk_io also runs in the
mmap and posix variants, because `set_piece_hashes()` uses the default disk I/O.

- **Cause.** libtorrent's `clock_type` is `std::chrono::high_resolution_clock`.
  libstdc++ maps it to `system_clock`, the wall clock, which steps back when the
  system time is adjusted. A duration measured across such a step comes out
  negative, and the disk I/O backends add it to a monotonic counter.
- **Measured.** In a Linux VM under Docker, `system_clock` stepped back 3 times in
  30 s (by up to 442 us); `steady_clock` never did.
- **Upstream.** Discussed in arvidn/libtorrent#7196.
- **Not affected.** libc++ (including the Android NDK) and MSVC map the clock to
  `steady_clock`.
- **Not fixed here.**

## Tests that need Python

Some tests start helper servers written in Python (`web_server.py`,
`socks.py`, ...). In an image without Python they fail with:

    exhausted all python interpreters

Seen with `test_web_seed` and with the proxy tests in `test_transfer`
(`socks5_pw`, `http`, `http_pw`, `i2p`); the other `test_transfer` tests do not
need Python. Other suites start these helpers too (for example `test_tracker`,
`test_upnp`).

## Tests that exist only with `TORRENT_ABI_VERSION == 1`

`test_transfer`'s `no_contiguous_buffers` is compiled only for ABI version 1. When
you name it explicitly in another build, the runner reports "UNKNOWN tests" and
"no unit tests run".
