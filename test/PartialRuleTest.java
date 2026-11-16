import app.wktalkclip.PartialRule;

public class PartialRuleTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] a) {
        check("longer text with the same start is growth", PartialRule.extendsText("hello everyone", "hello everyone let us"));
        check("a case-only difference is growth, not a revision", PartialRule.extendsText("Hello everyone Let us", "hello everyone let us"));
        check("a case change plus new words is growth", PartialRule.extendsText("hello everyone let us", "Hello everyone Let us start the meeting"));
        check("a real change of a word is a revision", !PartialRule.extendsText("hello world", "hallo world"));
        check("a shorter guess is a revision", !PartialRule.extendsText("hello everyone", "hello"));
        check("anything extends the empty text", PartialRule.extendsText("", "안녕하세요"));
        check("Korean text is compared as before", PartialRule.extendsText("안녕하세요", "안녕하세요 오늘") && !PartialRule.extendsText("안녕하세요", "안녕히세요"));
        check("the same text counts as the same", PartialRule.sameText("Hello Everyone", "hello everyone"));
        check("different text is not the same", !PartialRule.sameText("hello", "hello there"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
