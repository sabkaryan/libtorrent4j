package org.libtorrent4j;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.DiskPieceVerifiedAlert;
import org.libtorrent4j.alerts.ResumeDataMismatchAlert;
import org.libtorrent4j.alerts.SegmentReleasedAlert;
import org.libtorrent4j.swig.add_torrent_params;
import org.libtorrent4j.swig.bitfield;
import org.libtorrent4j.swig.byte_vector;
import org.libtorrent4j.swig.create_file_entry_vector;
import org.libtorrent4j.swig.create_flags_t;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.disk_piece_verified_alert;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.list_files_listener;
import org.libtorrent4j.swig.segment_released_alert;
import org.libtorrent4j.swig.set_piece_hashes_listener;
import org.libtorrent4j.swig.torrent_handle;
import org.libtorrent4j.swig.torrent_status;

import java.io.File;
import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.libtorrent4j.swig.libtorrent.list_files_ex;
import static org.libtorrent4j.swig.libtorrent.set_piece_hashes_ex;

/**
 * The segment files API through the binding: add_torrent_params.segment_size,
 * segment_suffix and unverified_pieces, torrent_handle.verify_disk_piece()
 * and release_segment(), and their three alerts.
 */
public class SegmentFilesTest {

    private static final int PIECE = 64 * 1024;
    private static final int PIECES = 4;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final BlockingQueue<Alert<?>> alerts = new LinkedBlockingQueue<>();

    @Test
    public void testSegmentFiles() throws Exception {
        File src = folder.newFolder();
        File data = new File(src, "data.bin");
        byte[] content = new byte[PIECES * PIECE];
        new Random(1).nextBytes(content);
        Utils.writeByteArrayToFile(data, content, false);
        TorrentInfo ti = makeTorrent(src, data);

        SessionManager s = new SessionManager();
        s.addListener(new AlertListener() {
            @Override
            public int[] types() {
                return new int[]{AlertType.DISK_PIECE_VERIFIED.swig(),
                        AlertType.RESUME_DATA_MISMATCH.swig(), AlertType.SEGMENT_RELEASED.swig()};
            }

            @Override
            public void alert(Alert<?> alert) {
                alerts.add(alert);
            }
        });
        s.start();
        try {
            File dir = folder.newFolder();
            torrent_handle h = add(s, ti, dir, null);
            TorrentHandle th = new TorrentHandle(h);
            waitFor(() -> th.status().state() != TorrentStatus.State.CHECKING_RESUME_DATA);
            // piece 0 is left out, so the torrent keeps its picker
            for (int p = 1; p < PIECES; p++) {
                byte[] piece = new byte[PIECE];
                System.arraycopy(content, p * PIECE, piece, 0, PIECE);
                th.addPiece(p, piece);
            }
            assertTrue("three pieces are had", waitFor(() -> th.status().numPieces() == 3));
            th.flushCache();
            File cell1 = new File(dir, "f0-s1.seg");
            assertTrue("the pieces are written to segment files",
                    waitFor(() -> cell1.length() == 2L * PIECE));
            assertTrue(new File(dir, "f0-s0.seg").exists());
            assertFalse("no file of the torrent's own name", new File(dir, "data.bin").exists());

            h.verify_disk_piece(2, BigInteger.valueOf(7), BigInteger.valueOf(3));
            DiskPieceVerifiedAlert v = next(DiskPieceVerifiedAlert.class);
            assertEquals(2, v.pieceIndex());
            assertEquals(7, v.requestId());
            assertEquals(3, v.epoch());
            assertEquals(disk_piece_verified_alert.result_t.verified, v.result());

            h.release_segment(0, 1, BigInteger.valueOf(11));
            SegmentReleasedAlert busy = next(SegmentReleasedAlert.class);
            assertEquals(11, busy.token());
            assertEquals(segment_released_alert.result_t.busy, busy.result());

            assertEquals(0, h.forget_piece(2));
            assertEquals(0, h.forget_piece(3));
            h.release_segment(0, 1, BigInteger.valueOf(12));
            SegmentReleasedAlert removed = next(SegmentReleasedAlert.class);
            assertEquals(0, removed.fileIndex());
            assertEquals(1, removed.cell());
            assertEquals(segment_released_alert.result_t.removed, removed.result());
            assertFalse(cell1.exists());

            // resume data that lists pieces of the deleted segment
            s.remove(th);
            waitFor(() -> s.find(ti.infoHash()) == null);
            bitfield have = new bitfield(PIECES, true);
            have.clear_bit(0);
            torrent_handle again = add(s, ti, dir, have);
            ResumeDataMismatchAlert m = next(ResumeDataMismatchAlert.class);
            assertEquals(0, m.fileIndex());
            assertEquals(1, m.cell());
            TorrentStatus st = new TorrentHandle(again).status();
            assertTrue("the torrent is paused", st.flags().and_(TorrentFlags.PAUSED).non_zero());
            assertEquals(0, st.numPieces());
        } finally {
            s.stop();
        }
    }

    @Test
    public void testUnverifiedPiecesRoundTrip() {
        add_torrent_params p = new add_torrent_params();
        bitfield b = new bitfield(5);
        b.set_bit(1);
        b.set_bit(4);
        p.set_unverified_pieces(b);
        bitfield got = p.get_unverified_pieces();
        assertEquals(5, got.size());
        assertEquals(2, got.count());
        assertTrue(got.get_bit(1) && got.get_bit(4));
        p.setSegment_size(8L * 1024 * 1024 * 1024);
        assertEquals(8L * 1024 * 1024 * 1024, p.getSegment_size());
        assertEquals("", p.getSegment_suffix());
    }

    private static torrent_handle add(SessionManager s, TorrentInfo ti, File dir, bitfield have) {
        AddTorrentParams p = new AddTorrentParams();
        p.setTorrentInfo(ti);
        p.setSavePath(dir.getAbsolutePath());
        p.setFlags(p.getFlags().and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.PAUSED.inv()));
        p.swig().setSegment_size(2L * PIECE);
        p.swig().setSegment_suffix(".seg");
        if (have != null) p.swig().set_have_pieces(have);
        error_code ec = new error_code();
        torrent_handle h = s.swig().add_torrent(p.swig(), ec);
        assertEquals(ec.message(), 0, ec.value());
        return h;
    }

    private <T> T next(Class<T> type) throws InterruptedException {
        long end = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < end) {
            Alert<?> a = alerts.poll(100, TimeUnit.MILLISECONDS);
            if (type.isInstance(a)) return type.cast(a);
        }
        throw new AssertionError("no " + type.getSimpleName());
    }

    private static TorrentInfo makeTorrent(File dir, File data) {
        list_files_listener files = new list_files_listener() {
            @Override
            public boolean pred(String p) {
                return true;
            }
        };
        create_file_entry_vector entries = list_files_ex(data.getAbsolutePath(), files, new create_flags_t());
        create_torrent ct = new create_torrent(entries, PIECE);
        error_code ec = new error_code();
        set_piece_hashes_ex(ct, dir.getAbsolutePath(), new set_piece_hashes_listener() {
            @Override
            public void progress(int i) {
            }
        }, ec);
        assertEquals(0, ec.value());
        byte_vector buffer = ct.generate().bencode();
        return TorrentInfo.bdecode(Vectors.byte_vector2bytes(buffer));
    }

    private interface Cond {
        boolean ok() throws Exception;
    }

    private static boolean waitFor(Cond c) throws Exception {
        long end = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return true;
            Thread.sleep(50);
        }
        return c.ok();
    }
}
