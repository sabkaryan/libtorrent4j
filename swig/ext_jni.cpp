// Hand-written JNI entry points that are not part of the SWIG-generated
// wrapper. Java side: src/main/java/org/libtorrent4j/swig/libtorrent_ext.java
//
// A torrent_handle (and a session_params, add_torrent_params, info_hash_t or
// bitfield) arrives as its SWIG C pointer (torrent_handle.getCPtr), exactly
// what libtorrent_jni.cpp receives as `jlong jarg1`. A memory storage pool
// arrives as the pointer returned by memory_pool_new(): a heap-allocated
// std::shared_ptr<memory_storage_pool>.

#include <jni.h>

#include <cstdint>
#include <exception>
#include <memory>
#include <vector>

#include "libtorrent/torrent_handle.hpp"
#include "libtorrent/add_torrent_params.hpp"
#include "libtorrent/info_hash.hpp"
#include "libtorrent/memory_disk_io.hpp"
#include "libtorrent/session_params.hpp"
#include "libtorrent/version.hpp"
#include "libtorrent/aux_/torrent.hpp"
#include "libtorrent/aux_/session_impl.hpp"
#include "libtorrent/aux_/session_call.hpp"

namespace {

libtorrent::torrent_handle const* handle_of(jlong ptr)
{
    return *reinterpret_cast<libtorrent::torrent_handle**>(&ptr);
}

template <typename T>
T* swig_ptr(jlong ptr)
{
    return *reinterpret_cast<T**>(&ptr);
}

libtorrent::memory_storage_pool& pool_of(jlong ptr)
{
    return **swig_ptr<std::shared_ptr<libtorrent::memory_storage_pool>>(ptr);
}

void throw_runtime(JNIEnv* env, char const* what)
{
    jclass const c = env->FindClass("java/lang/RuntimeException");
    if (c != nullptr) env->ThrowNew(c, what);
}

// runs f, turning a C++ exception into a Java RuntimeException (a C++
// exception must not cross the JNI frame). `fallback` is returned then
template <typename R, typename F>
R guarded(JNIEnv* env, R const fallback, F const& f)
{
    try {
        return f();
    } catch (std::exception const& e) {
        throw_runtime(env, e.what());
    } catch (...) {
        throw_runtime(env, "unknown C++ exception");
    }
    return fallback;
}

template <typename Index>
std::vector<Index> indices_of(JNIEnv* env, jintArray a)
{
    std::vector<Index> ret;
    if (a == nullptr) return ret;
    jsize const n = env->GetArrayLength(a);
    std::vector<jint> v(static_cast<std::size_t>(n));
    if (n > 0) env->GetIntArrayRegion(a, 0, n, v.data());
    ret.reserve(v.size());
    for (jint const i : v) ret.push_back(Index(i));
    return ret;
}

libtorrent::memory_policy policy_of(jint p)
{
    return static_cast<libtorrent::memory_policy>(p);
}

} // anonymous namespace

// the git revision of swig/deps/libtorrent this library is built from and the
// libtorrent4j version, written by swig/write-revision-header.sh. Every build
// runs it first (the build scripts and CI); a build that did not fails here
// rather than report a build it cannot name. A header rather than a -D define,
// so that b2 recompiles this file when the revision changes
#include "lt4j_revision.hpp"

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

// ---- memory storage pool (libtorrent/memory_disk_io.hpp) ----------------
// Java side: org.libtorrent4j.MemoryStoragePool. The Java side checks the
// arguments it can (a closed pool, null references, array bounds) before it
// calls these.

