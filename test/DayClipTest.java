import app.wktalkclip.DayClip;

public class DayClipTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] a) {
        String H = DayClip.header("2026-11-16");

        // first meeting of a day
        DayClip c = new DayClip();
        c.startMeeting("2026-11-16", "09:30", "");
        check("nothing to write before the first sentence", !c.needsWrite());
        c.add("첫 문장");
        c.add("둘째 문장");
        String t1 = c.text(100000);
        check("entry starts with the day header", t1.startsWith(H + "\n"));
        check("meeting has a time header and its sentences", t1.contains("[09:30] 회의\n첫 문장\n둘째 문장"));
        check("a sentence marks the entry dirty", c.needsWrite());
        c.markWritten();
        check("written once, nothing more to write", !c.needsWrite());

        // second Start on the same day appends, one entry
        c.startMeeting("2026-11-16", "14:05", t1);
        c.add("오후 문장");
        String t2 = c.text(100000);
        check("same day: still one header", t2.indexOf(H) == 0 && t2.indexOf(H, 1) < 0);
        check("same day: earlier meeting is kept", t2.contains("[09:30] 회의\n첫 문장\n둘째 문장"));
        check("same day: second meeting has its own time header", t2.contains("[14:05] 회의\n오후 문장"));
        check("same day: earlier meeting comes first", t2.indexOf("첫 문장") < t2.indexOf("오후 문장"));

        // a new day starts a fresh entry
        c.startMeeting("2026-11-17", "08:00", t2);
        c.add("다음 날");
        String t3 = c.text(100000);
        check("new day: fresh entry with the new header", t3.startsWith(DayClip.header("2026-11-17") + "\n"));
        check("new day: nothing of yesterday", !t3.contains("첫 문장") && !t3.contains("오후 문장"));

        // killed and restarted: a new object adopts the app's own clip of the same day
        DayClip k = new DayClip();
        k.startMeeting("2026-11-16", "18:00", t2);
        k.add("재시작 뒤 문장");
        String t4 = k.text(100000);
        check("restart same day: earlier text is kept", t4.contains("첫 문장") && t4.contains("오후 문장"));
        check("restart same day: new meeting appended", t4.contains("[18:00] 회의\n재시작 뒤 문장"));
        check("restart same day: still one header", t4.indexOf(H, 1) < 0);

        // someone else's clip is never adopted
        DayClip o = new DayClip();
        o.startMeeting("2026-11-16", "10:00", "장보기 목록\n우유");
        o.add("내 문장");
        check("a foreign clip is not adopted", !o.text(100000).contains("우유") && o.text(100000).startsWith(H));
        // an old day's own clip is not adopted either
        DayClip y = new DayClip();
        y.startMeeting("2026-11-16", "10:00", DayClip.header("2026-11-15") + "\n[09:00] 회의\n어제 문장");
        y.add("오늘 문장");
        check("yesterday's own clip is not adopted", !y.text(100000).contains("어제 문장"));

        // cap trimming: oldest text of the day goes first, the current meeting stays whole
        DayClip big = new DayClip();
        StringBuilder old = new StringBuilder(H + "\n[08:00] 회의\n");
        for (int i = 0; i < 50; i++) {
            old.append("오래된 문장 ").append(i).append('\n');
        }
        big.startMeeting("2026-11-16", "20:00", old.toString());
        big.add("지금 회의 문장 하나");
        big.add("지금 회의 문장 둘");
        String capped = big.text(300);
        check("cap: result fits", capped.length() <= 300);
        check("cap: header is kept", capped.startsWith(H + "\n"));
        check("cap: current meeting is whole", capped.contains("[20:00] 회의\n지금 회의 문장 하나\n지금 회의 문장 둘"));
        check("cap: the newest old line survives before the oldest one", capped.contains("오래된 문장 49") && !capped.contains("오래된 문장 0\n"));
        // the header survives even when the current meeting alone is longer than the cap
        DayClip huge = new DayClip();
        huge.startMeeting("2026-11-16", "21:00", "");
        for (int i = 0; i < 100; i++) {
            huge.add("아주 긴 회의 문장 " + i);
        }
        String hc = huge.text(200);
        check("cap: header survives a current meeting longer than the cap", hc.startsWith(H + "\n") && hc.length() <= 200);
        check("cap: that trimmed text keeps the newest sentence", hc.contains("아주 긴 회의 문장 99"));
        // the top line alone decides same day: a clip whose header is the same day is adopted
        DayClip top = new DayClip();
        top.startMeeting("2026-11-16", "22:00", H + "\n[07:00] 회의\n아침 문장");
        top.add("밤 문장");
        check("top line decides: same-day header adopts the entry", top.text(100000).contains("아침 문장") && top.text(100000).contains("밤 문장"));
        DayClip other = new DayClip();
        other.startMeeting("2026-11-16", "22:00", "[wktalkclip 2026-11-15]\n[07:00] 회의\n어제 문장\n" + H);
        other.add("밤 문장");
        check("top line decides: a header on a lower line does not count", !other.text(100000).contains("어제 문장"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
