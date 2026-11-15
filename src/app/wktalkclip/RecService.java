package app.wktalkclip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the process alive and the microphone allowed while a meeting is being recorded with the
 * screen off or the phone in a pocket. It holds no audio and no text: it only shows the
 * "recording" notification, runs as a microphone foreground service and holds a CPU wake lock.
 * Started when listening starts, stopped when it stops. No files, no network.
 */
public class RecService extends Service {

    private static final String CHANNEL = "rec";
    private static final int NOTE_ID = 7;
    private static final long WAKE_MAX_MS = 6L * 60L * 60L * 1000L;

    private PowerManager.WakeLock wake;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "녹음 중",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("wktalkclip")
                .setContentText("회의를 받아쓰는 중이에요")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wktalkclip:rec");
                wake.acquire(WAKE_MAX_MS);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) {
            wake.release();
        }
        wake = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
