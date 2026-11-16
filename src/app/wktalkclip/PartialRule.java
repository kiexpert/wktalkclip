package app.wktalkclip;

import java.util.Locale;

/**
 * Decides whether a new guess of the recognizer is the same sentence growing or a real revision.
 * The on-device engine flips letter case while it works ("Hello everyone Let us" then "hello everyone
 * let us start"), so a case-only difference is growth, not a revision: it must not turn the earlier
 * guess into a screen line of its own.
 */
public final class PartialRule {
    private PartialRule() {
    }

    /** True when guess is shown extended, ignoring letter case. The empty text is extended by anything. */
    public static boolean extendsText(String shown, String guess) {
        if (shown.length() == 0) {
            return true;
        }
        return guess.toLowerCase(Locale.ROOT).startsWith(shown.toLowerCase(Locale.ROOT));
    }

    public static boolean sameText(String a, String b) {
        return a.equalsIgnoreCase(b);
    }
}
