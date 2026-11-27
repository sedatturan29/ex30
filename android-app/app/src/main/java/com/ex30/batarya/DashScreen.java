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
import androidx.car.app.model.CarIcon;
import androidx.core.graphics.drawable.IconCompat;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
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
    private String page = "drive";
    private int sw, sh, sdpi;
    private Rect visible;   // medya kartı ve düğmeler dışında kalan alan

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
        @Override public void onVisibleAreaChanged(@NonNull Rect r) { h.post(() -> { visible = new Rect(r); applyInset(); }); }
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
            sw = c.getWidth(); sh = c.getHeight(); sdpi = c.getDpi();
            mirror.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    return WebHost.serveAsset(v.getContext(), r.getUrl());
                }
                @Override public void onPageFinished(WebView v, String url) { applyInset(); applyFollow(); }
            });
            pres.setContentView(mirror);
            pres.show();
            mirror.loadUrl("https://ex30.app/ex30-reader.html#mirror-" + ("follow".equals(page) ? "drive" : page));
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

    void show(String p) {
        page = p;
        if (mirror == null) return;
        applyFollow();
        if (!"follow".equals(p)) mirror.evaluateJavascript("goTab('" + p + "');window.scrollTo(0,0)", null);
    }

    /** "Telefondaki ekran": ayna, telefonda açık sekmeyi izler. */
    private void applyFollow() {
        if (mirror != null) mirror.evaluateJavascript("window.ex30MirrorFollow&&ex30MirrorFollow(" + "follow".equals(page) + ")", null);
    }

    /** Görünür alanı CSS piksel olarak sayfaya ver: içerik medya kartının altında kalmasın. */
    private void applyInset() {
        if (mirror == null || visible == null || sw <= 0) return;
        float k = sdpi > 0 ? sdpi / 160f : 1f;
        int l = Math.max(0, Math.round(visible.left / k)), t = 0;   // üstteki menü düğmesi içeriğin üzerinde yüzer
        int r = Math.max(0, Math.round((sw - visible.right) / k)), b = Math.max(0, Math.round((sh - visible.bottom) / k));
        mirror.evaluateJavascript("window.ex30Inset&&ex30Inset(" + l + "," + t + "," + r + "," + b + ")", null);
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
        // Ekranı kaplamasın diye tek, küçük menü düğmesi; sayfalar listeden seçilir
        Action menu = new Action.Builder()
                .setIcon(new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), R.drawable.ic_ex30_menu)).build())
                .setOnClickListener(() -> getScreenManager().push(new MoreScreen(getCarContext(), this))).build();
        return new NavigationTemplate.Builder()
                .setActionStrip(new ActionStrip.Builder().addAction(menu).build())
                .build();
    }

    /** Dört düğmeye sığmayan ekranlar: listeden seçilir, sonra aynaya dönülür. */
    static final class MoreScreen extends Screen {
        private final DashScreen dash;
        MoreScreen(@NonNull CarContext c, DashScreen d) { super(c); dash = d; }

        private Row row(String title, String sub, String p) {
            return new Row.Builder().setTitle(title).addText(sub).setOnClickListener(() -> { dash.show(p); getScreenManager().pop(); }).build();
        }

        @NonNull
        @Override
        public Template onGetTemplate() {
            // Araç sürerken liste en fazla 6 satır alabilir: sıra önem sırası, fazlası kesilir
            int lim = 6;
            try {
                lim = getCarContext().getCarService(androidx.car.app.constraints.ConstraintManager.class)
                        .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
            } catch (Throwable ignored) { }
            Row[] rows = {
                row("Sürüş", "Güç, yolculuk, ekonomi çizgisi, sıcaklıklar", "drive"),
                row("Performans", "Hız, kW, pedallar, G-metre", "perf"),
                row("Alper", "Aracın kendi verileri, tüm değerler", "alper"),
                row("Şarj", "Şarj gücü, şarj yüzdesi ve şarj eğrisi", "charge"),
                row("Yolculuklar", "Otomatik yolculuklar ve tüketim", "trip"),
                row("Panel", "Tüm değerler", "panel"),
                row("Telefondaki ekran", "Telefonda hangi sekme açıksa onu gösterir", "follow")
            };
            ItemList.Builder lb = new ItemList.Builder();
            for (int i = 0; i < rows.length && i < Math.max(1, lim); i++) lb.addItem(rows[i]);
            ItemList list = lb.build();
            return new ListTemplate.Builder().setTitle("EX30 ekranları").setHeaderAction(Action.BACK).setSingleList(list).build();
        }
    }
}
