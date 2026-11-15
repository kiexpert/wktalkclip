package app.wktalkclip;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Names and line format of the optional dated meeting file (see Features.SAVE_MEETING_FILE). */
public final class MeetingFile {
    private MeetingFile() {
    }

    public static String fileName(long millis, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(tz);
        return "meeting-" + f.format(new Date(millis)) + ".txt";
    }

    /** "[HH:mm:ss] text" plus a newline, or "" for blank text. Line breaks inside the text become spaces. */
    public static String line(long millis, String text, TimeZone tz) {
        String t = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() == 0) {
            return "";
        }
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
        f.setTimeZone(tz);
        return "[" + f.format(new Date(millis)) + "] " + t + "\n";
    }
}
