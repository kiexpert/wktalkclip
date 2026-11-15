package app.wktalkclip;

/** Recognition language: Korean by default, then English, then auto (Korean + English mixed). All on-device. */
public final class LangChoice {
    private int mode = 0; // 0 Korean, 1 English, 2 auto

    public String tag() {
        return mode == 1 ? "en-US" : "ko-KR";
    }

    public boolean isAuto() {
        return mode == 2;
    }

    public String label() {
        return mode == 1 ? "English" : mode == 2 ? "자동" : "한국어";
    }

    public void toggle() {
        mode = (mode + 1) % 3;
    }
}
