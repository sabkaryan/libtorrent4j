package org.libtorrent4j.swig;

/**
 * Hand-written entry points that are not part of the SWIG-generated wrapper
 * (see swig/ext_jni.cpp). They take the SWIG C pointer of a {@link torrent_handle}.
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
