package org.libtorrent4j;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.SessionStatsAlert;
import org.libtorrent4j.swig.byte_vector;
import org.libtorrent4j.swig.counters;
import org.libtorrent4j.swig.create_file_entry_vector;
import org.libtorrent4j.swig.create_flags_t;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.list_files_listener;
import org.libtorrent4j.swig.set_piece_hashes_listener;

import java.io.File;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.libtorrent4j.swig.libtorrent.list_files_ex;
import static org.libtorrent4j.swig.libtorrent.set_piece_hashes_ex;

/**
 * SessionStatsAlert.value() read in the alert listener reports the session's
 * counters: peers connected and payload received while a download runs. The
 * values live in the session's alert memory until the next alerts are popped,
 * so they are read in the listener, not kept.
 */
public class SessionStatsAlertTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static TorrentInfo makeTorrent(File dir, int size) throws Exception {
        File data = new File(dir, "data.bin");
        byte[] content = new byte[size];
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
        return TorrentInfo.bdecode(Vectors.byte_vector2bytes(buffer));
    }

    private static SessionParams params(int downloadRateLimit) {
        SettingsPack sp = new SettingsPack();
        sp.listenInterfaces("127.0.0.1:0");
        sp.setEnableDht(false);
        if (downloadRateLimit > 0) sp.downloadRateLimit(downloadRateLimit);
        return new SessionParams(sp);
    }

    @Test
    public void testValuesReadInTheListener() throws Exception {
        File seedDir = folder.newFolder();
        File dlDir = folder.newFolder();
        TorrentInfo ti = makeTorrent(seedDir, 2 * 1024 * 1024);

        final int connectedIdx = LibTorrent.findMetricIdx("peer.num_peers_connected");
        final int recvIdx = LibTorrent.findMetricIdx("net.recv_payload_bytes");
        assertTrue(connectedIdx >= 0);
        assertTrue(recvIdx >= 0);

        final AtomicLong maxConnected = new AtomicLong();
        final AtomicLong maxRecv = new AtomicLong();

        SessionManager seed = new SessionManager();
        SessionManager dl = new SessionManager();
        seed.start(params(0));
        // slow enough for the connection to last several stats intervals
        dl.start(params(200 * 1024));
        try {
            dl.addListener(new AlertListener() {
                @Override
                public int[] types() {
                    return new int[]{AlertType.SESSION_STATS.swig()};
                }

                @Override
                public void alert(Alert<?> alert) {
                    SessionStatsAlert a = (SessionStatsAlert) alert;
                    long connected = a.value(connectedIdx);
                    long recv = a.value(recvIdx);
                    maxConnected.set(Math.max(maxConnected.get(), connected));
                    maxRecv.set(Math.max(maxRecv.get(), recv));
                }
            });

            seed.download(ti, seedDir);
            dl.download(ti, dlDir);
            TorrentHandle sh = null;
            TorrentHandle th = null;
            long end = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < end) {
                sh = seed.find(ti.infoHash());
                th = dl.find(ti.infoHash());
                if (sh != null && th != null && sh.status().isSeeding()) break;
                Thread.sleep(50);
            }
            assertNotNull(th);
            // the torrent's own limit applies to local peers too: the download
            // lasts several stats intervals (2 MiB at 256 KiB/s)
            th.setDownloadLimit(256 * 1024);

            int maxPeerInfo = 0;
            long nextConnect = 0;
            end = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < end && maxConnected.get() < 1) {
                int peers = th.peerInfo().size();
                maxPeerInfo = Math.max(maxPeerInfo, peers);
                int port = seed.swig().listen_port();
                // until the seed listens and the torrent takes the connection
                if (peers == 0 && port > 0 && System.currentTimeMillis() >= nextConnect) {
                    th.swig().connect_peer(new TcpEndpoint("127.0.0.1", port).swig());
                    nextConnect = System.currentTimeMillis() + 1000;
                }
                Thread.sleep(100);
            }
            System.out.println("session stats read in the listener: max peers connected " + maxConnected.get()
                    + " (get_peer_info saw up to " + maxPeerInfo + ")");
            assertTrue("peers connected, read in the listener", maxConnected.get() >= 1);

            end = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < end && maxRecv.get() < 2 * 1024 * 1024) {
                Thread.sleep(100);
            }
            System.out.println("session stats read in the listener: max payload received " + maxRecv.get());
            assertEquals("payload received, read in the listener", 2 * 1024 * 1024, maxRecv.get());

            // the generated gauge enum has the native indices
            assertEquals(connectedIdx, counters.stats_gauge_t.num_peers_connected.swigValue());
            assertEquals(LibTorrent.findMetricIdx("disk.queued_write_bytes"),
                    counters.stats_gauge_t.queued_write_bytes.swigValue());
            assertEquals(LibTorrent.findMetricIdx("disk.cached_blocks"),
                    counters.stats_gauge_t.cached_blocks.swigValue());

        } finally {
            dl.stop();
            seed.stop();
        }
    }
}
