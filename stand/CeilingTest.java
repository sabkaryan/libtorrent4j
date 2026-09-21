import org.libtorrent4j.swig.*;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Rig for a "download ceiling" policy built only from stock libtorrent features:
 * pieces beyond the ceiling get priority 0, the ceiling is raised in steps, and
 * close_redundant_connections is off. Once everything below the ceiling is
 * downloaded the torrent becomes finished; this rig measures what that costs.
 *
 * All sessions run in one process on 127.0.0.1. Loopback peers normally fall in
 * libtorrent's "local" peer class, which ignores unchoke slots and rate limits;
 * every session here maps all addresses to the global class instead, so seeds
 * choke, keep slots and throttle as they would towards a remote peer.
 *
 * Usage: CeilingTest <scenario> <seed> <crc> [steps] [stepMiB] [rateKiB] [dwellMs]
 *   scenario  steps — raise the ceiling step by step, time the recovery
 *             seek  — while finished, jump far beyond the ceiling, time the
 *                     first far piece; with seed=... and crc=control it runs
 *                     the same jump without any ceiling (all pieces wanted)
 *             idle  — stay finished for dwellMs, then raise; for seed=busy
 *             toggle — stop/go cycles (steps = cycles, dwellMs = the stop), crc is
 *                     "<mechanism>:<close_redundant_connections>", mechanism
 *                     prio | upload | pause | pause-am (see toggle())
 *   seed      free      — one seed, 8 unchoke slots
 *             contended — one seed, 1 unchoke slot, a competing downloader
 *             busy      — one seed near its connection limit with a short
 *                         inactivity_timeout (time compressed, see below)
 *   crc       false|true — close_redundant_connections on the downloader;
 *             "control" (seek only) — no ceiling at all
 *
 * "busy": libtorrent drops a connection for mutual lack of interest only when
 * both sides have been uninterested longer than inactivity_timeout AND the
 * session is within 5 of connections_limit. The busy seed has connections_limit
 * 6 and inactivity_timeout 20 s instead of 600 s: the condition is the same,
 * only the wait is shorter.
 */
public class CeilingTest {
    static final int PIECE = 256 * 1024;
    static final long T0 = System.nanoTime();
    static final boolean DEBUG = System.getenv("CEILING_DEBUG") != null;
    static long now() { return (System.nanoTime() - T0) / 1_000_000; }

    public static void main(String[] a) throws Exception {
        String scenario = a[0], seedKind = a[1], crc = a[2];
        int steps = a.length > 3 ? Integer.parseInt(a[3]) : 6;
        int stepMiB = a.length > 4 ? Integer.parseInt(a[4]) : 16;
        int rateKiB = a.length > 5 ? Integer.parseInt(a[5]) : 4096;
        long dwellMs = a.length > 6 ? Long.parseLong(a[6]) : 3000;
        File dir = new File(System.getProperty("java.io.tmpdir"), "ceiling-" + ProcessHandle.current().pid());
        int stepPieces = stepMiB * 1024 * 1024 / PIECE;
        int pieces = Math.max(stepPieces * (steps + 1) + stepPieces * 4, 1024);
        File torrent = makeFixture(dir, pieces);
        System.out.printf("fixture: %d pieces x 256 KiB; step %d pieces (%d MiB); seed rate %d KiB/s; dwell %d ms%n",
            pieces, stepPieces, stepMiB, rateKiB, dwellMs);

        // ---- seeds. "mixed:N:K" = N seeds sharing the rate, K of them with one
        // unchoke slot held by their own steady competitor, the rest with free slots.
        int nSeeds = 1, nBusySlots = seedKind.equals("contended") ? 1 : 0;
        if (seedKind.startsWith("mixed:")) {
            String[] m = seedKind.split(":");
            nSeeds = Integer.parseInt(m[1]);
            nBusySlots = Integer.parseInt(m[2]);
        }
        List<session> seeds = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        torrent_handle hs = null;
        for (int i = 0; i < nSeeds; i++) {
            settings_pack ss = base();
            ss.set_int(settings_pack.int_types.upload_rate_limit.swigValue(), rateKiB * 1024 / nSeeds);
            ss.set_bool(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), true);
            boolean slotTaken = i < nBusySlots;
            if (slotTaken) ss.set_int(settings_pack.int_types.unchoke_slots_limit.swigValue(), 1);
            if (seedKind.equals("busy")) {
                ss.set_int(settings_pack.int_types.connections_limit.swigValue(), 6);
                ss.set_int(settings_pack.int_types.inactivity_timeout.swigValue(), 20);
            }
            session seed = open(ss);
            torrent_handle h1 = add(seed, torrent, new File(dir, "seed" + i), null, i > 0 ? new File(dir, "seed0") : null);
            waitState(h1, torrent_status.state_t.seeding, 120_000);
            if (hs == null) hs = h1;
            seeds.add(seed);
            ports.add(seed.listen_port());
            if (slotTaken) {
                // a competitor that wants everything, slowly, and stays interested
                settings_pack cs = base();
                cs.set_int(settings_pack.int_types.download_rate_limit.swigValue(), 256 * 1024);
                // competitors are found by the downloader through PEX; keep them from
                // serving it, so what is measured is the seeds' slots
                cs.set_int(settings_pack.int_types.upload_rate_limit.swigValue(), 1024);
                session comp = open(cs);
                torrent_handle hc = add(comp, torrent, new File(dir, "comp" + i), null, null);
                connect(hc, seed.listen_port());
            }
        }
        session seed = seeds.get(0);
        System.out.printf("swarm: %d seed(s), %d with their only slot contended, %d KiB/s each%n",
            nSeeds, nBusySlots, rateKiB / nSeeds);

