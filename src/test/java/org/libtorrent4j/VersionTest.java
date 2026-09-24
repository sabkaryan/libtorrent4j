/*
 * Copyright (c) 2018-2022, Alden Torres
 *
 * Licensed under the terms of the MIT license.
 * Copy of the license at https://opensource.org/licenses/MIT
 */

package org.libtorrent4j;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author aldenml
 */
public class VersionTest {

    // libtorrent4j versions are the libtorrent version (without its fourth
    // part) and a numeric build number: 2.1.2.0 -> 2.1.2-<n>
    @Test
    public void testLibtorrent4jVersionValue() {
        String lt = LibTorrent.version();
        String prefix = lt.substring(0, lt.lastIndexOf('.')) + "-";
        String v = LibTorrent.libtorrent4jVersion();
        assertTrue(v, v.startsWith(prefix) && v.substring(prefix.length()).matches("\\d+(\\.\\d+)*"));
    }

    @Test
    public void testVersionValue() {
        assertEquals("2.1.2.0", LibTorrent.version());
    }
}
