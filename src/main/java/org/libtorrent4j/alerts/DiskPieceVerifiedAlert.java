package org.libtorrent4j.alerts;

import org.libtorrent4j.ErrorCode;
import org.libtorrent4j.swig.disk_piece_verified_alert;

/**
 * Posted for each {@code verify_disk_piece()} call on a torrent handle: the
 * bytes of a piece that are in the files were checked against its hashes,
 * without any copy of the piece a cache or the memory disk I/O holds.
 * <p>
 * A {@code verified} result makes a piece of
 * {@code add_torrent_params.unverified_pieces} an ordinary downloaded piece.
 * {@code stale} says nothing about the bytes now: the piece was forgotten,
 * or the torrent removed, while it was checked. Category {@code storage}.
 */
public final class DiskPieceVerifiedAlert extends TorrentAlert<disk_piece_verified_alert> {

    DiskPieceVerifiedAlert(disk_piece_verified_alert alert) {
        super(alert);
    }

    /**
     * @return the index of the checked piece
     */
    public int pieceIndex() {
        return alert.getPiece_index();
    }

    /**
     * @return the request id passed to {@code verify_disk_piece()}, as an
     * unsigned 64-bit value
     */
    public long requestId() {
        return alert.getRequest_id().longValue();
    }

    /**
     * @return the epoch passed to {@code verify_disk_piece()}, as an unsigned
     * 64-bit value
     */
    public long epoch() {
        return alert.getEpoch().longValue();
    }

    /**
     * @return the outcome of the check
     */
    public disk_piece_verified_alert.result_t result() {
        return alert.getResult();
    }

    /**
     * @return the error behind a {@code missing_or_short} or {@code io_error}
     * result, otherwise no error
     */
    public ErrorCode error() {
        return new ErrorCode(alert.getError());
    }
}
