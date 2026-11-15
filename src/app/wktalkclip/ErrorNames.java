package app.wktalkclip;

/** Readable names for SpeechRecognizer error codes, for the diagnostics screen. */
public final class ErrorNames {
    private ErrorNames() {
    }

    public static String name(int code) {
        switch (code) {
            case 1: return "NETWORK_TIMEOUT(1)";
            case 2: return "NETWORK(2)";
            case 3: return "AUDIO(3) 마이크 입력 문제";
            case 4: return "SERVER(4)";
            case 5: return "CLIENT(5)";
            case 6: return "SPEECH_TIMEOUT(6)";
            case 7: return "NO_MATCH(7)";
            case 8: return "RECOGNIZER_BUSY(8)";
            case 9: return "INSUFFICIENT_PERMISSIONS(9)";
            case 10: return "TOO_MANY_REQUESTS(10)";
            case 11: return "SERVER_DISCONNECTED(11)";
            case 12: return "LANGUAGE_NOT_SUPPORTED(12)";
            case 13: return "LANGUAGE_UNAVAILABLE(13) 모델 없음";
            case 14: return "CANNOT_CHECK_SUPPORT(14)";
            case 15: return "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS(15)";
            default: return "UNKNOWN(" + code + ")";
        }
    }
}
