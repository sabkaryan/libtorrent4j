package org.libtorrent4j;

import org.junit.Test;
import org.libtorrent4j.swig.libtorrent;
import org.libtorrent4j.swig.session_params;
import org.libtorrent4j.swig.settings_pack;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The generated {@code outgoing_tcp_first} constant reaches the native setting of that name.
 */
public class OutgoingTcpFirstSettingTest {

    private static final int OUTGOING_TCP_FIRST = settings_pack.bool_types.outgoing_tcp_first.swigValue();

    @Test
    public void theConstantNamesTheNativeSetting() {
        assertEquals("outgoing_tcp_first", libtorrent.name_for_setting(OUTGOING_TCP_FIRST));
    }

    @Test
    public void isOffByDefault() {
        assertFalse(SettingsPack.defaultSettings().getBoolean(OUTGOING_TCP_FIRST));
    }

    @Test
    public void isWrittenAndReadBack() {
        SettingsPack settings = new SettingsPack();

        settings.setBoolean(OUTGOING_TCP_FIRST, true);
        assertTrue(settings.getBoolean(OUTGOING_TCP_FIRST));

        settings.setBoolean(OUTGOING_TCP_FIRST, false);
        assertFalse(settings.getBoolean(OUTGOING_TCP_FIRST));
    }

    @Test
    public void survivesTheSessionStateBytes() {
        SettingsPack settings = new SettingsPack();
        settings.setBoolean(OUTGOING_TCP_FIRST, true);
        SessionParams written = new SessionParams(settings);

        byte[] bytes = Vectors.byte_vector2bytes(session_params.write_session_params_buf(written.swig()));
        SessionParams read = new SessionParams(bytes);

        assertTrue(read.getSettings().getBoolean(OUTGOING_TCP_FIRST));
    }
}
