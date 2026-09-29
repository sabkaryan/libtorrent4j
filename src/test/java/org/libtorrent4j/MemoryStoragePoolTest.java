package org.libtorrent4j;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.SaveResumeDataAlert;
import org.libtorrent4j.swig.add_torrent_params;
import org.libtorrent4j.swig.byte_vector;
import org.libtorrent4j.swig.create_file_entry_vector;
import org.libtorrent4j.swig.create_flags_t;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.list_files_listener;
import org.libtorrent4j.swig.set_piece_hashes_listener;

import java.io.File;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.libtorrent4j.swig.libtorrent.list_files_ex;
import static org.libtorrent4j.swig.libtorrent.read_resume_data_ex;
import static org.libtorrent4j.swig.libtorrent.set_piece_hashes_ex;

/**
 * MemoryStoragePool: the in-memory disk back end through the JNI entry points
 * of swig/ext_jni.cpp. A session made with SessionParams.setMemoryDiskIo()
 * keeps the pieces in the pool, which reads, forgets and filters them.
 * <p>
 * This is also the guard that the native library has the pool: a native
 * library built without its entry points throws UnsatisfiedLinkError on the
 * first pool call (the constructor), so every test here fails loudly.
 */
public class MemoryStoragePoolTest {

    private static final int PIECE = 64 * 1024;
    private static final int LAST = 10000;
    private static final int NUM_PIECES = 5;
    // the short last piece
    private static final int LAST_PIECE = NUM_PIECES - 1;
    private static final long TIMEOUT_MS = 20000;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testPiecesInMemoryAreReadForgottenAndFilteredFromResumeData() throws Exception {
        byte[] content = new byte[(NUM_PIECES - 1) * PIECE + LAST];
        new Random(7).nextBytes(content);
        TorrentInfo ti = makeTorrent("source", content);
        assertEquals(NUM_PIECES, ti.numPieces());

        MemoryStoragePool pool = new MemoryStoragePool();
        pool.setDefaultPolicy(MemoryPolicy.MEMORY);
        pool.setLimit(64L * 1024 * 1024);

        BlockingQueue<AddTorrentParams> resume = new LinkedBlockingQueue<>();
        SessionManager s = startSession(pool, resume);
        try {
            s.download(ti, folder.newFolder("download"));
            TorrentHandle th = waitForDownloading(s, ti);

            // piece 2 starts in the file, the others in memory; piece 3 is
            // left out so that the torrent keeps its piece picker
            pool.persist(th, 2);
            for (int p = 0; p < NUM_PIECES; p++) {
                if (p == 3) continue;
                th.addPiece(p, Arrays.copyOfRange(content, p * PIECE, Math.min(content.length, (p + 1) * PIECE)));
            }

            long end = System.currentTimeMillis() + TIMEOUT_MS;
            MemoryPieces mem = pool.inMemory(th);
            while (System.currentTimeMillis() < end && mem.complete().count() < 3) {
                Thread.sleep(50);
                mem = pool.inMemory(th);
            }
            assertEquals("pieces 0, 1 and 4 complete in memory", 3, mem.complete().count());
            assertTrue(mem.complete().getBit(0));
            assertTrue(mem.complete().getBit(1));
            assertFalse("piece 2 goes to the file", mem.complete().getBit(2));
            assertFalse("piece 3 not added", mem.complete().getBit(3));
            assertTrue(mem.complete().getBit(LAST_PIECE));
            assertEquals(0, mem.partial().count());
            assertTrue(pool.heldBytes(th).complete() > 0);
            assertEquals(0, pool.heldBytes(th).partial());
            assertTrue(pool.heldBytes() >= pool.heldBytes(th).complete());
            assertEquals(0, pool.persistFailures(th));

            // whole piece
            byte[] dst = new byte[PIECE];
            assertEquals(PIECE, pool.read(th, 0, 0, dst, 0, PIECE));
            assertArrayEquals(Arrays.copyOfRange(content, 0, PIECE), dst);

            // into the middle of the array, from the middle of the piece
            dst = new byte[3000];
            Arrays.fill(dst, (byte) 0x5a);
            assertEquals(2000, pool.read(th, 1, 100, dst, 7, 2000));
            for (int i = 0; i < 7; i++) {
                assertEquals("dst[" + i + "] before dstOff untouched", (byte) 0x5a, dst[i]);
            }
            assertArrayEquals(Arrays.copyOfRange(content, PIECE + 100, PIECE + 2100),
                    Arrays.copyOfRange(dst, 7, 2007));
            for (int i = 2007; i < dst.length; i++) {
                assertEquals("dst[" + i + "] after dstOff + len untouched", (byte) 0x5a, dst[i]);
            }

            // the short last piece: at most up to its end
            dst = new byte[PIECE];
            assertEquals(LAST, pool.read(th, LAST_PIECE, 0, dst, 0, PIECE));
            assertArrayEquals(Arrays.copyOfRange(content, LAST_PIECE * PIECE, content.length),
                    Arrays.copyOfRange(dst, 0, LAST));
            dst = new byte[100];
            assertEquals(10, pool.read(th, LAST_PIECE, LAST - 10, dst, 5, 50));
            assertArrayEquals(Arrays.copyOfRange(content, content.length - 10, content.length),
                    Arrays.copyOfRange(dst, 5, 15));

            // the bounds of dst are checked before the pool is asked
            int[][] bad = {{-1, 1}, {0, -1}, {0, 101}, {95, 6}, {101, 0}};
            for (int[] b : bad) {
                try {
                    pool.read(th, 0, 0, dst, b[0], b[1]);
                    fail("dstOff " + b[0] + " len " + b[1]);
                } catch (IndexOutOfBoundsException expected) {
                    // expected
                }
            }
            assertEquals(0, pool.read(th, 0, 0, dst, 100, 0));

            // a piece in the file is not read from the pool
            assertEquals(MemoryStoragePool.NOT_IN_MEMORY, pool.read(th, 2, 0, dst, 0, 10));

            // resume data: pieces held only in memory are removed, the one in
            // the file stays (once it is written there)
            end = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < end
                    && !th.status(TorrentHandle.QUERY_FLUSHED_PIECES).flushedPieces().getBit(2)) {
                Thread.sleep(50);
            }
            assertTrue("piece 2 written to the file",
                    th.status(TorrentHandle.QUERY_FLUSHED_PIECES).flushedPieces().getBit(2));
            AddTorrentParams byHandle = saveResumeData(s, th, resume);
            AddTorrentParams byInfoHash = saveResumeData(s, th, resume);
            assertTrue("resume data names piece 0", byHandle.swig().get_have_pieces().get_bit(0));
            assertTrue("resume data names piece 2", byHandle.swig().get_have_pieces().get_bit(2));
            pool.filterResume(th, byHandle);
            assertFalse("piece 0 was only in memory", byHandle.swig().get_have_pieces().get_bit(0));
            assertFalse("the last piece was only in memory", byHandle.swig().get_have_pieces().get_bit(LAST_PIECE));
            assertTrue("piece 2 is in the file", byHandle.swig().get_have_pieces().get_bit(2));

            // forget a piece in memory: its bytes are dropped
            ForgetResult forgotten = forgetPiece(pool, th, 1);
            assertEquals(forgotten.toString(), 0, forgotten.code());
            assertEquals(PiecePlace.MEMORY, forgotten.place());
            assertEquals(MemoryStoragePool.NOT_IN_MEMORY, pool.read(th, 1, 0, dst, 0, 10));
            assertFalse(pool.inMemory(th).complete().getBit(1));
            assertEquals(0, pool.pendingPersistBytes(th));

            // a removed torrent is not managed; its resume data is still filtered
            s.remove(th);
            end = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < end
                    && pool.read(th, 0, 0, dst, 0, 10) != MemoryStoragePool.NOT_MANAGED) {
                Thread.sleep(50);
            }
            assertEquals(MemoryStoragePool.NOT_MANAGED, pool.read(th, 0, 0, dst, 0, 10));
            assertEquals(MemoryStoragePool.NOT_MANAGED, pool.pendingPersistBytes(th));
            assertEquals(MemoryStoragePool.NOT_MANAGED, pool.persistFailures(th));
            assertEquals(MemoryStoragePool.NOT_MANAGED, pool.heldBytes(th).complete());
            ForgetResult notManaged = pool.forgetPiece(th, 0);
            assertEquals(MemoryStoragePool.NOT_MANAGED, notManaged.code());
            assertEquals(PiecePlace.NONE, notManaged.place());
            assertEquals(0, pool.inMemory(th).complete().size());
            pool.filterResume(byInfoHash);
            assertFalse("piece 0 was only in memory", byInfoHash.swig().get_have_pieces().get_bit(0));
            assertTrue("piece 2 is in the file", byInfoHash.swig().get_have_pieces().get_bit(2));

