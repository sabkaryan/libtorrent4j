/*
 * Copyright (c) 2021-2022, Alden Torres
 *
 * Licensed under the terms of the MIT license.
 * Copy of the license at https://opensource.org/licenses/MIT
 */

package org.libtorrent4j;

import org.junit.Test;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.Alerts;
import org.libtorrent4j.swig.file_prio_alert;
import org.libtorrent4j.swig.oversized_file_alert;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * @author aldenml
 */
public class AlertTypeTest {

    @Test
    public void testFromSwig() {

        assertEquals(AlertType.FILE_PRIO, AlertType.fromSwig(file_prio_alert.alert_type));
        assertEquals(AlertType.OVERSIZED_FILE, AlertType.fromSwig(oversized_file_alert.alert_type));
    }

    // every alert type the native library can post must map to an AlertType,
    // otherwise the alerts loop switches on null and dies
    @Test
    public void testEveryNativeAlertTypeIsMapped() {
        for (int i = 0; i < Alerts.NUM_ALERT_TYPES; i++) {
            assertNotNull("AlertType.fromSwig(" + i + ")", AlertType.fromSwig(i));
        }
    }

    // and must have a cast entry, otherwise Alerts.cast() throws a NullPointerException
    @Test
    public void testEveryNativeAlertTypeCanBeCast() throws Exception {
        Field f = Alerts.class.getDeclaredField("TABLE");
        f.setAccessible(true);
        Object[] table = (Object[]) f.get(null);
        assertEquals(Alerts.NUM_ALERT_TYPES, table.length);
        for (int i = 0; i < table.length; i++) {
            assertNotNull("Alerts.TABLE[" + i + "]", table[i]);
        }
    }
}
