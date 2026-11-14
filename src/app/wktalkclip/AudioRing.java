package app.wktalkclip;

/**
 * Memory-only ring of the most recent 30 seconds of 16 kHz mono PCM.
 * Positions are absolute sample counts since the ring was created; only the
 * newest RING_SAMPLES samples are kept. No files, no network, no logging.
 */
public class AudioRing {

    public static final int RING_SAMPLES = 480000; // 30 s at 16 kHz

    private final short[] buf = new short[RING_SAMPLES];
    private long written = 0L;

    /** Appends the first n samples of src. */
    public synchronized void write(short[] src, int n) {
        int idx = (int) (written % RING_SAMPLES);
        for (int i = 0; i < n; i++) {
            buf[idx] = src[i];
            idx++;
            if (idx == RING_SAMPLES) {
                idx = 0;
            }
        }
        written += n;
    }

    /** Absolute count of samples ever written (the head position). */
    public synchronized long totalWritten() {
        return written;
    }

    /**
     * Copies samples starting at absolute position into dst. The start is clamped to the
     * oldest sample still held. Returns the number of samples copied, at most dst.length;
     * 0 when position is at or past the head.
     */
    public synchronized int readFrom(long position, short[] dst) {
        long oldest = Math.max(0L, written - RING_SAMPLES);
        long from = Math.max(position, oldest);
        if (from >= written) {
            return 0;
        }
        int count = (int) Math.min(dst.length, written - from);
        int idx = (int) (from % RING_SAMPLES);
        for (int i = 0; i < count; i++) {
            dst[i] = buf[idx];
            idx++;
            if (idx == RING_SAMPLES) {
                idx = 0;
            }
        }
        return count;
    }
}
