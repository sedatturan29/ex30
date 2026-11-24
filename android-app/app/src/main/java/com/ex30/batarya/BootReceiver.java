package com.ex30.batarya;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Orta ekran (Android Automotive) açıldığında kendiliğinden başlar: araç açık kaldıkça aracın kendi
 * verileriyle yolculuk ve kayıt tutar, son kullanılan OBD adaptörüne de bağlanmayı dener (bağlanınca
 * kayıt OBD verileriyle sürer). Yalnızca orta ekran sürümünün manifestinde kayıtlıdır.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        if (!c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return;
        try { WebHost.onBoot(c.getApplicationContext()); } catch (Throwable ignored) { }
    }
}
