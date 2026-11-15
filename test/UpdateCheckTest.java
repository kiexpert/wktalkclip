import app.wktalkclip.UpdateCheck;

public class UpdateCheckTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] a) {
        check("tag number of v0.1.34 is 34", UpdateCheck.tagNumber("v0.1.34") == 34);
        check("tag without the pattern is unknown", UpdateCheck.tagNumber("release-5") == -1 && UpdateCheck.tagNumber(null) == -1 && UpdateCheck.tagNumber("v0.1.") == -1);
        check("version code of build 34 is 134", UpdateCheck.codeForBuild(34) == 134);
        // installed app was built as run 33 => versionCode 133
        check("newer release is offered", UpdateCheck.decide(133, "v0.1.34", false) == UpdateCheck.Decision.NEWER);
        check("same release is not offered", UpdateCheck.decide(133, "v0.1.33", false) == UpdateCheck.Decision.NONE);
        check("older release is not offered", UpdateCheck.decide(133, "v0.1.20", false) == UpdateCheck.Decision.NONE);
        check("old manual versionCode 22 gets any CI release", UpdateCheck.decide(22, "v0.1.34", false) == UpdateCheck.Decision.NEWER);
        check("offline shows nothing", UpdateCheck.decide(133, "v0.1.99", true) == UpdateCheck.Decision.NONE);
        check("garbage tag shows nothing", UpdateCheck.decide(133, "nightly", false) == UpdateCheck.Decision.NONE);
        check("first check is due", UpdateCheck.dueForCheck(1000L, 0L));
        check("check within 6 h is not due", !UpdateCheck.dueForCheck(5L * 3600_000L, 1000L));
        check("check after 6 h is due", UpdateCheck.dueForCheck(6L * 3600_000L + 1000L, 1000L));
        String json = "{\"url\":\"x\",\"tag_name\":\"v0.1.40\",\"assets\":[{\"name\":\"other.txt\",\"browser_download_url\":\"https://h/o.txt\"},{\"name\":\"wktalkclip.apk\",\"browser_download_url\":\"https://github.com/r/releases/download/v0.1.40/wktalkclip.apk\"}]}";
        check("tag is read from the API answer", "v0.1.40".equals(UpdateCheck.parseTag(json)));
        check("the apk asset url is picked", "https://github.com/r/releases/download/v0.1.40/wktalkclip.apk".equals(UpdateCheck.parseApkUrl(json)));
        check("no apk asset means no url", UpdateCheck.parseApkUrl("{\"tag_name\":\"v0.1.1\",\"assets\":[]}") == null);
        check("a non-github url is never accepted", UpdateCheck.parseApkUrl("{\"assets\":[{\"browser_download_url\":\"https://evil.example/wktalkclip.apk\"}]}") == null);
        System.exit(fails == 0 ? 0 : 1);
    }
}
