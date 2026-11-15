package com.ex30.batarya;

import android.app.Activity;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothLeDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.bluetooth.le.ScanFilter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Build;

import java.util.List;
import java.util.regex.Pattern;

/**
 * "Arabaya binince otomatik başlat": OBD adaptörü Android'in eşlik eden cihaz (Companion Device)
 * sistemine kaydedilir. Araç açılıp adaptör yayına başlayınca sistem uygulamayı uyandırır
 * (AutoStartService), uygulama da telefona dokunmadan bağlanıp kayda başlar. Android 12+.
 */
final class AutoStart {

    static final int REQ_ASSOC = 21;
    private static java.util.function.Consumer<String> pendingCb;

    static boolean supported() { return Build.VERSION.SDK_INT >= 31; }

    /** "on" | "off" | "unsupported" */
    static String state(Context c) {
        if (!supported()) return "unsupported";
        try {
            CompanionDeviceManager m = c.getSystemService(CompanionDeviceManager.class);
            String addr = c.getSharedPreferences("ex30", Context.MODE_PRIVATE).getString("autoAddr", null);
            if (m == null || addr == null) return "off";
            if (Build.VERSION.SDK_INT >= 33) {
                for (AssociationInfo a : m.getMyAssociations())
                    if (a.getDeviceMacAddress() != null && addr.equalsIgnoreCase(a.getDeviceMacAddress().toString())) return "on";
                return "off";
            }
            @SuppressWarnings("deprecation") List<String> l = m.getAssociations();
            for (String s : l) if (addr.equalsIgnoreCase(s)) return "on";
            return "off";
        } catch (Throwable e) { return "off"; }
    }

    /** Sistem eşleştirme penceresini açar; sonuç "ok" | "hata: …" olarak cb'ye gelir. */
    static void setup(Activity act, String lastAddr, java.util.function.Consumer<String> cb) {
        if (!supported()) { cb.accept("hata: Android 12 ve üstü gerekiyor"); return; }
        CompanionDeviceManager m = act.getSystemService(CompanionDeviceManager.class);
        if (m == null) { cb.accept("hata: bu telefonda desteklenmiyor"); return; }
        ScanFilter.Builder sf = new ScanFilter.Builder();
        BluetoothLeDeviceFilter.Builder f = new BluetoothLeDeviceFilter.Builder();
        if (lastAddr != null && lastAddr.matches("(?i)[0-9A-F]{2}(:[0-9A-F]{2}){5}")) sf.setDeviceAddress(lastAddr.toUpperCase());
        else f.setNamePattern(Pattern.compile("(?i).*vlink.*|.*obd.*|.*elm.*"));
        f.setScanFilter(sf.build());
        AssociationRequest req = new AssociationRequest.Builder().addDeviceFilter(f.build()).setSingleDevice(true).build();
        pendingCb = cb;
        m.associate(req, new CompanionDeviceManager.Callback() {
            @Override public void onDeviceFound(IntentSender chooser) { launch(act, chooser); }
            @Override public void onAssociationPending(IntentSender chooser) { launch(act, chooser); }
            @Override public void onAssociationCreated(AssociationInfo info) {
                if (info.getDeviceMacAddress() != null) done(act, info.getDeviceMacAddress().toString());
            }
            @Override public void onFailure(CharSequence error) { finish("hata: " + (error == null ? "cihaz bulunamadı (araç açık mı?)" : error)); }
        }, null);
    }

    private static void launch(Activity act, IntentSender s) {
        try { act.startIntentSenderForResult(s, REQ_ASSOC, null, 0, 0, 0); }
        catch (Exception e) { finish("hata: " + e.getMessage()); }
    }

    /** Android 12: sonuç onActivityResult ile gelir. */
    static void onActivityResult(Activity act, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) { finish("hata: iptal edildi"); return; }
        if (Build.VERSION.SDK_INT >= 33) {
            AssociationInfo info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo.class);
            if (info != null && info.getDeviceMacAddress() != null) { done(act, info.getDeviceMacAddress().toString()); return; }
        }
        Object dev = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE);
        String addr = null;
        if (dev instanceof android.bluetooth.BluetoothDevice) addr = ((android.bluetooth.BluetoothDevice) dev).getAddress();
        else if (dev instanceof android.bluetooth.le.ScanResult) addr = ((android.bluetooth.le.ScanResult) dev).getDevice().getAddress();
        if (addr != null) done(act, addr); else if (Build.VERSION.SDK_INT < 33) finish("hata: cihaz bilgisi alınamadı");
    }

    private static void done(Context c, String addr) {
        c.getSharedPreferences("ex30", Context.MODE_PRIVATE).edit().putString("autoAddr", addr).putString("lastId", addr).apply();
        try {
            CompanionDeviceManager m = c.getSystemService(CompanionDeviceManager.class);
            //noinspection deprecation
            m.startObservingDevicePresence(addr);
            finish("ok");
        } catch (Throwable e) { finish("hata: " + e.getMessage()); }
    }

    static void disable(Context c) {
        try {
            CompanionDeviceManager m = c.getSystemService(CompanionDeviceManager.class);
            String addr = c.getSharedPreferences("ex30", Context.MODE_PRIVATE).getString("autoAddr", null);
            if (m != null && addr != null) {
                try { m.stopObservingDevicePresence(addr); } catch (Throwable ignored) { }
                if (Build.VERSION.SDK_INT >= 33) {
                    for (AssociationInfo a : m.getMyAssociations()) if (a.getDeviceMacAddress() != null && addr.equalsIgnoreCase(a.getDeviceMacAddress().toString())) m.disassociate(a.getId());
                } else {
                    //noinspection deprecation
                    m.disassociate(addr);
                }
            }
        } catch (Throwable ignored) { }
        c.getSharedPreferences("ex30", Context.MODE_PRIVATE).edit().remove("autoAddr").apply();
    }

    private static void finish(String r) {
        java.util.function.Consumer<String> cb = pendingCb; pendingCb = null;
        if (cb != null) cb.accept(r);
    }
}
