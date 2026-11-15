package app.wktalkclip;

/** Decides when a recording meeting is paused: during a phone call or while another app holds audio focus. */
public final class MeetingPause {
    private boolean inCall = false;
    private boolean hasFocus = true;

    public synchronized void onCallMode(boolean inCall) {
        this.inCall = inCall;
    }

    public synchronized void onFocus(boolean hasFocus) {
        this.hasFocus = hasFocus;
    }

    private boolean paused() {
        return inCall || !hasFocus;
    }

    /** True only when the user wants to listen and nothing forces a pause. */
    public synchronized boolean shouldListen(boolean userWantsListening) {
        return userWantsListening && !paused();
    }

    /** Empty when not paused, otherwise the text to show. A call wins over lost focus. */
    public synchronized String reason() {
        if (inCall) {
            return "통화 중";
        }
        if (!hasFocus) {
            return "다른 앱이 소리를 쓰는 중";
        }
        return "";
    }

    /** True when the activity was paused by us and both causes are now clear, so it should restart. */
    public synchronized boolean needsResume(boolean wasPaused) {
        return wasPaused && !paused();
    }
}
