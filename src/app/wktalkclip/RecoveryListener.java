package app.wktalkclip;

/** Receives the text of a recovered span. Called on the main thread. */
public interface RecoveryListener {

    void recovered(long spanStart, String text);
}
