import app.wktalkclip.Features;
import app.wktalkclip.MeetingFile;
import java.util.TimeZone;

public class MeetingFileTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] a) {
        TimeZone seoul = TimeZone.getTimeZone("Asia/Seoul");
        long t = 1794787200000L; // 2026-11-16 00:00:00 Asia/Seoul is 2026-11-15 15:00:00 UTC
        check("file is named by the local date", "meeting-2026-11-16.txt".equals(MeetingFile.fileName(t, seoul)));
        check("the same instant is the previous day in UTC", "meeting-2026-11-15.txt".equals(MeetingFile.fileName(t, TimeZone.getTimeZone("UTC"))));
        check("one line has a time stamp and ends with a newline", "[00:00:05] 안녕하세요\n".equals(MeetingFile.line(t + 5000L, "안녕하세요", seoul)));
        check("blank text makes no line", MeetingFile.line(t, "   ", seoul).length() == 0);
        check("line breaks inside the text are flattened", "[00:00:00] a b\n".equals(MeetingFile.line(t, "a\nb", seoul)));
        check("saving to a file is OFF by default", !Features.SAVE_MEETING_FILE);
        System.exit(fails == 0 ? 0 : 1);
    }
}
