import app.wktalkclip.LangChoice;

public class LangChoiceTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] a) {
        LangChoice l = new LangChoice();
        check("Korean is the default", "ko-KR".equals(l.tag()) && "한국어".equals(l.label()));
        l.toggle();
        check("toggle gives English", "en-US".equals(l.tag()) && "English".equals(l.label()));
        l.toggle();
        check("toggle again returns to Korean", "ko-KR".equals(l.tag()));
        System.exit(fails == 0 ? 0 : 1);
    }
}
