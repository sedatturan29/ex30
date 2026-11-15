package com.ex30.batarya;

import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.os.Handler;
import android.os.Looper;

/** Sistem, kayıtlı OBD adaptörü yayına başlayınca (araç açılınca) bu hizmeti uyandırır. */
public class AutoStartService extends CompanionDeviceService {

    private void appeared() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try { WebHost.onBoot(getApplicationContext()); } catch (Throwable ignored) { }
        });
    }

    @Override public void onDeviceAppeared(AssociationInfo info) { appeared(); }

    @SuppressWarnings("deprecation")
    @Override public void onDeviceAppeared(String address) { appeared(); }

    @Override public void onDeviceDisappeared(AssociationInfo info) { }

    @SuppressWarnings("deprecation")
    @Override public void onDeviceDisappeared(String address) { }
}
