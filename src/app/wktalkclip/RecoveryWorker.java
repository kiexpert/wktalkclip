package app.wktalkclip;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Recovers failed spans of audio beside the live loop. Owns a second on-device recognizer (not
 * segmented) and handles one span at a time from a queue of at most MAX_QUEUE spans. Each span is
 * copied out of the AudioRing, written into a pipe by a SpanWriter thread, and its text goes to the
 * RecoveryListener. Construct and call from the main thread only. No files, no network, no logging.
 */
public class RecoveryWorker implements RecognitionListener, Runnable {

    private static final int MAX_QUEUE = 10;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000L;
    private static final int SAMPLE_RATE = 16000;

    private final AudioRing ring;
    private final RecoveryListener listener;
    private final Handler handler;
    private final ArrayList<long[]> queue = new ArrayList<long[]>();
    private final SpeechRecognizer recognizer;

    // The span in flight: [curStart, curEnd) in ring samples.
    private boolean busy;
    private boolean retryPending;
    private long curStart;
    private long curEnd;
    private int retries;
    private ParcelFileDescriptor readEnd;

    public RecoveryWorker(Context context, AudioRing ring, RecoveryListener listener) {
        this.ring = ring;
        this.listener = listener;
        this.handler = new Handler(Looper.getMainLooper());
        this.recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
        this.recognizer.setRecognitionListener(this);
    }

    /** Queues the ring samples [start, end) for recovery. Drops the oldest span when full. */
    public void enqueue(long start, long end) {
        if (end <= start) {
            return;
        }
        if (queue.size() >= MAX_QUEUE) {
            queue.remove(0);
        }
        queue.add(new long[] { start, end });
        startNext();
    }

    /** Stops everything: clears the queue, releases the pipe and destroys the recognizer. */
    public void destroy() {
        handler.removeCallbacks(this);
        queue.clear();
        busy = false;
        retryPending = false;
        releasePipe();
        recognizer.destroy();
    }

    /** Scheduled retry, or the step to the next queued span. */
    @Override
    public void run() {
        if (retryPending) {
            retryPending = false;
            startCurrent();
            return;
        }
        startNext();
    }

    private void startNext() {
        if (busy || queue.isEmpty()) {
            return;
        }
        long[] span = queue.remove(0);
        curStart = span[0];
        curEnd = span[1];
        retries = 0;
        busy = true;
        startCurrent();
    }

    private void startCurrent() {
        busy = true;
        short[] data = new short[(int) (curEnd - curStart)];
        int n = ring.readFrom(curStart, data);
        if (n <= 0) {
            finishCurrent();
            return;
        }
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            finishCurrent();
            return;
        }
        readEnd = pipe[0];
        recognizer.startListening(buildIntent(readEnd));
        Thread writer = new Thread(new SpanWriter(pipe[1], data, n), "span-writer");
        writer.start();
    }

    private static Intent buildIntent(ParcelFileDescriptor source) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source);
        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE);
        return i;
    }

    // Ends the span in flight and lets the posted run() start the next one.
    private void finishCurrent() {
        busy = false;
        retryPending = false;
        releasePipe();
        handler.post(this);
    }

    private void releasePipe() {
        if (readEnd == null) {
            return;
        }
        try {
            readEnd.close();
        } catch (IOException e) {
            // already closed
        }
        readEnd = null;
    }

    private static String firstMatch(Bundle b) {
        if (b == null) {
            return "";
        }
        ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        return matches.get(0).trim();
    }

    // ---- RecognitionListener ----------------------------------------------------

    @Override
    public void onResults(Bundle results) {
        if (!busy || retryPending) {
            return;
        }
        long start = curStart;
        String text = firstMatch(results);
        finishCurrent();
        if (text.length() > 0) {
            listener.recovered(start, text);
        }
    }

    @Override
    public void onError(int error) {
        if (!busy || retryPending) {
            return;
        }
        if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                || error == SpeechRecognizer.ERROR_CLIENT) && retries < MAX_RETRIES) {
            retries++;
            releasePipe();
            retryPending = true;
            handler.postDelayed(this, RETRY_DELAY_MS);
            return;
        }
        finishCurrent();
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }
}
