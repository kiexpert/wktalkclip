import app.wktalkclip.ErrorNames;
import app.wktalkclip.EventLog;

public class DiagTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] a) {
        check("error 3 is the audio error", ErrorNames.name(3).startsWith("AUDIO"));
        check("error 8 is busy", ErrorNames.name(8).startsWith("RECOGNIZER_BUSY"));
        check("error 9 is permissions", ErrorNames.name(9).startsWith("INSUFFICIENT_PERMISSIONS"));
        check("error 13 is language unavailable", ErrorNames.name(13).startsWith("LANGUAGE_UNAVAILABLE"));
        check("unknown error keeps its number", ErrorNames.name(99).contains("99"));
        EventLog log = new EventLog(5);
        for (int i = 1; i <= 8; i++) {
            log.add(1000L * i, "e" + i);
        }
        String[] last = log.last(10);
        check("log keeps only the newest 5", last.length == 5);
        check("log is oldest first", last[0].endsWith("e4") && last[4].endsWith("e8"));
        check("log lines carry a time", last[4].startsWith("00:08"));
        check("count counts every event, not only the kept ones", log.count() == 8);
        check("last(2) returns the two newest", log.last(2).length == 2 && log.last(2)[1].endsWith("e8"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
