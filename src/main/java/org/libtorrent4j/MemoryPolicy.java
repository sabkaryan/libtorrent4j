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
    MEMORY,

    /**
     * Pieces are kept in the memory pool only. A piece that the limit (of
     * the pool or of the torrent) would send to the file is declined: none
     * of its bytes are stored, and the torrent downloads it again later,
     * without a disk error and without blaming its peers
     * ({@link MemoryStoragePool#declinedPieces()}). It applies to pieces that
     * start after it, so register it before the torrent is added
     * ({@link MemoryStoragePool#setPolicy(InfoHash, MemoryPolicy)}). Explicit
     * claims are stronger: a persist or a {@link #FILE} claim sends a piece
     * to the file, a {@link #MEMORY} claim makes it a plain memory piece.
     * <p>
     * The policy does not stop the regular disk back end from touching the
     * files on its own: raising the priority of a file from 0 creates the
     * file and moves its pieces out of the part file. To write nothing,
     * leave the file priorities alone and use piece priorities.
     * Needs {@link MemoryStoragePool#memoryOnlySupported()}.
     */
    MEMORY_ONLY
}
