package app.wktalkclip;

/** Where "카톡으로" sends the day's text: KakaoTalk directly when it is installed, else the system share sheet. */
public final class ShareRoute {
    public static final String KAKAO_PACKAGE = "com.kakao.talk";

    public enum Route { KAKAO, CHOOSER }

    private ShareRoute() {
    }

    public static Route route(boolean kakaoInstalled) {
        return kakaoInstalled ? Route.KAKAO : Route.CHOOSER;
    }

    /** When the direct send to KakaoTalk throws, the system share sheet is used instead. */
    public static Route afterFailure() {
        return Route.CHOOSER;
    }

    public static boolean nothingToShare(String text) {
        return text == null || text.trim().length() == 0;
    }
}
