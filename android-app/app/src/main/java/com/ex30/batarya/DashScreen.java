package com.ex30.batarya;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

/**
 * Android Auto ve orta ekran sürüş ekranı: telefondaki Panel'in AYNISI.
 * Navigasyon uygulamalarına verilen çizim yüzeyine bir sanal ekran bağlanır; üzerinde ayna modunda
 * (https://ex30.app/ex30-reader.html#mirror) açılan bir WebView, ana uygulamadaki panelin durumunu
 * yarım saniyede bir alıp aynı görünümü çizer. Kaydırma hareketleri sayfayı kaydırır.
 */
public class DashScreen extends Screen {

    private final Handler h = new Handler(Looper.getMainLooper());
    private VirtualDisplay vd;
    private Presentation pres;
    private WebView mirror;
    private String page = "panel";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            final WebView m = mirror;
            if (m != null) WebHost.mirrorState(v -> {
                if (v != null && !"null".equals(v) && mirror == m) m.evaluateJavascript("window.ex30MirrorApply&&ex30MirrorApply(" + v + ")", null);
            });
            h.postDelayed(this, 500);
        }
    };

    private final SurfaceCallback cb = new SurfaceCallback() {
        @Override public void onSurfaceAvailable(@NonNull SurfaceContainer c) { h.post(() -> attach(c)); }
        @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer c) { h.post(DashScreen.this::detach); }
        @Override public void onVisibleAreaChanged(@NonNull Rect r) { }
        @Override public void onStableAreaChanged(@NonNull Rect r) { }
        @Override public void onScroll(float dx, float dy) {
            h.post(() -> { if (mirror != null) mirror.evaluateJavascript("window.scrollBy(0," + Math.round(dy) + ")", null); });
        }
        @Override public void onFling(float vx, float vy) {
            h.post(() -> { if (mirror != null) mirror.evaluateJavascript("window.scrollBy({top:" + Math.round(-vy / 4) + ",behavior:'smooth'})", null); });
        }
    };

    public DashScreen(@NonNull CarContext ctx) {
        super(ctx);
        try { ctx.getCarService(AppManager.class).setSurfaceCallback(cb); } catch (Throwable ignored) { }
        getLifecycle().addObserver((LifecycleEventObserver) (s, e) -> {
            if (e == Lifecycle.Event.ON_START) { h.removeCallbacks(tick); h.post(tick); }
            else if (e == Lifecycle.Event.ON_STOP) h.removeCallbacks(tick);
            else if (e == Lifecycle.Event.ON_DESTROY) detach();
        });
    }

    private void attach(SurfaceContainer c) {
        detach();
        Surface s = c.getSurface();
        if (s == null || c.getWidth() <= 0) return;
        try {
            Context ctx = getCarContext();
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            vd = dm.createVirtualDisplay("ex30-dash", c.getWidth(), c.getHeight(), c.getDpi(), s,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            pres = new Presentation(ctx, vd.getDisplay());
            mirror = new WebView(pres.getContext());
            mirror.setBackgroundColor(0xFF0E1116);
            WebSettings ws = mirror.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            mirror.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    return WebHost.serveAsset(v.getContext(), r.getUrl());
                }
            });
            pres.setContentView(mirror);
            pres.show();
            mirror.loadUrl("https://ex30.app/ex30-reader.html#mirror" + ("perf".equals(page) ? "-perf" : ""));
        } catch (Throwable e) {
            detach();
            drawError(c, "Ekran açılamadı: " + e.getMessage());
        }
    }

    private void detach() {
        try { if (pres != null) pres.dismiss(); } catch (Throwable ignored) { }
        try { if (mirror != null) mirror.destroy(); } catch (Throwable ignored) { }
        try { if (vd != null) vd.release(); } catch (Throwable ignored) { }
        pres = null; mirror = null; vd = null;
    }

    private void show(String p) {
        page = p;
        if (mirror != null) mirror.evaluateJavascript("goTab('" + p + "');window.scrollTo(0,0)", null);
    }

    private static void drawError(SurfaceContainer c, String msg) {
        try {
            Surface s = c.getSurface(); Canvas cv = s.lockCanvas(null);
            cv.drawColor(0xFF0E1116);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(0xFFE8ECF2); p.setTextSize(28);
            cv.drawText(msg, 40, 80, p);
            s.unlockCanvasAndPost(cv);
        } catch (Throwable ignored) { }
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        Action panel = new Action.Builder().setTitle("Panel").setOnClickListener(() -> show("panel")).build();
        Action perf = new Action.Builder().setTitle("Performans").setOnClickListener(() -> show("perf")).build();
        Action list = new Action.Builder().setTitle("Liste")
                .setOnClickListener(() -> getScreenManager().push(new BatteryScreen(getCarContext()))).build();
        return new NavigationTemplate.Builder()
                .setActionStrip(new ActionStrip.Builder().addAction(panel).addAction(perf).addAction(list).build())
                .build();
    }
}
