package com.ex30.batarya;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Telefon ile orta ekran (AAOS) arasında Bluetooth köprüsü.
 * Telefon araca zaten eşli olduğundan ek kurulum gerekmez:
 *  - orta ekrandaki uygulama bir RFCOMM hizmeti açar ve bekler,
 *  - telefon eşli araç(lar)a bu hizmet kimliğiyle bağlanmayı dener.
 * Bağlantı satır satır JSON taşır. Aracın kendi verileri ("car") ve
 * kayıt eşitleme mesajları sayfaya window.__link(...) ile verilir.
 * Araca hiçbir şey yazılmaz; yalnızca iki uygulama birbiriyle konuşur.
 */
final class PhoneLink {
    static final UUID ID = UUID.fromString("6f1c2d3e-4b5a-4e30-9a30-e30a1b2c0001");
    private static final String NAME = "EX30 Reader köprü";

    private static PhoneLink inst;
    private final Context ctx;
    private final boolean isCar;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile WebView web;
    private volatile BluetoothSocket sock;
    private volatile OutputStream out;
    private volatile String peer = "";
    private volatile String state = "kapalı";
    private volatile boolean running;
    private final LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>(4000);

    static synchronized PhoneLink get(Context c) {
        if (inst == null) inst = new PhoneLink(c.getApplicationContext());
        return inst;
    }

    static PhoneLink peek() { return inst; }

