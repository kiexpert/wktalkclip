package app.wktalkclip;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Keeps the last uncaught stack trace in memory, then hands the crash to the previous handler.
 * No files, no logs.
 */
public class CrashShow implements Thread.UncaughtExceptionHandler {

    public static volatile String lastCrash;

    private final Thread.UncaughtExceptionHandler previous;

    public CrashShow(Thread.UncaughtExceptionHandler previous) {
        this.previous = previous;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            lastCrash = sw.toString();
        } catch (RuntimeException ignored) {
            // keep the crash flowing to the previous handler
        }
        if (previous != null) {
            previous.uncaughtException(t, e);
        }
    }
}
