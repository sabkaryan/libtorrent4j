package org.libtorrent4j;

/**
 * Bytes of piece data held in memory by one torrent, from
 * {@link MemoryStoragePool#heldBytes(TorrentHandle)}. Both are
 * {@link MemoryStoragePool#NOT_MANAGED} for a torrent without storage in the
 * pool.
 */
public final class MemoryHeld {

    private final long complete;
    private final long partial;

    MemoryHeld(long complete, long partial) {
        this.complete = complete;
        this.partial = partial;
    }

    /**
     * @return bytes in pieces whose every block is present
     */
    public long complete() {
        return complete;
    }

    /**
     * @return bytes in pieces with missing blocks
     */
    public long partial() {
        return partial;
    }
}
