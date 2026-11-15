import app.wktalkclip.Backoff;

public class BackoffTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] a) {
        check("first retry is quick", Backoff.delayMs(0) == 200L);
        check("delay doubles", Backoff.delayMs(1) == 400L && Backoff.delayMs(2) == 800L);
        check("delay is capped at 30 s", Backoff.delayMs(10) == 30000L && Backoff.delayMs(500) == 30000L);
        check("negative count is treated as zero", Backoff.delayMs(-3) == 200L);
        check("restart allowed after the minimum gap", Backoff.mayRestart(10000L, 4000L, 5000L));
        check("restart refused inside the minimum gap", !Backoff.mayRestart(8000L, 4000L, 5000L));
        check("first ever restart is allowed", Backoff.mayRestart(1000L, 0L, 5000L));
        System.exit(fails == 0 ? 0 : 1);
    }
}
