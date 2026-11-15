package app.wktalkclip;

import java.util.ArrayList;

/**
 * One clipboard entry for a whole calendar day. Every meeting of the day is appended to the same
 * entry under its own time header; a new day starts a fresh entry. After the app was killed, the
 * day's text is recovered from the clipboard itself, but only when that clip is this app's own
 * entry of the same day (it starts with the day header). No files are involved.
 */
public final class DayClip {
    private String day = null;
    private String base = "";                       // earlier meetings of the day, without the header line
    private String meetingHm = "";
    private final ArrayList<String> sentences = new ArrayList<String>();
    private boolean dirty = false;

    public static String header(String day) {
        return "[wktalkclip " + day + "]";
    }

    /** Begins a meeting. clipText is what the clipboard holds now (used only after a restart). */
    public void startMeeting(String dayKey, String hm, String clipText) {
        String section = section();
        if (day != null && day.equals(dayKey)) {
            // same process, same day: fold the meeting that just ended into the day's text
            base = join(base, section);
        } else {
            String head = header(dayKey) + "\n";
            if (clipText != null && clipText.startsWith(head)) {
                base = stripTrailingNewlines(clipText.substring(head.length()));
            } else {
                base = "";
            }
            day = dayKey;
        }
        sentences.clear();
        meetingHm = hm;
    }

    public void add(String sentence) {
        String t = sentence.trim();
        if (t.length() == 0 || day == null) {
            return;
        }
        sentences.add(t);
        dirty = true;
    }

    public boolean needsWrite() {
        return dirty;
    }

    public void markWritten() {
        dirty = false;
    }

    /** The whole day's entry, trimmed to at most cap characters: the oldest text goes first. */
    public String text(int cap) {
        if (day == null) {
            return "";
        }
        String head = header(day) + "\n";
        String cur = section();
        String full = head + join(base, cur);
        if (full.length() <= cap) {
            return full;
        }
        String b = base;
        while (b.length() > 0 && head.length() + join(b, cur).length() > cap) {
            int nl = b.indexOf('\n');
            b = nl < 0 ? "" : b.substring(nl + 1);
        }
        String out = head + join(b, cur);
        if (out.length() > cap) {
            // the current meeting alone is too long: keep its newest part
            out = head + cur.substring(Math.max(0, cur.length() - Math.max(0, cap - head.length())));
        }
        return out;
    }

    private String section() {
        if (sentences.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[" + meetingHm + "] 회의");
        for (String s : sentences) {
            sb.append('\n').append(s);
        }
        return sb.toString();
    }

    private static String join(String a, String b) {
        if (a.length() == 0) {
            return b;
        }
        if (b.length() == 0) {
            return a;
        }
        return a + "\n" + b;
    }

    private static String stripTrailingNewlines(String s) {
        int e = s.length();
        while (e > 0 && (s.charAt(e - 1) == '\n' || s.charAt(e - 1) == '\r')) {
            e--;
        }
        return s.substring(0, e);
    }
}
