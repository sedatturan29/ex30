package com.ex30.batarya;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.net.Uri;
import android.webkit.GeolocationPermissions;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * EX30 Batarya: HTML uygulamasını tam ekran gösterir. Uygulama içi tarayıcı Web Bluetooth'u
 * desteklemediği için navigator.bluetooth, BleBridge üzerinden Android'in kendi BLE
 * arayüzüne bağlanır (ble-polyfill.js).
 */
public class MainActivity extends Activity {

    static final int REQ_BLE = 10;
    static final int REQ_GEO = 11;

    private WebView web;
    private BleBridge ble;
    private Object carBridge;   // yalnızca Android Automotive'de (CarBridge)
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF0E1116);
        getWindow().setNavigationBarColor(0xFF0E1116);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0E1116);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF0E1116);
        root.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        // Sistem çubuklarının (araçta alt/üst çubuk) altında kalmamak için kenar boşlukları
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        ble = new BleBridge(this, web);
        web.addJavascriptInterface(ble, "AndroidBle");
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            try {
                CarBridge cb = new CarBridge(this, web);
                carBridge = cb;
                web.addJavascriptInterface(cb, "AndroidCar");
            } catch (Throwable ignored) { }
        }

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    geoCb = callback;
                    geoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO);
                }
            }
        });

        // https://ex30.app/<dosya> adresleri uygulamanın içindeki dosyalardan sunulur;
        // böylece ana sayfa ile Canlı panel arasında bağlantılar çalışır.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!"ex30.app".equals(u.getHost())) return null;
                String path = u.getPath();
                String name = (path == null || path.equals("/") || path.isEmpty()) ? "ex30-batarya.html" : path.substring(1);
                if (name.endsWith(".html")) {
                    String html = withPolyfill(readAsset(name));
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                }
                try {
                    String mime = name.endsWith(".js") ? "text/javascript" : name.endsWith(".css") ? "text/css" : "application/octet-stream";
                    return new WebResourceResponse(mime, "UTF-8", getAssets().open(name));
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
                }
            }
        });
        web.loadUrl("https://ex30.app/ex30-batarya.html");
    }

    private String withPolyfill(String html) {
        String inject = "<script>" + readAsset("ble-polyfill.js") + "</script>";
        int i = html.indexOf("<head>");
        return i >= 0 ? html.substring(0, i + 6) + inject + html.substring(i + 6) : inject + html;
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<html><body style='color:#fff;background:#000'>Dosya okunamadı: " + name + "</body></html>";
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CarBridge.REQ_CAR) {
            if (carBridge != null) ((CarBridge) carBridge).onPermissionResult();
        } else if (requestCode == REQ_BLE) {
            ble.onPermissionResult();
        } else if (requestCode == REQ_GEO && geoCb != null) {
            boolean ok = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            geoCb.invoke(geoOrigin, ok, false);
            geoCb = null;
        }
    }

    @Override
    protected void onDestroy() {
        ble.shutdown();
        if (carBridge != null) ((CarBridge) carBridge).shutdown();
        web.destroy();
        super.onDestroy();
    }
}
