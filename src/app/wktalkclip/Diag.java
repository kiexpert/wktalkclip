package app.wktalkclip;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.SpeechRecognizer;

import java.util.List;

/** Reads the phone's state that decides whether dictation can work. Screen only, nothing is stored or sent. */
public final class Diag {
    private Diag() {
    }

    public static String system(Context c) {
        StringBuilder sb = new StringBuilder();
        line(sb, "폰", Build.MANUFACTURER + " " + Build.MODEL + " / 안드로이드 " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        try {
            line(sb, "인식기 사용 가능", "기기 안=" + SpeechRecognizer.isOnDeviceRecognitionAvailable(c)
                    + " / 일반=" + SpeechRecognizer.isRecognitionAvailable(c));
        } catch (RuntimeException e) {
            line(sb, "인식기 사용 가능", "확인 실패 " + e.getClass().getSimpleName());
        }
        try {
            line(sb, "마이크 권한", String.valueOf(c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED));
            if (Build.VERSION.SDK_INT >= 33) {
                line(sb, "알림 권한", String.valueOf(c.checkSelfPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED));
            }
        } catch (RuntimeException e) {
            line(sb, "권한", "확인 실패");
        }
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            line(sb, "오디오 모드", modeName(am.getMode()) + " / 마이크 음소거=" + am.isMicrophoneMute()
                    + " / 스피커폰=" + am.isSpeakerphoneOn());
            line(sb, "볼륨", "미디어 " + am.getStreamVolume(AudioManager.STREAM_MUSIC) + "/"
                    + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) + ", 통화 "
                    + am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) + ", 벨소리 모드 " + am.getRingerMode());
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo comm = am.getCommunicationDevice();
                line(sb, "통화 장치", comm == null ? "없음" : "type " + comm.getType() + " " + comm.getProductName());
            }
            StringBuilder in = new StringBuilder();
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                in.append("[type ").append(d.getType()).append(' ').append(d.getProductName()).append("] ");
            }
            line(sb, "입력 장치", in.length() == 0 ? "없음" : in.toString());
            List<AudioRecordingConfiguration> recs = am.getActiveRecordingConfigurations();
            StringBuilder rs = new StringBuilder();
            for (AudioRecordingConfiguration r : recs) {
                rs.append("[소스 ").append(r.getClientAudioSource());
                if (Build.VERSION.SDK_INT >= 29) {
                    rs.append(" 무음처리=").append(r.isClientSilenced());
                }
                rs.append("] ");
            }
            line(sb, "지금 녹음 중인 앱 수", recs.size() + " " + rs);
        } catch (RuntimeException e) {
            line(sb, "오디오", "확인 실패 " + e.getClass().getSimpleName());
        }
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            line(sb, "절전", "절전모드=" + pm.isPowerSaveMode() + " / 배터리 최적화 제외="
                    + pm.isIgnoringBatteryOptimizations(c.getPackageName()) + " / 화면켜짐=" + pm.isInteractive());
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            line(sb, "방해금지 필터", String.valueOf(nm.getCurrentInterruptionFilter()) + " (1=모두 허용)");
        } catch (RuntimeException e) {
            line(sb, "절전/방해금지", "확인 실패");
        }
        try {
            List<ResolveInfo> svc = c.getPackageManager().queryIntentServices(
                    new Intent("android.speech.RecognitionService"), 0);
            StringBuilder names = new StringBuilder();
            for (ResolveInfo r : svc) {
                names.append(r.serviceInfo.packageName).append(' ');
            }
            line(sb, "음성 인식 서비스", names.length() == 0 ? "없음" : names.toString());
            line(sb, "기본 인식 서비스", String.valueOf(Settings.Secure.getString(
                    c.getContentResolver(), "voice_recognition_service")));
        } catch (RuntimeException e) {
            line(sb, "음성 인식 서비스", "확인 실패");
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String k, String v) {
        sb.append(k).append(": ").append(v).append('\n');
    }

    private static String modeName(int m) {
        switch (m) {
            case AudioManager.MODE_NORMAL: return "NORMAL";
            case AudioManager.MODE_RINGTONE: return "RINGTONE";
            case AudioManager.MODE_IN_CALL: return "IN_CALL";
            case AudioManager.MODE_IN_COMMUNICATION: return "IN_COMMUNICATION";
            default: return String.valueOf(m);
        }
    }
}
