package app.wktalkclip;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The only network use of the app: it reads the latest release of the public repository and
 * downloads its APK, to install a newer version. Nothing the user says or the app writes is ever
 * sent. The APK is streamed straight into the system installer; no file is created.
 */
public final class UpdateNet {
    private static final String API = "https://api.github.com/repos/kiexpert/wktalkclip/releases/latest";
    public static final String FALLBACK_APK =
            "https://github.com/kiexpert/wktalkclip/releases/latest/download/wktalkclip.apk";
    private static final int MAX_JSON = 400000;
    private static final long MIN_APK = 20000L; // the real APK is about 45 KB

    public interface Latest {
        void onLatest(String tag, String apkUrl);

        void onFail();
    }

    public interface Progress {
        void onText(String text);

        void onDone(boolean started);
    }

    private UpdateNet() {
    }

    public static void fetchLatest(final Handler main, final Latest cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String tag = null;
                String url = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setRequestProperty("User-Agent", "wktalkclip");
                    if (c.getResponseCode() == 200) {
                        String body = readAll(c.getInputStream(), MAX_JSON);
                        tag = UpdateCheck.parseTag(body);
                        url = UpdateCheck.parseApkUrl(body);
                    }
                    c.disconnect();
                } catch (IOException e) {
                    tag = null;
                } catch (RuntimeException e) {
                    tag = null;
                }
                final String t = tag;
                final String u = url;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (t != null && u != null) {
                            cb.onLatest(t, u);
                        } else {
                            cb.onFail();
                        }
                    }
                });
            }
        }, "update-check").start();
    }

    /** Streams apkUrl into a PackageInstaller session and commits it; the system asks the user to confirm. */
    public static void install(final Context ctx, final String apkUrl, final Handler main, final Progress cb) {
        if (apkUrl == null || !apkUrl.startsWith("https://github.com/")) {
            cb.onDone(false);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                PackageInstaller.Session session = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", "wktalkclip");
                    if (c.getResponseCode() == 200) {
                        long total = c.getContentLengthLong();
                        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                        p.setAppPackageName(ctx.getPackageName());
                        int id = pi.createSession(p);
                        session = pi.openSession(id);
                        OutputStream out = session.openWrite("wktalkclip.apk", 0L, total > 0 ? total : -1L);
                        InputStream in = c.getInputStream();
                        byte[] buf = new byte[65536];
                        long done = 0L;
                        long lastShown = 0L;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            if (done - lastShown > 500000L) {
                                lastShown = done;
                                final String msg = "새 버전 받는 중 " + (done / 1000L) + " KB"
                                        + (total > 0 ? " / " + (total / 1000L) + " KB" : "");
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        cb.onText(msg);
                                    }
                                });
                            }
                        }
                        if (done >= MIN_APK) {
                            session.fsync(out);
                            out.close();
                            Intent result = new Intent(ctx, UpdateResultReceiver.class);
                            PendingIntent pending = PendingIntent.getBroadcast(ctx, id, result,
                                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                            session.commit(pending.getIntentSender());
                            session = null;
                            ok = true;
                        } else {
                            out.close();
                        }
                    }
                    c.disconnect();
                } catch (IOException e) {
                    ok = false;
                } catch (RuntimeException e) {
                    ok = false;
                } finally {
                    if (session != null) {
                        try {
                            session.abandon();
                        } catch (RuntimeException e) {
                            // nothing left to clean
                        }
                    }
                }
                final boolean fin = ok;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(fin);
                    }
                });
            }
        }, "update-install").start();
    }

    private static String readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0 && bo.size() < max) {
            bo.write(b, 0, n);
        }
        return bo.toString("UTF-8");
    }
}