JNIEXPORT jlong JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1new(
    JNIEnv* env, jclass, jint slab_bytes)
{
    using libtorrent::memory_storage_pool;
    return guarded(env, jlong(0), [&] {
        memory_storage_pool::params p;
        if (slab_bytes > 0) p.slab_bytes = slab_bytes;
        auto* const pool = new std::shared_ptr<memory_storage_pool>(
            std::make_shared<memory_storage_pool>(p));
        return reinterpret_cast<jlong>(pool);
    });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1delete(
    JNIEnv*, jclass, jlong pool)
{
    // a session made with this pool keeps its own reference
    delete swig_ptr<std::shared_ptr<libtorrent::memory_storage_pool>>(pool);
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1set_1disk_1io(
    JNIEnv* env, jclass, jlong pool, jlong params_ptr, jobject)
{
    guarded(env, 0, [&] {
        auto const& sp = *swig_ptr<std::shared_ptr<libtorrent::memory_storage_pool>>(pool);
        swig_ptr<libtorrent::session_params>(params_ptr)->disk_io_constructor
            = libtorrent::memory_disk_io_constructor(sp);
        return 0;
    });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1set_1default_1policy(
    JNIEnv* env, jclass, jlong pool, jint policy)
{
    guarded(env, 0, [&] { pool_of(pool).set_default_policy(policy_of(policy)); return 0; });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1set_1policy_1info_1hash(
    JNIEnv* env, jclass, jlong pool, jlong ih_ptr, jobject, jint policy)
{
    guarded(env, 0, [&] {
        pool_of(pool).set_policy(*swig_ptr<libtorrent::info_hash_t>(ih_ptr), policy_of(policy));
        return 0;
    });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1set_1policy(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jint owner, jint policy
    , jintArray files)
{
    guarded(env, 0, [&] {
        auto const f = indices_of<libtorrent::file_index_t>(env, files);
        pool_of(pool).set_policy(*handle_of(handle_ptr)
            , static_cast<libtorrent::memory_owner_t>(owner), policy_of(policy), f);
        return 0;
    });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1set_1limit(
    JNIEnv* env, jclass, jlong pool, jlong bytes)
{
    guarded(env, 0, [&] { pool_of(pool).set_limit(std::int64_t(bytes)); return 0; });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1persist(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jintArray pieces)
{
    guarded(env, 0, [&] {
        auto const p = indices_of<libtorrent::piece_index_t>(env, pieces);
        pool_of(pool).persist(*handle_of(handle_ptr), p);
        return 0;
    });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1set_1persist(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jint owner, jintArray pieces)
{
    guarded(env, 0, [&] {
        auto const p = indices_of<libtorrent::piece_index_t>(env, pieces);
        pool_of(pool).set_persist(*handle_of(handle_ptr)
            , static_cast<libtorrent::memory_owner_t>(owner), p);
        return 0;
    });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1drop_1owner(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jint owner)
{
    guarded(env, 0, [&] {
        pool_of(pool).drop_owner(*handle_of(handle_ptr), static_cast<libtorrent::memory_owner_t>(owner));
        return 0;
    });
}

JNIEXPORT jlong JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1pending_1persist_1bytes(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject)
{
    return guarded(env, jlong(0), [&] {
        return jlong(pool_of(pool).pending_persist_bytes(*handle_of(handle_ptr)));
    });
}

JNIEXPORT jint JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1persist_1failures(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject)
{
    return guarded(env, jint(0), [&] {
        return jint(pool_of(pool).persist_failures(*handle_of(handle_ptr)));
    });
}

// the code is returned, the place (piece_place) is stored in place[0].
// Waits for the network thread
JNIEXPORT jint JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1forget_1piece(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jint piece, jintArray place)
{
    return guarded(env, jint(0), [&] {
        auto const r = pool_of(pool).forget_piece(*handle_of(handle_ptr), libtorrent::piece_index_t(piece));
        jint const p = static_cast<jint>(r.place);
        env->SetIntArrayRegion(place, 0, 1, &p);
        return jint(r.code);
    });
}

// the caller (Java) has checked 0 <= dst_off, 0 <= len and
// dst_off + len <= dst.length. The array is pinned only for the copy, under
// the pool's mutex, with no other JNI call in between
JNIEXPORT jint JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1read(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jint piece, jint offset
    , jbyteArray dst, jint dst_off, jint len)
{
    return guarded(env, jint(0), [&] {
        void* p = nullptr;
        return jint(pool_of(pool).read(*handle_of(handle_ptr), libtorrent::piece_index_t(piece)
            , offset, len
            , [&]() -> char* {
                p = env->GetPrimitiveArrayCritical(dst, nullptr);
                return p == nullptr ? nullptr : static_cast<char*>(p) + dst_off;
            }
            , [&] { env->ReleasePrimitiveArrayCritical(dst, p, 0); }));
    });
}

// fills the two (SWIG) bitfields
JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1in_1memory(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject
    , jlong complete_ptr, jobject, jlong partial_ptr, jobject)
{
    guarded(env, 0, [&] {
        auto r = pool_of(pool).in_memory(*handle_of(handle_ptr));
        *swig_ptr<libtorrent::bitfield>(complete_ptr) = std::move(r.complete);
        *swig_ptr<libtorrent::bitfield>(partial_ptr) = std::move(r.partial);
        return 0;
    });
}

// held[0] = complete, held[1] = partial
JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1held_1bytes_1of(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jlongArray held)
{
    guarded(env, 0, [&] {
        auto const r = pool_of(pool).held_bytes(*handle_of(handle_ptr));
        jlong const v[2] = { jlong(r.complete), jlong(r.partial) };
        env->SetLongArrayRegion(held, 0, 2, v);
        return 0;
    });
}

JNIEXPORT jlong JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1held_1bytes(
    JNIEnv* env, jclass, jlong pool)
{
    return guarded(env, jlong(0), [&] { return jlong(pool_of(pool).held_bytes()); });
}

JNIEXPORT jlong JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1retired_1bytes(
    JNIEnv* env, jclass, jlong pool)
{
    return guarded(env, jlong(0), [&] { return jlong(pool_of(pool).retired_bytes()); });
}

JNIEXPORT jlong JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1spilled_1pieces(
    JNIEnv* env, jclass, jlong pool)
{
    return guarded(env, jlong(0), [&] { return jlong(pool_of(pool).spilled_pieces()); });
}

JNIEXPORT jlong JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1hash_1missing_1blocks(
    JNIEnv* env, jclass, jlong pool)
{
    return guarded(env, jlong(0), [&] { return jlong(pool_of(pool).hash_missing_blocks()); });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1filter_1resume_1of(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jlong atp_ptr, jobject)
{
    guarded(env, 0, [&] {
        pool_of(pool).filter_resume(*handle_of(handle_ptr), *swig_ptr<libtorrent::add_torrent_params>(atp_ptr));
        return 0;
    });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1filter_1resume(
    JNIEnv* env, jclass, jlong pool, jlong atp_ptr, jobject)
{
    guarded(env, 0, [&] {
        pool_of(pool).filter_resume(*swig_ptr<libtorrent::add_torrent_params>(atp_ptr));
        return 0;
    });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1forget_1record(
    JNIEnv* env, jclass, jlong pool, jlong ih_ptr, jobject)
{
    guarded(env, 0, [&] {
        pool_of(pool).forget_record(*swig_ptr<libtorrent::info_hash_t>(ih_ptr));
        return 0;
    });
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
