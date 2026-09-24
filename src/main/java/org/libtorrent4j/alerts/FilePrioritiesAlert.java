package org.libtorrent4j.alerts;

import org.libtorrent4j.swig.file_priorities_alert;

/**
 * Posted in response to {@code torrent_handle::post_file_priorities()}.
 * The priorities themselves are not mapped to Java.
 */
public final class FilePrioritiesAlert extends TorrentAlert<file_priorities_alert> {

    FilePrioritiesAlert(file_priorities_alert alert) {
        super(alert);
    }
}
