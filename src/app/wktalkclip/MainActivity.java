package app.wktalkclip;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * On-device meeting dictation (v1.9, direct microphone only). Speech is recognized by the
 * phone itself with its own microphone input. The transcript lives only in memory.
 *
 * Append-only display: a character shown on screen is never removed or replaced. The grey
 * composing line only grows while the recognizer extends its guess. When the recognizer
 * revises a guess, the old text stays as a line and ONE diff line shows only the difference.
 * The clear button is the only way lines are removed.
 *
 * A stall watchdog restarts the recognizer when it stops reporting events while the phone
 * still hears sound, or when it has been silent for too long.
 */
public class MainActivity extends Activity
        implements View.OnClickListener, Runnable, RecognitionListener, Handler.Callback {

    private static final int REQ_RECORD_AUDIO = 1;
    private static final long CLEAR_ARM_MS = 3000L;
    private static final long RESTART_DELAY_MS = 40L;
    private static final long RESULT_RESTART_DELAY_MS = 60L;
    private static final long RMS_RENDER_INTERVAL_MS = 100L;
    private static final long ERROR_WINDOW_MS = 60000L;
    private static final int ERROR_LIMIT = 20;
    private static final long SEGMENT_SILENCE_MS = 600000L;
    // Stall watchdog: a tick every 200 ms while listening.
    private static final int WHAT_WATCHDOG = 1;
    private static final long WATCHDOG_TICK_MS = 200L;
    // Restart when no recognizer event for 2 s and sound was heard in the last 2 s,
    // or when no recognizer event for 15 s at all.
    private static final long STALL_WITH_SOUND_MS = 2000L;
    private static final long STALL_ANY_MS = 15000L;
    private static final int[] MUTED_STREAMS = {
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM, AudioManager.STREAM_RING };

    // Finished lines. Only onClearPressed removes them.
    private final ArrayList<String> lines = new ArrayList<String>();
    // The same sentences as one clipboard entry for this screen. Written on pause or stop, replaced
    // each time, so the clipboard history gets one entry per screen and not one per sentence.
    private final ScreenClip screenClip = new ScreenClip();
    private final LangChoice lang = new LangChoice();
    // Number of clipboard writes this run, shown in the debug line so one entry per screen can be checked.
    private int clipWrites = 0;
    // Composing (grey) line: the guess that is still being extended. Append-only.
    private String shown = "";
    // Partial results since the last commit, shown in the debug marker.
    private int partialCount = 0;

    private boolean listening = false;
    private boolean clearArmed = false;
    private boolean restartPending = false;
    // True from a successful startListening() until the recognizer reports
    // results, an error or the end of a segmented session.
    private boolean sessionRunning = false;

    private long lastRmsRenderMs = 0L;
    private long errorWindowStart = 0L;
    private int errorCount = 0;

    // Event trace of the recognizer, shown on the second status line.
    private String lastEvent = "none";
    private long lastEventMs = 0L;
    // Last time the input showed sound (rms bars).
    private long lastSoundMs = 0L;
    private int restartCount = 0;

    // Direct microphone only: the recognizer records its own audio. The pipe path is off.
    private boolean usePipe = false;

    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private Handler handler;

    // Never created in direct-mic mode. Every use is null-checked.
    private RecoveryWorker recovery;

    // Start-beep mute, active for the whole session.
    private AudioManager audio;
    private boolean muted = false;

    private Button startButton;
    private Button copyButton;
    private Button clearButton;
    private Button langButton;
    private Button downloadButton;
    private TextView statusView;
    private TextView eventView;
    private TextView transcriptView;
    private ScrollView scrollView;

    @Override
    public void run() {
        if (restartPending) {
            restartPending = false;
            if (listening && !sessionRunning) {
                startSegment();
            }
            return;
        }
        clearArmed = false;
        clearButton.setText("지우기");
    }

    @Override
    public void onClick(View v) {
        if (v == startButton) {
            onStartStopPressed();
        } else if (v == copyButton) {
            copyAll();
        } else if (v == clearButton) {
            onClearPressed();
        } else if (v == langButton) {
            onLangPressed();
        } else if (v == downloadButton) {
            onDownloadPressed();
        }
    }

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (!(prev instanceof CrashShow)) {
            Thread.setDefaultUncaughtExceptionHandler(new CrashShow(prev));
        }
        handler = new Handler(getMainLooper(), this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        buildUi();
        setStatus("시작 버튼을 누르면 받아써요.");
        String crash = CrashShow.lastCrash;
        if (crash != null) {
            if (crash.length() > 300) {
                crash = crash.substring(0, 300);
            }
            setStatus("이전 오류: " + crash);
        }
    }

    private void buildUi() {
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        startButton = makeButton("시작");
        startButton.setOnClickListener(this);

        copyButton = makeButton("전체 복사");
        copyButton.setOnClickListener(this);

        clearButton = makeButton("지우기");
        clearButton.setOnClickListener(this);

        langButton = makeButton(lang.label());
        langButton.setOnClickListener(this);

        row.addView(startButton, weightParams());
        row.addView(copyButton, weightParams());
        row.addView(clearButton, weightParams());
        row.addView(langButton, weightParams());
        root.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, pad, 0, pad / 2);
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        eventView = new TextView(this);
        eventView.setTextSize(12);
        eventView.setTextColor(Color.GRAY);
        root.addView(eventView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        downloadButton = makeButton("음성 모델 받기");
        downloadButton.setVisibility(View.GONE);
        downloadButton.setOnClickListener(this);
        root.addView(downloadButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        transcriptView = new TextView(this);
        transcriptView.setTextSize(18);
        transcriptView.setPadding(0, pad / 2, 0, pad / 2);

        scrollView = new ScrollView(this);
        scrollView.addView(transcriptView);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        render();
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private LinearLayout.LayoutParams weightParams() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void setStatus(String text) {
        statusView.setText(text);
    }

    // Second status line: the recognizer trace. Refreshed by onRmsChanged and the watchdog tick.
    private void updateEventLine() {
        eventView.setText("상태: " + lastEvent + " / running=" + sessionRunning
                + " / clips=" + clipWrites + " / lang=" + lang.tag() + " / pipe=" + usePipe + " / restarts=" + restartCount);
    }

    // Records the last callback of the recognizer and when it arrived.
    private void noteEvent(String name) {
        lastEvent = name;
        lastEventMs = System.currentTimeMillis();
    }

    // ---- start / stop -------------------------------------------------------

    private void onStartStopPressed() {
        try {
            if (listening) {
                endSession("멈췄어요.");
                return;
            }
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                setStatus("이 폰에는 기기 안 음성 인식기가 없어요.");
                return;
            }
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { android.Manifest.permission.RECORD_AUDIO },
                        REQ_RECORD_AUDIO);
                return;
            }
            beginSession();
        } catch (RuntimeException e) {
            setStatus("오류: " + e);
        }
    }

    private void beginSession() {
        listening = true;
        restartCount = 0;
        usePipe = false;
        lastEvent = "none";
        lastEventMs = System.currentTimeMillis();
        lastSoundMs = 0L;
        shown = "";
        partialCount = 0;
        downloadButton.setVisibility(View.GONE);
        startButton.setText("멈추기");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        muteBeeps();
        setStatus("회의 중에는 폰 소리를 꺼 둬요");
        startSegment();
        scheduleWatchdog();
    }

    private void endSession(String message) {
        restartPending = false;
        handler.removeMessages(WHAT_WATCHDOG);
        commitShown("");
        listening = false;
        // Drop the recognizer instead of only stopping it: a recognizer that is still finishing
        // the old session answers the next start with busy/client errors and nothing is heard.
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
            recognizer = null;
        }
        sessionRunning = false;
        flushClip();
        unmuteBeeps();
        startButton.setText("시작");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setStatus(message);
        render();
    }

    // ---- beep mute ------------------------------------------------------------

    private void muteBeeps() {
        if (muted || audio == null) {
            return;
        }
        muted = true;
        for (int s : MUTED_STREAMS) {
            try {
                audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0);
            } catch (SecurityException e) {
                // platform refused this stream: leave it
            }
        }
    }

    private void unmuteBeeps() {
        if (!muted || audio == null) {
            return;
        }
        muted = false;
        for (int s : MUTED_STREAMS) {
            try {
                audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0);
            } catch (SecurityException e) {
                // platform refused this stream: leave it
            }
        }
    }

    // ---- recognizer sessions --------------------------------------------------

    // Starts one recognizer session on the microphone. Returns when a session is still running.
    private void startSegment() {
        if (sessionRunning) {
            return;
        }
        ensureRecognizer();
        listenOnce();
    }

    private void ensureRecognizer() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
        }
        if (recognizerIntent == null) {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag());
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            if (Build.VERSION.SDK_INT >= 33) {
                i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true);
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        SEGMENT_SILENCE_MS);
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        SEGMENT_SILENCE_MS);
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        SEGMENT_SILENCE_MS);
            }
            recognizerIntent = i;
        }
    }

    // Single start point. Returns without starting when a session is still running.
    private void listenOnce() {
        if (sessionRunning) {
            return;
        }
        try {
            recognizer.startListening(recognizerIntent);
        } catch (RuntimeException e) {
            setStatus("마이크를 열지 못했어요: " + e.getClass().getSimpleName());
            noteEvent("startFail");
            return;
        }
        sessionRunning = true;
        setStatus("듣는 중이에요. 말씀하세요.");
    }

    // ---- stall watchdog -------------------------------------------------------

    // Handler.Callback: the watchdog tick is a message, not the Runnable path.
    @Override
    public boolean handleMessage(Message msg) {
        if (msg.what != WHAT_WATCHDOG) {
            return false;
        }
        watchdogTick();
        return true;
    }

    private void scheduleWatchdog() {
        handler.removeMessages(WHAT_WATCHDOG);
        handler.sendEmptyMessageDelayed(WHAT_WATCHDOG, WATCHDOG_TICK_MS);
    }

    // Runs every 200 ms while listening. Restarts the recognizer when it has gone quiet
    // while sound is present, or when it has gone quiet for too long regardless.
    private void watchdogTick() {
        if (!listening) {
            return;
        }
        long now = System.currentTimeMillis();
        long quiet = now - lastEventMs;
        boolean soundRecent = now - lastSoundMs < STALL_WITH_SOUND_MS;
        boolean stalled = quiet > STALL_ANY_MS || (quiet > STALL_WITH_SOUND_MS && soundRecent);
        if (stalled && !restartPending && recognizer != null) {
            forceRestart();
        }
        updateEventLine();
        scheduleWatchdog();
    }

    // Drops the stalled recognizer and starts a fresh session. The composing text is committed
    // as a line marked as a stall, so nothing heard is hidden.
    private void forceRestart() {
        restartCount++;
        noteEvent("stall");
        recognizer.cancel();
        recognizer.destroy();
        recognizer = null;
        sessionRunning = false;
        commitShown(" …(stall)");
        render();
        startSegment();
    }

    // ---- buttons ------------------------------------------------------------

    private void copyAll() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "복사할 내용이 없어요.", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("회의 받아쓰기", sb.toString()));
        Toast.makeText(this, "복사했어요", Toast.LENGTH_SHORT).show();
    }

    private void onClearPressed() {
        if (!clearArmed) {
            clearArmed = true;
            clearButton.setText("한 번 더 누르면 지워요");
            handler.postDelayed(this, CLEAR_ARM_MS);
            return;
        }
        if (!restartPending) {
            handler.removeCallbacks(this);
        }
        clearArmed = false;
        clearButton.setText("지우기");
        lines.clear();
        screenClip.clear();
        shown = "";
        partialCount = 0;
        render();
        setStatus("지웠어요.");
    }

    // Switches Korean <-> English. The recognizer is rebuilt for the new language; a running
    // session keeps listening with it. Text already shown stays.
    private void onLangPressed() {
        lang.toggle();
        langButton.setText(lang.label());
        recognizerIntent = null;
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
            recognizer = null;
        }
        sessionRunning = false;
        if (listening) {
            commitShown("");
            render();
            startSegment();
        }
        setStatus("인식 언어: " + lang.label());
    }

    private void onDownloadPressed() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        ensureRecognizer();
        recognizer.triggerModelDownload(recognizerIntent);
        setStatus("폰 시스템이 인식 모델만 내려받아요. 소리는 보내지 않아요.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            onStartStopPressed();
        } else {
            setStatus("마이크 권한이 있어야 받아쓸 수 있어요.");
        }
    }

    // ---- display ------------------------------------------------------------

    // Finished lines, then the grey composing line with the debug marker at its end only.
    private void render() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        if (shown.length() > 0) {
            long now = System.currentTimeMillis();
            int start = sb.length();
            sb.append(shown);
            sb.append(" ⟨" + partialCount + "p " + (now - lastEventMs) + "ms " + lastEvent + "⟩");
            sb.setSpan(new ForegroundColorSpan(Color.GRAY), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        transcriptView.setText(sb);
        scrollView.fullScroll(View.FOCUS_DOWN);
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

    private static String nowText() {
        return new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date());
    }

    // ---- transcript commits -------------------------------------------------

    // Every finished sentence is shown and joins this screen's single clipboard entry. The entry
    // is written once when the screen pauses or listening stops (flushClip), so the phone's
    // clipboard history gets one entry per screen. Nothing is written to a file and nothing
    // leaves the phone. Diff lines are display only and are not copied.
    private void addLine(String text) {
        lines.add("[" + nowText() + "] " + text);
        screenClip.add(text);
    }

    private void flushClip() {
        if (!screenClip.needsWrite()) {
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("wktalkclip", screenClip.text()));
                screenClip.markWritten();
                clipWrites++;
            }
        } catch (RuntimeException e) {
            lastEvent = "clip failed " + e.getClass().getSimpleName();
        }
    }

    // Commits the composing line as a normal line when it holds text, then resets it.
    // The caller renders.
    private void commitShown(String suffix) {
        if (shown.length() > 0) {
            addLine(shown + suffix);
        }
        shown = "";
        partialCount = 0;
    }

    // One line that shows only the difference between old text a and new text b. The common
    // prefix and suffix are elided with an ellipsis. Example: a="안녕하세요" b="안녕히계세요".
    private static String diffLine(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int c = 0;
        while (c < max && a.charAt(c) == b.charAt(c)) {
            c++;
        }
        int s = 0;
        while (s < max - c && a.charAt(a.length() - 1 - s) == b.charAt(b.length() - 1 - s)) {
            s++;
        }
        return "  ± " + (c > 0 ? "…" : "") + "[-" + a.substring(c, a.length() - s)
                + "][+" + b.substring(c, b.length() - s) + "]" + (s > 0 ? "…" : "");
    }

    // Final result of a session. An empty result or one equal to the composing text only
    // commits the composing text. A result that extends it is committed as the whole line.
    // Any other result commits the composing text, then one diff line.
    private void commitFinal(Bundle b) {
        String f = firstMatch(b);
        if (f.length() == 0 || f.equals(shown)) {
            commitShown("");
        } else if (f.startsWith(shown)) {
            addLine(f);
            shown = "";
            partialCount = 0;
        } else {
            String d = diffLine(shown, f);
            commitShown("");
            lines.add(d);
        }
        render();
    }

    // ---- recognition callbacks ---------------------------------------------

    // RecognitionListener callbacks: this activity is the listener (no inner classes).

    @Override
    public void onReadyForSpeech(Bundle params) {
        noteEvent("ready");
        if (listening && lastRmsRenderMs == 0L) {
            setStatus("듣고 있어요. 소리는 이 폰 안에서만 글자로 바꿔요.");
        }
    }

    @Override
    public void onBeginningOfSpeech() {
        noteEvent("begin");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        if (!listening) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRmsRenderMs < RMS_RENDER_INTERVAL_MS) {
            return;
        }
        lastRmsRenderMs = now;
        int bars = (int) ((rmsdB + 2f) / 1.5f);
        if (bars < 0) {
            bars = 0;
        }
        if (bars > 8) {
            bars = 8;
        }
        if (bars > 0) {
            lastSoundMs = now;
        }
        StringBuilder sb = new StringBuilder("듣는 중 ");
        for (int i = 0; i < bars; i++) {
            sb.append('▮');
        }
        setStatus(sb.toString());
        updateEventLine();
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
        noteEvent("endSpeech");
    }

    @Override
    public void onError(int error) {
        noteEvent("err" + error);
        sessionRunning = false;

        if (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            // Normal end of an utterance: keep what was heard as a line, then restart.
            commitShown("");
            render();
            if (listening) {
                startSegment();
            }
            return;
        }

        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
            commitShown(" …(e" + error + ")");
            render();
            endSession("폰 시스템이 인식 모델만 내려받아요. 소리는 보내지 않아요.");
            if (Build.VERSION.SDK_INT >= 33) {
                downloadButton.setVisibility(View.VISIBLE);
            }
            return;
        }

        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || !listening) {
            commitShown(" …(e" + error + ")");
            render();
            endSession("인식 오류 " + error + ". 시작 버튼을 다시 눌러 주세요.");
            return;
        }

        // Client (5) and busy (8) errors carry the code as "(eN)". Other errors use an ellipsis
        // and the code. Nothing is hidden: the code is always in the line.
        boolean clientOrBusy = error == SpeechRecognizer.ERROR_CLIENT
                || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
        commitShown(clientOrBusy ? " (e" + error + ")" : " …(e" + error + ")");
        render();

        long now = System.currentTimeMillis();
        if (now - errorWindowStart > ERROR_WINDOW_MS) {
            errorWindowStart = now;
            errorCount = 0;
        }
        errorCount++;
        if (errorCount > ERROR_LIMIT) {
            endSession("인식기가 계속 실패해요(오류 " + error + "). 다시 눌러 주세요.");
            return;
        }

        setStatus("인식 오류 " + error + ". 인식기를 다시 만들어요.");
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        restartPending = true;
        handler.postDelayed(this, RESTART_DELAY_MS);
    }

    @Override
    public void onResults(Bundle results) {
        noteEvent("result");
        sessionRunning = false;
        if (Build.VERSION.SDK_INT < 33) {
            if (listening) {
                listenOnce();
            }
            commitFinal(results);
            return;
        }
        // SDK 33+: do not restart in this callback (it races the segmented session's own restart).
        // Schedule it; run() restarts only if nothing else started a session meanwhile.
        commitFinal(results);
        if (listening) {
            restartPending = true;
            handler.postDelayed(this, RESULT_RESTART_DELAY_MS);
        }
    }

    @Override
    public void onSegmentResults(Bundle segmentResults) {
        noteEvent("segment");
        commitFinal(segmentResults);
    }

    @Override
    public void onEndOfSegmentedSession() {
        noteEvent("segEnd");
        sessionRunning = false;
        commitShown("");
        render();
        if (listening) {
            startSegment();
        }
    }

    // Append-only: a guess that extends the composing text only grows it. A revision commits the
    // composing text, adds one diff line and starts the composing line from the revised guess.
    @Override
    public void onPartialResults(Bundle partialResults) {
        noteEvent("partial");
        String p = firstMatch(partialResults);
        if (p.length() == 0) {
            return; // never erase on an empty guess
        }
        if (p.startsWith(shown)) {
            shown = p;
        } else {
            String d = diffLine(shown, p);
            commitShown("");
            lines.add(d);
            shown = p;
        }
        partialCount++;
        render();
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        noteEvent("ev" + eventType);
    }

    @Override
    protected void onPause() {
        flushClip();
        super.onPause();
    }

    // Back from the background or a screen-off: the recognizer may have been cut off while the
    // permission or the microphone changed. Re-check the permission and restart the session.
    @Override
    protected void onResume() {
        super.onResume();
        if (!listening) {
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            endSession("마이크 권한이 없어요. 시작을 눌러 허용해 주세요.");
            return;
        }
        if (!sessionRunning && !restartPending) {
            if (recognizer != null) {
                recognizer.destroy();
                recognizer = null;
            }
            startSegment();
        }
    }

    @Override
    protected void onDestroy() {
        flushClip();
        restartPending = false;
        listening = false;
        handler.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        unmuteBeeps();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (recovery != null) {
            recovery.destroy();
            recovery = null;
        }
        super.onDestroy();
    }
}
