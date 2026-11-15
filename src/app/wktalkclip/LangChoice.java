package app.wktalkclip;

/** Recognition language: Korean by default, English on request. Both use the on-device recognizer. */
public final class LangChoice {
    private boolean english = false;

    public String tag() {
        return english ? "en-US" : "ko-KR";
    }

    public String label() {
        return english ? "English" : "한국어";
    }

    public void toggle() {
        english = !english;
    }
}
