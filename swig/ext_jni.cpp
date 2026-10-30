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

// thrown inside guarded(): a JNI call left a Java exception pending, the
// entry point returns at once
struct java_pending {};
// thrown inside guarded(): a null (or deleted) object or array argument,
// a NullPointerException
struct null_argument { char const* what; };
// thrown inside guarded(): an array too short or a range outside it, an
// IllegalArgumentException
struct bad_argument { char const* what; };

// the object behind a SWIG pointer, which is 0 for null or a deleted object
template <typename T>
T& ref_of(jlong ptr, char const* what)
{
    T* const p = swig_ptr<T>(ptr);
    if (p == nullptr) throw null_argument{what};
    return *p;
}

libtorrent::torrent_handle const& handle_ref(jlong ptr)
{
    return ref_of<libtorrent::torrent_handle const>(ptr, "torrent_handle");
}

libtorrent::memory_storage_pool& pool_of(jlong ptr)
{
    return *ref_of<std::shared_ptr<libtorrent::memory_storage_pool>>(ptr, "memory pool");
}

// the array exists and has at least n elements
void check_array(JNIEnv* env, jarray a, jsize n, char const* what)
{
    if (a == nullptr) throw null_argument{what};
    if (env->GetArrayLength(a) < n) throw bad_argument{what};
}

void check_jni(JNIEnv* env)
{
    if (env->ExceptionCheck()) throw java_pending{};
}

void throw_java(JNIEnv* env, char const* cls, char const* what)
{
    jclass const c = env->FindClass(cls);
    if (c != nullptr) env->ThrowNew(c, what);
}

