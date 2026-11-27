package org.libtorrent4j.alerts;

import org.libtorrent4j.ErrorCode;
import org.libtorrent4j.swig.segment_released_alert;

/**
 * Posted for each {@code release_segment()} call on a torrent handle. A
 * segment is {@code busy}, and left as it is, while the torrent has or
 * downloads a piece with bytes in it. Category {@code storage}.
 */
public final class SegmentReleasedAlert extends TorrentAlert<segment_released_alert> {

    SegmentReleasedAlert(segment_released_alert alert) {
        super(alert);
    }

    /**
     * @return the file index passed to {@code release_segment()}
     */
    public int fileIndex() {
        return alert.getFile();
    }

    /**
     * @return the cell passed to {@code release_segment()}
     */
    public long cell() {
        return alert.getCell();
    }

    /**
     * @return the token passed to {@code release_segment()}, as an unsigned
     * 64-bit value
     */
    public long token() {
        return alert.getToken().longValue();
    }

    /**
     * @return removed, absent (there was no such file), busy or error
     */
    public segment_released_alert.result_t result() {
        return alert.getResult();
    }

    /**
     * @return the error behind an {@code error} or {@code absent} result,
     * otherwise no error
     */
    public ErrorCode error() {
        return new ErrorCode(alert.getError());
    }
}
