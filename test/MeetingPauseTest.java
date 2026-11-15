import app.wktalkclip.MeetingPause;

public class MeetingPauseTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] a) {
        MeetingPause p = new MeetingPause();
        check("normal listening", p.shouldListen(true) && p.reason().isEmpty());
        check("fresh state does not need resume", !p.needsResume(false));

        p.onCallMode(true);
        check("call pauses", !p.shouldListen(true));
        check("call reason", "통화 중".equals(p.reason()));
        check("still paused so no resume yet", !p.needsResume(true));
        p.onCallMode(false);
        check("call ends listens again", p.shouldListen(true) && p.reason().isEmpty());
        check("call ended needs resume", p.needsResume(true));
        check("not paused before so no resume", !p.needsResume(false));

        p.onFocus(false);
        check("focus lost pauses", !p.shouldListen(true));
        check("focus reason", "다른 앱이 소리를 쓰는 중".equals(p.reason()));
        p.onFocus(true);
        check("focus regained listens", p.shouldListen(true) && p.reason().isEmpty());
        check("focus regained needs resume", p.needsResume(true));

        p.onCallMode(true);
        p.onFocus(false);
        check("both: call reason wins", "통화 중".equals(p.reason()));
        p.onCallMode(false);
        check("call clear but focus lost stays paused", !p.shouldListen(true) && !p.needsResume(true));
        check("reason falls to focus", "다른 앱이 소리를 쓰는 중".equals(p.reason()));
        p.onFocus(true);
        check("both clear resumes", p.shouldListen(true) && p.needsResume(true));

        p.onFocus(false);
        p.onCallMode(true);
        p.onFocus(true);
        check("focus clear but call active stays paused", !p.shouldListen(true) && !p.needsResume(true));
        p.onCallMode(false);
        check("both clear in other order resumes", p.shouldListen(true));

        MeetingPause q = new MeetingPause();
        check("user not listening is never overridden", !q.shouldListen(false));
        q.onCallMode(true);
        q.onCallMode(false);
        q.onFocus(false);
        q.onFocus(true);
        check("user not listening stays false after pauses", !q.shouldListen(false));
        check("repeated same events are harmless", repeat());
        System.exit(fails == 0 ? 0 : 1);
    }

    static boolean repeat() {
        MeetingPause p = new MeetingPause();
        p.onCallMode(true);
        p.onCallMode(true);
        p.onCallMode(false);
        p.onFocus(true);
        return p.shouldListen(true);
    }
}
