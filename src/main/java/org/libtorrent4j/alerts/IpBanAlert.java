package org.libtorrent4j.alerts;

import org.libtorrent4j.swig.ip_ban_alert;

/**
 * Posted when an IP address is banned (category {@code ip_block}).
 * The banned address is not mapped to Java; see {@link #message()}.
 */
public final class IpBanAlert extends AbstractAlert<ip_ban_alert> {

    IpBanAlert(ip_ban_alert alert) {
        super(alert);
    }
}
