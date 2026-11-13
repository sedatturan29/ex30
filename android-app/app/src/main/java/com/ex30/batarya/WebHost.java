package com.ex30.batarya;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Uygulamanın tek WebView'u. Telefon ekranı açıkken MainActivity'de görünür; Android Auto'dan
 * başlatıldığında ekransız çalışır ve son adaptöre kendiliğinden bağlanır. Böylece okuma, kayıt ve
 * otomatik yolculuk telefondaki ile aynı kodla yürür; ekran sonradan açılırsa aynı oturum görünür.
 */
final class WebHost {

    private static WebHost inst;
    private static volatile boolean autoReq;
    private static volatile boolean keepAlive;
    private static volatile boolean carSession;

    final WebView web;
    final BleBridge ble;
    private final MutableContextWrapper wrap;
    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Activity act;
    private CarBridge carBridge;
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!keepAlive) return;
            web.evaluateJavascript("window.__ex30tick&&__ex30tick()", null);
            h.postDelayed(this, 100);
        }
    };

    static boolean autoRequested() { return autoReq; }

    static WebHost get(Context c) {
        if (inst == null) inst = new WebHost(c.getApplicationContext());
        return inst;
    }

    static WebHost peek() { return inst; }

    /** Android Auto ekranı açıldı: ekran yoksa ekransız başlat ve bağlan. */
    static void onCarStart(Context c) {
        carSession = true;
        autoReq = true;
        WebHost w = get(c);
        w.web.evaluateJavascript("window.ex30AutoConnect&&ex30AutoConnect()", null);
    }

    /** Android Auto oturumu kapandı. Bağlantı sürüyorsa okumaya devam edilir (araç kapanınca biter). */
    static void onCarStop() {
        carSession = false;
        autoReq = false;
        maybeRelease();
    }

    static void setKeepAlive(boolean on) {
        keepAlive = on;
        WebHost w = inst;
        if (w != null) w.h.post(() -> { w.h.removeCallbacks(w.tick); if (on) w.h.post(w.tick); });
        if (!on) maybeRelease();
    }

    /** Ekran, Android Auto ve bağlantı yoksa WebView'u kapat. */
    private static void maybeRelease() {
        WebHost w = inst;
        if (w == null) return;
        w.h.postDelayed(() -> {
            if (inst == w && w.act == null && !carSession && !keepAlive) w.destroy();
        }, 30_000);
    }

    private WebHost(Context app) {
        this.app = app;
        wrap = new MutableContextWrapper(app);
        web = new WebView(wrap);
        if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.setBackgroundColor(0xFF0E1116);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        ble = new BleBridge(app, web);
        web.addJavascriptInterface(ble, "AndroidBle");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else if (act != null) {
                    geoCb = callback;
                    geoOrigin = origin;
                    act.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, MainActivity.REQ_GEO);
                } else {
                    callback.invoke(origin, false, false);
                }
            }
        });

        // https://ex30.app/<dosya> adresleri uygulamanın içindeki dosyalardan sunulur
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!"ex30.app".equals(u.getHost())) return null;
                String path = u.getPath();
                String name = (path == null || path.equals("/") || path.isEmpty()) ? "ex30-reader.html" : path.substring(1);
                if (name.endsWith(".html")) {
                    String html = withPolyfill(readAsset(name));
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                }
                try {
                    String mime = name.endsWith(".js") ? "text/javascript" : name.endsWith(".css") ? "text/css" : "application/octet-stream";
                    return new WebResourceResponse(mime, "UTF-8", app.getAssets().open(name));
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
                }
            }
        });
        web.loadUrl("https://ex30.app/ex30-reader.html");
    }

    /** Telefon ekranı açıldı: WebView'u ekrana yerleştir. */
    void attach(Activity a, ViewGroup parent) {
        act = a;
        wrap.setBaseContext(a);
        ble.setActivity(a);
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        parent.addView(web, new ViewGroup.LayoutParams(-1, -1));
        if (carBridge == null && a.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            try {
                carBridge = new CarBridge(a, web);
                web.addJavascriptInterface(carBridge, "AndroidCar");
                web.reload();   // AndroidCar arayüzü yeni sayfada görünür
            } catch (Throwable ignored) { }
        }
    }

    /** Telefon ekranı kapandı: bağlantı ya da Android Auto sürüyorsa WebView yaşamaya devam eder. */
    void detach(Activity a) {
        if (act != a) return;
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        act = null;
        ble.setActivity(null);
        wrap.setBaseContext(app);
        if (!keepAlive && !carSession) destroy();
    }

    void onPermissionResult(int requestCode) {
        if (requestCode == CarBridge.REQ_CAR) {
            if (carBridge != null) carBridge.onPermissionResult();
        } else if (requestCode == MainActivity.REQ_BLE) {
            ble.onPermissionResult();
        } else if (requestCode == MainActivity.REQ_GEO && geoCb != null) {
            boolean ok = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            geoCb.invoke(geoOrigin, ok, false);
            geoCb = null;
        }
    }

    private void destroy() {
        if (inst == this) inst = null;
        h.removeCallbacksAndMessages(null);
        ReaderService.stop(app);
        try { ble.shutdown(); } catch (Exception ignored) { }
        if (carBridge != null) try { carBridge.shutdown(); } catch (Exception ignored) { }
        try { web.destroy(); } catch (Exception ignored) { }
    }

    boolean canGoBack() { return web.canGoBack(); }

    void goBack() { web.goBack(); }

    private String withPolyfill(String html) {
        String inject = "<script>" + readAsset("ble-polyfill.js") + "</script>";
        int i = html.indexOf("<head>");
        return i >= 0 ? html.substring(0, i + 6) + inject + html.substring(i + 6) : inject + html;
    }

    private String readAsset(String name) {
        try (InputStream in = app.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<html><body style='color:#fff;background:#000'>Dosya okunamadı: " + name + "</body></html>";
        }
    }
}
