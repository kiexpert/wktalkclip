import app.wktalkclip.ScreenClip;

public class ScreenClipTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] a) {
        ScreenClip c = new ScreenClip();
        check("empty screen has nothing to write", !c.needsWrite());
        c.add("첫 문장");
        c.add("  ");
        c.add("둘째 문장");
        check("sentences wait for a flush", c.needsWrite());
        check("one entry holds the whole screen", "첫 문장\n둘째 문장".equals(c.text()));
        c.markWritten();
        check("written once, nothing more to write", !c.needsWrite());
        c.add("셋째 문장");
        check("new sentence marks the same entry dirty", c.needsWrite());
        check("entry is replaced with all sentences, not appended", "첫 문장\n둘째 문장\n셋째 문장".equals(c.text()));
        c.clear();
        check("new screen starts empty", !c.needsWrite() && c.text().length() == 0);
        System.exit(fails == 0 ? 0 : 1);
    }
}
