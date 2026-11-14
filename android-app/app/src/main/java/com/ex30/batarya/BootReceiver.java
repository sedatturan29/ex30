package com.ex30.batarya;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Orta ekran (Android Automotive) açıldığında okumayı kendiliğinden başlatır: son kullanılan
 * adaptöre bağlanmayı 10 dakika boyunca dener. Yalnızca orta ekran sürümünün manifestinde kayıtlıdır.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        if (!c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return;
        if (c.getSharedPreferences("ex30", Context.MODE_PRIVATE).getString("lastId", null) == null) return;   // hiç bağlanılmamış
        try { WebHost.onBoot(c.getApplicationContext()); } catch (Throwable ignored) { }
    }
}
