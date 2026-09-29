package org.libtorrent4j;

import org.libtorrent4j.swig.bitfield;
import org.libtorrent4j.swig.libtorrent_ext;
import org.libtorrent4j.swig.session_params;
import org.libtorrent4j.swig.torrent_handle;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The shared state of the in-memory disk back end (the native
 * {@code memory_storage_pool}) and the calls of the client. A session uses it
 * once it is given to {@link SessionParams#setMemoryDiskIo(MemoryStoragePool)};
 * one pool may serve several sessions.
 * <p>
 * A call naming a {@link TorrentHandle} finds the storage of the handle's
 * torrent. If it has none in the pool (removed, not added through a session
 * of this pool, or an invalid handle) the call answers {@link #NOT_MANAGED}
 * (or an empty result). A claim ({@code setPolicy} by handle,
 * {@code persist}, {@code setPersist}, {@code dropOwner}) for a torrent that
 * has no metadata yet waits for it and applies once its storage is created.
 * <p>
 * All calls are safe from any thread. Only
 * {@link #forgetPiece(TorrentHandle, int)} waits for the network thread; every
 * other call works under the pool's mutex and returns at once.
 * <p>
 * {@link #close()} releases this object's reference to the native pool; a
 * session made with it keeps its own, so closing it while a session runs is
 * safe. Any call after {@code close()} throws {@link IllegalStateException}.
 * A pool that is never closed keeps its native object until the process ends.
 */
public final class MemoryStoragePool implements AutoCloseable {

    /**
     * Returned by {@link #read} when the requested bytes are not in memory.
     */
    public static final int NOT_IN_MEMORY = -1;

    /**
     * Returned by calls naming a torrent that has no storage in the pool.
     */
    public static final int NOT_MANAGED = -2;

    private static final int BLOCK_SIZE = 16 * 1024;

    // calls hold the read lock, close() the write lock: a pointer is never
    // deleted while a call uses it
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private long ptr;

    /**
     * An empty pool with the default slab size. Its limit is 0 (every piece
     * goes to the file) until {@link #setLimit(long)} is called.
     */
    public MemoryStoragePool() {
        this.ptr = libtorrent_ext.memoryPoolNew(0);
    }

    /**
     * An empty pool that maps memory {@code slabBytes} at a time.
     *
     * @param slabBytes the size of one slab, a positive multiple of 16 kiB
     */
    public MemoryStoragePool(int slabBytes) {
        if (slabBytes < BLOCK_SIZE || slabBytes % BLOCK_SIZE != 0) {
            throw new IllegalArgumentException("slabBytes must be a positive multiple of 16 kiB: " + slabBytes);
        }
        this.ptr = libtorrent_ext.memoryPoolNew(slabBytes);
    }

    /**
     * Releases this object's reference to the native pool. Idempotent.
     */
    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (ptr != 0) {
                libtorrent_ext.memoryPoolDelete(ptr);
                ptr = 0;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * @return true once {@link #close()} was called
     */
    public boolean isClosed() {
        lock.readLock().lock();
        try {
            return ptr == 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * The policy of torrents that are not registered with
     * {@link #setPolicy(InfoHash, MemoryPolicy)}. Initially {@link MemoryPolicy#FILE}.
     */
    public void setDefaultPolicy(MemoryPolicy policy) {
        int p = policy.ordinal();
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolSetDefaultPolicy(pool, p);
        } finally {
            release();
        }
    }

    /**
     * Registers the policy of a torrent before it is added. It matches by the
     * v1 info-hash or by the truncated v2 info-hash.
     */
    public void setPolicy(InfoHash ih, MemoryPolicy policy) {
        int p = policy.ordinal();
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolSetPolicy(pool, ih.swig(), p);
        } finally {
            release();
        }
    }

    /**
     * The claim of one owner on a torrent, or on the given files of it (all
     * files if none). It replaces the owner's previous claim. It decides the
     * place of pieces that start from now on, and the pieces held in memory
     * that it sends to the file, complete or partial, are moved there (see
     * {@link #pendingPersistBytes(TorrentHandle)}).
     *
     * @param owner the owner, picked by the client (all 32 bits)
     */
    public void setPolicy(TorrentHandle th, int owner, MemoryPolicy policy, int... files) {
        torrent_handle h = th.swig();
        int p = policy.ordinal();
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolSetPolicy(pool, h, owner, p, files);
        } finally {
            release();
        }
    }

    /**
     * The number of bytes the pool may hold, for all sessions. It is 0 until
     * this is called: every piece goes to the file. The limit is soft: a piece
     * that starts while the pool holds at least the limit goes to the file
     * (see {@link #spilledPieces()}). Lowering it drops nothing already held.
     */
    public void setLimit(long bytes) {
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolSetLimit(pool, bytes);
        } finally {
            release();
        }
    }

    /**
     * A one-shot request to store the given pieces in the file: a piece held
     * in memory is moved there, a piece that has not started starts there. A
     * piece leaves the request once it is in the file.
     */
    public void persist(TorrentHandle th, int... pieces) {
        torrent_handle h = th.swig();
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolPersist(pool, h, pieces);
        } finally {
            release();
        }
    }

    /**
     * Replaces the set of pieces this owner wants stored in the file. The set
     * stays until the owner changes or drops it: a piece of it that is
     * forgotten and downloaded again goes to the file again. Its pieces held
     * in memory are moved to the file; pieces in the file or being moved are
     * left alone.
     */
    public void setPersist(TorrentHandle th, int owner, int... pieces) {
        torrent_handle h = th.swig();
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolSetPersist(pool, h, owner, pieces);
        } finally {
            release();
        }
    }

    /**
     * Drops every claim of the owner on the torrent. No bytes are moved.
     */
    public void dropOwner(TorrentHandle th, int owner) {
        torrent_handle h = th.swig();
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolDropOwner(pool, h, owner);
        } finally {
            release();
        }
    }

    /**
     * Bytes in memory that wait to be moved to the file. The calls that move
     * pieces ({@code setPolicy} by handle, {@code persist}, {@code setPersist})
     * count them before they return, and the moves run on the network thread
     * without waiting: 0 right after such a call means nothing is to be moved.
     * Pieces that have not arrived are not counted. The blocks of a partial
     * piece count until the default back end wrote them; the move flushes them
     * without waiting for a download, a full cache or a pause.
     *
     * @return the bytes, or {@link #NOT_MANAGED}
     */
    public long pendingPersistBytes(TorrentHandle th) {
        torrent_handle h = th.swig();
        long pool = acquire();
        try {
            return libtorrent_ext.memoryPoolPendingPersistBytes(pool, h);
        } finally {
            release();
        }
    }

    /**
     * The number of moves to the file that failed (a write error, or the hash
     * of the default back end differs from the pool's): the piece stays in
     * memory. A partial piece whose write failed goes to the file anyway,
     * without the failed block: its hash fails later and it is downloaded
     * again. Every failed attempt counts: a piece moved again that fails
     * again counts again.
     *
     * @return the count, or {@link #NOT_MANAGED}
     */
    public int persistFailures(TorrentHandle th) {
        torrent_handle h = th.swig();
        long pool = acquire();
        try {
            return libtorrent_ext.memoryPoolPersistFailures(pool, h);
        } finally {
            release();
        }
    }

    /**
     * Forgets the piece atomically: one task on the network thread of the
     * handle's session forgets the piece in the torrent and, if that returns
     * 0, drops the bytes the pool holds for it and the piece is not "in file"
     * any more.
     * <p>
     * This call waits for the network thread. It must not be called from the
     * network thread: the session's alert notify callback
     * ({@code session_handle.set_alert_notify_callback}) or an extension. An {@link AlertListener} of
     * {@link SessionManager} runs on its own thread, and may call it.
     * <p>
     * With {@link PiecePlace#FILE} the caller may release the bytes in the
     * file, with the limitation of the torrent's forget piece: in a torrent
     * with only v2 hashes added from a magnet link, code 0 may come before all
     * the piece's blocks are in the file.
     *
     * @return the code and the place the piece's bytes had
     */
    public ForgetResult forgetPiece(TorrentHandle th, int piece) {
        torrent_handle h = th.swig();
        int[] place = new int[1];
        int code;
        long pool = acquire();
        try {
            code = libtorrent_ext.memoryPoolForgetPiece(pool, h, piece, place);
        } finally {
            release();
        }
        return new ForgetResult(code, PiecePlace.values()[place[0]]);
    }

    /**
     * Copies bytes of a piece held in memory into {@code dst}, at most the
     * piece size minus {@code offset} (pad blocks read as zeros; a piece being
     * moved to the file is still read from memory). Nothing is copied unless
     * every requested byte is in memory.
     *
     * @return the number of bytes copied, {@link #NOT_IN_MEMORY} or
     * {@link #NOT_MANAGED}
     * @throws IndexOutOfBoundsException if {@code dstOff} and {@code len} do
     *                                   not fit in {@code dst}
     */
    public int read(TorrentHandle th, int piece, int offset, byte[] dst, int dstOff, int len) {
        if (dstOff < 0 || len < 0 || dstOff > dst.length - len) {
            throw new IndexOutOfBoundsException("dst.length " + dst.length + ", dstOff " + dstOff + ", len " + len);
        }
        torrent_handle h = th.swig();
        long pool = acquire();
        try {
            return libtorrent_ext.memoryPoolRead(pool, h, piece, offset, dst, dstOff, len);
        } finally {
            release();
        }
    }

    /**
     * @return the pieces of the torrent held in memory
     */
    public MemoryPieces inMemory(TorrentHandle th) {
        torrent_handle h = th.swig();
        bitfield complete = new bitfield();
        bitfield partial = new bitfield();
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolInMemory(pool, h, complete, partial);
        } finally {
            release();
        }
        return new MemoryPieces(new PieceIndexBitfield(complete), new PieceIndexBitfield(partial));
    }

    /**
     * @return the bytes held in memory by one torrent (both
     * {@link #NOT_MANAGED} if it has no storage in the pool)
     */
    public MemoryHeld heldBytes(TorrentHandle th) {
        torrent_handle h = th.swig();
        long[] held = new long[2];
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolHeldBytes(pool, h, held);
        } finally {
            release();
        }
        return new MemoryHeld(held[0], held[1]);
    }

    /**
     * @return the bytes held in memory by the whole pool
     */
    public long heldBytes() {
        long pool = acquire();
        try {
            return libtorrent_ext.memoryPoolHeldBytes(pool);
        } finally {
            release();
        }
    }

    /**
     * @return bytes of forgotten pieces that are still in use by disk jobs,
     * of removed torrents too
     */
    public long retiredBytes() {
        long pool = acquire();
        try {
            return libtorrent_ext.memoryPoolRetiredBytes(pool);
        } finally {
            release();
        }
    }

    /**
     * @return pieces that were bound for memory and went to the file because
     * the pool was at its limit, of removed torrents too
     */
    public long spilledPieces() {
        long pool = acquire();
        try {
            return libtorrent_ext.memoryPoolSpilledPieces(pool);
        } finally {
            release();
        }
    }

    /**
     * @return blocks that were missing when a piece held in memory was hashed
     */
    public long hashMissingBlocks() {
        long pool = acquire();
        try {
            return libtorrent_ext.memoryPoolHashMissingBlocks(pool);
        } finally {
            release();
        }
    }

    /**
     * Removes from resume data the pieces that are not stored in the file.
     * Apply it to the result of a save resume data before it is written:
     * after a restart the pool is empty, and resume data saved without it
     * names pieces that were only in memory. It uses the storage of the
     * handle's torrent (by info-hash, as {@link #filterResume(AddTorrentParams)},
     * if it has none).
     */
    public void filterResume(TorrentHandle th, AddTorrentParams params) {
        torrent_handle h = th.swig();
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolFilterResume(pool, h, params.swig());
        } finally {
            release();
        }
    }

    /**
     * As {@link #filterResume(TorrentHandle, AddTorrentParams)}, for a torrent
     * already removed: its storages are found by the info-hashes of the resume
     * data, among the live ones and those the pool keeps of removed torrents;
     * a piece stays only if every one of them has it. A torrent the pool does
     * not know is left unchanged.
     */
    public void filterResume(AddTorrentParams params) {
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolFilterResume(pool, params.swig());
        } finally {
            release();
        }
    }

    /**
     * Drops what the pool keeps of removed torrents with these info-hashes
     * (the v1 or the v2 one matches) for {@link #filterResume(AddTorrentParams)}.
     * Every removal leaves such a record; without this call they pile up for
     * the life of the pool. Call it after the filtered resume data of the
     * removed torrent is written, or at the removal if there will be none:
     * {@code filterResume} leaves the resume data of a torrent the pool does
     * not know unchanged.
     */
    public void forgetRecord(InfoHash ih) {
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolForgetRecord(pool, ih.swig());
        } finally {
            release();
        }
    }

    // SessionParams.setMemoryDiskIo()
    void setDiskIo(session_params params) {
        long pool = acquire();
        try {
            libtorrent_ext.memoryPoolSetDiskIo(pool, params);
        } finally {
            release();
        }
    }

    // takes the read lock and returns the pointer; release() must follow
    private long acquire() {
        lock.readLock().lock();
        if (ptr == 0) {
            lock.readLock().unlock();
            throw new IllegalStateException("MemoryStoragePool is closed");
        }
        return ptr;
    }

    private void release() {
        lock.readLock().unlock();
    }
}
