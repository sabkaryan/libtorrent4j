package org.libtorrent4j.swig;

/**
 * Hand-written entry points that are not part of the SWIG-generated wrapper
 * (see swig/ext_jni.cpp). They take the SWIG C pointer of a {@link torrent_handle}.
 * <p>
 * The {@code memoryPool*} calls are the in-memory disk back end
 * (libtorrent/memory_disk_io.hpp); use them through
 * {@link org.libtorrent4j.MemoryStoragePool}, which owns the {@code pool}
 * pointer and checks the arguments. A native library without them throws
 * {@link UnsatisfiedLinkError} on the first call.
 */
public final class libtorrent_ext {

    static {
        // make sure the native library is loaded (libtorrent_jni's static block)
        try {
            Class.forName("org.libtorrent4j.swig.libtorrent_jni");
        } catch (ClassNotFoundException e) {
            throw new LinkageError("libtorrent_jni not found", e);
        }
    }

    private libtorrent_ext() {
    }

    /**
     * {@code torrent_handle::forget_piece(piece)}: stop considering the piece
     * as downloaded, so it is picked and downloaded again, without
     * disconnecting peers or re-checking. Synchronous; safe from any thread.
     *
     * @return 0 forgotten, 1 not had, 2 no piece picker (seed), 3 piece is
     * being downloaded or not all of its blocks are on disk yet (try again
     * later; release the piece's bytes on disk only after 0), 4 bad index /
     * no metadata, 5 invalid handle
     */
    public static int forgetPiece(torrent_handle h, int piece) {
        return forget_piece(torrent_handle.getCPtr(h), piece);
    }

    private static native int forget_piece(long handlePtr, int piece);

    /**
     * The build the native library comes from, as
     * {@code "<libtorrent version> <git revision of swig/deps/libtorrent>
     * <libtorrent4j version>"}
     * ({@code "unknown"} instead of the revision if b2 was run without
     * swig/write-revision-header.sh, which the build scripts run).
     * A native library from before this entry point was added throws
     * {@link UnsatisfiedLinkError}.
     */
    public static String nativeBuild() {
        return native_build();
    }

    private static native String native_build();

    // ---- memory storage pool. `pool` is the pointer memoryPoolNew() returned.
    //      The SWIG objects are passed along with their pointers so that they
    //      stay reachable during the native call.

    /** A new {@code std::shared_ptr<memory_storage_pool>}; slabBytes <= 0 for the default params. */
    public static long memoryPoolNew(int slabBytes) {
        return memory_pool_new(slabBytes);
    }

    /** Deletes the pointer memoryPoolNew() returned (a session keeps its own reference). */
    public static void memoryPoolDelete(long pool) {
        memory_pool_delete(pool);
    }

    /** {@code params.disk_io_constructor = memory_disk_io_constructor(pool)}. */
    public static void memoryPoolSetDiskIo(long pool, session_params params) {
        memory_pool_set_disk_io(pool, session_params.getCPtr(params), params);
    }

    public static void memoryPoolSetDefaultPolicy(long pool, int policy) {
        memory_pool_set_default_policy(pool, policy);
    }

    public static void memoryPoolSetPolicy(long pool, info_hash_t ih, int policy) {
        memory_pool_set_policy_info_hash(pool, info_hash_t.getCPtr(ih), ih, policy);
    }

    public static void memoryPoolSetPolicy(long pool, torrent_handle h, int owner, int policy, int[] files) {
        memory_pool_set_policy(pool, torrent_handle.getCPtr(h), h, owner, policy, files);
    }

    public static void memoryPoolSetLimit(long pool, long bytes) {
        memory_pool_set_limit(pool, bytes);
    }

    public static void memoryPoolPersist(long pool, torrent_handle h, int[] pieces) {
        memory_pool_persist(pool, torrent_handle.getCPtr(h), h, pieces);
    }

    public static void memoryPoolSetPersist(long pool, torrent_handle h, int owner, int[] pieces) {
        memory_pool_set_persist(pool, torrent_handle.getCPtr(h), h, owner, pieces);
    }

    public static void memoryPoolDropOwner(long pool, torrent_handle h, int owner) {
        memory_pool_drop_owner(pool, torrent_handle.getCPtr(h), h, owner);
    }

    public static long memoryPoolPendingPersistBytes(long pool, torrent_handle h) {
        return memory_pool_pending_persist_bytes(pool, torrent_handle.getCPtr(h), h);
    }

    public static int memoryPoolPersistFailures(long pool, torrent_handle h) {
        return memory_pool_persist_failures(pool, torrent_handle.getCPtr(h), h);
    }

    /** Returns the code; place[0] receives the place. Waits for the network thread. */
    public static int memoryPoolForgetPiece(long pool, torrent_handle h, int piece, int[] place) {
        return memory_pool_forget_piece(pool, torrent_handle.getCPtr(h), h, piece, place);
    }

