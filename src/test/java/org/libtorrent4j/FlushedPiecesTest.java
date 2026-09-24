package org.libtorrent4j;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.libtorrent4j.swig.byte_vector;
import org.libtorrent4j.swig.create_file_entry_vector;
import org.libtorrent4j.swig.create_flags_t;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.list_files_listener;
import org.libtorrent4j.swig.set_piece_hashes_listener;

import java.io.File;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.libtorrent4j.swig.libtorrent.list_files_ex;
import static org.libtorrent4j.swig.libtorrent.set_piece_hashes_ex;

/**
 * TorrentStatus.flushedPieces(): the pieces whose bytes are in the files, as
 * a snapshot. Only filled in when queried with QUERY_FLUSHED_PIECES, and
 * independently of QUERY_PIECES.
 */
public class FlushedPiecesTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testSeedHasEveryPieceFlushed() throws Exception {
        File dir = folder.newFolder();
        final File data = new File(dir, "data.bin");
        byte[] content = new byte[4 * 64 * 1024];
        new Random(1).nextBytes(content);
        Utils.writeByteArrayToFile(data, content, false);

        list_files_listener files = new list_files_listener() {
            @Override
            public boolean pred(String p) {
                return true;
            }
        };
        create_file_entry_vector entries = list_files_ex(data.getAbsolutePath(), files, new create_flags_t());
        create_torrent ct = new create_torrent(entries, 64 * 1024);
        error_code ec = new error_code();
        set_piece_hashes_listener hashes = new set_piece_hashes_listener() {
            @Override
            public void progress(int i) {
            }
        };
        set_piece_hashes_ex(ct, dir.getAbsolutePath(), hashes, ec);
        assertEquals(0, ec.value());
        byte_vector buffer = ct.generate().bencode();
        TorrentInfo ti = TorrentInfo.bdecode(Vectors.byte_vector2bytes(buffer));

        SessionManager s = new SessionManager();
        s.start();
        try {
            s.download(ti, dir);
            TorrentHandle th = null;
            long end = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < end) {
                th = s.find(ti.infoHash());
                if (th != null && th.isValid() && th.status().isSeeding()) break;
                Thread.sleep(50);
            }
            assertNotNull(th);
            assertTrue("the torrent checks its file and seeds", th.status().isSeeding());

            int n = ti.numPieces();
            TorrentStatus both = th.status(TorrentHandle.QUERY_PIECES.or_(TorrentHandle.QUERY_FLUSHED_PIECES));
            assertEquals(n, both.pieces().count());
            assertEquals(n, both.flushedPieces().size());
            assertEquals(n, both.flushedPieces().count());

            // each flag fills in its own field only
            TorrentStatus flushedOnly = th.status(TorrentHandle.QUERY_FLUSHED_PIECES);
            assertEquals(n, flushedOnly.flushedPieces().count());
            assertEquals(0, flushedOnly.pieces().size());
            TorrentStatus piecesOnly = th.status(TorrentHandle.QUERY_PIECES);
            assertEquals(0, piecesOnly.flushedPieces().size());
        } finally {
            s.stop();
        }
    }
}
