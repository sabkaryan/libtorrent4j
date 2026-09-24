// Hand-written JNI entry points that are not part of the SWIG-generated
// wrapper. Java side: src/main/java/org/libtorrent4j/swig/libtorrent_ext.java
//
// The first argument of every function is the SWIG C pointer of a
// torrent_handle (torrent_handle.getCPtr), exactly what libtorrent_jni.cpp
// receives as `jlong jarg1`.

#include <jni.h>

#include "libtorrent/torrent_handle.hpp"
#include "libtorrent/version.hpp"
#include "libtorrent/aux_/torrent.hpp"
#include "libtorrent/aux_/session_impl.hpp"
#include "libtorrent/aux_/session_call.hpp"

namespace {

libtorrent::torrent_handle const* handle_of(jlong ptr)
{
    return *reinterpret_cast<libtorrent::torrent_handle**>(&ptr);
}

} // anonymous namespace

// the git revision of swig/deps/libtorrent this library is built from, written
// by swig/write-revision-header.sh (the build scripts run it). A header rather
// than a -D define, so that b2 recompiles this file when the revision changes
#if __has_include("lt4j_revision.hpp")
#include "lt4j_revision.hpp"
#else
#define LT4J_LIBTORRENT_REVISION "unknown"
#define LT4J_VERSION "unknown"
#endif

extern "C" {

// "<libtorrent version> <revision> <libtorrent4j version>": what the jar
// compares with the build it expects (LibTorrent.expectedNativeBuild())
JNIEXPORT jstring JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_native_1build(JNIEnv* env, jclass)
{
    return env->NewStringUTF(LIBTORRENT_VERSION " " LT4J_LIBTORRENT_REVISION " " LT4J_VERSION);
}

JNIEXPORT jint JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_forget_1piece(
    JNIEnv*, jclass, jlong handle_ptr, jint piece)
{
    auto const* h = handle_of(handle_ptr);
    if (h == nullptr) return 5;
    // synchronous, runs on the network thread, 5 for an invalid handle
    return h->forget_piece(libtorrent::piece_index_t(piece));
}

#if TORRENT_USE_ASSERTS

// ---- positive controls for assert-enabled builds ----------------------
// Never call these from product code. They exist so a build with asserts
// can prove it is able to fail before its silence is taken as evidence.

// Control A: run aux::torrent::forget_piece() on the calling (JNI) thread
// instead of the network thread. TORRENT_ASSERT(is_single_thread()) at the
// top of forget_piece() must fire.
JNIEXPORT jint JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_forget_1piece_1off_1thread_1for_1test(
    JNIEnv*, jclass, jlong handle_ptr, jint piece)
{
    auto const* h = handle_of(handle_ptr);
    if (h == nullptr) return 5;
    auto t = h->native_handle();
    if (!t) return 5;
    return t->forget_piece(libtorrent::piece_index_t(piece));
}

// Control B: drop the piece from the picker WITHOUT the surrounding
// bookkeeping (no update_gauge, no interest update). On a torrent that is
// `finished` this leaves the gauge state stale and torrent::check_invariant
// (`current_stats_state() == m_current_gauge_state + ...`) must fire at the
// next INVARIANT_CHECK, e.g. on the next forget_piece().
JNIEXPORT jint JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_break_1picker_1for_1test(
    JNIEnv*, jclass, jlong handle_ptr, jint piece)
{
    using libtorrent::piece_index_t;
    auto const* h = handle_of(handle_ptr);
    if (h == nullptr) return 5;
    auto t = h->native_handle();
    if (!t) return 5;

    auto& ses = static_cast<libtorrent::aux::session_impl&>(t->session());
    bool done = false;
    int ret = 5;
    boost::asio::dispatch(ses.get_context(), [t, piece, &ret, &done, &ses]()
    {
        if (!t->has_picker()) ret = 2;
        else if (!t->picker().have_piece(piece_index_t(piece))) ret = 1;
        else { t->picker().we_dont_have(piece_index_t(piece)); ret = 0; }
        std::unique_lock<std::mutex> l(ses.mut);
        done = true;
        ses.cond.notify_all();
    });
    libtorrent::aux::torrent_wait(done, ses);
    return ret;
}

#endif // TORRENT_USE_ASSERTS

} // extern "C"