        // ---- downloader
        settings_pack ls = base();
        boolean control = crc.equals("control");
        // toggle: crc is "<mechanism>:<close_redundant_connections>", e.g. prio:true
        String mech = scenario.equals("toggle") ? crc.split(":")[0] : null;
        if (mech != null) crc = crc.split(":")[1];
        ls.set_bool(settings_pack.bool_types.close_redundant_connections.swigValue(), crc.equals("true"));
        session leech = open(ls);
        int ceiling = control || scenario.equals("toggle") ? pieces : (scenario.equals("slide") ? 8 : stepPieces);
        torrent_handle h = add(leech, torrent, new File(dir, "leech"), prios(pieces, ceiling));
        long tConnect = now();
        for (int port : ports) connect(h, port);
        Watch w = new Watch(leech, seed, h, pieces);
        w.otherSeeds = seeds;
        w.ceilingTarget = ceiling;
        w.hs = hs;

        if (scenario.equals("seek")) { seek(w, h, pieces, stepPieces, dwellMs, control); finish(dir); return; }
        if (scenario.equals("toggle")) {
            // stop/go cycles: steps = cycles, dwellMs = gap
            System.out.printf("toggle: mech=%s close_redundant_connections=%s%n", mech, crc);
            toggle(w, h, pieces, mech, crc.equals("true"), steps, dwellMs);
            finish(dir);
            return;
        }
        if (scenario.equals("slide")) {
            // ceiling 0: only the playback window is wanted. steps = rest at 0 ("false"),
            // control = rest at normal priority, interested all the time
            slide(w, h, pieces, control, 1024, 8, dwellMs);
            finish(dir);
            return;
        }

