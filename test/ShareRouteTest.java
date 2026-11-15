import app.wktalkclip.ShareRoute;

public class ShareRouteTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] a) {
        check("package name of KakaoTalk", "com.kakao.talk".equals(ShareRoute.KAKAO_PACKAGE));
        check("KakaoTalk installed: send to it directly", ShareRoute.route(true) == ShareRoute.Route.KAKAO);
        check("KakaoTalk missing: system share sheet", ShareRoute.route(false) == ShareRoute.Route.CHOOSER);
        check("KakaoTalk intent failed: system share sheet", ShareRoute.afterFailure() == ShareRoute.Route.CHOOSER);
        check("nothing to share when blank", ShareRoute.nothingToShare("") && ShareRoute.nothingToShare("  \n"));
        check("something to share when there is text", !ShareRoute.nothingToShare("[wktalkclip 2026-11-16]"));
        check("null is nothing to share", ShareRoute.nothingToShare(null));
        System.exit(fails == 0 ? 0 : 1);
    }
}
