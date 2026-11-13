package com.ex30.batarya;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * OBD bağlıyken çalışan ön plan hizmeti. Başka uygulamaya geçildiğinde ya da ekran
 * kapandığında Android'in uygulamayı dondurmasını/kapatmasını önler (bildirimde görünür).
 */
public class ReaderService extends Service {

    private static final String CH = "ex30_reader";
    private PowerManager.WakeLock wl;

    static void start(Context c) {
        Intent i = new Intent(c, ReaderService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, ReaderService.class)); } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Veri okuma", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Notification n = b.setContentTitle("EX30 Reader")
                .setContentText("Araç verileri okunuyor ve kaydediliyor")
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                startForeground(1, n, type);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            try { startForeground(1, n); } catch (Exception ignored) { stopSelf(); return START_NOT_STICKY; }
        }
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ex30:reader");
            wl.setReferenceCounted(false);
            wl.acquire(12 * 60 * 60 * 1000L);
        }
        MainActivity.setKeepAlive(true);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        MainActivity.setKeepAlive(false);
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
