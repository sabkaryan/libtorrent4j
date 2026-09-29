package org.libtorrent4j;

/**
 * Where the pieces of a torrent (or of the files a claim names) are meant to
 * be stored by the in-memory disk back end ({@link MemoryStoragePool}).
 * The ordinals are the values of the native {@code memory_policy}.
 */
public enum MemoryPolicy {

    /**
     * Pieces are written to files by the regular disk back end.
     */
    FILE,

    /**
     * Pieces are kept in the memory pool.
     */
    MEMORY
}
