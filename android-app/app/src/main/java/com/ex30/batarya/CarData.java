package com.ex30.batarya;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;

/** Telefondaki sayfanın gönderdiği son ölçüm; Android Auto ekranı buradan okur. */
final class CarData {

    interface Listener { void onCarDataChanged(); }

    private static volatile JSONObject latest;
    private static volatile long receivedAt;
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private CarData() { }

    static void update(String json) {
        try {
            latest = new JSONObject(json);
            receivedAt = System.currentTimeMillis();
        } catch (Exception e) {
            return;
        }
        main.post(() -> { for (Listener l : listeners) l.onCarDataChanged(); });
    }

    static JSONObject latest() { return latest; }

    static long ageMs() { return receivedAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - receivedAt; }

    static void add(Listener l) { listeners.addIfAbsent(l); }

    static void remove(Listener l) { listeners.remove(l); }
}
