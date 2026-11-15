package app.wktalkclip;

/** The newest events of the recognizer, kept in memory only, for the diagnostics screen. */
public final class EventLog {
    private final String[] ring;
    private int next = 0;
    private int count = 0;

    public EventLog(int capacity) {
        ring = new String[Math.max(1, capacity)];
    }

    /** Adds one event; elapsedMs is the time since the session started. */
    public synchronized void add(long elapsedMs, String text) {
        long s = Math.max(0L, elapsedMs) / 1000L;
        long ms = Math.max(0L, elapsedMs) % 1000L;
        String stamp = String.format("%02d:%02d.%03d ", s / 60L, s % 60L, ms);
        ring[next] = stamp + text;
        next = (next + 1) % ring.length;
        count++;
    }

    /** Total events ever added. */
    public synchronized int count() {
        return count;
    }

    /** The newest up to n events, oldest first. */
    public synchronized String[] last(int n) {
        int have = Math.min(Math.min(count, ring.length), Math.max(0, n));
        String[] out = new String[have];
        for (int i = 0; i < have; i++) {
            int idx = ((next - have + i) % ring.length + ring.length) % ring.length;
            out[i] = ring[idx];
        }
        return out;
    }
}
