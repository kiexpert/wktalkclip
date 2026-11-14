package app.wktalkclip;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * One long-lived microphone recorder for a listening session. Each gained block goes into the
 * AudioRing (memory only) and, while a pipe is attached, into the pipe write end that the
 * recognizer reads. The pipe write end is O_NONBLOCK and is written with Os.write, so nothing
 * here ever blocks while the pump lock is held. No files, no network, no logging.
 */
public class AudioPump implements Runnable {

    private static final int SAMPLE_RATE = 16000;
    private static final int BLOCK_SAMPLES = 1600; // 100 ms at 16 kHz
    private static final int SEND_SAMPLES = BLOCK_SAMPLES * 4; // pipe catch-up chunk, 400 ms
    private static final long MAX_BACKLOG = 32000L; // 2 s at 16 kHz = 64000 bytes, fits the pipe buffer
    private static final float GAIN_START = 4.0f;
    private static final float GAIN_MAX = 12.0f;
    private static final float GAIN_MIN = 0.1f;
    private static final int CEILING = 26000;
    private static final int FLOOR = 8000;

    public volatile boolean stopped;
    public volatile int lastLevel;
    public volatile boolean failed;

    private final AudioRing ring;
    private final short[] sendChunk = new short[SEND_SAMPLES];
    private final byte[] sendBytes = new byte[SEND_SAMPLES * 2];
    private final short[] halfSample = new short[1];
    private final byte[] halfByte = new byte[1];

    // Guarded by this: the attached pipe, if any, and the ring position of the next sample it needs.
    // halfSent: the low byte of sample pipePos is already in the pipe, its high byte is not yet.
    private ParcelFileDescriptor pipeFd;
    private long pipePos;
    private boolean halfSent;

    public AudioPump(AudioRing ring) {
        this.ring = ring;
    }

    private static AudioRecord newRecord() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 4, SAMPLE_RATE * 2);
        return new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
    }

    /** True when this device can open the microphone for the pipe path. */
    public static boolean canOpen() {
        AudioRecord rec = null;
        try {
            rec = newRecord();
            return rec.getState() == AudioRecord.STATE_INITIALIZED;
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (rec != null) {
                rec.release();
            }
        }
    }

    /**
     * Attaches a new pipe for the next recognizer session. The write end is made non-blocking;
     * if that fails the pipe is closed and recording continues. The backlog from fromPosition up
     * to the ring head is sent as far as the pipe accepts, then every live block follows. Any pipe
     * already attached is closed. Takes ownership of writeEnd.
     */
    public synchronized void attachPipe(ParcelFileDescriptor writeEnd, long fromPosition) {
        closePipeLocked();
        pipeFd = writeEnd;
        try {
            Os.fcntlInt(writeEnd.getFileDescriptor(), OsConstants.F_SETFL, OsConstants.O_NONBLOCK);
        } catch (ErrnoException e) {
            closePipeLocked();
            return;
        }
        pipePos = Math.max(0L, fromPosition);
        halfSent = false;
        sendLocked();
    }

    /** Closes the attached pipe write end. Recording continues into the ring. */
    public synchronized void detachPipe() {
        closePipeLocked();
    }

    @Override
    public void run() {
        AudioRecord rec = null;
        AutomaticGainControl agc = null;
        NoiseSuppressor ns = null;
        try {
            rec = newRecord();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                failed = true;
                return;
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(rec.getAudioSessionId());
                if (agc != null) {
                    agc.setEnabled(true);
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.getAudioSessionId());
                if (ns != null) {
                    ns.setEnabled(true);
                }
            }
            rec.startRecording();

            short[] block = new short[BLOCK_SAMPLES];
            float gain = GAIN_START;
            while (!stopped) {
                int n = rec.read(block, 0, BLOCK_SAMPLES);
                if (n <= 0) {
                    if (n < 0) {
                        failed = true;
                    }
                    break;
                }
                int peak = 0;
                for (int i = 0; i < n; i++) {
                    int a = Math.abs((int) block[i]);
                    if (a > peak) {
                        peak = a;
                    }
                }
                if (peak * gain > CEILING) {
                    gain *= 0.7f;
                } else if (peak * gain < FLOOR && gain < GAIN_MAX) {
                    gain *= 1.05f;
                }
                if (gain < GAIN_MIN) {
                    gain = GAIN_MIN;
                }
                float level = peak * gain;
                lastLevel = (int) Math.min(level, 32767f);

                for (int i = 0; i < n; i++) {
                    int v = Math.round(block[i] * gain);
                    if (v > 32767) {
                        v = 32767;
                    } else if (v < -32768) {
                        v = -32768;
                    }
                    block[i] = (short) v;
                }
                ring.write(block, n);
                synchronized (this) {
                    sendLocked();
                }
            }
        } catch (RuntimeException e) {
            failed = true;
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (IllegalStateException e) {
                    // not recording
                }
                rec.release();
            }
            if (agc != null) {
                agc.release();
            }
            if (ns != null) {
                ns.release();
            }
            detachPipe();
        }
    }

    // Caller holds the lock. Never blocks (the pipe is O_NONBLOCK). Sends ring samples from pipePos
    // up to the head. EAGAIN leaves pipePos where it is, so the next round resends from there.
    // A write that ends on an odd byte leaves halfSent set: the sample's high byte goes out first
    // next round, so the recognizer's byte stream never shifts.
    private void sendLocked() {
        if (pipeFd == null) {
            return;
        }
        FileDescriptor fd = pipeFd.getFileDescriptor();
        try {
            if (halfSent && !finishHalfLocked(fd)) {
                return;
            }
            long head = ring.totalWritten();
            long oldest = head - AudioRing.RING_SAMPLES;
            if (pipePos < oldest) {
                pipePos = oldest;
            }
            if (head - pipePos > MAX_BACKLOG) {
                pipePos = head - MAX_BACKLOG;
            }
            while (true) {
                int n = ring.readFrom(pipePos, sendChunk);
                if (n <= 0) {
                    return;
                }
                for (int i = 0; i < n; i++) {
                    int v = sendChunk[i];
                    sendBytes[2 * i] = (byte) (v & 0xff);
                    sendBytes[2 * i + 1] = (byte) ((v >> 8) & 0xff);
                }
                int want = n * 2;
                int w = Os.write(fd, sendBytes, 0, want);
                pipePos += w / 2;
                if (w % 2 != 0) {
                    halfSent = true;
                    return;
                }
                if (w < want) {
                    return;
                }
            }
        } catch (ErrnoException e) {
            if (e.errno != OsConstants.EAGAIN) {
                // Recognizer closed its end or the pipe is broken: detach only, keep recording.
                closePipeLocked();
            }
        } catch (InterruptedIOException e) {
            // try again on the next round
        }
    }

    // Caller holds the lock. Sends the high byte of the sample whose low byte already went out.
    // Returns true when that byte was accepted.
    private boolean finishHalfLocked(FileDescriptor fd) throws ErrnoException, InterruptedIOException {
        if (ring.readFrom(pipePos, halfSample) <= 0) {
            return false;
        }
        halfByte[0] = (byte) ((halfSample[0] >> 8) & 0xff);
        if (Os.write(fd, halfByte, 0, 1) < 1) {
            return false;
        }
        pipePos += 1;
        halfSent = false;
        return true;
    }

    // Caller holds the lock.
    private void closePipeLocked() {
        halfSent = false;
        closeQuietly(pipeFd);
        pipeFd = null;
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
