package app.wktalkclip;

import java.util.ArrayList;

// The append-only transcript: finished lines, plus one composing line that grows while the
// recognizer refines its guess. Nothing is ever erased. Plain Java, no Android imports.
public class Transcript {
    private final ArrayList<String> lines = new ArrayList<String>();
    private String composing = "";
    private int partialCount = 0;
    private long version = 0L;

    // Number of finished lines.
    public int size() {
        return lines.size();
    }

    public String line(int i) {
        return lines.get(i);
    }

    public String composing() {
        return composing;
    }

    public int partialCount() {
        return partialCount;
    }

    // Grows by at least one on every change, so a UI can skip rendering when it is unchanged.
    public long version() {
        return version;
    }

    // A guess that extends the composing text only grows it. A revision commits the composing
    // text, adds one diff line and starts composing from the new guess. An empty guess is ignored.
    public void partial(String p) {
        if (p == null || p.length() == 0) {
            return;
        }
        if (p.startsWith(composing)) {
            composing = p;
        } else {
            String d = diffLine(composing, p);
            commit("");
            lines.add(d);
            composing = p;
        }
        partialCount++;
        version++;
    }

    // Finish the composing text as a line (with an optional suffix). Nothing to finish: no-op.
    public void commit(String suffix) {
        if (composing.length() == 0) {
            return;
        }
        lines.add(composing + suffix);
        composing = "";
        partialCount = 0;
        version++;
    }

    // The last lastLines lines joined with \n, then the composing line. O(lastLines).
    public String window(int lastLines) {
        int n = lines.size();
        int from = lastLines <= 0 ? n : Math.max(0, n - lastLines);
        StringBuilder b = new StringBuilder();
        for (int i = from; i < n; i++) {
            b.append(lines.get(i)).append('\n');
        }
        b.append(composing);
        return b.toString();
    }

    // One line that shows only the difference between old text a and new text b. The common
    // prefix and suffix are elided with an ellipsis. Same logic as MainActivity.diffLine.
    static String diffLine(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int c = 0;
        while (c < max && a.charAt(c) == b.charAt(c)) {
            c++;
        }
        int s = 0;
        while (s < max - c && a.charAt(a.length() - 1 - s) == b.charAt(b.length() - 1 - s)) {
            s++;
        }
        return "  ± " + (c > 0 ? "…" : "") + "[-" + a.substring(c, a.length() - s)
                + "][+" + b.substring(c, b.length() - s) + "]" + (s > 0 ? "…" : "");
    }
}
