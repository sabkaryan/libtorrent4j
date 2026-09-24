package org.libtorrent4j;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The native library must be built from the libtorrent this jar was built
 * against. A native library from another build either lacks the entry point
 * (UnsatisfiedLinkError) or reports a different libtorrent.
 */
public class NativeBuildTest {

    @Test
    public void testNativeLibraryMatchesJar() {
        assertEquals(LibTorrent.expectedNativeBuild(), LibTorrent.nativeBuild());
    }

    @Test
    public void testExpectedNativeBuildNamesVersionAndRevision() {
        String expected = LibTorrent.expectedNativeBuild();
        assertTrue(expected, expected.matches("\\d+\\.\\d+\\.\\d+\\.\\d+ [0-9a-f]{40}"));
    }
}
