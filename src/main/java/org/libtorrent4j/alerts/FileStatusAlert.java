package org.libtorrent4j.alerts;

import org.libtorrent4j.swig.file_status_alert;

/**
 * Posted in response to {@code torrent_handle::post_file_status()}.
 * The file states themselves are not mapped to Java.
 */
public final class FileStatusAlert extends TorrentAlert<file_status_alert> {

    FileStatusAlert(file_status_alert alert) {
        super(alert);
    }
}
