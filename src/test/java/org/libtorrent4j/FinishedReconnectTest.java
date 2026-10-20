package org.libtorrent4j;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.libtorrent4j.swig.address;
import org.libtorrent4j.swig.byte_vector;
import org.libtorrent4j.swig.create_file_entry_vector;
import org.libtorrent4j.swig.create_flags_t;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.list_files_listener;
import org.libtorrent4j.swig.set_piece_hashes_listener;
import org.libtorrent4j.swig.settings_pack;
import org.libtorrent4j.swig.tcp_endpoint;
import org.libtorrent4j.swig.torrent_handle;

import java.io.File;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.libtorrent4j.swig.libtorrent.list_files_ex;
import static org.libtorrent4j.swig.libtorrent.set_piece_hashes_ex;

/**
 * A torrent that leaves the finished state connects again to the seed whose
 * connection was closed while it was finished: closed by the downloader
 * (close_redundant_connections), or, with close_redundant_connections off, by
 * pausing the finished torrent and resuming it at once. Two sessions on the loopback interface, and nothing else tells the
 * downloader about the seed: no tracker, DHT or local peer discovery, and the
 * seed makes no outgoing connections.
 */
public class FinishedReconnectTest {

    private static final int PIECE_SIZE = 64 * 1024;
    private static final int NUM_PIECES = 16;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    interface Resume {
        // makes the finished torrent want pieces again, in the given cycle
        // (from 0); returns the number of pieces it wants after it
        int resume(TorrentHandle th, int numPieces, int cycle, int seedPort);
    }

    @Test
    public void testReconnectAfterPiecePriority() throws Exception {
        run(folder, new Resume() {
            @Override
            public int resume(TorrentHandle th, int numPieces, int cycle, int seedPort) {
                Priority[] all = new Priority[numPieces];
                Arrays.fill(all, Priority.DEFAULT);
                th.prioritizePieces(all);
                return numPieces;
            }
        }, true, 1, 1);
    }

    @Test
    public void testReconnectAfterForgetPiece() throws Exception {
        run(folder, FORGET, true, 1, 1);
    }

    @Test
    public void testPauseThreeCycles() throws Exception {
        run(folder, FORGET, false, 3, 1);
    }

    // forgets piece `cycle`: the torrent downloads it again, the other half
    // is still not wanted
    static final Resume FORGET = new Resume() {
        @Override
        public int resume(TorrentHandle th, int numPieces, int cycle, int seedPort) {
            assertEquals(0, th.swig().forget_piece(cycle));
            return numPieces / 2;
        }
    };

