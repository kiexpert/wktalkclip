package app.wktalkclip;

/** Retry timing for a recognizer that keeps failing: never give up, wait a little longer each time. */
public final class Backoff {
    private static final long FIRST_MS = 200L;
    private static final long MAX_MS = 30000L;

    private Backoff() {
    }

    /** Delay before retry number n (0 = first retry): 200 ms, doubling, at most 30 s. */
    public static long delayMs(int consecutiveFailures) {
        int n = Math.max(0, Math.min(consecutiveFailures, 20));
        long d = FIRST_MS << n;
        return Math.min(d, MAX_MS);
    }

    /** A forced restart is allowed only when the last one is at least minGapMs ago. */
    public static boolean mayRestart(long nowMs, long lastRestartMs, long minGapMs) {
        return lastRestartMs == 0L || nowMs - lastRestartMs >= minGapMs;
    }
}