    private PhoneLink(Context c) {
        ctx = c;
        isCar = c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    void attach(WebView w) { web = w; pushState(); }
    void detach(WebView w) { if (web == w) web = null; }

    boolean isCar() { return isCar; }
    boolean connected() { return sock != null && out != null; }
    @JavascriptInterface
    public String state() {
        try { return new JSONObject().put("role", isCar ? "car" : "phone").put("up", connected()).put("peer", peer).put("state", state).toString(); }
        catch (Exception e) { return "{}"; }
    }

    private boolean permitted() {
        return Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    /** Uygulama açıkken bir kez çağrılır; arka planda bağlantıyı kurar ve koparsa yeniden dener. */
    synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(isCar ? this::serverLoop : this::clientLoop, "ex30-link");
        t.setDaemon(true);
        t.start();
    }

    // ---- Orta ekran: hizmeti aç, telefonu bekle ----
    @SuppressWarnings("MissingPermission")
    private void serverLoop() {
        while (running) {
            BluetoothServerSocket ss = null;
            try {
                BluetoothAdapter a = adapter();
                if (a == null || !a.isEnabled() || !permitted()) { setState(a == null ? "Bluetooth yok" : !permitted() ? "Bluetooth izni yok" : "Bluetooth kapalı"); sleep(5000); continue; }
                ss = a.listenUsingRfcommWithServiceRecord(NAME, ID);
                setState("telefon bekleniyor");
                BluetoothSocket s = ss.accept();
                try { ss.close(); } catch (Exception ignored) { }
                ss = null;
                session(s);
            } catch (Throwable e) {
                setState("hizmet açılamadı: " + e.getMessage());
                sleep(20000);
            } finally {
                if (ss != null) try { ss.close(); } catch (Exception ignored) { }
            }
        }
    }

    // ---- Telefon: eşli araç(lar)a bağlanmayı dene ----
    @SuppressWarnings("MissingPermission")
    private void clientLoop() {
        SharedPreferences sp = ctx.getSharedPreferences("link", Context.MODE_PRIVATE);
        while (running) {
            try {
                BluetoothAdapter a = adapter();
                if (a == null || !a.isEnabled() || !permitted()) { setState(a == null ? "Bluetooth yok" : !permitted() ? "Bluetooth izni yok" : "Bluetooth kapalı"); sleep(20000); continue; }
                List<BluetoothDevice> list = candidates(a, sp.getString("addr", ""));
                if (list.isEmpty()) { setState("eşli araç bulunamadı"); sleep(60000); continue; }
                boolean ok = false;
                for (BluetoothDevice d : list) {
                    if (!running) break;
                    BluetoothSocket s = null;
                    try {
                        setState("deneniyor: " + d.getName());
                        try { a.cancelDiscovery(); } catch (Exception ignored) { }
                        s = d.createRfcommSocketToServiceRecord(ID);
                        s.connect();
                        sp.edit().putString("addr", d.getAddress()).apply();
                        ok = true;
                        session(s);
                        s = null;
                        break;
                    } catch (Throwable e) {
                        if (s != null) try { s.close(); } catch (Exception ignored) { }
                    }
                }
                if (!ok) setState("araç uygulamasına ulaşılamadı");
                // araca şu an Bluetooth ile bağlı bir cihaz varsa sık dene (araç uygulaması birkaç sn içinde açılır); yoksa pil için seyrek
                boolean carNear = false;
                for (BluetoothDevice d : list) if (Boolean.TRUE.equals(isConnected(d))) { carNear = true; break; }
                sleep(ok ? 3000 : carNear ? 8000 : 45000);
            } catch (Throwable e) {
                setState("hata: " + e.getMessage());
                sleep(30000);
            }
        }
    }

    /** Önce son bağlanılan cihaz, sonra şu an bağlı ve araç sınıfındaki eşli cihazlar. */
    @SuppressWarnings("MissingPermission")
    private List<BluetoothDevice> candidates(BluetoothAdapter a, String last) {
        List<BluetoothDevice> first = new ArrayList<>(), car = new ArrayList<>();
        for (BluetoothDevice d : a.getBondedDevices()) {
            if (d.getType() == BluetoothDevice.DEVICE_TYPE_LE) continue;   // OBD adaptörü gibi yalnız BLE olanlar
            Boolean conn = isConnected(d);
            BluetoothClass c = d.getBluetoothClass();
            int dc = c == null ? 0 : c.getDeviceClass();
            boolean isCarClass = dc == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || dc == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE;
            if (d.getAddress().equals(last)) { if (!Boolean.FALSE.equals(conn)) first.add(0, d); }
            else if (Boolean.TRUE.equals(conn) || (conn == null && isCarClass)) car.add(d);
        }
        first.addAll(car);
        return first;
    }

    /** Gizli API; okunamazsa null (bilinmiyor). */
    private static Boolean isConnected(BluetoothDevice d) {
        try { return (Boolean) BluetoothDevice.class.getMethod("isConnected").invoke(d); }
        catch (Throwable e) { return null; }
    }

    @SuppressWarnings("MissingPermission")
    private void session(BluetoothSocket s) {
        try {
            sock = s;
            out = s.getOutputStream();
            String n = null;
            try { n = s.getRemoteDevice().getName(); } catch (Throwable ignored) { }
            peer = n == null ? "" : n;
            q.clear();
            setState("bağlı");
            notifyLink(true);
            if (!isCar) main.post(() -> WebHost.onLink(ctx, true));
            Thread w = new Thread(this::writer, "ex30-link-w");
            w.setDaemon(true);
            w.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8), 65536);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                final String l = line;
                WebView v = web;
                if (v != null) main.post(() -> v.evaluateJavascript("window.__link&&__link(" + JSONObject.quote(l) + ")", null));
            }
        } catch (Throwable ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) { }
            sock = null;
            out = null;
            q.offer("");   // yazıcıyı uyandır
            setState("bağlantı koptu");
            notifyLink(false);
            if (!isCar) main.post(() -> WebHost.onLink(ctx, false));
        }
    }

    private void writer() {
        BluetoothSocket mine = sock;
        try {
            while (sock == mine && mine != null) {
                String l = q.take();
                OutputStream o = out;
                if (o == null || sock != mine) break;
                if (l.isEmpty()) continue;
                o.write((l + "\n").getBytes(StandardCharsets.UTF_8));
                if (q.isEmpty()) o.flush();
            }
        } catch (Throwable e) {
            try { if (mine != null) mine.close(); } catch (Exception ignored) { }
        }
    }

    /** Sayfadan gelen mesaj (tek satır JSON). Kuyruk doluysa düşer. */
    @JavascriptInterface
    public boolean send(String line) {
        if (!connected() || line == null) return false;
        return q.offer(line.replace('\n', ' '));
    }

    /** Orta ekran: aracın her yeni değerini telefona da ilet. */
    void car(String json) {
        if (isCar && connected() && q.size() < 2000) q.offer("{\"k\":\"car\",\"o\":" + json + "}");
    }

    private void setState(String s) {
        state = s;
        pushState();
    }
    private void pushState() {
        WebView v = web;
        if (v == null) return;
        final String st = state();
        main.post(() -> v.evaluateJavascript("window.__linkState&&__linkState(" + st + ")", null));
    }

    /** Bağlanınca bildirim: telefonda "Orta ekran bağlı", orta ekranda "Telefon bağlı". Kopunca kalkar. */
    private void notifyLink(boolean up) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (!up) { nm.cancel(3030); return; }
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(new android.app.NotificationChannel("link", "Telefon ve orta ekran", android.app.NotificationManager.IMPORTANCE_LOW));
            String title = isCar ? "Telefon bağlı" : "Araç (orta ekran) bağlı";
            String text = (peer.isEmpty() ? "" : peer + " · ") + "yolculuklar, şarjlar ve kayıtlar eşitleniyor";
            android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(ctx, "link") : new android.app.Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true);
            android.content.Intent it = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (it != null) b.setContentIntent(android.app.PendingIntent.getActivity(ctx, 3030, it, android.app.PendingIntent.FLAG_IMMUTABLE));
            nm.notify(3030, b.build());
        } catch (Throwable ignored) { }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