        for (int s = 1; s <= steps; s++) {
            // download up to the ceiling
            w.until(() -> w.have >= Math.min(w.ceilingTarget, pieces), 300_000);
            long tDone = now();
            double preRate = w.rateOver(2000);
            long tFinished = w.waitStateEvent("finished", tDone - 500, 3000);
            long flushMs = w.flushAfter(tFinished, 3000);
            int peersAtDone = w.peers;
            // stay finished: the viewer is consuming what is buffered
            w.idle(dwellMs);
            int peersBeforeRaise = w.peers;
            int dropsIdle = w.drops;
            // evidence that the flap happened: before the raise we must be uninterested and choked
            String before = (w.interesting ? "interested" : "not-interested") + "/" + (w.unchokedByAny ? "unchoked" : "choked");
            // raise
            ceiling += stepPieces;
            w.ceilingTarget = ceiling;
            long tRaise = now();
            long base = w.bytes;
            h.prioritize_pieces_ex(prios(pieces, ceiling));
            long[] ev = w.track(() -> w.rateOver(1000) >= 0.8 * preRate, base, 120_000);
            long tInterest = ev[0], tUnchoke = ev[1], tFirst = ev[2], tRecover = ev[3];
            System.out.printf("STEP %d seed=%s crc=%s pre_rate_KiBs=%.0f finished_state=%s release_ms=%s before_raise=%s "
                    + "peers_done=%d peers_before_raise=%d drops_while_finished=%d "
                    + "raise_to_interest_ms=%s raise_to_unchoke_ms=%s raise_to_first_byte_ms=%s raise_to_rate_recovered_ms=%s drops_total=%d%n",
                s, seedKind, crc, preRate / 1024, tFinished >= 0 ? "yes" : "no", ms(flushMs), before,
                peersAtDone, peersBeforeRaise, dropsIdle,
                rel(tInterest, tRaise), rel(tUnchoke, tRaise), rel(tFirst, tRaise), rel(tRecover, tRaise), w.drops);
            w.drops = 0;
        }
        finish(dir);
    }

    // seek: while finished (or, for control, while downloading with no ceiling),
    // want a piece far beyond everything downloaded, time its arrival
    static void seek(Watch w, torrent_handle h, int pieces, int stepPieces, long dwellMs, boolean control) throws Exception {
        for (int round = 1; round <= 5; round++) {
            int far = pieces - 1 - round * 8; // well beyond the ceiling and anything downloaded
            if (!control) {
                w.until(() -> w.have >= w.ceilingTarget, 300_000);
                w.idle(dwellMs);
            } else {
                w.idle(dwellMs); // downloading all the time, interested and unchoked
            }
            String st = w.state;
            String before = (w.interesting ? "interested" : "not-interested") + "/" + (w.unchokedByAny ? "unchoked" : "choked");
            // near jumps: back into what is downloaded, forward within the ceiling.
            // They need no network: the piece is already here at the moment of the jump.
            int back = Math.max(0, w.have / 4), ahead = Math.max(0, Math.min(w.ceilingTarget, pieces) - 2);
            System.out.printf("NEAR %d back_piece=%d have=%s ahead_piece=%d have=%s%n", round, back, h.have_piece(back), ahead, h.have_piece(ahead));
            long tSeek = now();
            h.piece_priority_ex(far, (byte) 7);
            h.set_piece_deadline(far, 0);
            long[] ev = w.track(() -> h.have_piece(far), Long.MAX_VALUE, 120_000);
            long tInterest = ev[0], tUnchoke = ev[1], tPiece = ev[3];
            System.out.printf("SEEK %d mode=%s state_at_seek=%s before=%s peers=%d seek_to_interest_ms=%s seek_to_unchoke_ms=%s seek_to_piece_ms=%s drops=%d%n",
                round, control ? "no-ceiling" : "ceiling", st, before, w.peers, rel(tInterest, tSeek), rel(tUnchoke, tSeek), rel(tPiece, tSeek), w.drops);
            if (!control) {
                // back to the window: drop the far piece again so the torrent can finish
                h.reset_piece_deadline(far);
                h.piece_priority_ex(far, (byte) 0);
            }
            w.drops = 0;
        }
    }

    // toggle: download for ON_MS, stop, wait gapMs, go again. Mechanisms:
    //   prio     — every piece to priority 0 and back (the torrent becomes finished)
    //   upload   — torrent upload_mode on and off (connections kept, requests cancelled)
    //   pause    — torrent pause()/resume()
    //   pause-am — the same, auto_managed left on (the default for added torrents);
    //              the others clear it first, as an embedding application would
    // Per cycle: bytes still arriving after the stop (overshoot) and when the last of
    // them came; whether the flow had actually stopped by "go" (no byte in the last
    // 100 ms); then, counting only what arrives after "go": first byte, and the rate
    // back to 80 % of the rate before the stop.
    // "unstick" (crc=true, prio): no recovery within STUCK_MS -> a new, never seen
    // peer is added the way tracker and DHT replies add one (torrent::add_peer),
    // then the rig waits for the seed to come back; every such cycle adds its own.
    static final long ON_MS = 2000, TRACK_MS = 90_000, STUCK_MS = 15_000;

    static void toggle(Watch w, torrent_handle h, int pieces, String mech, boolean crcOn, int cycles, long gapMs) throws Exception {
        if (!mech.equals("pause-am")) {
            // the default add flags carry "paused": with auto_managed gone, nobody but
            // us will ever resume the torrent, so resume() must follow the unset
            h.unset_flags(libtorrent.getAuto_managed());
            h.resume();
        }
        byte_vector all0 = prios(pieces, 0), all4 = prios(pieces, pieces);
        // CEILING_DEADPEER: know one non-seed we never manage to connect to, the way a real
        // swarm always knows a few. It keeps peer_list's candidate count off zero, which is
        // the condition the whole stuck case rests on.
        if (System.getenv("CEILING_DEADPEER") != null) { connect(h, 9); connect(h, 1); }
        w.until(() -> w.bytes > 0, 60_000);
        for (int c = 1; c <= cycles; c++) {
            w.idle(ON_MS);
            double preRate = w.rateOver(1500);
            long b0 = w.bytes, tStop = now();
            int peersBefore = w.peers;
            w.drops = 0;
            switch (mech) {
                case "prio": h.prioritize_pieces_ex(all0); break;
                case "upload": h.set_flags(libtorrent.getUpload_mode()); break;
                default: h.pause(); break;
            }
            // watch the gap: overshoot, when the last byte came, whether the stop held
            long lastByteAt = tStop, lastBytes = b0, end = tStop + gapMs, stopLeakedAt = -1;
            int peersMinInGap = peersBefore;
            boolean interestedInGap = false;
            while (now() < end) {
                w.tick();
                if (w.bytes > lastBytes) { lastBytes = w.bytes; lastByteAt = now(); }
                peersMinInGap = Math.min(peersMinInGap, w.peers);
                if (now() - tStop > 500 && w.interesting) interestedInGap = true;
                if (stopLeakedAt < 0 && !stopHolds(h, mech)) stopLeakedAt = now();
                Thread.sleep(10);
            }
            long overshoot = w.bytes - b0;
            int dropsInGap = w.drops;
            // the whole stuck case hinges on this being zero at the moment of "go"
            int candidatesAtGo = h.status().getConnect_candidates();
            long tGo = now(), base = w.bytes;
            boolean flowStopped = tGo - lastByteAt >= 100;
            switch (mech) {
                case "prio": h.prioritize_pieces_ex(all4); break;
                case "upload": h.unset_flags(libtorrent.getUpload_mode()); break;
                default: h.resume(); break;
            }
            boolean stuckCase = mech.equals("prio") && crcOn;
            long[] ev = w.track(() -> w.recovered(tGo, preRate), base, stuckCase ? STUCK_MS : TRACK_MS);
            System.out.printf("CYCLE %d mech=%s gap_ms=%d pre_rate_KiBs=%.0f overshoot_KiB=%d last_byte_after_stop_ms=%d flow_stopped=%s "
                    + "stop_undone_after_ms=%s interested_in_gap=%s peers_before=%d peers_min_in_gap=%d drops_in_gap=%d candidates_at_go=%d "
                    + "go_to_first_byte_ms=%s go_to_rate_recovered_ms=%s peers_after=%d drops_after_go=%d%n",
                c, mech, gapMs, preRate / 1024, overshoot / 1024, lastByteAt - tStop, flowStopped,
                rel(stopLeakedAt, tStop), interestedInGap, peersBefore, peersMinInGap, dropsInGap, candidatesAtGo,
                rel(ev[2], tGo), rel(ev[3], tGo), w.peers, w.drops - dropsInGap);
            if (ev[3] < 0 && stuckCase) unstick(w, h, c, tStop, tGo, preRate);
        }
    }

    static boolean stopHolds(torrent_handle h, String mech) {
        torrent_flags_t f = h.flags(), z = new torrent_flags_t();
        switch (mech) {
            case "prio": return true; // nothing in libtorrent flips priorities back
            case "upload": return !f.and_(libtorrent.getUpload_mode()).eq(z);
            default: return !f.and_(libtorrent.getPaused()).eq(z);
        }
    }

    static void unstick(Watch w, torrent_handle h, int cycle, long tDrop, long tGo, double preRate) throws Exception {
        // an empty downloader nobody has seen: not a seed, so it is a connect candidate
        settings_pack ns = base();
        ns.set_int(settings_pack.int_types.upload_rate_limit.swigValue(), 1024);
        // and it must not compete for the seed's rate once PEX tells it about the seed
        ns.set_int(settings_pack.int_types.download_rate_limit.swigValue(), 16 * 1024);
        session fresh = open(ns);
        File dir = new File(System.getProperty("java.io.tmpdir"), "ceiling-" + ProcessHandle.current().pid());
        torrent_handle hf = add(fresh, new File(dir, "data.torrent"), new File(dir, "fresh" + cycle), null); // removed with dir by finish()
        waitState(hf, torrent_status.state_t.downloading, 30_000);
        long tAdd = now(), base = w.bytes;
        connect(h, fresh.listen_port());
        long[] ev = w.track(() -> w.recovered(tAdd, preRate), base, TRACK_MS);
        System.out.printf("UNSTICK cycle=%d new_peer_added_after_drop_ms=%d raise_after_drop_ms=%d first_byte_after_drop_ms=%s "
                + "rate_recovered_after_drop_ms=%s peers=%d%n",
            cycle, tAdd - tDrop, tGo - tDrop, rel(ev[2], tDrop), rel(ev[3], tDrop), w.peers);
    }

    // slide: a playback clock at bitrateKiB consumes one piece at a time; the
    // window is the next windowPieces pieces. Each time playback advances, the
    // piece entering the window becomes wanted (deadline). With the ceiling at 0
    // nothing beyond the window is wanted; control wants everything.
    static void slide(Watch w, torrent_handle h, int pieces, boolean control, int bitrateKiB, int windowPieces, long durationMs) throws Exception {
        long msPerPiece = (long) PIECE * 1000 / (bitrateKiB * 1024L);
        byte[] prio = new byte[pieces];
        for (int i = 0; i < pieces; i++) prio[i] = (byte) (control ? 4 : 0);
        for (int i = 0; i < windowPieces; i++) prio[i] = 7;
        byte_vector bv = new byte_vector();
        for (byte b : prio) bv.add(Byte.valueOf(b));
        h.prioritize_pieces_ex(bv);
        for (int i = 0; i < windowPieces; i++) h.set_piece_deadline(i, (int) (msPerPiece * i));
        long[] wanted = new long[pieces];
        Arrays.fill(wanted, -1);
        for (int i = 0; i < windowPieces; i++) wanted[i] = now();
        List<Long> latency = new ArrayList<>();
        boolean[] seen = new boolean[pieces];

        long tStart = now();
        long startup = w.until(() -> h.have_piece(0), 120_000);
        System.out.printf("SLIDE start mode=%s startup_ms=%s ms_per_piece=%d window=%d%n",
            control ? "no-ceiling" : "ceiling-0", rel(startup, tStart), msPerPiece, windowPieces);
        int pos = 0, rebuffers = 0, finishedTicks = 0, flaps = 0, windowViolations = 0;
        long stallStart = -1, stallTotal = 0, next = now() + msPerPiece, lastPrioCheck = 0;
        boolean wasInteresting = true;
        long end = now() + durationMs;
        while (now() < end && pos < pieces - windowPieces - 1) {
            w.tick();
            long t = now();
            if (wasInteresting && !w.interesting) flaps++;
            wasInteresting = w.interesting;
            if (w.state.equals("finished")) finishedTicks++;
            for (int i = pos; i < pos + windowPieces; i++) {
                if (!seen[i] && wanted[i] >= 0 && h.have_piece(i)) { seen[i] = true; latency.add(t - wanted[i]); }
            }
            if (t >= next) {
                if (h.have_piece(pos)) {
                    if (stallStart >= 0) { stallTotal += t - stallStart; stallStart = -1; }
                    pos++;
                    next = t + msPerPiece;
                    int np = pos + windowPieces - 1;
                    wanted[np] = t;
                    h.set_piece_deadline(np, (int) (msPerPiece * (windowPieces - 1)));
                } else if (stallStart < 0) {
                    stallStart = t;
                    rebuffers++;
                }
            }
            if (t - lastPrioCheck > 1000) {
                lastPrioCheck = t;
                byte_vector cur = h.get_piece_priorities_ex();
                for (int i = pos; i < pos + windowPieces; i++) if (cur.get(i) == 0) windowViolations++;
            }
            Thread.sleep(20);
        }
        if (stallStart >= 0) stallTotal += now() - stallStart;
        Collections.sort(latency);
        String lat = latency.isEmpty() ? "n/a" : String.format("min=%d med=%d p90=%d max=%d n=%d",
            latency.get(0), latency.get(latency.size() / 2), latency.get(latency.size() * 9 / 10), latency.get(latency.size() - 1), latency.size());
        System.out.printf("SLIDE mode=%s played_pieces=%d rebuffers=%d stall_total_ms=%d wanted_to_have_ms[%s] finished_ticks=%d interest_flaps=%d window_prio0_violations=%d drops=%d%n",
            control ? "no-ceiling" : "ceiling-0", pos, rebuffers, stallTotal, lat, finishedTicks, flaps, windowViolations, w.drops);
    }

    // ---------------------------------------------------------------- watching

    interface Cond { boolean ok() throws Exception; }

    static class Watch {
        final session leech, seed;
        final torrent_handle h;
        torrent_handle hs;
        List<session> otherSeeds = new ArrayList<>();
        final int pieces;
        final alert_ptr_vector v = new alert_ptr_vector();
        final peer_info_vector pv = new peer_info_vector();
        final ArrayDeque<long[]> samples = new ArrayDeque<>(); // {t, bytes}
        int ceilingTarget, have, peers, drops;
        long bytes, bytesOffset, lastTp;
        boolean interesting, unchokedByAny;
        String state = "?";
        final List<long[]> stateEvents = new ArrayList<>(); // {t, stateOrdinal}
        final List<Long> flushEvents = new ArrayList<>();

        Watch(session leech, session seed, torrent_handle h, int pieces) {
            this.leech = leech; this.seed = seed; this.h = h; this.pieces = pieces;
        }

        void tick() {
            torrent_status st = h.status();
            have = st.getNum_pieces();
            // total_payload_download restarts from zero on every resume(); all_time_download
            // does not, but moves only once a second. Keep the live one, carried over resets.
            long tp = st.getTotal_payload_download();
            if (tp < lastTp) bytesOffset += lastTp;
            lastTp = tp;
            bytes = bytesOffset + tp;
            state = st.getState().toString();
            long t = now();
            samples.addLast(new long[]{t, bytes});
            while (!samples.isEmpty() && samples.peekFirst()[0] < t - 5000) samples.pollFirst();
            h.get_peer_info(pv);
            peers = (int) pv.size();
            interesting = false;
            unchokedByAny = false;
            for (int i = 0; i < pv.size(); i++) {
                peer_info p = pv.get(i);
                peer_flags_t f = p.getFlags();
                if (!f.and_(peer_info.interesting).eq(new peer_flags_t())) interesting = true;
                if (f.and_(peer_info.remote_choked).eq(new peer_flags_t())) unchokedByAny = true;
            }
            leech.pop_alerts(v);
            for (int i = 0; i < v.size(); i++) {
                alert al = v.get(i);
                int ty = al.type();
                if (ty == peer_disconnected_alert.alert_type) {
                    drops++;
                    peer_disconnected_alert d = alert.cast_to_peer_disconnected_alert(al);
                    System.out.println("  [" + t + "] DISCONNECT " + d.getError().message() + " op=" + d.getOp() + " reason=" + d.getReason());
                } else if (ty == peer_connect_alert.alert_type) {
                    peer_connect_alert pc = alert.cast_to_peer_connect_alert(al);
                    System.out.println("  [" + t + "] CONNECT out " + pc.get_endpoint().port());
                } else if (ty == incoming_connection_alert.alert_type) {
                    System.out.println("  [" + t + "] CONNECT in");
                } else if (ty == state_changed_alert.alert_type) {
                    state_changed_alert sc = alert.cast_to_state_changed_alert(al);
                    stateEvents.add(new long[]{t, sc.getState().swigValue()});
                } else if (ty == cache_flushed_alert.alert_type) {
                    flushEvents.add(t);
                }
            }
            for (session o : otherSeeds) o.pop_alerts(v); // drained; seed-side disconnects show up on the downloader too
        }

        double rateOver(long ms) {
            long t = now();
            long[] first = null, last = samples.peekLast();
            for (long[] s : samples) if (s[0] >= t - ms) { first = s; break; }
            if (first == null || last == null || last[0] == first[0]) return 0;
            return (last[1] - first[1]) * 1000.0 / (last[0] - first[0]);
        }

        // rate back to 80 % of `pre`, from samples taken after `since` only (a window of
        // up to 1 s, at least 250 ms): bytes that were in flight before "go" do not count
        boolean recovered(long since, double pre) {
            long t = now();
            if (t - since < 250) return false;
            long from = Math.max(since, t - 1000);
            long[] first = null, last = samples.peekLast();
            for (long[] s : samples) if (s[0] >= from) { first = s; break; }
            if (first == null || last == null || last[0] - first[0] < 200) return false;
            return (last[1] - first[1]) * 1000.0 / (last[0] - first[0]) >= 0.8 * pre;
        }

        long until(Cond c, long capMs) throws Exception {
            long end = now() + capMs, nextDump = now() + 5000;
            while (now() < end) {
                tick();
                if (c.ok()) return now();
                if (DEBUG && now() >= nextDump) { dump(); nextDump = now() + 5000; }
                Thread.sleep(50);
            }
            return -1;
        }

        // both sides' view of the connection, to tell "we did not ask" from "they did not answer"
        void dump() {
            StringBuilder b = new StringBuilder("  [" + now() + "] leech:");
            h.get_peer_info(pv);
            for (int i = 0; i < pv.size(); i++) b.append(' ').append(flags(pv.get(i)));
            if (hs != null) {
                b.append(" | seed:");
                peer_info_vector sv = new peer_info_vector();
                hs.get_peer_info(sv);
                for (int i = 0; i < sv.size(); i++) b.append(' ').append(flags(sv.get(i)));
            }
            b.append(" | leech state=").append(state).append(" have=").append(have);
            System.out.println(b);
        }

        static String flags(peer_info p) {
            peer_flags_t f = p.getFlags(), z = new peer_flags_t();
            return "{we-interested=" + !f.and_(peer_info.interesting).eq(z)
                + " we-choke-them=" + !f.and_(peer_info.choked).eq(z)
                + " they-interested=" + !f.and_(peer_info.remote_interested).eq(z)
                + " they-choke-us=" + !f.and_(peer_info.remote_choked).eq(z)
                + " upload_only=" + !f.and_(peer_info.upload_only).eq(z)
                + " queue=" + p.getDownload_queue_length() + "}";
        }

        // first moment of each event, in one loop, whatever their order:
        // {interest, unchoke, first byte after base, done}; -1 = not seen
        long[] track(Cond done, long base, long capMs) throws Exception {
            long ti = -1, tu = -1, tb = -1, td = -1, end = now() + capMs, nextDump = now() + 5000;
            while (now() < end) {
                tick();
                long t = now();
                if (ti < 0 && interesting) ti = t;
                if (tu < 0 && unchokedByAny) tu = t;
                if (tb < 0 && bytes > base) tb = t;
                if (done.ok()) { td = t; break; }
                if (DEBUG && t >= nextDump) { dump(); nextDump = t + 5000; }
                Thread.sleep(10);
            }
            return new long[]{ti, tu, tb, td};
        }

        void idle(long ms) throws Exception {
            long end = now() + ms;
            while (now() < end) { tick(); Thread.sleep(50); }
        }

        long waitStateEvent(String name, long since, long capMs) throws Exception {
            int want = name.equals("finished") ? torrent_status.state_t.finished.swigValue() : -1;
            long end = now() + capMs;
            while (now() < end) {
                tick();
                for (long[] e : stateEvents) if (e[0] >= since && e[1] == want) return e[0];
                if (state.equals(name)) return now();
                Thread.sleep(50);
            }
            return -1;
        }

        long flushAfter(long t, long capMs) throws Exception {
            if (t < 0) return -1;
            long end = now() + capMs;
            while (now() < end) {
                tick();
                for (long f : flushEvents) if (f >= t) return f - t;
                Thread.sleep(50);
            }
            return -1;
        }
    }

    static String rel(long t, long base) { return t < 0 ? "n/s" : Long.toString(t - base); }  // n/s: not seen (cap, or faster than the poll)
    static String ms(long v) { return v < 0 ? "n/a" : Long.toString(v); }

    // ---------------------------------------------------------------- plumbing

    static settings_pack base() {
        settings_pack sp = new settings_pack();
        sp.set_int(settings_pack.int_types.alert_mask.swigValue(), alert_category_t.all().to_int());
        sp.set_bool(settings_pack.bool_types.enable_dht.swigValue(), false);
        sp.set_bool(settings_pack.bool_types.enable_lsd.swigValue(), false);
        sp.set_bool(settings_pack.bool_types.enable_upnp.swigValue(), false);
        sp.set_bool(settings_pack.bool_types.enable_natpmp.swigValue(), false);
        sp.set_str(settings_pack.string_types.listen_interfaces.swigValue(), "127.0.0.1:0");
        // every peer here is 127.0.0.1; by default libtorrent keeps one connection per IP
        sp.set_bool(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), true);
        return sp;
    }

    static session open(settings_pack sp) {
        session s = new session(new session_params(sp));
        // every address in the global peer class (bit 0): no "local" class exemptions
        ip_filter f = new ip_filter();
        error_code ec = new error_code();
        f.add_rule(address.from_string("0.0.0.0", ec), address.from_string("255.255.255.255", ec), 1);
        s.set_peer_class_filter(f);
        return s;
    }

    static torrent_handle add(session s, File torrent, File saveDir, byte_vector prios) {
        return add(s, torrent, saveDir, prios, null);
    }

    // copyFrom: an existing seed directory to hard-link the data from
    static torrent_handle add(session s, File torrent, File saveDir, byte_vector prios, File copyFrom) {
        saveDir.mkdirs();
        if (copyFrom != null) {
            try {
                java.nio.file.Files.createLink(new File(saveDir, "data.bin").toPath(), new File(copyFrom, "data.bin").toPath());
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        add_torrent_params p = libtorrent.load_torrent_file(torrent.getAbsolutePath());
        p.setSave_path(saveDir.getAbsolutePath());
        if (prios != null) p.set_piece_priorities(prios);
        error_code ec = new error_code();
        torrent_handle h = s.add_torrent(p, ec);
        if (ec.value() != 0) throw new RuntimeException(ec.message());
        return h;
    }

    static void connect(torrent_handle h, int port) {
        error_code ec = new error_code();
        h.connect_peer(new tcp_endpoint(address.from_string("127.0.0.1", ec), port));
    }

    static byte_vector prios(int pieces, int ceiling) {
        byte_vector v = new byte_vector();
        for (int i = 0; i < pieces; i++) v.add(Byte.valueOf((byte) (i < ceiling ? 4 : 0)));
        return v;
    }

    static void waitState(torrent_handle h, torrent_status.state_t want, long ms) throws Exception {
        long end = now() + ms;
        while (now() < end) {
            if (h.status().getState().swigValue() == want.swigValue()) return;
            Thread.sleep(50);
        }
        throw new RuntimeException("timeout waiting for " + want);
    }

    static File makeFixture(File dir, int pieces) throws Exception {
        File seedDir = new File(dir, "seed0");
        seedDir.mkdirs();
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        ByteArrayOutputStream hashes = new ByteArrayOutputStream();
        Random r = new Random(7);
        byte[] buf = new byte[PIECE];
        try (FileOutputStream o = new FileOutputStream(new File(seedDir, "data.bin"))) {
            for (int i = 0; i < pieces; i++) {
                r.nextBytes(buf);
                o.write(buf);
                sha1.reset();
                sha1.update(buf);
                hashes.write(sha1.digest());
            }
        }
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write('d'); bstr(t, "info"); t.write('d');
        bstr(t, "length"); bint(t, (long) pieces * PIECE);
        bstr(t, "name"); bstr(t, "data.bin");
        bstr(t, "piece length"); bint(t, PIECE);
        bstr(t, "pieces"); bbytes(t, hashes.toByteArray());
        t.write('e'); t.write('e');
        File torrent = new File(dir, "data.torrent");
        try (FileOutputStream o = new FileOutputStream(torrent)) { o.write(t.toByteArray()); }
        return torrent;
    }

    static void finish(File dir) {
        System.out.println("done");
        deleteTree(dir);
        System.exit(0); // native sessions are left to the OS: finalizers at shutdown are unreliable
    }

    static void deleteTree(File f) {
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteTree(x);
        f.delete();
    }

    static void bstr(OutputStream o, String s) throws IOException { bbytes(o, s.getBytes("UTF-8")); }
    static void bbytes(OutputStream o, byte[] b) throws IOException { o.write((b.length + ":").getBytes("US-ASCII")); o.write(b); }
    static void bint(OutputStream o, long v) throws IOException { o.write(("i" + v + "e").getBytes("US-ASCII")); }
}
