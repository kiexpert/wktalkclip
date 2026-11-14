package app.wktalkclip;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * Writes one recovered span (16 kHz mono PCM16, little endian) into a pipe write end from its own
 * thread, then closes the write end so the recognizer sees end of input. A broken pipe (the
 * recognizer closed its read end) just ends the write.
 */
public class SpanWriter implements Runnable {

    private final ParcelFileDescriptor writeEnd;
    private final byte[] bytes;

    public SpanWriter(ParcelFileDescriptor writeEnd, short[] samples, int n) {
        this.writeEnd = writeEnd;
        this.bytes = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            bytes[2 * i] = (byte) (samples[i] & 0xff);
            bytes[2 * i + 1] = (byte) ((samples[i] >> 8) & 0xff);
        }
    }

    @Override
    public void run() {
        try {
            int off = 0;
            while (off < bytes.length) {
                int w = Os.write(writeEnd.getFileDescriptor(), bytes, off, bytes.length - off);
                if (w <= 0) {
                    break;
                }
                off += w;
            }
        } catch (ErrnoException e) {
            // recognizer closed its end: stop writing
        } catch (InterruptedIOException e) {
            // stop writing
        } finally {
            try {
                writeEnd.close();
            } catch (IOException e) {
                // already closed
            }
        }
    }
}
