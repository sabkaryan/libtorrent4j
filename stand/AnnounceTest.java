import com.sun.net.httpserver.HttpServer;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Which announce event a downloader sends while its torrent is "finished but
 * not a seed" (everything wanted is downloaded, other pieces have priority 0).
 * libtorrent turns a regular announce into event=paused in that state
 * (BEP 21, partial seed). A local HTTP tracker records every announce.
 *
 * Usage: AnnounceTest [steps] [dwellMs]
 *
 * Time is compressed: the downloader's min_announce_interval is 5 s instead of
 * 300 s and the tracker asks for 5 s, so regular re-announces fall inside both
 * the downloading and the finished phases of each step.
 *
 * Runs on a desktop JVM (uses the JDK's com.sun.net.httpserver).
 */
public class AnnounceTest {
    static final int PIECE = 256 * 1024;
    static final long T0 = System.nanoTime();
    static long now() { return (System.nanoTime() - T0) / 1_000_000; }

    static final List<String> log = Collections.synchronizedList(new ArrayList<>());
    static final Map<Integer, Long> leftByPort = Collections.synchronizedMap(new TreeMap<>());
    static volatile String phase = "setup";
    static volatile int downloaderPort = -1;

    public static void main(String[] a) throws Exception {
        int steps = a.length > 0 ? Integer.parseInt(a[0]) : 3;
        long dwellMs = a.length > 1 ? Long.parseLong(a[1]) : 15000;
        File dir = new File(System.getProperty("java.io.tmpdir"), "announce-" + ProcessHandle.current().pid());

        HttpServer tracker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tracker.createContext("/announce", ex -> {
            Map<String, String> q = parse(ex.getRequestURI().getRawQuery());
            int port = Integer.parseInt(q.getOrDefault("port", "0"));
            long left = Long.parseLong(q.getOrDefault("left", "-1"));
            String event = q.getOrDefault("event", "(none)");
            leftByPort.put(port, left);
            if (port == downloaderPort) {
                log.add(String.format("ANNOUNCE t=%d phase=%s event=%s left=%d", now(), phase, event, left));
            }
            ByteArrayOutputStream peers = new ByteArrayOutputStream();
            synchronized (leftByPort) {
                for (Map.Entry<Integer, Long> e : leftByPort.entrySet()) {
                    if (e.getKey() == port) continue;
                    peers.write(new byte[]{127, 0, 0, 1});
                    peers.write(e.getKey() >> 8);
                    peers.write(e.getKey() & 0xff);
                }
            }
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            r.write('d'); bstr(r, "interval"); bint(r, 5); bstr(r, "peers"); bbytes(r, peers.toByteArray()); r.write('e');
            byte[] body = r.toByteArray();
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        });
        tracker.start();
        String announce = "http://127.0.0.1:" + tracker.getAddress().getPort() + "/announce";

        int stepPieces = 64, pieces = 1024;
        File torrent = CeilingTest.makeFixture(dir, pieces);
        torrent = withAnnounce(torrent, announce);

        settings_pack ss = CeilingTest.base();
        ss.set_int(settings_pack.int_types.upload_rate_limit.swigValue(), 4 * 1024 * 1024);
        session seed = CeilingTest.open(ss);
        torrent_handle hs = CeilingTest.add(seed, torrent, new File(dir, "seed0"), null);
        CeilingTest.waitState(hs, torrent_status.state_t.seeding, 60_000);

        settings_pack ls = CeilingTest.base();
        ls.set_bool(settings_pack.bool_types.close_redundant_connections.swigValue(), false);
        ls.set_int(settings_pack.int_types.min_announce_interval.swigValue(), 5);
        session leech = CeilingTest.open(ls);
        downloaderPort = leech.listen_port();
        int ceiling = stepPieces;
        phase = "downloading";
        torrent_handle h = CeilingTest.add(leech, torrent, new File(dir, "leech"), CeilingTest.prios(pieces, ceiling));

        for (int s = 1; s <= steps; s++) {
            phase = "downloading";
            // a forced announce while downloading, for comparison
            Thread.sleep(300);
            h.force_reannounce(0, -1, torrent_handle.ignore_min_interval);
            long end = now() + 300_000;
            while (h.status().getNum_pieces() < ceiling && now() < end) Thread.sleep(50);
            phase = "finished(step " + s + ")";
            Thread.sleep(dwellMs / 2);
            // an explicit re-announce in the middle of the finished phase, as a client would do
            // after a network change; regular ones keep coming every 5 s
            h.force_reannounce(0, -1, torrent_handle.ignore_min_interval);
            Thread.sleep(dwellMs / 2);
            ceiling += stepPieces;
            h.prioritize_pieces_ex(CeilingTest.prios(pieces, ceiling));
        }
        phase = "done";
        Thread.sleep(2000);
        synchronized (log) { for (String l : log) System.out.println(l); }
        Map<String, Integer> byPhase = new TreeMap<>();
        synchronized (log) {
            for (String l : log) {
                String k = (l.contains("phase=downloading") ? "downloading" : l.contains("phase=finished") ? "finished" : "other")
                    + " -> " + l.replaceAll(".*event=([^ ]+).*", "$1");
                byPhase.merge(k, 1, Integer::sum);
            }
        }
        System.out.println("SUMMARY " + byPhase);
        tracker.stop(0);
        CeilingTest.finish(dir);
    }

    static Map<String, String> parse(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            String k = kv.substring(0, i);
            if (k.equals("info_hash") || k.equals("peer_id")) continue; // raw bytes, not needed
            m.put(k, URLDecoder.decode(kv.substring(i + 1), StandardCharsets.ISO_8859_1));
        }
        return m;
    }

    // rewrite the fixture's .torrent with an "announce" key (keys sorted: announce < info)
    static File withAnnounce(File torrent, String url) throws IOException {
        byte[] t = java.nio.file.Files.readAllBytes(torrent.toPath());
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write('d');
        bstr(o, "announce"); bstr(o, url);
        o.write(t, 1, t.length - 1); // the rest of the original dict, starting with 4:info
        File out = new File(torrent.getParentFile(), "announce.torrent");
        try (FileOutputStream f = new FileOutputStream(out)) { f.write(o.toByteArray()); }
        return out;
    }

    static void bstr(OutputStream o, String s) throws IOException { bbytes(o, s.getBytes(StandardCharsets.UTF_8)); }
    static void bbytes(OutputStream o, byte[] b) throws IOException { o.write((b.length + ":").getBytes(StandardCharsets.US_ASCII)); o.write(b); }
    static void bint(OutputStream o, long v) throws IOException { o.write(("i" + v + "e").getBytes(StandardCharsets.US_ASCII)); }
}
