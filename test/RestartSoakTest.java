import app.wktalkclip.Backoff;
import app.wktalkclip.MeetingPause;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Deterministic 3-hour simulated run of the recognizer restart loop (fake clock, no sleeping). */
public class RestartSoakTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    static final long TICK = 100L;
    static final long START = 1000L;
    static final long SECONDS = 3L * 3600L;
    static final long END = START + SECONDS * 1000L;

    static boolean clash(List<long[]> list, long s, long e, long margin) {
        for (long[] x : list) {
            if (s < x[1] + margin && e > x[0] - margin) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        Random r = new Random(20260101L);
        boolean[] bad = new boolean[(int) SECONDS + 2];
        for (int i = 0; i < bad.length; i++) {
            bad[i] = r.nextInt(100) < 3;
        }
        // 5 calls of 2-10 minutes, one slot each (seconds from session start).
        List<long[]> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long s = i * 2000L + 300 + r.nextInt(800);
            long d = 120 + r.nextInt(481);
            calls.add(new long[]{s, s + d});
        }
        List<long[]> pauses = new ArrayList<>(calls);
        // Focus losses: one overlapping the end of call 1 (both at once), then four on their own.
        List<long[]> focus = new ArrayList<>();
        long[] c1 = calls.get(1);
        focus.add(new long[]{c1[0] + 20, c1[1] + 30});
        pauses.add(focus.get(0));
        while (focus.size() < 5) {
            long s = 200 + r.nextInt(10000);
            long d = 30 + r.nextInt(61);
            if (!clash(pauses, s, s + d, 150)) {
                long[] f = new long[]{s, s + d};
                focus.add(f);
                pauses.add(f);
            }
        }
        // 6 bursts of 60 s of consecutive failures, away from every pause.
        List<long[]> bursts = new ArrayList<>();
        while (bursts.size() < 6) {
            long s = 200 + r.nextInt(9800);
            if (!clash(pauses, s, s + 60, 150) && !clash(bursts, s, s + 60, 150)) {
                bursts.add(new long[]{s, s + 60});
                for (long k = s; k < s + 60; k++) {
                    bad[(int) k] = true;
                }
            }
        }

        // Events: {timeMs, kind} 0 call on, 1 call off, 2 focus lost, 3 focus gained.
        List<long[]> ev = new ArrayList<>();
        for (long[] c : calls) {
            ev.add(new long[]{START + c[0] * 1000, 0});
            ev.add(new long[]{START + c[1] * 1000, 1});
        }
        for (long[] f : focus) {
            ev.add(new long[]{START + f[0] * 1000, 2});
            ev.add(new long[]{START + f[1] * 1000, 3});
        }
        ev.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
        int evi = 0;

        MeetingPause pause = new MeetingPause();
        boolean active = false;
        boolean wasPaused = false;
        boolean pausedPrev = false;
        long clearAt = 0L;
        int failures = 0;
        long nextRetry = START;
        long lastRestart = 0L;
        int restarts = 0;
        List<Long> attempts = new ArrayList<>();
        long outageStart = -1L;
        long lastWorking = START;
        long worstOutageMs = 0L;
        boolean outageOk = true;
        boolean resumeOk = true;
        long worstResume = 0L;
        int resumes = 0;
        boolean waitingResumeAttempt = false;
        long nextForced = START + 600000L;

        for (long now = START; now < END; now += TICK) {
            while (evi < ev.size() && ev.get(evi)[0] <= now) {
                int k = (int) ev.get(evi)[1];
                if (k == 0) pause.onCallMode(true);
                if (k == 1) pause.onCallMode(false);
                if (k == 2) pause.onFocus(false);
                if (k == 3) pause.onFocus(true);
                evi++;
            }
            boolean paused = !pause.shouldListen(true);
            if (pausedPrev && !paused) {
                clearAt = now;
            }
            pausedPrev = paused;
            int sec = (int) ((now - START) / 1000L);
            boolean attemptedNow = false;

            if (paused) {
                active = false;
                wasPaused = true;
                failures = 0;
                nextRetry = now;
                waitingResumeAttempt = false;
                if (outageStart >= 0) {
                    outageStart = -1L;
                }
                continue;
            }
            if (pause.needsResume(wasPaused)) {
                wasPaused = false;
                waitingResumeAttempt = true;
                nextRetry = now;
            }
            if (active && bad[sec]) {
                active = false;
                nextRetry = now + Backoff.delayMs(failures);
                failures++;
            }
            if (active && now >= nextForced) {
                nextForced = now + 600000L;
                if (Backoff.mayRestart(now, lastRestart, 5000L)) {
                    active = false;
                    nextRetry = now;
                }
            }
            if (!active && now >= nextRetry) {
                if (!waitingResumeAttempt && !Backoff.mayRestart(now, lastRestart, TICK)) {
                    // at most one attempt per tick
                } else {
                    restarts++;
                    attempts.add(now);
                    lastRestart = now;
                    attemptedNow = true;
                    if (waitingResumeAttempt) {
                        waitingResumeAttempt = false;
                        long lat = now - clearAt;
                        worstResume = Math.max(worstResume, lat);
                        resumes++;
                        if (lat > 5000L) {
                            resumeOk = false;
                        }
                    }
                    if (bad[sec]) {
                        nextRetry = now + Backoff.delayMs(failures);
                        failures++;
                    } else {
                        active = true;
                        failures = 0;
                    }
                }
            }
            if (active) {
                lastWorking = now;
                if (outageStart >= 0) {
                    long len = now - outageStart;
                    worstOutageMs = Math.max(worstOutageMs, len);
                    long allowed = 60000L;
                    long sS = (outageStart - START) / 1000L - 1;
                    long eS = (now - START) / 1000L + 1;
                    for (long[] b : bursts) {
                        if (sS <= b[1] && eS >= b[0]) {
                            allowed = 60000L + 60000L;
                        }
                    }
                    if (len >= allowed) {
                        outageOk = false;
                        System.out.println("long outage " + len + " ms at " + (outageStart - START) / 1000 + " s");
                    }
                    outageStart = -1L;
                }
            } else if (outageStart < 0) {
                outageStart = now;
            }
        }

        // Tight loop: most restart attempts inside any 1 s window.
        int worstWindow = 0;
        for (int i = 0; i < attempts.size(); i++) {
            int n = 0;
            for (int j = i; j < attempts.size() && attempts.get(j) - attempts.get(i) < 1000L; j++) {
                n++;
            }
            worstWindow = Math.max(worstWindow, n);
        }

        boolean listeningAtEnd = pause.shouldListen(true) && active && END - lastWorking <= 60000L;
        System.out.println("restarts=" + restarts + " worstOutageMs=" + worstOutageMs
                + " resumes=" + resumes + " worstResumeMs=" + worstResume + " worstWindow=" + worstWindow);
        check("(a) still listening at hour 3", listeningAtEnd);
        check("(b) total restarts under 2000", restarts < 2000);
        check("(c) no working-recognizer gap over 60 s after a burst ends (plus burst length)", outageOk);
        check("(d) every pause (calls " + calls.size() + ") resumed within 5 s", resumeOk && resumes >= calls.size());
        check("(e) never more than 3 restart attempts in any 1 s window", worstWindow <= 3);
        System.exit(fails == 0 ? 0 : 1);
    }
}
