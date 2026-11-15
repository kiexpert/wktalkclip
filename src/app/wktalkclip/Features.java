package app.wktalkclip;

/** Switches for behaviour that changes what the app stores. Read the README before flipping one. */
public final class Features {
    private Features() {
    }

    /**
     * When true, every finished sentence is also appended, with a time stamp, to a dated text file
     * in the app's own private folder, so a killed app loses nothing. OFF: the app creates no
     * files, which is what the README promises. Turn on only with the owner's explicit approval.
     */
    public static final boolean SAVE_MEETING_FILE = false;
}
