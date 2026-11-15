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
    private static final int WHAT_FLUSH = 2;
    private static final int WHAT_DIAG = 3;
    // No recognizer callback (rms included) for this long means the recognizer is really stuck.
    private static final long STALL_QUIET_MS = 12000L;
    private static final long MIN_FORCE_RESTART_GAP_MS = 5000L;
    private static final long NO_MATCH_RESTART_MS = 300L;
    // While the screen is off the clipboard entry is refreshed this often (one replaced entry), so a
    // killed process loses at most this much.
    private static final long FLUSH_WHILE_PAUSED_MS = 15L * 60L * 1000L;
    private static final int MAX_CLIP_CHARS = 200000;
    private static final int REQ_NOTIFY = 2;
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
    private int consecutiveErrors = 0;
    private long lastForceRestartMs = 0L;
    private boolean resumed = false;
    private int renderedLines = 0;
    private boolean feedMode = false;
    private TextView composingView;
    // Diagnostics screen: everything that decides whether dictation works, refreshed every second.
    private final EventLog eventLog = new EventLog(80);
    private long sessionStartMs = 0L;
    private float rmsMin = 99f;
    private float rmsMax = -99f;
    private int rmsCount = 0;
    private String supportText = "아직 확인 전";
    private Button diagButton;
    private Button shareButton;
    // A phone call or voice-call app pauses the meeting; it restarts by itself when the call ends.
    private final MeetingPause meetingPause = new MeetingPause();
    private FocusGuard focusGuard;
    private boolean pausedByPolicy = false;
    private ScrollView diagScroll;
    private TextView diagView;

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
        } else if (v == diagButton) {
            toggleDiag();
        } else if (v == shareButton) {
            shareAll();
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
        feedMode = getIntent().getBooleanExtra("feed", false) && FeedSource.clipFile(this) != null;
        setStatus("시작 버튼을 누르면 받아써요.\n1분 확인: ① 시작 ② 10초 말하기 ③ 글자가 나오는지 보기 "
                + "④ 멈추기 → 클립보드에 복사돼요. 안 되면 '진단'을 눌러 화면을 캡처해 주세요.");
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

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        diagButton = makeButton("진단");
        diagButton.setOnClickListener(this);
        shareButton = makeButton("공유");
        shareButton.setOnClickListener(this);
        row2.addView(diagButton, weightParams());
        row2.addView(shareButton, weightParams());
        root.addView(row2, new LinearLayout.LayoutParams(
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

        composingView = new TextView(this);
        composingView.setTextSize(18);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.addView(transcriptView);
        textBox.addView(composingView);

        diagView = new TextView(this);
        diagView.setTextSize(11);
        diagView.setTypeface(android.graphics.Typeface.MONOSPACE);
        diagView.setTextIsSelectable(true);
        diagScroll = new ScrollView(this);
        diagScroll.addView(diagView);
        diagScroll.setVisibility(View.GONE);
        root.addView(diagScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        scrollView = new ScrollView(this);
        scrollView.addView(textBox);
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
                + " / clips=" + clipWrites + " / lang=" + lang.label() + "(" + lang.tag() + ")" + " / pipe=" + usePipe + " / restarts=" + restartCount);
    }

    // Records the last callback of the recognizer and when it arrived.
    private void noteEvent(String name) {
        lastEvent = name;
        lastEventMs = System.currentTimeMillis();
        if (!"partial".equals(name)) {
            eventLog.add(lastEventMs - sessionStartMs, name);
        }
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
        sessionStartMs = System.currentTimeMillis();
        rmsMin = 99f;
        rmsMax = -99f;
        rmsCount = 0;
        eventLog.add(0L, "시작 누름 lang=" + lang.tag());
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
        try {
            startForegroundService(new Intent(this, RecService.class));
        } catch (RuntimeException e) {
            lastEvent = "service " + e.getClass().getSimpleName();
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS },
                    REQ_NOTIFY);
        }
        setStatus("회의 중에는 폰 소리를 꺼 둬요");
        pausedByPolicy = false;
        focusGuard = new FocusGuard(this, meetingPause, new Runnable() {
            @Override
            public void run() {
                recheckPause();
            }
        });
        focusGuard.start();
        recheckPause();
        startSegment();
        scheduleWatchdog();
    }

    // Called when a call starts or ends. Pauses the recognizer during a call and restarts it
    // as soon as the phone is free again.
    private void recheckPause() {
        if (!listening) {
            return;
        }
        boolean shouldListen = meetingPause.shouldListen(true);
        if (!shouldListen && !pausedByPolicy) {
            pausedByPolicy = true;
            restartPending = false;
            handler.removeCallbacks(this);
            if (recognizer != null) {
                recognizer.cancel();
                recognizer.destroy();
                recognizer = null;
            }
            sessionRunning = false;
            commitShown("");
            render();
            eventLog.add(System.currentTimeMillis() - sessionStartMs, "일시정지 " + meetingPause.reason());
            setStatus("일시정지: " + meetingPause.reason() + ". 끝나면 자동으로 다시 들어요.");
        } else if (shouldListen && pausedByPolicy) {
            pausedByPolicy = false;
            consecutiveErrors = 0;
            lastForceRestartMs = 0L;
            lastEventMs = System.currentTimeMillis();
            eventLog.add(System.currentTimeMillis() - sessionStartMs, "다시 시작 (통화 끝)");
            setStatus("다시 듣고 있어요.");
            startSegment();
        }
    }

    private void endSession(String message) {
        pausedByPolicy = false;
        if (focusGuard != null) {
            focusGuard.stop();
        }
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
        updateEventLine(); // the watchdog is stopped now, so refresh the clips= counter here
        FeedSource.stop();
        handler.removeMessages(WHAT_FLUSH);
        stopService(new Intent(this, RecService.class));
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
        if (sessionRunning || pausedByPolicy) {
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
            if (lang.isAuto() && Build.VERSION.SDK_INT >= 34) {
                // Mixed Korean/English meetings: let the on-device recognizer detect the language
                // and switch between the two while it listens. Needs engine support; if the engine
                // ignores it the primary language (Korean) is used.
                ArrayList<String> both = new ArrayList<String>();
                both.add("ko-KR");
                both.add("en-US");
                i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
                i.putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, both);
                i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                        RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
                i.putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, both);
            }
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
            Intent go = recognizerIntent;
            if (feedMode) {
                // QA only: hear a prerecorded clip pushed into this app's own files folder instead
                // of the microphone. Off unless the launch intent asks for it and the clip exists.
                android.os.ParcelFileDescriptor fd = FeedSource.open(FeedSource.clipFile(this), null);
                if (fd != null) {
                    go = new Intent(recognizerIntent);
                    go.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, fd);
                    go.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                    go.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                            android.media.AudioFormat.ENCODING_PCM_16BIT);
                    go.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000);
                }
            }
            recognizer.startListening(go);
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
        if (msg.what == WHAT_DIAG) {
            if (diagScroll.getVisibility() == View.VISIBLE) {
                refreshDiag();
                handler.sendEmptyMessageDelayed(WHAT_DIAG, 1000L);
            }
            return true;
        }
        if (msg.what == WHAT_FLUSH) {
            if (listening && !resumed) {
                flushClip();
                handler.sendEmptyMessageDelayed(WHAT_FLUSH, FLUSH_WHILE_PAUSED_MS);
            }
            return true;
        }
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
        if (pausedByPolicy) {
            updateEventLine();
            scheduleWatchdog();
            return;
        }
        long now = System.currentTimeMillis();
        long quiet = now - lastEventMs;
        // rms callbacks also refresh lastEventMs, so this only fires when the recognizer is silent
        // in every way for a long time, not while someone merely pauses or speaks fast.
        boolean stalled = quiet > STALL_QUIET_MS;
        if (stalled && !restartPending && recognizer != null
                && Backoff.mayRestart(now, lastForceRestartMs, MIN_FORCE_RESTART_GAP_MS)) {
            forceRestart();
        }
        updateEventLine();
        scheduleWatchdog();
    }

    // Drops the stalled recognizer and starts a fresh session. The composing text is committed
    // as a line marked as a stall, so nothing heard is hidden.
    private void forceRestart() {
        restartCount++;
        lastForceRestartMs = System.currentTimeMillis();
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
        try {
            String all = sb.toString();
            if (all.length() > MAX_CLIP_CHARS) {
                all = all.substring(all.length() - MAX_CLIP_CHARS);
            }
            cm.setPrimaryClip(ClipData.newPlainText("회의 받아쓰기", all));
            Toast.makeText(this, "복사했어요", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, "복사하지 못했어요", Toast.LENGTH_SHORT).show();
        }
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

    // One tap: hand the whole meeting text to the phone's share sheet (mail, messenger, notes).
    // The app itself stores nothing; the text only goes where the user sends it.
    private void shareAll() {
        String all = screenClip.text();
        if (all.length() == 0) {
            Toast.makeText(this, "공유할 내용이 없어요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (all.length() > 500000) {
            all = all.substring(all.length() - 500000);
        }
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, all);
            startActivity(Intent.createChooser(send, "회의 내용 공유"));
        } catch (RuntimeException e) {
            Toast.makeText(this, "공유하지 못했어요", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleDiag() {
        if (diagScroll.getVisibility() == View.VISIBLE) {
            diagScroll.setVisibility(View.GONE);
            handler.removeMessages(WHAT_DIAG);
            return;
        }
        diagScroll.setVisibility(View.VISIBLE);
        queryModels();
        refreshDiag();
        handler.sendEmptyMessageDelayed(WHAT_DIAG, 1000L);
    }

    // Asks the phone which speech models are installed for the chosen language.
    private void queryModels() {
        if (Build.VERSION.SDK_INT < 33) {
            supportText = "안드로이드 13 미만: 확인 불가";
            return;
        }
        try {
            ensureRecognizer();
            recognizer.checkRecognitionSupport(recognizerIntent, getMainExecutor(),
                    new android.speech.RecognitionSupportCallback() {
                        @Override
                        public void onSupportResult(android.speech.RecognitionSupport s) {
                            supportText = "설치됨=" + s.getInstalledOnDeviceLanguages()
                                    + " 받는중=" + s.getPendingOnDeviceLanguages()
                                    + " 받을수있음=" + s.getSupportedOnDeviceLanguages()
                                    + " 온라인=" + s.getOnlineLanguages();
                        }

                        @Override
                        public void onError(int error) {
                            supportText = "모델 확인 오류 " + ErrorNames.name(error);
                        }
                    });
        } catch (RuntimeException e) {
            supportText = "모델 확인 실패 " + e.getClass().getSimpleName();
        }
    }

    private void refreshDiag() {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        sb.append("== 앱 ==\n");
        sb.append("듣는중=").append(listening).append(" 세션=").append(sessionRunning)
                .append(" 재시작대기=").append(restartPending).append('\n');
        sb.append("언어=").append(lang.label()).append('(').append(lang.tag()).append(") 연속오류=")
                .append(consecutiveErrors).append(" 재시작=").append(restartCount)
                .append(" 클립쓰기=").append(clipWrites).append('\n');
        sb.append("마지막 이벤트=").append(lastEvent).append(" (")
                .append(lastEventMs == 0L ? "-" : String.valueOf((now - lastEventMs) / 1000L)).append("초 전)\n");
        sb.append("소리크기 받음 ").append(rmsCount).append("회, 최소 ")
                .append(rmsCount == 0 ? "-" : String.format("%.1f", rmsMin)).append(" 최대 ")
                .append(rmsCount == 0 ? "-" : String.format("%.1f", rmsMax))
                .append(" dB (계속 같은 값이면 마이크가 무음)\n");
        sb.append("음성모델: ").append(supportText).append("\n\n");
        sb.append("== 폰 ==\n").append(Diag.system(this)).append('\n');
        sb.append("== 최근 이벤트 (오래된 것부터) ==\n");
        String[] ev = eventLog.last(40);
        for (String e : ev) {
            sb.append(e).append('\n');
        }
        if (ev.length == 0) {
            sb.append("(아직 없음. 시작을 눌러 보세요)\n");
        }
        diagView.setText(sb);
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

    // Finished lines are appended once to their own view and never rebuilt; only the small grey
    // composing line is redrawn on a partial result. Nothing is drawn while the app is hidden;
    // onResume draws what was missed. This keeps a 3 hour meeting as fast as the first minute.
    private void render() {
        if (!resumed) {
            return;
        }
        if (renderedLines > lines.size()) {
            transcriptView.setText("");
            renderedLines = 0;
        }
        boolean grew = false;
        while (renderedLines < lines.size()) {
            transcriptView.append(lines.get(renderedLines));
            transcriptView.append("\n");
            renderedLines++;
            grew = true;
        }
        if (shown.length() > 0) {
            long now = System.currentTimeMillis();
            SpannableStringBuilder sb = new SpannableStringBuilder(shown);
            sb.append(" ⟨" + partialCount + "p " + (now - lastEventMs) + "ms " + lastEvent + "⟩");
            sb.setSpan(new ForegroundColorSpan(Color.GRAY), 0, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            composingView.setText(sb);
        } else {
            composingView.setText("");
        }
        if (grew || shown.length() > 0) {
            scrollView.fullScroll(View.FOCUS_DOWN);
        }
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
        clipAdd(text);
    }

    // A finished sentence joins this screen's clipboard entry, and, only when Features.SAVE_MEETING_FILE
    // is switched on, is also appended to the dated meeting file in the app's private folder.
    private void clipAdd(String text) {
        screenClip.add(text);
        if (Features.SAVE_MEETING_FILE) {
            String l = MeetingFile.line(System.currentTimeMillis(), text, java.util.TimeZone.getDefault());
            if (l.length() > 0) {
                try {
                    java.io.File f = new java.io.File(getFilesDir(),
                            MeetingFile.fileName(System.currentTimeMillis(), java.util.TimeZone.getDefault()));
                    java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
                    try {
                        out.write(l.getBytes("UTF-8"));
                    } finally {
                        out.close();
                    }
                } catch (java.io.IOException e) {
                    lastEvent = "file failed";
                }
            }
        }
    }

    // Display line whose text is not (or no longer) the best guess: shown, never copied.
    private void addDisplayLine(String text) {
        lines.add("[" + nowText() + "] " + text);
    }

    private void commitShownDisplayOnly() {
        if (shown.length() > 0) {
            addDisplayLine(shown);
        }
        shown = "";
        partialCount = 0;
    }

    private void flushClip() {
        if (!screenClip.needsWrite()) {
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                String t = screenClip.text();
                if (t.length() > MAX_CLIP_CHARS) {
                    t = t.substring(t.length() - MAX_CLIP_CHARS); // the clipboard has a size limit
                }
                cm.setPrimaryClip(ClipData.newPlainText("wktalkclip", t));
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
            lines.add("[" + nowText() + "] " + shown + suffix);
            clipAdd(shown);
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
            commitShownDisplayOnly();
            lines.add(d);
            addLine(f);
            shown = "";
            partialCount = 0;
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
        lastEventMs = now; // proof that the recognizer is alive
        rmsCount++;
        if (rmsdB < rmsMin) {
            rmsMin = rmsdB;
        }
        if (rmsdB > rmsMax) {
            rmsMax = rmsdB;
        }
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
        eventLog.add(System.currentTimeMillis() - sessionStartMs, "오류 " + ErrorNames.name(error));
        sessionRunning = false;

        if (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            // Normal end of an utterance or a quiet stretch: keep what was heard, then restart
            // after a short fixed pause so a silent or muted input cannot spin the CPU.
            commitShown("");
            render();
            if (listening) {
                restartPending = true;
                handler.postDelayed(this, NO_MATCH_RESTART_MS);
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

        if (!listening) {
            commitShown(" …(e" + error + ")");
            render();
            endSession("인식 오류 " + error + ". 시작 버튼을 다시 눌러 주세요.");
            return;
        }

        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            commitShown(" …(e" + error + ")");
            render();
            endSession("마이크 권한이 없어요. 시작을 눌러 허용해 주세요.");
            return;
        }

        // Every other error is treated as temporary (busy, client, server, a phone call, the app
        // in the background). The recording is never given up: retry with a growing pause, up
        // to 30 s, and show the code. A successful result resets the pause.
        boolean clientOrBusy = error == SpeechRecognizer.ERROR_CLIENT
                || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
        commitShown(clientOrBusy ? " (e" + error + ")" : " …(e" + error + ")");
        render();

        long wait = Backoff.delayMs(consecutiveErrors);
        consecutiveErrors++;
        setStatus("인식 오류 " + error + ". " + (wait / 1000L) + "초 뒤 다시 시도해요.");
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        restartPending = true;
        handler.postDelayed(this, Math.max(wait, RESTART_DELAY_MS));
    }

    @Override
    public void onResults(Bundle results) {
        noteEvent("result");
        consecutiveErrors = 0;
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
        consecutiveErrors = 0;
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
        consecutiveErrors = 0;
        String p = firstMatch(partialResults);
        if (p.length() == 0) {
            return; // never erase on an empty guess
        }
        if (p.startsWith(shown)) {
            shown = p;
        } else {
            String d = diffLine(shown, p);
            commitShownDisplayOnly();
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
        resumed = false;
        flushClip();
        if (listening) {
            handler.removeMessages(WHAT_FLUSH);
            handler.sendEmptyMessageDelayed(WHAT_FLUSH, FLUSH_WHILE_PAUSED_MS);
        }
        super.onPause();
    }

    // Back from the background or a screen-off: the recognizer may have been cut off while the
    // permission or the microphone changed. Re-check the permission and restart the session.
    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeMessages(WHAT_FLUSH);
        render();
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
        FeedSource.stop();
        if (focusGuard != null) {
            focusGuard.stop();
        }
        stopService(new Intent(this, RecService.class));
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
