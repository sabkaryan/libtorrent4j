package org.libtorrent4j.alerts;

import org.libtorrent4j.swig.piece_flushed_alert;

/**
 * Posted once for a piece when all of its blocks have been written to the
 * files: their bytes have been handed to the file system and are visible to
 * anyone reading the files. {@link PieceFinishedAlert} only says that a piece
 * passed its hash check; with a disk I/O backend that caches writes the bytes
 * may reach the files later. An application that reads the downloaded files
 * directly, rather than through {@code TorrentHandle.readPiece}, should wait
 * for this alert.
 * <p>
 * For a piece that passed its hash check it is posted at or after the piece's
 * {@link PieceFinishedAlert}, never before. It says nothing about durability
 * (no fsync is implied). Category {@code piece_progress}.
 */
public final class PieceFlushedAlert extends TorrentAlert<piece_flushed_alert> {

    PieceFlushedAlert(piece_flushed_alert alert) {
        super(alert);
    }

    /**
     * The index of the piece whose blocks have all been written.
     *
     * @return the piece index
     */
    public int pieceIndex() {
        return alert.getPiece_index();
    }
}
