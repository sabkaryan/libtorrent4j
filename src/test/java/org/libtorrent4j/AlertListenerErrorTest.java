package org.libtorrent4j;

import org.junit.Test;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * An exception thrown by an alert listener is caught so the alert loop goes
 * on, and it is logged with the alert type, the exception's class and the
 * exception itself, so it does not look like an alert that never came.
 */
public class AlertListenerErrorTest {

    @Test
    public void testListenerExceptionIsLoggedWithTheAlertType() throws Exception {
        final RuntimeException boom = new IllegalStateException("boom");
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getThrown() == boom || String.valueOf(r.getMessage()).contains("boom"))
                    records.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger("lt4j");
        logger.addHandler(handler);

        SettingsPack sp = new SettingsPack();
        sp.listenInterfaces("127.0.0.1:0");
        sp.setEnableDht(false);
        SessionManager s = new SessionManager();
        s.addListener(new AlertListener() {
            @Override
            public int[] types() {
                // posted by the alert loop about once a second
                return new int[]{AlertType.SESSION_STATS.swig()};
            }

            @Override
            public void alert(Alert<?> alert) {
                throw boom;
            }
        });
        s.start(new SessionParams(sp));
        try {
            long end = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < end && records.isEmpty()) {
                Thread.sleep(100);
            }
            assertSame(boom, s.lastAlertError());
        } finally {
            s.stop();
            logger.removeHandler(handler);
        }

        assertTrue("the listener's exception was logged", !records.isEmpty());
        LogRecord r = records.get(0);
        assertEquals(Level.WARNING, r.getLevel());
        assertSame("the exception itself, for its stack trace", boom, r.getThrown());
        assertNotNull(r.getMessage());
        assertTrue(r.getMessage(), r.getMessage().contains("SESSION_STATS"));
        assertTrue(r.getMessage(), r.getMessage().contains(IllegalStateException.class.getName()));
    }
}
