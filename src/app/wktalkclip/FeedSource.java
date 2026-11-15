package app.wktalkclip;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Test-only audio source: plays a prerecorded 16 kHz mono 16-bit PCM WAV into a pipe in real time so
 * the on-device recognizer can hear it through EXTRA_AUDIO_SOURCE instead of the microphone. The
 * clip loops with a short silence between repeats until the pipe closes or stop() is called.
 * Read only: no files are created, no network, nothing is logged.
 */
public final class FeedSource {

    private static final int RATE = 16000;
    private static final int TICK_BYTES = 3200;   // 100 ms of 16-bit mono at 16 kHz
    private static final long TICK_MS = 100L;
    private static final int SILENCE_BYTES = 48000; // 1.5 s
    private static final int MAX_PCM_BYTES = 16 * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static volatile boolean stopped;
    private static Thread current;
    private static ParcelFileDescriptor currentWrite;

    private FeedSource() {
    }

    /** The clip file in this app's own external files dir, or null when it is not there. */
    public static File clipFile(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) {
            return null;
        }
        File f = new File(dir, "feed.wav");
        return f.isFile() ? f : null;
    }

    /**
     * Starts feeding the clip and returns the read end of the pipe for the intent, or null when the
     * file is not a PCM 16-bit mono 16000 Hz WAV. onDone (may be null) runs once on the feeder thread
     * when feeding ends.
     */
    public static ParcelFileDescriptor open(File wav, final Runnable onDone) {
        final byte[] pcm = readPcm(wav);
        if (pcm == null || pcm.length == 0) {
            return null;
        }
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            return null;
        }
        stop();
        final ParcelFileDescriptor writeEnd = pipe[1];
        stopped = false;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                feed(writeEnd, pcm);
                if (onDone != null) {
                    onDone.run();
                }
            }
        }, "feed-source");
        t.setDaemon(true);
        synchronized (LOCK) {
            current = t;
            currentWrite = writeEnd;
        }
        t.start();
        return pipe[0];
    }

    /** Stops feeding and closes the write end. Safe to call at any time. */
    public static void stop() {
        stopped = true;
        ParcelFileDescriptor w;
        synchronized (LOCK) {
            w = currentWrite;
            currentWrite = null;
            current = null;
        }
        if (w != null) {
            try {
                w.close();
            } catch (IOException e) {
                // already closed
            }
        }
    }

    private static void feed(ParcelFileDescriptor writeEnd, byte[] pcm) {
        OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd);
        byte[] tick = new byte[TICK_BYTES];
        long next = System.nanoTime();
        try {
            while (!stopped) {
                next = writePaced(out, pcm, pcm.length, tick, next);
                next = writePaced(out, null, SILENCE_BYTES, tick, next);
            }
        } catch (IOException e) {
            // reader closed or pipe broken: stop feeding
        } catch (InterruptedException e) {
            // stop requested
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                // already closed
            }
        }
    }

    // Writes len bytes (pcm, or zeros when pcm is null) in 100 ms ticks; returns the next tick time.
    private static long writePaced(OutputStream out, byte[] pcm, int len, byte[] tick, long next)
            throws IOException, InterruptedException {
        int pos = 0;
        while (pos < len) {
            if (stopped) {
                throw new InterruptedException();
            }
            int n = Math.min(TICK_BYTES, len - pos);
            if (pcm != null) {
                System.arraycopy(pcm, pos, tick, 0, n);
            } else {
                java.util.Arrays.fill(tick, 0, n, (byte) 0);
            }
            out.write(tick, 0, n);
            out.flush();
            pos += n;
            next += TICK_MS * 1000000L;
            long sleepMs = (next - System.nanoTime()) / 1000000L;
            if (sleepMs > 0) {
                Thread.sleep(sleepMs);
            } else if (sleepMs < -1000) {
                next = System.nanoTime();
            }
        }
        return next;
    }

    // Returns the PCM bytes of a 16 kHz mono 16-bit WAV, or null if the format differs.
    private static byte[] readPcm(File wav) {
        InputStream in = null;
        try {
            in = new FileInputStream(wav);
            byte[] h = new byte[44];
            if (!readFully(in, h)) {
                return null;
            }
            if (!tag(h, 0, "RIFF") || !tag(h, 8, "WAVE") || !tag(h, 12, "fmt ")) {
                return null;
            }
            int fmtLen = le32(h, 16);
            int format = le16(h, 20);
            int channels = le16(h, 22);
            int rate = le32(h, 24);
            int bits = le16(h, 34);
            if (fmtLen != 16 || format != 1 || channels != 1 || rate != RATE || bits != 16) {
                return null;
            }
            if (!tag(h, 36, "data")) {
                return null;
            }
            int declared = le32(h, 40);
            long avail = wav.length() - 44;
            long size = (declared <= 0 || declared > avail) ? avail : declared;
            if (size <= 0 || size > MAX_PCM_BYTES) {
                return null;
            }
            size &= ~1L;
            byte[] pcm = new byte[(int) size];
            if (!readFully(in, pcm)) {
                return null;
            }
            return pcm;
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    private static boolean tag(byte[] b, int off, String s) {
        for (int i = 0; i < 4; i++) {
            if (b[off + i] != (byte) s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int off) {
        return le16(b, off) | (le16(b, off + 2) << 16);
    }
}
