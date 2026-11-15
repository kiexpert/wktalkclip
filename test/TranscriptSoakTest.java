import app.wktalkclip.Transcript;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Random;

// Soak: a 3 hour meeting of very fast speech (36,000 words, about 200 words per minute).
public class TranscriptSoakTest {
    static int fails = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name + " (" + detail + ")");
        if (!ok) {
            fails++;
        }
    }

    static long usedHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    static String word(Random rnd) {
        int len = 2 + rnd.nextInt(7);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < len; i++) {
            b.append((char) ('a' + rnd.nextInt(26)));
        }
        return b.toString();
    }

    public static void main(String[] a) {
        final int TOTAL_WORDS = 36000;
        Random rnd = new Random(20240607L);
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();

        Transcript t = new Transcript();
        ArrayList<String> sentences = new ArrayList<String>();
        long heapBefore = usedHeap();

        // Count events first is not possible without generating; estimate the final 10% by words.
        int words = 0;
        long cpuTotal = 0;
        long worstTail = 0;
        long events = 0;
        long lastVersion = t.version();
        boolean versionOk = true;
        int sentenceNo = 0;
        while (words < TOTAL_WORDS) {
            int n = 8 + rnd.nextInt(13);
            String[] w = new String[n];
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                w[i] = word(rnd);
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(w[i]);
            }
            String sentence = sb.toString();
            sentences.add(sentence);

            // About 8 growing partials, each a prefix of the final text.
            ArrayList<String> partials = new ArrayList<String>();
            int steps = 8;
            for (int s = 1; s <= steps; s++) {
                int upto = Math.max(1, n * s / steps);
                StringBuilder p = new StringBuilder();
                for (int i = 0; i < upto; i++) {
                    if (i > 0) {
                        p.append(' ');
                    }
                    p.append(w[i]);
                }
                partials.add(p.toString());
            }
            if (rnd.nextInt(10) == 0) {
                // one revision: an earlier word changes in the middle of the sentence
                int at = 4;
                int upto = Math.max(2, n * 4 / steps);
                StringBuilder p = new StringBuilder();
                for (int i = 0; i < upto; i++) {
                    if (i > 0) {
                        p.append(' ');
                    }
                    p.append(i == 0 ? w[0] + "x" : w[i]);
                }
                partials.add(at, p.toString());
            }

            boolean tail = words >= TOTAL_WORDS * 9 / 10;
            for (String p : partials) {
                long c0 = mx.getCurrentThreadCpuTime();
                long n0 = System.nanoTime();
                t.partial(p);
                long n1 = System.nanoTime();
                long c1 = mx.getCurrentThreadCpuTime();
                cpuTotal += c1 - c0;
                if (tail && n1 - n0 > worstTail) {
                    worstTail = n1 - n0;
                }
                events++;
                if (t.version() <= lastVersion && !p.equals(t.composing())) {
                    versionOk = false;
                }
                lastVersion = t.version();
            }
            long c0 = mx.getCurrentThreadCpuTime();
            long n0 = System.nanoTime();
            t.commit("");
            long n1 = System.nanoTime();
            long c1 = mx.getCurrentThreadCpuTime();
            cpuTotal += c1 - c0;
            if (tail && n1 - n0 > worstTail) {
                worstTail = n1 - n0;
            }
            events++;
            if (t.version() <= lastVersion) {
                versionOk = false;
            }
            lastVersion = t.version();
            words += n;
            sentenceNo++;
        }

        // (a) every committed sentence is still present, in order
        int next = 0;
        int size = t.size();
        for (int i = 0; i < size && next < sentences.size(); i++) {
            if (t.line(i).equals(sentences.get(next))) {
                next++;
            }
        }
        check("a: all " + sentences.size() + " sentences present in order, none erased",
                next == sentences.size() && size >= sentences.size(),
                "found " + next + "/" + sentences.size() + ", lines=" + size);

        // (b) no quadratic slowdown
        double cpuMs = cpuTotal / 1e6;
        double worstMs = worstTail / 1e6;
        check("b1: total CPU for " + events + " updates under 3 s", cpuMs < 3000.0,
                String.format("%.1f ms, words=%d", cpuMs, words));
        check("b2: worst single update on final 10% under 20 ms", worstMs < 20.0,
                String.format("%.3f ms", worstMs));

        // (c) heap growth
        long heapAfter = usedHeap();
        long growthMb = (heapAfter - heapBefore) / (1024 * 1024);
        check("c: heap growth under 64 MB", heapAfter - heapBefore < 64L * 1024 * 1024,
                growthMb + " MB, keep=" + t.size());

        // (d) window returns the last N lines plus composing, without copying history
        t.partial("tail composing words");
        int N = 50;
        StringBuilder exp = new StringBuilder();
        for (int i = size - N; i < size; i++) {
            exp.append(t.line(i)).append('\n');
        }
        exp.append(t.composing());
        boolean same = exp.toString().equals(t.window(N));
        check("d1: window(50) is exactly last 50 lines plus composing", same && t.size() == size,
                "len=" + t.window(N).length());
        int REPS = 20000;
        long w0 = System.nanoTime();
        int sink = 0;
        for (int i = 0; i < REPS; i++) {
            sink += t.window(N).length();
        }
        long fullNs = System.nanoTime() - w0;
        Transcript small = new Transcript();
        for (int i = 0; i < N + 5; i++) {
            small.partial(sentences.get(i));
            small.commit("");
        }
        small.partial("tail composing words");
        w0 = System.nanoTime();
        for (int i = 0; i < REPS; i++) {
            sink += small.window(N).length();
        }
        long smallNs = System.nanoTime() - w0;
        double perCallUs = fullNs / 1e3 / REPS;
        check("d2: window cost independent of history (" + size + " lines vs " + (N + 5) + ")",
                perCallUs < 200.0 && fullNs < smallNs * 20 + 20_000_000L,
                String.format("%.2f us/call full, %.2f us/call small, sink=%d", perCallUs,
                        smallNs / 1e3 / REPS, sink > 0 ? 1 : 0));
        check("d3: window(0) is only the composing line and window(huge) is everything",
                "tail composing words".equals(t.window(0))
                        && t.window(Integer.MAX_VALUE).split("\n", -1).length == size + 1,
                "ok");

        // (e) version only moves forward, and a no-op does not bump it
        long v = t.version();
        t.partial("");
        boolean still = v == t.version();
        check("e: version monotonic and unchanged by an empty guess", versionOk && still,
                "version=" + t.version());

        System.exit(fails == 0 ? 0 : 1);
    }
}
