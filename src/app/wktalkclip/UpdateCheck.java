package app.wktalkclip;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a newer release exists. Pure logic, no network and no files here.
 * A CI build number N is the release tag v0.1.N and the app's versionCode is 100 + N.
 */
public final class UpdateCheck {
    public enum Decision { NONE, NEWER }

    private static final Pattern TAG = Pattern.compile("^v0\\.1\\.(\\d{1,6})$");
    private static final Pattern TAG_FIELD = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern URL_FIELD = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final long SIX_HOURS_MS = 6L * 3600L * 1000L;

    private UpdateCheck() {
    }

    /** 34 for "v0.1.34", -1 when the tag does not look like one of our releases. */
    public static int tagNumber(String tag) {
        if (tag == null) {
            return -1;
        }
        Matcher m = TAG.matcher(tag.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    public static int codeForBuild(int buildNumber) {
        return 100 + buildNumber;
    }

    public static Decision decide(int myVersionCode, String latestTag, boolean offline) {
        if (offline) {
            return Decision.NONE;
        }
        int n = tagNumber(latestTag);
        if (n < 0) {
            return Decision.NONE;
        }
        return codeForBuild(n) > myVersionCode ? Decision.NEWER : Decision.NONE;
    }

    /** At most one check per 6 hours (the time is kept in memory only). */
    public static boolean dueForCheck(long nowMs, long lastCheckMs) {
        return lastCheckMs == 0L || nowMs - lastCheckMs >= SIX_HOURS_MS;
    }

    public static String parseTag(String json) {
        if (json == null) {
            return null;
        }
        Matcher m = TAG_FIELD.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** The wktalkclip.apk asset of the release, only when it lives on github.com. */
    public static String parseApkUrl(String json) {
        if (json == null) {
            return null;
        }
        Matcher m = URL_FIELD.matcher(json);
        while (m.find()) {
            String u = m.group(1);
            if (u.startsWith("https://github.com/") && u.endsWith("/wktalkclip.apk")) {
                return u;
            }
        }
        return null;
    }
}
