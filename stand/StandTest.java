import org.libtorrent4j.swig.*;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Test rig for {@code torrent_handle::forget_piece()} (exposed as
 * {@link libtorrent_ext#forgetPiece}). Runs on an Android device or emulator
 * through {@code app_process}, see run-android.sh.
 *
 * Only SWIG-level classes are used on purpose: the higher-level Java wrappers
 * release native memory from finalizers, which run in an undefined order at
 * shutdown and make a short-lived process flaky.
 *
 * Fixture: a two-file torrent, 8 pieces of 256 KiB each. File A is present on
 * disk, file B is absent and has priority 0. After checking, the torrent is
 * {@code finished} but not seeding, so the piece picker is alive. (If both
 * files were present, all 16 pieces would be found, the torrent would become
 * a seed and release its picker; forget_piece would then return 2.)
 *
 * Modes (args[0]), args[1] is a scratch directory:
 * <ul>
 * <li>{@code control}    — never calls forget_piece; nothing may change. On a
 *                          library without the patch the {@code forget} mode
 *                          must fail with UnsatisfiedLinkError.</li>
 * <li>{@code forget}     — forget_piece(2) == 0; have(2) false; num_pieces −1;
 *                          total_wanted_done −piece; finished → downloading;
 *                          ses.num_have_pieces unchanged (a monotonic counter);
 *                          then the return codes: again → 1, bad index → 4,
 *                          the handle of a removed torrent → 5.</li>
 * <li>{@code redownload} — a second, seeding session in the same process;
 *                          after forget_piece(2) the piece is fetched again,
 *                          exactly one piece of payload, and the file is
 *                          byte-equal to the reference.</li>
 * <li>{@code resume}     — after forget_piece(2) the saved resume data has bit 2
 *                          clear, and a torrent re-added from it does not have
 *                          piece 2 without any re-check. (need_save_resume_data
 *                          is already true right after checking, so this mode
 *                          cannot tell whether forget_piece set the flag.)</li>
 * <li>{@code bench [pieces] [kib] [posix|default]} — timing of libtorrent's own
 *                          work, to compare a release and a checked build: hash
 *                          check to seeding, first piece and full download from
 *                          a local seed. Defaults to 2000 pieces of 64 KiB:
 *                          the checked build's invariant checks cost O(pieces)
 *                          per picker operation, so a handful of pieces would
 *                          show no difference whatever the real cost.</li>
 * <li>{@code offthread}  — positive control for a checked build: calls the
 *                          torrent method on the wrong thread; the process
 *                          must abort on is_single_thread().</li>
 * <li>{@code breakpick}  — positive control for a checked build: drops a piece
 *                          from the picker without bookkeeping; the next call
 *                          must abort on torrent::check_invariant().</li>
 * </ul>
 * The two positive controls exist so that a checked build is first shown to
 * be able to fail; only then does its silence in the other modes mean anything.
 */
public class StandTest {
    static final int PIECE = 256 * 1024;
    static final int PIECES_PER_FILE = 8;
    static int againRc = -1, badIndexRc = -1;

    public static void main(String[] a) throws Exception {
        String mode = a[0];
        File dir = new File(a[1]);
        dir.mkdirs();
        if (mode.equals("bench")) {
            bench(dir, a.length > 2 ? Integer.parseInt(a[2]) : 2000,
                a.length > 3 ? Integer.parseInt(a[3]) : 64,
                a.length > 4 ? a[4] : "posix");
            return;
        }
        File data = new File(dir, "stand");
        data.mkdirs();
        byte[] fa = pattern(PIECE * PIECES_PER_FILE, 0x11);
        byte[] fb = pattern(PIECE * PIECES_PER_FILE, 0x22);
        write(new File(data, "a.bin"), fa);
        // b.bin is deliberately not written, see the class comment
        File torrent = new File(dir, "stand.torrent");
        write(torrent, makeTorrent(fa, fb));

        session ses = newSession();

        add_torrent_params p = libtorrent.load_torrent_file(torrent.getAbsolutePath());
        p.setSave_path(dir.getAbsolutePath()); // the torrent name adds the "stand" directory
        byte_vector prio = new byte_vector();
        prio.add(Byte.valueOf((byte) 4));
        prio.add(Byte.valueOf((byte) 0));
        p.set_file_priorities(prio);
        error_code ec = new error_code();
        torrent_handle h = ses.add_torrent(p, ec);
        if (ec.value() != 0) throw new RuntimeException("add_torrent: " + ec.message());

        torrent_status st = waitState(h, torrent_status.state_t.finished, 20000);
        System.out.println("before: state=" + st.getState() + " finished=" + st.getIs_finished()
            + " num_pieces=" + st.getNum_pieces() + " total_wanted_done=" + st.getTotal_wanted_done()
            + " have(2)=" + h.have_piece(2));
        long haveBefore = statsCounter(ses, "ses.num_have_pieces");
        System.out.println("before: ses.num_have_pieces=" + haveBefore);
        if (st.getNum_pieces() != PIECES_PER_FILE) throw new RuntimeException("fixture: expected 8 pieces, got " + st.getNum_pieces());

        int rc;
        switch (mode) {
            case "forget":
                rc = libtorrent_ext.forgetPiece(h, 2);
                System.out.println("forgetPiece(2) rc=" + rc);
                break;
            case "control":
                rc = -1;
                System.out.println("control: forgetPiece NOT called");
                break;
            case "offthread":
                System.out.println("offthread: calling forgetPieceOffThreadForTest(2) - a checked build must abort here");
                rc = libtorrent_ext.forgetPieceOffThreadForTest(h, 2);
                System.out.println("offthread: SURVIVED rc=" + rc + " (expected only on a release build)");
                break;
            case "breakpick":
                rc = libtorrent_ext.breakPickerForTest(h, 2);
                System.out.println("breakPickerForTest(2) rc=" + rc + " - the next call must trip the invariant on a checked build");
                rc = libtorrent_ext.forgetPiece(h, 3);
                System.out.println("breakpick: SURVIVED forgetPiece(3) rc=" + rc + " (expected only on a release build)");
                break;
            case "redownload":
                redownload(ses, h, dir, data, torrent, fa, fb);
                return; // exits inside
            case "resume":
                resume(ses, h, dir, torrent);
                return; // exits inside
            default:
                throw new IllegalArgumentException(mode);
        }

        Thread.sleep(500);
        torrent_status st2 = h.status();
        long haveAfter = statsCounter(ses, "ses.num_have_pieces");
        System.out.println("after: state=" + st2.getState() + " finished=" + st2.getIs_finished()
            + " num_pieces=" + st2.getNum_pieces() + " total_wanted_done=" + st2.getTotal_wanted_done()
            + " have(2)=" + h.have_piece(2) + " ses.num_have_pieces=" + haveAfter);

        if (mode.equals("forget")) {
            int rc2 = libtorrent_ext.forgetPiece(h, 2);
            System.out.println("forgetPiece(2) again rc=" + rc2 + " (expect 1)");
            int rc3 = libtorrent_ext.forgetPiece(h, 999);
            System.out.println("forgetPiece(999) rc=" + rc3 + " (expect 4)");
            againRc = rc2;
            badIndexRc = rc3;
        }

        boolean ok = true;
        if (mode.equals("forget")) {
            ok &= check("rc==0", rc == 0);
            ok &= check("have(2)==false", !h.have_piece(2));
            ok &= check("num_pieces-1", st2.getNum_pieces() == st.getNum_pieces() - 1);
            ok &= check("total_wanted_done-piece", st2.getTotal_wanted_done() == st.getTotal_wanted_done() - PIECE);
            ok &= check("state downloading", st2.getState().swigValue() == torrent_status.state_t.downloading.swigValue());
            // ses.num_have_pieces counts pieces ever completed; it is not a gauge and
            // forget_piece leaves it alone (a checked build asserts on a decrement)
            ok &= check("ses.num_have_pieces unchanged (monotonic)", haveAfter == haveBefore);
            ok &= check("again rc==1", againRc == 1);
            ok &= check("bad index rc==4", badIndexRc == 4);
        } else if (mode.equals("control")) {
            ok &= check("have(2)==true", h.have_piece(2));
            ok &= check("num_pieces same", st2.getNum_pieces() == st.getNum_pieces());
            ok &= check("total_wanted_done same", st2.getTotal_wanted_done() == st.getTotal_wanted_done());
            ok &= check("state finished", st2.getState().swigValue() == torrent_status.state_t.finished.swigValue());
        }
        ses.remove_torrent(h);
        Thread.sleep(500);
        if (mode.equals("forget")) {
            // a handle whose torrent has been removed (torrent_handle has no public
            // default constructor in the Java binding, this is the reachable invalid one)
            int rc5 = libtorrent_ext.forgetPiece(h, 0);
            System.out.println("forgetPiece(removed torrent's handle, 0) rc=" + rc5 + " (expect 5)");
            ok &= check("removed torrent's handle rc==5", rc5 == 5);
        }
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");

        ses.abort();
        ses.delete();
        System.out.println("done");
        System.exit(ok ? 0 : 1);
    }

    static void redownload(session ses, torrent_handle h, File dir, File data, File torrent
        , byte[] fa, byte[] fb) throws Exception {
        File seedDir = new File(dir, "seed");
        File seedData = new File(seedDir, "stand");
        seedData.mkdirs();
        write(new File(seedData, "a.bin"), fa);
        write(new File(seedData, "b.bin"), fb);
        session seed = newSession();
        add_torrent_params ps = libtorrent.load_torrent_file(torrent.getAbsolutePath());
        ps.setSave_path(seedDir.getAbsolutePath());
        error_code ec2 = new error_code();
        torrent_handle hs = seed.add_torrent(ps, ec2);
        waitState(hs, torrent_status.state_t.seeding, 20000);
        int port = seed.listen_port();
        System.out.println("seed: seeding on 127.0.0.1:" + port);

        // corrupt piece 2 on disk so the re-download is visible in the bytes too
        try (RandomAccessFile f = new RandomAccessFile(new File(data, "a.bin"), "rw")) {
            f.seek(2L * PIECE);
            f.write(new byte[PIECE]);
        }
        int rc = libtorrent_ext.forgetPiece(h, 2);
        System.out.println("forgetPiece(2) rc=" + rc);

        // connect AFTER forgetting. While the torrent is finished, a seed is a
        // redundant connection that close_redundant_connections drops at once,
        // and with no other peer source (no tracker, DHT or LSD here) the
        // torrent would not reconnect before min_reconnect_time (60 s)
        error_code ec3 = new error_code();
        h.connect_peer(new tcp_endpoint(address.from_string("127.0.0.1", ec3), port));
        Thread.sleep(1500);
        torrent_status stc = h.status();
        System.out.println("connected: num_peers=" + stc.getNum_peers() + " num_seeds=" + stc.getNum_seeds()
            + " state=" + stc.getState());
        long end = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < end && !h.have_piece(2)) Thread.sleep(200);
        // have_piece() says the piece passed its hash check. With a write-back
        // disk cache its bytes reach the file a little later, so wait for
        // them (bounded) instead of reading the file too early
        long passedAt = System.currentTimeMillis();
        boolean same;
        while (true) {
            byte[] back = Files.readAllBytes(new File(data, "a.bin").toPath());
            same = Arrays.equals(back, fa);
            if (same || System.currentTimeMillis() > passedAt + 5000) break;
            Thread.sleep(20);
        }
        System.out.println("a.bin byte-equal to reference: " + same
            + " (" + (System.currentTimeMillis() - passedAt) + " ms after have(2))");
        torrent_status st3 = h.status();
        System.out.println("redownload: have(2)=" + h.have_piece(2) + " state=" + st3.getState()
            + " num_pieces=" + st3.getNum_pieces() + " payload_download=" + st3.getTotal_payload_download());
        boolean ok = check("rc==0", rc == 0)
            & check("have(2) again", h.have_piece(2))
            & check("state finished again", st3.getState().swigValue() == torrent_status.state_t.finished.swigValue())
            & check("payload_download == PIECE", st3.getTotal_payload_download() == PIECE)
            & check("a.bin byte-equal", same);
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        seed.remove_torrent(hs);
        ses.remove_torrent(h);
        Thread.sleep(300);
        seed.abort();
        ses.abort();
        seed.delete();
        ses.delete();
        System.exit(ok ? 0 : 1);
    }

    // resume: after forget_piece(2) the torrent asks to be saved, the saved
    // have_pieces bitfield has bit 2 clear, and a torrent re-added from that
    // resume data does not have piece 2 without any re-check.
    static void resume(session ses, torrent_handle h, File dir, File torrent) throws Exception {
        boolean needBefore = h.need_save_resume_data();
        int rc = libtorrent_ext.forgetPiece(h, 2);
        boolean needAfter = h.need_save_resume_data();
        System.out.println("forgetPiece(2) rc=" + rc + " need_save_resume_data before=" + needBefore + " after=" + needAfter);

        h.save_resume_data();
        add_torrent_params saved = null;
        long end = System.currentTimeMillis() + 10000;
        alert_ptr_vector v = new alert_ptr_vector();
        while (saved == null && System.currentTimeMillis() < end) {
            ses.wait_for_alert_ms(500);
            ses.pop_alerts(v);
            for (int i = 0; i < v.size(); i++) {
                alert al = v.get(i);
                if (al.type() == save_resume_data_alert.alert_type) {
                    // the alert (and its params) stay valid until the next pop_alerts;
                    // no SWIG copy is possible, the memory is owned by the alert
                    saved = alert.cast_to_save_resume_data_alert(al).getParams();
                }
            }
        }
        if (saved == null) throw new RuntimeException("no save_resume_data_alert");
        bitfield hp = saved.get_have_pieces();
        System.out.println("saved have_pieces: size=" + hp.size() + " count=" + hp.count() + " bit2=" + hp.get_bit(2));

        ses.remove_torrent(h);
        Thread.sleep(300);
        // the saved params carry no torrent_info; re-add from the .torrent and
        // transplant have_pieces, the way a client restoring from resume data does
        add_torrent_params p2 = libtorrent.load_torrent_file(torrent.getAbsolutePath());
        p2.setSave_path(dir.getAbsolutePath());
        byte_vector prio = new byte_vector();
        prio.add(Byte.valueOf((byte) 4));
        prio.add(Byte.valueOf((byte) 0));
        p2.set_file_priorities(prio);
        p2.set_have_pieces(hp);
        error_code ec = new error_code();
        torrent_handle h2 = ses.add_torrent(p2, ec);
        if (ec.value() != 0) throw new RuntimeException("re-add: " + ec.message());
        // no re-check expected: the torrent should settle from resume data quickly
        long end2 = System.currentTimeMillis() + 10000;
        torrent_status st2 = h2.status();
        while (System.currentTimeMillis() < end2
            && st2.getState().swigValue() != torrent_status.state_t.downloading.swigValue()
            && st2.getState().swigValue() != torrent_status.state_t.finished.swigValue()) {
            Thread.sleep(100);
            st2 = h2.status();
        }
        System.out.println("re-added: state=" + st2.getState() + " num_pieces=" + st2.getNum_pieces()
            + " have(2)=" + h2.have_piece(2) + " have(1)=" + h2.have_piece(1));
        boolean ok = check("rc==0", rc == 0)
            & check("need_save_resume_data after forget", needAfter)
            & check("saved bit 2 clear", !hp.get_bit(2))
            & check("saved count == 7", hp.count() == 7)
            & check("re-added: have(2)==false", !h2.have_piece(2))
            & check("re-added: have(1)==true", h2.have_piece(1))
            & check("re-added: num_pieces == 7", st2.getNum_pieces() == 7);
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        ses.remove_torrent(h2);
        Thread.sleep(200);
        ses.abort();
        ses.delete();
        System.exit(ok ? 0 : 1);
    }

    // bench: how much slower a library is in libtorrent's own work, to compare a
    // release build with a checked one. One single-file torrent of <pieces>
    // pieces of <kib> KiB. Invariant checks cost O(pieces) per picker operation,
    // so the piece count, not the size, is what makes this realistic.
    // Prints one line: BENCH check_ms=.. first_piece_ms=.. full_ms=..
    // (a value of -1 means the 300 s cap was hit).
    static void bench(File dir, int pieces, int kib, String diskIo) throws Exception {
        int pieceLen = kib * 1024;
        long total = (long) pieces * pieceLen;
        File seedDir = new File(dir, "seed");
        File leechDir = new File(dir, "leech");
        seedDir.mkdirs();
        leechDir.mkdirs();
        File data = new File(seedDir, "bench.bin");
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        ByteArrayOutputStream hashes = new ByteArrayOutputStream();
        Random r = new Random(0x5eed);
        byte[] buf = new byte[pieceLen];
        try (FileOutputStream o = new FileOutputStream(data)) {
            for (int i = 0; i < pieces; i++) {
                r.nextBytes(buf);
                o.write(buf);
                sha1.reset();
                sha1.update(buf);
                hashes.write(sha1.digest());
            }
        }
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write('d');
        bstr(t, "info"); t.write('d');
        bstr(t, "length"); bint(t, total);
        bstr(t, "name"); bstr(t, "bench.bin");
        bstr(t, "piece length"); bint(t, pieceLen);
        bstr(t, "pieces"); bbytes(t, hashes.toByteArray());
        t.write('e'); t.write('e');
        File torrent = new File(dir, "bench.torrent");
        write(torrent, t.toByteArray());
        System.out.println("bench: " + pieces + " pieces x " + kib + " KiB = " + (total >> 20) + " MiB, disk io " + diskIo);

        session seed = newSession(diskIo);
        session leech = newSession(diskIo);
        long cap = 300_000;

        add_torrent_params ps = libtorrent.load_torrent_file(torrent.getAbsolutePath());
        ps.setSave_path(seedDir.getAbsolutePath());
        error_code ec = new error_code();
        long t0 = System.nanoTime();
        torrent_handle hs = seed.add_torrent(ps, ec);
        long checkMs = waitFor(() -> hs.status().getState().swigValue() == torrent_status.state_t.seeding.swigValue(), t0, cap);

        long firstMs = -1, fullMs = -1;
        if (checkMs >= 0) {
            add_torrent_params pl = libtorrent.load_torrent_file(torrent.getAbsolutePath());
            pl.setSave_path(leechDir.getAbsolutePath());
            torrent_handle hl = leech.add_torrent(pl, ec);
            error_code ec2 = new error_code();
            long t1 = System.nanoTime();
            hl.connect_peer(new tcp_endpoint(address.from_string("127.0.0.1", ec2), seed.listen_port()));
            firstMs = waitFor(() -> hl.status().getNum_pieces() >= 1, t1, cap);
            if (firstMs >= 0) {
                fullMs = waitFor(() -> hl.status().getNum_pieces() == pieces, t1, cap);
            }
            leech.remove_torrent(hl);
        }
        System.out.println("BENCH check_ms=" + checkMs + " first_piece_ms=" + firstMs + " full_ms=" + fullMs);
        seed.remove_torrent(hs);
        Thread.sleep(300);
        seed.abort();
        leech.abort();
        seed.delete();
        leech.delete();
        System.exit(checkMs >= 0 && firstMs >= 0 && fullMs >= 0 ? 0 : 1);
    }

    interface Cond { boolean ok() throws Exception; }

    // milliseconds from t0 until cond holds, polled every 20 ms; -1 after capMs
    static long waitFor(Cond cond, long t0, long capMs) throws Exception {
        while (true) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (cond.ok()) return ms;
            if (ms > capMs) return -1;
            Thread.sleep(20);
        }
    }

    static session newSession(String diskIo) {
        settings_pack sp = new settings_pack();
        sp.set_int(settings_pack.int_types.alert_mask.swigValue(), 0);
        sp.set_bool(settings_pack.bool_types.enable_dht.swigValue(), false);
        sp.set_bool(settings_pack.bool_types.enable_lsd.swigValue(), false);
        sp.set_bool(settings_pack.bool_types.enable_upnp.swigValue(), false);
        sp.set_bool(settings_pack.bool_types.enable_natpmp.swigValue(), false);
        sp.set_str(settings_pack.string_types.listen_interfaces.swigValue(), "127.0.0.1:0");
        session_params params = new session_params(sp);
        if (diskIo.equals("posix")) params.set_posix_disk_io_constructor();
        return new session(params);
    }

    static session newSession() {
        settings_pack sp = new settings_pack();
        sp.set_int(settings_pack.int_types.alert_mask.swigValue(), alert_category_t.all().to_int());
        sp.set_bool(settings_pack.bool_types.enable_dht.swigValue(), false);
        sp.set_bool(settings_pack.bool_types.enable_lsd.swigValue(), false);
        sp.set_bool(settings_pack.bool_types.enable_upnp.swigValue(), false);
        sp.set_bool(settings_pack.bool_types.enable_natpmp.swigValue(), false);
        sp.set_str(settings_pack.string_types.listen_interfaces.swigValue(), "127.0.0.1:0");
        return new session(new session_params(sp));
    }

    static boolean check(String name, boolean cond) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + name);
        return cond;
    }

    static torrent_status waitState(torrent_handle h, torrent_status.state_t want, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        torrent_status st = h.status();
        while (System.currentTimeMillis() < end) {
            st = h.status();
            if (st.getState().swigValue() == want.swigValue()) return st;
            Thread.sleep(100);
        }
        throw new RuntimeException("timeout waiting for state " + want + ", got " + st.getState());
    }

    static long statsCounter(session ses, String name) throws Exception {
        int idx = libtorrent.find_metric_idx_ex(name);
        if (idx < 0) throw new RuntimeException("metric not found: " + name);
        ses.post_session_stats();
        long end = System.currentTimeMillis() + 5000;
        alert_ptr_vector v = new alert_ptr_vector();
        while (System.currentTimeMillis() < end) {
            ses.wait_for_alert_ms(500);
            ses.pop_alerts(v);
            for (int i = 0; i < v.size(); i++) {
                alert al = v.get(i);
                if (al.type() == session_stats_alert.alert_type) {
                    return alert.cast_to_session_stats_alert(al).get_value(idx);
                }
            }
        }
        throw new RuntimeException("no session_stats_alert");
    }

    static byte[] pattern(int n, int seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static void write(File f, byte[] b) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
    }

    // a minimal .torrent, written by hand (bencode + SHA-1 piece hashes)
    static byte[] makeTorrent(byte[] fa, byte[] fb) throws Exception {
        byte[] all = new byte[fa.length + fb.length];
        System.arraycopy(fa, 0, all, 0, fa.length);
        System.arraycopy(fb, 0, all, fa.length, fb.length);
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        ByteArrayOutputStream pieces = new ByteArrayOutputStream();
        for (int off = 0; off < all.length; off += PIECE) {
            sha1.reset();
            sha1.update(all, off, Math.min(PIECE, all.length - off));
            pieces.write(sha1.digest());
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write('d');
        bstr(o, "info"); o.write('d');
        bstr(o, "files"); o.write('l');
        o.write('d'); bstr(o, "length"); bint(o, fa.length); bstr(o, "path"); o.write('l'); bstr(o, "a.bin"); o.write('e'); o.write('e');
        o.write('d'); bstr(o, "length"); bint(o, fb.length); bstr(o, "path"); o.write('l'); bstr(o, "b.bin"); o.write('e'); o.write('e');
        o.write('e');
        bstr(o, "name"); bstr(o, "stand");
        bstr(o, "piece length"); bint(o, PIECE);
        bstr(o, "pieces"); bbytes(o, pieces.toByteArray());
        o.write('e'); o.write('e');
        return o.toByteArray();
    }

    static void bstr(OutputStream o, String s) throws IOException { bbytes(o, s.getBytes("UTF-8")); }
    static void bbytes(OutputStream o, byte[] b) throws IOException {
        o.write((b.length + ":").getBytes("US-ASCII")); o.write(b);
    }
    static void bint(OutputStream o, long v) throws IOException { o.write(("i" + v + "e").getBytes("US-ASCII")); }
}