// runs f, turning a C++ exception into a Java exception (a C++ exception
// must not cross the JNI frame). `fallback` is returned then
template <typename R, typename F>
R guarded(JNIEnv* env, R const fallback, F const& f)
{
    try {
        return f();
    } catch (java_pending const&) {
    } catch (null_argument const& e) {
        throw_java(env, "java/lang/NullPointerException", e.what);
    } catch (bad_argument const& e) {
        throw_java(env, "java/lang/IllegalArgumentException", e.what);
    } catch (std::exception const& e) {
        throw_java(env, "java/lang/RuntimeException", e.what());
    } catch (...) {
        throw_java(env, "java/lang/RuntimeException", "unknown C++ exception");
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
    check_jni(env);
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
// Java side: org.libtorrent4j.MemoryStoragePool, which checks a closed pool
// and the bounds of read(). These check every argument again: a null or
// deleted object or array throws NullPointerException, an array too short
// IllegalArgumentException, and nothing is dereferenced after a JNI call
// left an exception pending.

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
        auto const& sp = ref_of<std::shared_ptr<libtorrent::memory_storage_pool>>(pool, "memory pool");
        ref_of<libtorrent::session_params>(params_ptr, "session_params").disk_io_constructor
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
        pool_of(pool).set_policy(ref_of<libtorrent::info_hash_t>(ih_ptr, "info_hash_t"), policy_of(policy));
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
        pool_of(pool).set_policy(handle_ref(handle_ptr)
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
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1set_1torrent_1limit(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jlong bytes)
{
    guarded(env, 0, [&] {
        pool_of(pool).set_limit(handle_ref(handle_ptr), std::int64_t(bytes));
        return 0;
    });
}

JNIEXPORT jlong JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1declined_1pieces(
    JNIEnv* env, jclass, jlong pool)
{
    return guarded(env, jlong(0), [&] { return jlong(pool_of(pool).declined_pieces()); });
}

// the native library has libtorrent::memory_policy::memory_only (a library
// from before it has no such entry point)
JNIEXPORT jboolean JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1only_1supported(JNIEnv*, jclass)
{
    return static_cast<jboolean>(libtorrent::memory_policy::memory_only == libtorrent::memory_policy(2));
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1persist(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jintArray pieces)
{
    guarded(env, 0, [&] {
        auto const p = indices_of<libtorrent::piece_index_t>(env, pieces);
        pool_of(pool).persist(handle_ref(handle_ptr), p);
        return 0;
    });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1set_1persist(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jint owner, jintArray pieces)
{
    guarded(env, 0, [&] {
        auto const p = indices_of<libtorrent::piece_index_t>(env, pieces);
        pool_of(pool).set_persist(handle_ref(handle_ptr)
            , static_cast<libtorrent::memory_owner_t>(owner), p);
        return 0;
    });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1drop_1owner(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jint owner)
{
    guarded(env, 0, [&] {
        pool_of(pool).drop_owner(handle_ref(handle_ptr), static_cast<libtorrent::memory_owner_t>(owner));
        return 0;
    });
}

JNIEXPORT jlong JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1pending_1persist_1bytes(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject)
{
    return guarded(env, jlong(0), [&] {
        return jlong(pool_of(pool).pending_persist_bytes(handle_ref(handle_ptr)));
    });
}

JNIEXPORT jint JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1persist_1failures(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject)
{
    return guarded(env, jint(0), [&] {
        return jint(pool_of(pool).persist_failures(handle_ref(handle_ptr)));
    });
}

// the code is returned, the place (piece_place) is stored in place[0].
// Waits for the network thread
JNIEXPORT jint JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1forget_1piece(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jint piece, jintArray place)
{
    return guarded(env, jint(0), [&] {
        check_array(env, place, 1, "place");
        auto const r = pool_of(pool).forget_piece(handle_ref(handle_ptr), libtorrent::piece_index_t(piece));
        jint const p = static_cast<jint>(r.place);
        env->SetIntArrayRegion(place, 0, 1, &p);
        return jint(r.code);
    });
}

// 0 <= dst_off, 0 <= len and dst_off + len <= dst.length, or
// IllegalArgumentException. The array is pinned only for the copy, under the
// pool's mutex, with no other JNI call in between
JNIEXPORT jint JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1read(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jint piece, jint offset
    , jbyteArray dst, jint dst_off, jint len)
{
    return guarded(env, jint(0), [&] {
        if (dst == nullptr) throw null_argument{"dst"};
        if (dst_off < 0 || len < 0 || dst_off > env->GetArrayLength(dst) - len)
            throw bad_argument{"dst range"};
        void* p = nullptr;
        return jint(pool_of(pool).read(handle_ref(handle_ptr), libtorrent::piece_index_t(piece)
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
        auto& complete = ref_of<libtorrent::bitfield>(complete_ptr, "complete");
        auto& partial = ref_of<libtorrent::bitfield>(partial_ptr, "partial");
        auto r = pool_of(pool).in_memory(handle_ref(handle_ptr));
        complete = std::move(r.complete);
        partial = std::move(r.partial);
        return 0;
    });
}

// held[0] = complete, held[1] = partial
JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1held_1bytes_1of(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jlongArray held)
{
    guarded(env, 0, [&] {
        check_array(env, held, 2, "held");
        auto const r = pool_of(pool).held_bytes(handle_ref(handle_ptr));
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

} // extern "C"

namespace {

// the (SWIG) bitfields of sets[0..4]: ram complete, ram partial, transfer,
// file ready and file pending. Checked before the snapshot is taken
struct snapshot_sets
{
    libtorrent::bitfield* b[5];
};

snapshot_sets sets_of(JNIEnv* env, jlongArray sets, jlongArray numbers)
{
    check_array(env, sets, 5, "sets");
    check_array(env, numbers, 7, "numbers");
    jlong p[5];
    env->GetLongArrayRegion(sets, 0, 5, p);
    check_jni(env);
    snapshot_sets ret;
    for (int i = 0; i < 5; ++i) ret.b[i] = &ref_of<libtorrent::bitfield>(p[i], "sets element");
    return ret;
}

// numbers[0..6] receive held complete, held partial, pending persist,
// reserved, pool held, pool reserved and pool retired. Returns the status
jint write_snapshot(JNIEnv* env, libtorrent::memory_snapshot& s, snapshot_sets const& out
    , jlongArray numbers)
{
    *out.b[0] = std::move(s.ram_complete);
    *out.b[1] = std::move(s.ram_partial);
    *out.b[2] = std::move(s.transfer);
    *out.b[3] = std::move(s.file_ready);
    *out.b[4] = std::move(s.file_pending);
    jlong const v[7] = { jlong(s.held_complete), jlong(s.held_partial), jlong(s.pending_persist)
        , jlong(s.reserved), jlong(s.pool_held), jlong(s.pool_reserved), jlong(s.pool_retired) };
    env->SetLongArrayRegion(numbers, 0, 7, v);
    check_jni(env);
    return jint(s.status);
}

} // anonymous namespace

extern "C" {

// see sets_of() and write_snapshot(). Waits for the network thread
JNIEXPORT jint JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1snapshot(
    JNIEnv* env, jclass, jlong pool, jlong handle_ptr, jobject, jlongArray sets, jlongArray numbers)
{
    return guarded(env, jint(0), [&] {
        snapshot_sets const out = sets_of(env, sets, numbers);
        auto s = pool_of(pool).snapshot(handle_ref(handle_ptr));
        return write_snapshot(env, s, out, numbers);
    });
}

#if defined LT4J_TEST_EXPORTS

// only in a library built for the binding's tests (b2 define=LT4J_TEST_EXPORTS):
// release/publish.sh refuses a library that exports it. A made-up snapshot
// through the same conversion: status 0, five pieces, piece i in set i only,
// numbers 101..107
JNIEXPORT jint JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1snapshot_1for_1test(
    JNIEnv* env, jclass, jlongArray sets, jlongArray numbers)
{
    return guarded(env, jint(0), [&] {
        snapshot_sets const out = sets_of(env, sets, numbers);
        libtorrent::memory_snapshot s;
        std::vector<libtorrent::typed_bitfield<libtorrent::piece_index_t>*> const b{&s.ram_complete
            , &s.ram_partial, &s.transfer, &s.file_ready, &s.file_pending};
        for (int i = 0; i < 5; ++i)
        {
            b[std::size_t(i)]->resize(5, false);
            b[std::size_t(i)]->set_bit(libtorrent::piece_index_t(i));
        }
        s.held_complete = 101;
        s.held_partial = 102;
        s.pending_persist = 103;
        s.reserved = 104;
        s.pool_held = 105;
        s.pool_reserved = 106;
        s.pool_retired = 107;
        return write_snapshot(env, s, out, numbers);
    });
}

#endif // LT4J_TEST_EXPORTS

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
        pool_of(pool).filter_resume(handle_ref(handle_ptr), ref_of<libtorrent::add_torrent_params>(atp_ptr, "add_torrent_params"));
        return 0;
    });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1filter_1resume(
    JNIEnv* env, jclass, jlong pool, jlong atp_ptr, jobject)
{
    guarded(env, 0, [&] {
        pool_of(pool).filter_resume(ref_of<libtorrent::add_torrent_params>(atp_ptr, "add_torrent_params"));
        return 0;
    });
}

JNIEXPORT void JNICALL
Java_org_libtorrent4j_swig_libtorrent_1ext_memory_1pool_1forget_1record(
    JNIEnv* env, jclass, jlong pool, jlong ih_ptr, jobject)
{
    guarded(env, 0, [&] {
        pool_of(pool).forget_record(ref_of<libtorrent::info_hash_t>(ih_ptr, "info_hash_t"));
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
