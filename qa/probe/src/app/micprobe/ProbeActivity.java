package app.micprobe;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;

public class ProbeActivity extends Activity {
    private static final String TAG = "MICPROBE";
    private static final int RATE = 16000;
    private static final long DURATION_MS = 5000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    probe();
                } catch (Throwable t) {
                    Log.i(TAG, "MICPROBE error=" + t);
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            finish();
                        }
                    });
                }
            }
        }, "micprobe").start();
    }

    private void probe() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Log.i(TAG, "MICPROBE error=" + min);
            return;
        }
        int bufBytes = min * 4;
        AudioRecord record = null;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "MICPROBE error=" + record.getState());
                return;
            }
            record.startRecording();
            short[] buf = new short[bufBytes / 2];
            long end = System.currentTimeMillis() + DURATION_MS;
            int peak = 0;
            double sumSq = 0;
            long n = 0;
            int err = 0;
            while (System.currentTimeMillis() < end) {
                int r = record.read(buf, 0, buf.length);
                if (r < 0) {
                    err = r;
                    break;
                }
                for (int i = 0; i < r; i++) {
                    int a = Math.abs((int) buf[i]);
                    if (a > peak) {
                        peak = a;
                    }
                    sumSq += (double) buf[i] * buf[i];
                }
                n += r;
            }
            if (err != 0) {
                Log.i(TAG, "MICPROBE error=" + err);
                return;
            }
            double rms = n > 0 ? Math.sqrt(sumSq / n) : 0;
            Log.i(TAG, "peak=" + peak + " rms=" + rms + " samples=" + n
                    + " state=" + record.getState());
        } finally {
            if (record != null) {
                try {
                    record.stop();
                } catch (IllegalStateException ignored) {
                }
                record.release();
            }
        }
    }
}