    /** The caller has checked the bounds of dst, dstOff and len. */
    public static int memoryPoolRead(long pool, torrent_handle h, int piece, int offset,
                                     byte[] dst, int dstOff, int len) {
        return memory_pool_read(pool, torrent_handle.getCPtr(h), h, piece, offset, dst, dstOff, len);
    }

    public static void memoryPoolInMemory(long pool, torrent_handle h, bitfield complete, bitfield partial) {
        memory_pool_in_memory(pool, torrent_handle.getCPtr(h), h,
                bitfield.getCPtr(complete), complete, bitfield.getCPtr(partial), partial);
    }

    /** held[0] complete, held[1] partial. */
    public static void memoryPoolHeldBytes(long pool, torrent_handle h, long[] held) {
        memory_pool_held_bytes_of(pool, torrent_handle.getCPtr(h), h, held);
    }

    public static long memoryPoolHeldBytes(long pool) {
        return memory_pool_held_bytes(pool);
    }

    public static long memoryPoolRetiredBytes(long pool) {
        return memory_pool_retired_bytes(pool);
    }

    public static long memoryPoolSpilledPieces(long pool) {
        return memory_pool_spilled_pieces(pool);
    }

    public static long memoryPoolHashMissingBlocks(long pool) {
        return memory_pool_hash_missing_blocks(pool);
    }

    public static void memoryPoolFilterResume(long pool, torrent_handle h, add_torrent_params atp) {
        memory_pool_filter_resume_of(pool, torrent_handle.getCPtr(h), h, add_torrent_params.getCPtr(atp), atp);
    }

    public static void memoryPoolFilterResume(long pool, add_torrent_params atp) {
        memory_pool_filter_resume(pool, add_torrent_params.getCPtr(atp), atp);
    }

    public static void memoryPoolForgetRecord(long pool, info_hash_t ih) {
        memory_pool_forget_record(pool, info_hash_t.getCPtr(ih), ih);
    }

    private static native long memory_pool_new(int slabBytes);

    private static native void memory_pool_delete(long pool);

    private static native void memory_pool_set_disk_io(long pool, long params, session_params paramsRef);

    private static native void memory_pool_set_default_policy(long pool, int policy);

    private static native void memory_pool_set_policy_info_hash(long pool, long ih, info_hash_t ihRef, int policy);

    private static native void memory_pool_set_policy(long pool, long h, torrent_handle hRef, int owner,
                                                      int policy, int[] files);

    private static native void memory_pool_set_limit(long pool, long bytes);

    private static native void memory_pool_persist(long pool, long h, torrent_handle hRef, int[] pieces);

    private static native void memory_pool_set_persist(long pool, long h, torrent_handle hRef, int owner,
                                                       int[] pieces);

    private static native void memory_pool_drop_owner(long pool, long h, torrent_handle hRef, int owner);

    private static native long memory_pool_pending_persist_bytes(long pool, long h, torrent_handle hRef);

    private static native int memory_pool_persist_failures(long pool, long h, torrent_handle hRef);

    private static native int memory_pool_forget_piece(long pool, long h, torrent_handle hRef, int piece,
                                                       int[] place);

    private static native int memory_pool_read(long pool, long h, torrent_handle hRef, int piece, int offset,
                                               byte[] dst, int dstOff, int len);

    private static native void memory_pool_in_memory(long pool, long h, torrent_handle hRef,
                                                     long complete, bitfield completeRef,
                                                     long partial, bitfield partialRef);

    private static native void memory_pool_held_bytes_of(long pool, long h, torrent_handle hRef, long[] held);

    private static native long memory_pool_held_bytes(long pool);

    private static native long memory_pool_retired_bytes(long pool);

    private static native long memory_pool_spilled_pieces(long pool);

    private static native long memory_pool_hash_missing_blocks(long pool);

    private static native void memory_pool_filter_resume_of(long pool, long h, torrent_handle hRef,
                                                            long atp, add_torrent_params atpRef);

    private static native void memory_pool_filter_resume(long pool, long atp, add_torrent_params atpRef);

    private static native void memory_pool_forget_record(long pool, long ih, info_hash_t ihRef);

    // ---- test-only controls, present only in builds with TORRENT_USE_ASSERTS
    //      (the product build does not export them; calling them there throws
    //      UnsatisfiedLinkError). They exist so an assert-enabled build can
    //      prove it is able to fail before its silence is taken as evidence.

    /** Calls aux::torrent::forget_piece on the calling thread: must trip is_single_thread(). */
    public static int forgetPieceOffThreadForTest(torrent_handle h, int piece) {
        return forget_piece_off_thread_for_test(torrent_handle.getCPtr(h), piece);
    }

    /** Drops the piece from the picker without any bookkeeping: must trip torrent::check_invariant later. */
    public static int breakPickerForTest(torrent_handle h, int piece) {
        return break_picker_for_test(torrent_handle.getCPtr(h), piece);
    }

    private static native int forget_piece_off_thread_for_test(long handlePtr, int piece);

    private static native int break_picker_for_test(long handlePtr, int piece);
}