    // closeRedundant false: the connection is closed by a pause and a resume
    static void run(TemporaryFolder folder, Resume resume, boolean closeRedundant, int cycles,
                    int minReconnectTime) throws Exception {
        File seedDir = folder.newFolder();
        File data = new File(seedDir, "data.bin");
        byte[] content = new byte[NUM_PIECES * PIECE_SIZE];
        new Random(1).nextBytes(content);
        Utils.writeByteArrayToFile(data, content, false);
        TorrentInfo ti = makeTorrent(seedDir, data);
        int n = ti.numPieces();

        SessionManager seed = new SessionManager();
        SessionManager dl = new SessionManager();
        SettingsPack seedSettings = settings(true, minReconnectTime);
        seedSettings.setBoolean(settings_pack.bool_types.enable_outgoing_tcp.swigValue(), false);
        seedSettings.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), false);
        seed.start(new SessionParams(seedSettings));
        dl.start(new SessionParams(settings(closeRedundant, minReconnectTime)));
        try {
            seed.download(ti, seedDir);
            waitFor(20000, () -> {
                TorrentHandle h = seed.find(ti.infoHash());
                return h != null && h.isValid() && h.status(true).isSeeding();
            });
            int seedPort = seed.swig().listen_port();
            assertTrue("the seed listens", seedPort > 0);

            // the downloader wants the first half of the pieces only
            AddTorrentParams p = new AddTorrentParams();
            p.setTorrentInfo(ti);
            p.setSavePath(folder.newFolder().getAbsolutePath());
            Priority[] half = new Priority[n];
            for (int i = 0; i < n; i++) {
                half[i] = i < n / 2 ? Priority.DEFAULT : Priority.IGNORE;
            }
            p.piecePriorities(half);
            p.setFlags(p.getFlags().and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.PAUSED.inv()));
            error_code ec = new error_code();
            torrent_handle h = dl.swig().add_torrent(p.swig(), ec);
            assertEquals(ec.message(), 0, ec.value());
            TorrentHandle th = new TorrentHandle(h);
            h.connect_peer(new tcp_endpoint(address.from_string("127.0.0.1", ec), seedPort));

            for (int cycle = 0; cycle < cycles; cycle++) {
                String at = "cycle " + cycle + ": ";
                if (!closeRedundant) {
                    assertTrue(at + "the downloader finishes", waitFor(20000, () -> {
                        TorrentStatus st = th.status(true);
                        return st.isFinished() && st.numPeers() > 0;
                    }));
                    th.pause();
                }
                // finished, and the connection to the seed closed
                assertTrue(at + "the downloader finishes and the seed is dropped", waitFor(20000, () -> {
                    TorrentStatus st = th.status(true);
                    return st.isFinished() && st.numPeers() == 0;
                }));
                if (!closeRedundant) th.resume();
                assertEquals(1, th.status(true).listPeers());
                // the session tries to connect a peer about once a second, and the
                // finished torrent's first try leaves the peer list without candidates
                Thread.sleep(3000);
                assertEquals(0, th.status(true).numPeers());

                int wanted = resume.resume(th, n, cycle, seedPort);
                TorrentStatus after = th.status(true);
                assertTrue(at + "no longer finished", !after.isFinished());
                int candidates = after.connectCandidates();

                boolean done = waitFor(20000, () -> {
                    TorrentStatus st = th.status(true);
                    return st.isFinished() && st.numPieces() == wanted;
                });
                TorrentStatus end = th.status(true);
                assertTrue(at + "the downloader connects to the seed again and finishes: connect candidates "
                    + candidates + " after the resume, then peers " + end.numPeers() + ", pieces "
                    + end.numPieces() + " of " + wanted, done);
            }
        } finally {
            dl.stop();
            seed.stop();
        }
    }

    private static SettingsPack settings(boolean closeRedundant, int minReconnectTime) {
        SettingsPack sp = new SettingsPack();
        sp.listenInterfaces("127.0.0.1:0");
        sp.setEnableDht(false);
        sp.setEnableLsd(false);
        sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false);
        sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false);
        sp.setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), closeRedundant);
        // a dropped peer is not tried again before min_reconnect_time (60 s
        // by default) has passed since it was dropped
        sp.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), minReconnectTime);
        return sp;
    }

    private static TorrentInfo makeTorrent(File dir, File data) {
        list_files_listener files = new list_files_listener() {
            @Override
            public boolean pred(String p) {
                return true;
            }
        };
        create_file_entry_vector entries = list_files_ex(data.getAbsolutePath(), files, new create_flags_t());
        create_torrent ct = new create_torrent(entries, PIECE_SIZE);
        error_code ec = new error_code();
        set_piece_hashes_listener hashes = new set_piece_hashes_listener() {
            @Override
            public void progress(int i) {
            }
        };
        set_piece_hashes_ex(ct, dir.getAbsolutePath(), hashes, ec);
        assertEquals(0, ec.value());
        byte_vector buffer = ct.generate().bencode();
        return TorrentInfo.bdecode(Vectors.byte_vector2bytes(buffer));
    }

    private interface Condition {
        boolean met();
    }

    private static boolean waitFor(long ms, Condition c) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.met()) return true;
            Thread.sleep(50);
        }
        return c.met();
    }
}
