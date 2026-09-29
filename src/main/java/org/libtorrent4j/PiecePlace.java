package org.libtorrent4j;

/**
 * Where the current bytes of one piece live in the in-memory disk back end
 * ({@link MemoryStoragePool}). The ordinals are the values of the native
 * {@code piece_place}.
 */
public enum PiecePlace {

    /**
     * The piece has not been started (or it was cleared).
     */
    NONE,

    /**
     * The piece started in the memory pool.
     */
    MEMORY,

    /**
     * The piece is held in memory and is being moved to the file.
     */
    TRANSFER,

    /**
     * The piece is stored in the file by the regular disk back end.
     */
    FILE
}