            // the session keeps its own reference: closing the pool is safe
            pool.close();
            try {
                pool.read(th, 0, 0, dst, 0, 10);
                fail("read after close");
            } catch (IllegalStateException expected) {
                // expected
            }
        } finally {
            pool.close();
            s.stop();
        }
    }

    // every other call once: a registration by info-hash, claims by handle,
    // the owner's persist set, dropping an owner, forgetting a record and the
    // counters
    @Test
    public void testRegistrationClaimsRecordsAndCounters() throws Exception {
        final byte[] a = new byte[4 * PIECE];
        new Random(11).nextBytes(a);
        final byte[] b = new byte[4 * PIECE];
        new Random(12).nextBytes(b);
        TorrentInfo tiA = makeTorrent("a", a);
        TorrentInfo tiB = makeTorrent("b", b);

        final MemoryStoragePool pool = new MemoryStoragePool();
        pool.setDefaultPolicy(MemoryPolicy.MEMORY);
        pool.setLimit(64L * 1024 * 1024);
        // registered before it is added: b goes to the file
        pool.setPolicy(tiB.infoHashes(), MemoryPolicy.FILE);

        BlockingQueue<AddTorrentParams> resume = new LinkedBlockingQueue<>();
        SessionManager s = startSession(pool, resume);
        try {
            s.download(tiA, folder.newFolder("download-a"));
            s.download(tiB, folder.newFolder("download-b"));
            final TorrentHandle thA = waitForDownloading(s, tiA);
            final TorrentHandle thB = waitForDownloading(s, tiB);

            // piece 3 is left out of both for now
            for (int p = 0; p < 3; p++) {
                thA.addPiece(p, piece(a, p));
                thB.addPiece(p, piece(b, p));
            }
            waitFor("pieces 0-2 of a in memory", () -> inMemory(pool, thA, 0, 1, 2));
            waitFor("pieces 0-2 of b written to the file", () -> flushed(thB, 0, 1, 2));
            assertEquals("b is registered for the file", 0, pool.inMemory(thB).complete().count());
            assertEquals(0, pool.inMemory(thB).partial().count());
            assertEquals(0, pool.heldBytes(thB).complete());
            assertEquals(0, pool.heldBytes(thB).partial());
            assertEquals(3L * PIECE, pool.heldBytes(thA).complete());

            // the persist set of owner 5 moves piece 0 to the file
            pool.setPersist(thA, 5, 0);
            waitFor("piece 0 moved to the file", () -> pool.pendingPersistBytes(thA) == 0
                    && !inMemory(pool, thA, 0) && flushed(thA, 0));
            assertTrue(inMemory(pool, thA, 1, 2));

            // owner 1 claims file 0 (every piece) for the file: pieces 1 and 2 move
            pool.setPolicy(thA, 1, MemoryPolicy.FILE, 0);
            waitFor("pieces 1 and 2 moved to the file", () -> pool.pendingPersistBytes(thA) == 0
                    && pool.inMemory(thA).complete().count() == 0 && flushed(thA, 1, 2));
            assertEquals(0, pool.heldBytes(thA).complete());

            // without the claims of owners 1 and 5 the next piece starts in
            // memory again (the default policy); the bytes already in the
            // file stay there
            pool.dropOwner(thA, 1);
            pool.dropOwner(thA, 5);
            thA.addPiece(3, piece(a, 3));
            waitFor("piece 3 in memory", () -> inMemory(pool, thA, 3));
            assertEquals(1, pool.inMemory(thA).complete().count());
            assertEquals((long) PIECE, pool.heldBytes(thA).complete());
            assertEquals(0, pool.heldBytes(thA).partial());

            // the counters
            assertTrue(pool.heldBytes() >= PIECE);
            assertTrue(pool.retiredBytes() >= 0);
            assertEquals("nothing spilled below the limit", 0, pool.spilledPieces());
            assertEquals(0, pool.hashMissingBlocks());
            assertEquals(0, pool.persistFailures(thA));
            assertEquals(0, pool.persistFailures(thB));
            assertEquals(0, pool.pendingPersistBytes(thB));

            // a removed torrent's record filters its resume data until it is
            // forgotten; then the resume data is left unchanged
            AddTorrentParams filtered = saveResumeData(s, thA, resume);
            AddTorrentParams unchanged = saveResumeData(s, thA, resume);
            for (int p = 0; p < 4; p++) {
                assertTrue("resume data names piece " + p, unchanged.swig().get_have_pieces().get_bit(p));
            }
            s.remove(thA);
            waitFor("a removed", () -> pool.pendingPersistBytes(thA) == MemoryStoragePool.NOT_MANAGED);
            pool.filterResume(filtered);
            assertFalse("piece 3 was only in memory", filtered.swig().get_have_pieces().get_bit(3));
            for (int p = 0; p < 3; p++) {
                assertTrue("piece " + p + " is in the file", filtered.swig().get_have_pieces().get_bit(p));
            }
            pool.forgetRecord(tiA.infoHashes());
            pool.filterResume(unchanged);
            for (int p = 0; p < 4; p++) {
                assertTrue("after forgetRecord piece " + p + " stays",
                        unchanged.swig().get_have_pieces().get_bit(p));
            }
        } finally {
            pool.close();
            s.stop();
        }
    }

    @Test
    public void testCallAfterCloseThrows() {
        MemoryStoragePool pool = new MemoryStoragePool(1024 * 1024);
        assertEquals(0, pool.heldBytes());
        assertFalse(pool.isClosed());
        pool.close();
        assertTrue(pool.isClosed());
        pool.close();
        try {
            pool.heldBytes();
            fail("heldBytes after close");
        } catch (IllegalStateException expected) {
            // expected
        }
        try {
            pool.setLimit(1);
            fail("setLimit after close");
        } catch (IllegalStateException expected) {
            // expected
        }
        try {
            pool.setDefaultPolicy(MemoryPolicy.MEMORY);
            fail("setDefaultPolicy after close");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSlabBytesMustBeAMultipleOfTheBlockSize() {
        new MemoryStoragePool(1000);
    }

    private static ForgetResult forgetPiece(MemoryStoragePool pool, TorrentHandle th, int piece)
            throws InterruptedException {
        // 3: not all blocks written yet, try again
        long end = System.currentTimeMillis() + TIMEOUT_MS;
        ForgetResult r = pool.forgetPiece(th, piece);
        while (r.code() == 3 && System.currentTimeMillis() < end) {
            Thread.sleep(50);
            r = pool.forgetPiece(th, piece);
        }
        return r;
    }

    private static AddTorrentParams saveResumeData(SessionManager s, TorrentHandle th,
                                                   BlockingQueue<AddTorrentParams> resume)
            throws InterruptedException {
        th.saveResumeData();
        AddTorrentParams p = resume.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull("save resume data (listener error: " + s.lastAlertError() + ")", p);
        return p;
    }

    private static TorrentHandle waitForDownloading(SessionManager s, TorrentInfo ti) throws InterruptedException {
        long end = System.currentTimeMillis() + TIMEOUT_MS;
        TorrentHandle th = null;
        while (System.currentTimeMillis() < end) {
            th = s.find(ti.infoHash());
            if (th != null && th.isValid() && th.status().state() == TorrentStatus.State.DOWNLOADING) break;
            Thread.sleep(50);
        }
        assertNotNull(th);
        assertEquals(TorrentStatus.State.DOWNLOADING, th.status().state());
        return th;
    }

    // a session on the pool whose save resume data alerts go to `resume`
    private static SessionManager startSession(MemoryStoragePool pool, final BlockingQueue<AddTorrentParams> resume) {
        SessionManager s = new SessionManager();
        s.addListener(new AlertListener() {
            @Override
            public int[] types() {
                return new int[]{AlertType.SAVE_RESUME_DATA.swig()};
            }

            @Override
            public void alert(Alert<?> alert) {
                // a copy (through the bencoded form a client writes): the
                // alert's params live only as long as the alert
                byte[] buf = AddTorrentParams.writeResumeDataBuf(((SaveResumeDataAlert) alert).params());
                error_code ec = new error_code();
                add_torrent_params copy = read_resume_data_ex(Vectors.bytes2byte_vector(buf), ec);
                assertEquals(ec.message(), 0, ec.value());
                resume.add(new AddTorrentParams(copy));
            }
        });
        SettingsPack sp = new SettingsPack();
        sp.setEnableDht(false);
        sp.setEnableLsd(false);
        SessionParams params = new SessionParams(sp);
        params.setMemoryDiskIo(pool);
        s.start(params);
        return s;
    }

    private static byte[] piece(byte[] content, int p) {
        return Arrays.copyOfRange(content, p * PIECE, Math.min(content.length, (p + 1) * PIECE));
    }

    private static boolean flushed(TorrentHandle th, int... pieces) {
        PieceIndexBitfield f = th.status(TorrentHandle.QUERY_FLUSHED_PIECES).flushedPieces();
        for (int p : pieces) {
            if (f.size() <= p || !f.getBit(p)) return false;
        }
        return true;
    }

    private static boolean inMemory(MemoryStoragePool pool, TorrentHandle th, int... pieces) {
        PieceIndexBitfield c = pool.inMemory(th).complete();
        for (int p : pieces) {
            if (c.size() <= p || !c.getBit(p)) return false;
        }
        return true;
    }

    private static void waitFor(String what, Condition c) throws InterruptedException {
        long end = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < end && !c.met()) {
            Thread.sleep(50);
        }
        assertTrue(what, c.met());
    }

    private interface Condition {
        boolean met();
    }

    private TorrentInfo makeTorrent(String name, byte[] content) throws Exception {
        File dir = folder.newFolder(name);
        File data = new File(dir, "data.bin");
        Utils.writeByteArrayToFile(data, content, false);

        list_files_listener files = new list_files_listener() {
            @Override
            public boolean pred(String p) {
                return true;
            }
        };
        create_file_entry_vector entries = list_files_ex(data.getAbsolutePath(), files, new create_flags_t());
        create_torrent ct = new create_torrent(entries, PIECE);
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
}
