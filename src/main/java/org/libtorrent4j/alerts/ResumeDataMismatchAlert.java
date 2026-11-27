package org.libtorrent4j.alerts;

import org.libtorrent4j.ErrorCode;
import org.libtorrent4j.swig.resume_data_mismatch_alert;

/**
 * Posted when the segment files of a torrent stored as segments do not hold
 * the pieces its resume data lists: a segment is missing or shorter than a
 * piece needs. The torrent is paused, taken out of auto management and its
 * files are not checked; the caller decides what to do with it.
 * Category {@code storage} and {@code error}.
 */
public final class ResumeDataMismatchAlert extends TorrentAlert<resume_data_mismatch_alert> {

    ResumeDataMismatchAlert(resume_data_mismatch_alert alert) {
        super(alert);
    }

    /**
     * @return the index of the file whose segment does not match
     */
    public int fileIndex() {
        return alert.getFile();
    }

    /**
     * @return the cell of the segment that does not match
     */
    public long cell() {
        return alert.getCell();
    }

    /**
     * @return ENOENT if the segment is missing, {@code file_too_short} if it
     * is shorter than the piece
     */
    public ErrorCode error() {
        return new ErrorCode(alert.getError());
    }
}
