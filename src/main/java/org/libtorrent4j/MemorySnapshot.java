package org.libtorrent4j;

/**
 * Where the pieces of one torrent are and the bytes the pool holds, all taken
 * at one moment, from {@link MemoryStoragePool#snapshot(TorrentHandle)}. A
 * piece is in at most one of the five sets; a piece in none has no bytes in the
 * pool and none in the file that the pool knows of. Unless the status is 0, the
 * sets are empty and the numbers 0.
 */
public final class MemorySnapshot {

    private final int status;
    private final PieceIndexBitfield ramComplete;
    private final PieceIndexBitfield ramPartial;
    private final PieceIndexBitfield transfer;
    private final PieceIndexBitfield fileReady;
    private final PieceIndexBitfield filePending;
    private final long[] numbers;

    MemorySnapshot(int status, PieceIndexBitfield ramComplete, PieceIndexBitfield ramPartial,
                   PieceIndexBitfield transfer, PieceIndexBitfield fileReady,
                   PieceIndexBitfield filePending, long[] numbers) {
        this.status = status;
        this.ramComplete = ramComplete;
        this.ramPartial = ramPartial;
        this.transfer = transfer;
        this.fileReady = fileReady;
        this.filePending = filePending;
        this.numbers = numbers;
    }

    /**
     * @return 0 if the snapshot was taken, {@link MemoryStoragePool#NOT_MANAGED}
     * or {@link MemoryStoragePool#INVALID_HANDLE}
     */
    public int status() {
        return status;
    }

    /**
     * @return the pieces held in memory whose every block is present
     */
    public PieceIndexBitfield ramComplete() {
        return ramComplete;
    }

    /**
     * @return the pieces held in memory with missing blocks
     */
    public PieceIndexBitfield ramPartial() {
        return ramPartial;
    }

    /**
     * @return the pieces being moved from memory to the file; their blocks
     * still in memory count in the held bytes
     */
    public PieceIndexBitfield transfer() {
        return transfer;
    }

    /**
     * @return the pieces the default back end can read from the file: the
     * torrent has the piece (it passed its hash check), it is "in file" (as
     * {@code filterResume} keeps it) and its current bytes are not in memory.
     * Whether the bytes are on the disk (a write cache, no fsync) and whether
     * the file is still there is not checked
     */
    public PieceIndexBitfield fileReady() {
        return fileReady;
    }

    /**
     * @return the pieces whose bytes go to (or were written to) the file but
     * that are not ready: blocks missing in the file, or not checked yet
     */
    public PieceIndexBitfield filePending() {
        return filePending;
    }

    /**
     * @return the torrent's bytes in pieces whose every block is present, as
     * {@link MemoryStoragePool#heldBytes(TorrentHandle)}
     */
    public long heldComplete() {
        return numbers[0];
    }

    /**
     * @return the torrent's bytes in pieces with missing blocks
     */
    public long heldPartial() {
        return numbers[1];
    }

    /**
     * @return the torrent's bytes in memory that wait to be moved to the file,
     * as {@link MemoryStoragePool#pendingPersistBytes(TorrentHandle)}
     */
    public long pendingPersist() {
        return numbers[2];
    }

    /**
     * @return the torrent's whole-piece reservations
     */
    public long reserved() {
        return numbers[3];
    }

    /**
     * @return the bytes held by the whole pool, as {@link MemoryStoragePool#heldBytes()}
     */
    public long poolHeld() {
        return numbers[4];
    }

    /**
     * @return the whole-piece reservations of the whole pool, of removed
     * torrents too
     */
    public long poolReserved() {
        return numbers[5];
    }

    /**
     * @return the retired bytes of the whole pool, as
     * {@link MemoryStoragePool#retiredBytes()}
     */
    public long poolRetired() {
        return numbers[6];
    }
}
