package com.ex30.batarya;

import android.app.Activity;
import android.car.Car;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android Automotive (orta ekran): aracın uygulamalara açtığı değerleri Car API ile okur
 * ve sayfaya gönderir (window.__car). Yalnızca okur; araca hiçbir şey yazmaz.
 * Bu sınıf yalnızca araçta yüklenir (telefonda android.car kütüphanesi yoktur).
 */
@SuppressWarnings("deprecation")
public class CarBridge {

    static final int REQ_CAR = 12;

    /** Kullanıcının onaylaması gereken izinler (diğerleri kurulumda verilir). */
    private static final String[] RUNTIME_PERMS = {
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_TIRES_3P",
            "android.car.permission.CAR_MILEAGE_3P",
            "android.car.permission.CAR_ENGINE_DETAILED_3P",
    };

    private static final Map<Integer, String> NAMES = new HashMap<>();
    static {
        NAMES.put(291504910, "EV_BATTERY_AVERAGE_TEMPERATURE");
        NAMES.put(291504905, "EV_BATTERY_LEVEL");
        NAMES.put(291504909, "EV_CURRENT_BATTERY_CAPACITY");
        NAMES.put(291504390, "INFO_EV_BATTERY_CAPACITY");
        NAMES.put(291504908, "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE");
        NAMES.put(291504904, "RANGE_REMAINING");
        NAMES.put(289410881, "EV_CHARGE_STATE");
        NAMES.put(289410883, "EV_CHARGE_TIME_REMAINING");
        NAMES.put(291508032, "EV_CHARGE_PERCENT_LIMIT");
        NAMES.put(291508031, "EV_CHARGE_CURRENT_DRAW_LIMIT");
        NAMES.put(289410884, "EV_REGENERATIVE_BRAKING_STATE");
        NAMES.put(287310603, "EV_CHARGE_PORT_CONNECTED");
        NAMES.put(287310602, "EV_CHARGE_PORT_OPEN");
        NAMES.put(291504647, "PERF_VEHICLE_SPEED");
        NAMES.put(291504648, "PERF_VEHICLE_SPEED_DISPLAY");
        NAMES.put(291504644, "PERF_ODOMETER");
        NAMES.put(291504658, "INSTANTANEOUS_EV_EFFICIENCY");
        NAMES.put(392168201, "TIRE_PRESSURE");
        NAMES.put(291505923, "ENV_OUTSIDE_TEMPERATURE");
        NAMES.put(289408000, "GEAR_SELECTION");
        NAMES.put(289408001, "CURRENT_GEAR");
        NAMES.put(287310850, "PARKING_BRAKE_ON");
        NAMES.put(289408009, "IGNITION_STATE");
        NAMES.put(289408013, "EV_STOPPING_MODE");
        NAMES.put(287310855, "NIGHT_MODE");
        NAMES.put(286261505, "INFO_MAKE");
        NAMES.put(286261506, "INFO_MODEL");
        NAMES.put(289407235, "INFO_MODEL_YEAR");
        NAMES.put(0x11200403, "PARKING_BRAKE_AUTO_APPLY");
        NAMES.put(0x11400109, "INFO_EV_PORT_LOCATION");
        NAMES.put(0x11410105, "INFO_FUEL_TYPE");
        NAMES.put(0x11410107, "INFO_EV_CONNECTOR_TYPE");
        NAMES.put(0x1141010b, "INFO_EXTERIOR_DIMENSIONS");
        NAMES.put(0x1141010c, "INFO_MULTI_EV_PORT_LOCATIONS");
        NAMES.put(0x1540010a, "INFO_DRIVER_SEAT");
        NAMES.put(0x11510306, "WHEEL_TICK");
        NAMES.put(0x11200405, "FUEL_LEVEL_LOW");
        NAMES.put(291504901, "ENGINE_RPM");
        // Kabin: araç izin verirse (iklim izni) mevcut ve ayarlı sıcaklık
        NAMES.put(0x15600502, "HVAC_TEMPERATURE_CURRENT");
        NAMES.put(0x15600503, "HVAC_TEMPERATURE_SET");
    }

