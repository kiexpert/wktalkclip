package app.wktalkclip;

/**
 * What Start does and in which order. The recognizer comes first so that nothing else (a service
 * start, a permission dialog, the call watch) can get between the button and the microphone.
 * Simple mode does only the recognizer, the way the very first build did, so a phone where
 * dictation fails can tell whether one of the other steps is the cause.
 */
public final class StartPlan {
    private StartPlan() {
    }

    public static String[] steps(boolean simple) {
        if (simple) {
            return new String[] { "recognizer" };
        }
        return new String[] { "recognizer", "read-clip", "service", "call-watch", "notification-permission" };
    }
}
