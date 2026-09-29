package org.libtorrent4j;

/**
 * The pieces of one torrent held in memory, from
 * {@link MemoryStoragePool#inMemory(TorrentHandle)}. Both are empty for a
 * torrent without storage in the pool.
 */
public final class MemoryPieces {

    private final PieceIndexBitfield complete;
    private final PieceIndexBitfield partial;

    MemoryPieces(PieceIndexBitfield complete, PieceIndexBitfield partial) {
        this.complete = complete;
        this.partial = partial;
    }

    /**
     * @return the pieces whose every block is present
     */
    public PieceIndexBitfield complete() {
        return complete;
    }

    /**
     * @return the pieces with missing blocks
     */
    public PieceIndexBitfield partial() {
        return partial;
    }
}