    // Hızlı değişen sinyaller: aracın izin verdiği en yüksek hızda (en çok 50/sn) okunur
    private static final java.util.Set<String> FAST = new java.util.HashSet<>(java.util.Arrays.asList(
        "PERF_VEHICLE_SPEED", "PERF_VEHICLE_SPEED_DISPLAY", "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", "ENGINE_RPM", "WHEEL_TICK", "INSTANTANEOUS_EV_EFFICIENCY"));
    private final java.util.Set<Integer> continuous = new java.util.HashSet<>();
    private final java.util.Map<String, Long> linkSent = new java.util.HashMap<>();
    private final java.util.Map<String, String> linkPend = new java.util.LinkedHashMap<>();
    private final StringBuilder batch = new StringBuilder();
    private boolean flushQueued;
    private long lastFlush;

    private final android.content.Context ctx;   // uygulama bağlamı: ekran açık olmasa da (sürüş ekranı/arka plan) çalışır
    private volatile Activity act;               // izin istemek için; yoksa sadece verilmiş izinlerle okunur
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Car car;
    private CarPropertyManager cpm;
    private boolean started;

    CarBridge(android.content.Context ctx, WebView web) {
        this.ctx = ctx.getApplicationContext();
        this.web = web;
    }

    void setActivity(Activity a) {
        act = a;
        // Ekran ilk kez açıldı ve eksik izin varsa şimdi iste
        if (a != null && started) main.post(() -> {
            List<String> missing = new ArrayList<>();
            for (String p : RUNTIME_PERMS) if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
            if (!missing.isEmpty()) a.requestPermissions(missing.toArray(new String[0]), REQ_CAR);
        });
    }

    private void js(final String code) { main.post(() -> web.evaluateJavascript(code, null)); }

    private void status(String s) { js("window.__carStatus&&__carStatus(" + JSONObject.quote(s) + ")"); }

    @JavascriptInterface
    public boolean available() { return true; }

    /** Sayfa çağırır: izinleri ister, sonra tüm okunabilir özelliklere abone olur. */
    @JavascriptInterface
    public void start() {
        main.post(() -> {
            if (started) { disconnect(); }
            List<String> missing = new ArrayList<>();
            for (String p : RUNTIME_PERMS)
                if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
            Activity a = act;
            if (!missing.isEmpty() && !started && a != null) {
                started = true;
                a.requestPermissions(missing.toArray(new String[0]), REQ_CAR);
            } else {
                started = true;
                connect();
            }
        });
    }

    void onPermissionResult() { connect(); }

    private void connect() {
        try {
            if (car != null) return;
            car = Car.createCar(ctx);
            cpm = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            if (cpm == null) { status("Araç servisi yok"); return; }
            List<CarPropertyConfig> configs = cpm.getPropertyList();
            int n = 0;
            for (CarPropertyConfig cfg : configs) {
                int id = cfg.getPropertyId();
                float rate = 0f;
                if (cfg.getChangeMode() == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
                    continuous.add(id);
                    float want = FAST.contains(NAMES.get(id)) ? 50f : 5f;
                    rate = Math.max(cfg.getMinSampleRate(), Math.min(cfg.getMaxSampleRate(), want));
                }
                try { cpm.registerCallback(callback, id, rate); n++; } catch (Throwable ignored) { }
                try {
                    int[] areas = cfg.getAreaIds();
                    if (areas == null || areas.length == 0) areas = new int[]{0};
                    for (int a : areas) {
                        try {
                            CarPropertyValue<?> v = cpm.getProperty(id, a);
                            if (v != null) push(v);
                        } catch (Throwable ignored) { }
                    }
                } catch (Throwable ignored) { }
            }
            status(n + " araç özelliği okunuyor");
        } catch (Throwable e) {
            status("Araç verisine bağlanılamadı: " + e);
        }
    }

