package com.ex30.batarya;

import android.app.Activity;
import android.car.Car;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
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
        NAMES.put(291504901, "ENGINE_RPM");
    }

    private final Activity act;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Car car;
    private CarPropertyManager cpm;
    private boolean started;

    CarBridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
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
                if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
            if (!missing.isEmpty() && !started) {
                started = true;
                act.requestPermissions(missing.toArray(new String[0]), REQ_CAR);
            } else {
                started = true;
                connect();
            }
        });
    }

    void onPermissionResult() { connect(); }

    private void connect() {
        try {
            car = Car.createCar(act);
            cpm = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            if (cpm == null) { status("Araç servisi yok"); return; }
            List<CarPropertyConfig> configs = cpm.getPropertyList();
            int n = 0;
            for (CarPropertyConfig cfg : configs) {
                int id = cfg.getPropertyId();
                float rate = 0f;
                if (cfg.getChangeMode() == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
                    rate = Math.max(cfg.getMinSampleRate(), Math.min(cfg.getMaxSampleRate(), 5f));
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
            js("window.__car&&__car(" + o + ")");
        } catch (Throwable ignored) { }
    }

    private void disconnect() {
        try { if (cpm != null) cpm.unregisterCallback(callback); } catch (Throwable ignored) { }
        try { if (car != null) car.disconnect(); } catch (Throwable ignored) { }
        cpm = null;
        car = null;
    }

    void shutdown() { disconnect(); }
}
