package app.wktalkclip;

import android.content.Context;
import android.media.AudioManager;

/**
 * Watches the phone's audio mode. A phone call or a voice-call app (ringing, in call, in
 * communication) takes the microphone away from other apps, so the meeting is paused then and
 * restarted by the activity as soon as the call ends. Audio focus is deliberately not requested:
 * the microphone does not need it, asking for it would stop the user's other media, and a
 * permanent focus loss may never be followed by a gain, which would leave the meeting paused.
 * No phone permission is needed. Nothing is stored or sent.
 */
public final class FocusGuard {
    private final Context ctx;
    private final MeetingPause pause;
    private final Runnable onChange;
    private AudioManager am;
    private AudioManager.OnModeChangedListener modeListener;

    public FocusGuard(Context c, MeetingPause p, Runnable onChange) {
        this.ctx = c;
        this.pause = p;
        this.onChange = onChange;
    }

    private void notifyChange() {
        try {
            if (onChange != null) {
                onChange.run();
            }
        } catch (RuntimeException e) {
            // never crash the app
        }
    }

    public void start() {
        try {
            stop();
            am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            modeListener = new AudioManager.OnModeChangedListener() {
                @Override
                public void onModeChanged(int mode) {
                    try {
                        pause.onCallMode(isCallMode(mode));
                        notifyChange();
                    } catch (RuntimeException e) {
                        // never crash the app
                    }
                }
            };
            am.addOnModeChangedListener(ctx.getMainExecutor(), modeListener);
            pause.onCallMode(isCallMode(am.getMode()));
        } catch (RuntimeException e) {
            // never crash the app
        }
    }

    private static boolean isCallMode(int mode) {
        return mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_RINGTONE
                || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    public void stop() {
        try {
            if (am != null && modeListener != null) {
                am.removeOnModeChangedListener(modeListener);
            }
        } catch (RuntimeException e) {
            // never crash the app
        }
        modeListener = null;
    }
}