    private final CarPropertyManager.CarPropertyEventCallback callback = new CarPropertyManager.CarPropertyEventCallback() {
        @Override
        public void onChangeEvent(CarPropertyValue value) { push(value); }

        @Override
        public void onErrorEvent(int propId, int zone) { }
    };

    private void push(CarPropertyValue<?> v) {
        try {
            int id = v.getPropertyId();
            JSONObject o = new JSONObject();
            o.put("id", id);
            String name = NAMES.get(id);
            o.put("name", name != null ? name : "0x" + Integer.toHexString(id));
            o.put("area", v.getAreaId());
            Object x = v.getValue();
            if (x instanceof Number || x instanceof Boolean || x instanceof String) o.put("value", x);
            else if (x instanceof Object[]) { JSONArray a = new JSONArray(); for (Object e : (Object[]) x) a.put(e); o.put("value", a); }
            else if (x instanceof byte[]) { StringBuilder sb = new StringBuilder(); for (byte b : (byte[]) x) sb.append(String.format("%02X", b)); o.put("value", sb.toString()); }
            else o.put("value", String.valueOf(x));
            // Ölçüm anı (aracın zaman damgası → duvar saati): ivme/0–100 hesabı için varış anından daha doğru
            long ts = v.getTimestamp();
            if (ts > 0) {
                long ms = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - ts) / 1000000L;
                if (Math.abs(ms - System.currentTimeMillis()) < 10000) o.put("ts", ms);
            }
            String j = o.toString();
            String key = o.getString("name") + "@" + v.getAreaId();
            boolean cont = continuous.contains(id);
            synchronized (batch) {
                if (batch.length() > 0) batch.append(',');
                batch.append(j);
                // Telefona: sürekli değişenler en çok 5/sn, durum değişiklikleri hemen
                if (cont) linkPend.put(key, j); else { linkPend.remove(key); sendLink(j, key); }
            }
            scheduleFlush();
        } catch (Throwable ignored) { }
    }

    private void sendLink(String j, String key) {
        PhoneLink l = PhoneLink.peek();
        if (l != null) { l.car(j); linkSent.put(key, SystemClock.elapsedRealtime()); }
    }

    // Değerler 40 ms'de bir tek seferde sayfaya verilir (her değer için ayrı JavaScript çağrısı yerine)
    private void scheduleFlush() {
        synchronized (batch) { if (flushQueued) return; flushQueued = true; }
        long wait = Math.max(0, 40 - (SystemClock.elapsedRealtime() - lastFlush));
        main.postDelayed(this::flush, wait);
    }

    private void flush() {
        String arr;
        synchronized (batch) {
            flushQueued = false; lastFlush = SystemClock.elapsedRealtime();
            if (batch.length() == 0) return;
            arr = "[" + batch + "]"; batch.setLength(0);
            long now = SystemClock.elapsedRealtime();
            java.util.Iterator<java.util.Map.Entry<String, String>> it = linkPend.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, String> e = it.next();
                Long t = linkSent.get(e.getKey());
                if (t == null || now - t >= 200) { sendLink(e.getValue(), e.getKey()); it.remove(); }
            }
            if (!linkPend.isEmpty()) main.postDelayed(this::scheduleFlush, 200);
        }
        web.evaluateJavascript("(function(a){if(window.__carB)__carB(a);else if(window.__car)a.forEach(function(o){__car(o)});})(" + arr + ")", null);
    }

    private void disconnect() {
        try { if (cpm != null) cpm.unregisterCallback(callback); } catch (Throwable ignored) { }
        try { if (car != null) car.disconnect(); } catch (Throwable ignored) { }
        cpm = null;
        car = null;
    }

    void shutdown() { disconnect(); }
}
