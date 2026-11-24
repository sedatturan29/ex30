package com.ex30.batarya;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

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

import org.json.JSONObject;

import java.util.Locale;

/**
 * Android Auto'da kendi tasarımımız: navigasyon uygulamalarına verilen çizim yüzeyine
 * (harita alanı) EX30 göstergesini çizer. Sürüş sırasında da açık kalır.
 * "Liste" düğmesi şablonlu liste ekranını (BatteryScreen) açar.
 */
public class DashScreen extends Screen {

    private static final Locale TR = new Locale("tr", "TR");
    private final Handler h = new Handler(Looper.getMainLooper());
    private SurfaceContainer surface;
    private Rect visible, stable;

    private final Paint bg = paint(0xFF0E1116), card = paint(0xFF151A21), line = paint(0xFF2C3440),
            txt = text(0xFFE8ECF2, false), mut = text(0xFF8A94A6, false), big = text(0xFFE8ECF2, true);

    private final Runnable tick = new Runnable() {
        @Override public void run() { draw(); h.postDelayed(this, 500); }
    };

    private final SurfaceCallback cb = new SurfaceCallback() {
        @Override public void onSurfaceAvailable(@NonNull SurfaceContainer c) { surface = c; draw(); }
        @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer c) { surface = null; }
        @Override public void onVisibleAreaChanged(@NonNull Rect r) { visible = r; draw(); }
        @Override public void onStableAreaChanged(@NonNull Rect r) { stable = r; draw(); }
    };

    public DashScreen(@NonNull CarContext ctx) {
        super(ctx);
        try { ctx.getCarService(AppManager.class).setSurfaceCallback(cb); } catch (Throwable ignored) { }
        getLifecycle().addObserver((LifecycleEventObserver) (s, e) -> {
            if (e == Lifecycle.Event.ON_START) h.post(tick);
            else if (e == Lifecycle.Event.ON_STOP) h.removeCallbacks(tick);
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        Action list = new Action.Builder().setTitle("Liste")
                .setOnClickListener(() -> getScreenManager().push(new BatteryScreen(getCarContext()))).build();
        return new NavigationTemplate.Builder()
                .setActionStrip(new ActionStrip.Builder().addAction(list).build())
                .build();
    }

    // ------------------------------------------------------------------ çizim

    private static Paint paint(int c) { Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(c); return p; }

    private static Paint text(int c, boolean bold) {
        Paint p = paint(c);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }

    private static Double num(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        double v = o.optDouble(k, Double.NaN);
        return Double.isNaN(v) ? null : v;
    }

    private static String f(Double v, int d) { return v == null ? "–" : String.format(TR, "%." + d + "f", v); }

    private void draw() {
        SurfaceContainer sc = surface;
        if (sc == null) return;
        Surface s = sc.getSurface();
        if (s == null || !s.isValid()) return;
        Canvas c;
        try { c = s.lockCanvas(null); } catch (Throwable e) { return; }
        try { paintDash(c, sc.getWidth(), sc.getHeight()); }
        catch (Throwable ignored) { }
        finally { try { s.unlockCanvasAndPost(c); } catch (Throwable ignored) { } }
    }

    private void paintDash(Canvas c, int w, int hgt) {
        c.drawRect(0, 0, w, hgt, bg);
        Rect a = stable != null ? stable : visible != null ? visible : new Rect(0, 0, w, hgt);
        float pad = Math.max(10, a.width() * 0.015f);
        RectF box = new RectF(a.left + pad, a.top + pad, a.right - pad, a.bottom - pad);
        float u = Math.min(box.width() / 32f, box.height() / 18f);   // ölçek birimi

        JSONObject d = CarData.latest();
        JSONObject aa = d == null ? null : d.optJSONObject("aa");
        boolean live = d != null && d.optBoolean("conn", false) && CarData.ageMs() < 20_000;
        if (!live) {
            big.setTextSize(u * 1.6f); mut.setTextSize(u * 0.9f);
            c.drawText("EX30 Reader", box.left, box.top + u * 2, big);
            String st = d == null ? "" : d.optString("status", "");
            c.drawText(st.isEmpty() ? "OBD adaptörüne bağlanılıyor…" : st, box.left, box.top + u * 3.6f, mut);
            return;
        }

        // Sol: güç yayı
        Double kw = num(aa, "kw"); if (kw == null) kw = num(d, "P");
        float gw = Math.min(box.width() * 0.45f, box.height());
        float cx = box.left + gw / 2, cy = box.top + gw * 0.55f, r = gw * 0.42f;
        RectF arc = new RectF(cx - r, cy - r, cx + r, cy + r);
        Paint ring = paint(0xFF242A34); ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(u * 1.1f); ring.setStrokeCap(Paint.Cap.ROUND);
        c.drawArc(arc, 150, 240, false, ring);
        double lo = -80, hi = 200, z = (0 - lo) / (hi - lo) * 240;
        if (kw != null) {
            double v = Math.max(lo, Math.min(hi, kw)), p = (v - lo) / (hi - lo) * 240;
            Paint fill = paint(kw >= 0 ? 0xFF3CCF8E : 0xFF4EA1FF); fill.setStyle(Paint.Style.STROKE); fill.setStrokeWidth(u * 1.1f);
            c.drawArc(arc, (float) (150 + Math.min(z, p)), (float) Math.abs(p - z), false, fill);
        }
        big.setTextSize(u * 2.6f); big.setTextAlign(Paint.Align.CENTER);
        c.drawText((kw != null && kw > 0 ? "+" : "") + f(kw, 0), cx, cy + u * 0.6f, big);
        mut.setTextSize(u * 0.8f); mut.setTextAlign(Paint.Align.CENTER);
        c.drawText("kW", cx, cy + u * 1.7f, mut);
        txt.setTextSize(u * 1.3f); txt.setTextAlign(Paint.Align.CENTER);
        c.drawText(f(num(aa, "vs"), 0) + " km/s", cx, cy + r * 0.95f, txt);
        big.setTextAlign(Paint.Align.LEFT); mut.setTextAlign(Paint.Align.LEFT); txt.setTextAlign(Paint.Align.LEFT);

        // Sağ: kutucuklar (2 sütun × 3 satır)
        float x0 = box.left + gw + pad, cw = (box.right - x0 - pad) / 2f, ch = (box.height() - 2 * pad) / 3f;
        String[][] tiles = {
            {"Batarya", f(num(d, "avg"), 1) + "°", "maks " + f(num(d, "max"), 1) + "° · %" + f(num(d, "soc"), 0)},
            {"Soğutma", f(num(d, "cool"), 0) + "° / " + f(num(aa, "cout"), 0) + "°", "giriş / güç devresi"},
            {"Motor", f(num(aa, "motor"), 0) + "°", f(num(aa, "rpm"), 0) + " d/dk · " + f(num(aa, "tq"), 0) + " Nm"},
            {"Akım", f(num(aa, "cur"), 0) + " A", f(num(aa, "volt"), 0) + " V"},
            tripTile(aa),
            {"Dış hava", f(num(aa, "out"), 0) + "°", "menzil " + f(num(aa, "rng"), 0) + " km"},
        };
        for (int i = 0; i < tiles.length; i++) {
            float tx = x0 + (i % 2) * (cw + pad), ty = box.top + (i / 2) * (ch + pad);
            c.drawRoundRect(new RectF(tx, ty, tx + cw, ty + ch), u * 0.6f, u * 0.6f, card);
            mut.setTextSize(u * 0.75f); c.drawText(tiles[i][0], tx + u * 0.6f, ty + u * 1.1f, mut);
            big.setTextSize(u * 1.6f); c.drawText(tiles[i][1], tx + u * 0.6f, ty + ch * 0.62f, big);
            mut.setTextSize(u * 0.7f); c.drawText(tiles[i][2], tx + u * 0.6f, ty + ch - u * 0.5f, mut);
        }
    }

    private static String[] tripTile(JSONObject aa) {
        JSONObject t = aa == null ? null : aa.optJSONObject("trip");
        if (t == null) return new String[]{"Yolculuk", "–", ""};
        return new String[]{"Yolculuk " + t.optString("name", ""), f(num(t, "cons"), 1) + " kWh/100",
                f(num(t, "km"), 1) + " km · " + t.optInt("min", 0) + " dk"};
    }
}
