package it.keepgold.monitor;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;

// Keeps the web server alive and brings the show back to the front return_minutes after someone left it.
public final class KioskService extends Service {
    static final String ACTION_ALIVE = "it.keepgold.monitor.ALIVE";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final long CHECK_MS = 5000;

    // Set by MainActivity: 0 while the show is on screen, otherwise when it left.
    static volatile long awaySince = 0;

    static volatile MainActivity activity;

    private WebServer server;

    static void start(Context context) {
        context.startService(new Intent(context, KioskService.class));
    }

    static void contentChanged(boolean restart) {
        MAIN.post(() -> {
            MainActivity a = activity;
            if (a != null) a.onContentChanged(restart);
        });
    }

    static void bringToFront(Context context) {
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.startActivity(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, notification());
        scheduleAlive();
        server = new WebServer(this);
        startServer();
        MAIN.postDelayed(watchdog, CHECK_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        MAIN.removeCallbacks(watchdog);
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            startServer();
            Store store = Store.get(KioskService.this);

            // After Android killed the app only this service comes back: reopen the show at once.
            if (activity == null && store.autostart) {
                bringToFront(KioskService.this);
                MAIN.postDelayed(this, CHECK_MS * 4);
                return;
            }
            long away = awaySince;
            long limit = store.returnMinutes * 60_000L;
            if (away > 0 && SystemClock.elapsedRealtime() - away >= limit) {
                awaySince = SystemClock.elapsedRealtime();
                bringToFront(KioskService.this);
            }
            MAIN.postDelayed(this, CHECK_MS);
        }
    };

    // Port busy (for example by another app): the watchdog tries again every few seconds.
    private void startServer() {
        if (server.isAlive()) return;
        try {
            server.begin();
        } catch (IOException e) {
            // Retried by the watchdog.
        }
    }

    // If Android kills the process for memory, this alarm starts the service again within 5 minutes.
    private void scheduleAlive() {
        Intent i = new Intent(this, BootReceiver.class).setAction(ACTION_ALIVE);
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 5 * 60_000L, 5 * 60_000L, pi);
    }

    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("kiosk", "TV", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, "kiosk");
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("TV Palestra").build();
    }
}
