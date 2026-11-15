package app.wktalkclip;

import java.util.ArrayList;

/** The sentences of one screen. They go to the clipboard as one entry that is replaced, never appended. */
public final class ScreenClip {
    private final ArrayList<String> sentences = new ArrayList<String>();
    private boolean dirty = false;

    public void add(String sentence) {
        String t = sentence.trim();
        if (t.length() == 0) {
            return;
        }
        sentences.add(t);
        dirty = true;
    }

    public boolean needsWrite() {
        return dirty;
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(sentences.get(i));
        }
        return sb.toString();
    }

    public void markWritten() {
        dirty = false;
    }

    public void clear() {
        sentences.clear();
        dirty = false;
    }
}
