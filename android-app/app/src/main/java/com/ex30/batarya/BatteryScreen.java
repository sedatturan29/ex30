package com.ex30.batarya;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Android Auto ekranı (ListTemplate, her satırda başlık + 2 metin).
 * Sıra: batarya, güç/sürüş, yolculuk, lastikler, dış hava/menzil, kayıt.
 * Araç izin verilen satır sayısını düşürürse alttakiler düşer.
 */
public class BatteryScreen extends Screen implements CarData.Listener {

    private static final Locale TR = new Locale("tr", "TR");
    private static final long STALE_MS = 20_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable staleCheck = new Runnable() {
        @Override
        public void run() {
            if (CarData.ageMs() > STALE_MS) invalidate();
            handler.postDelayed(this, 10_000);
        }
    };

    public BatteryScreen(@NonNull CarContext carContext) {
        super(carContext);
        getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event == Lifecycle.Event.ON_START) {
                CarData.add(this);
                handler.postDelayed(staleCheck, 10_000);
            } else if (event == Lifecycle.Event.ON_STOP) {
                CarData.remove(this);
                handler.removeCallbacks(staleCheck);
            }
        });
    }

    @Override
    public void onCarDataChanged() {
        invalidate();
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        JSONObject d = CarData.latest();
        if (d == null || !d.optBoolean("conn", false)) {
            return new MessageTemplate.Builder(
                    "Telefonda EX30 Reader uygulamasını açıp OBD adaptörüne bağlanın. Bağlanınca değerler burada görünür.")
                    .setTitle("EX30 Reader")
                    .setHeaderAction(Action.APP_ICON)
                    .build();
        }

        List<Row> rows = new ArrayList<>();
        JSONObject aa = d.optJSONObject("aa");
        if (aa == null) aa = new JSONObject();

        // 1) Batarya: sıcaklık, şarj, durum
        Double avg = num(d, "avg"), max = num(d, "max"), cool = num(d, "cool"), soc = num(d, "soc");
        Double rate = num(d, "rate");
        String cls = d.optString("stateCls", "");
        int color = "heat".equals(cls) ? 0xFFFF7A45 : "cool".equals(cls) ? 0xFF4EA1FF : 0xFF8A94A6;
        rows.add(new Row.Builder()
                .setTitle("Batarya " + f(avg, 1) + "°C  ·  maks " + f(max, 1) + "°C  ·  %" + f(soc, 1))
                .addText("Soğutma giriş " + f(cool, 0) + "°C  ·  çıkış " + f(num(aa, "cout"), 0) + "°C  ·  motor " + f(num(aa, "motor"), 0) + "°C")
                .addText(d.optString("state", "–") + (rate == null ? "" : "  ·  " + (rate > 0 ? "+" : "") + f(rate, 2) + " °C/dk"))
                .setImage(dot(color), Row.IMAGE_TYPE_SMALL)
                .build());

        // 2) Güç ve sürüş
        Double p = num(aa, "kw");
        if (p == null) p = num(d, "P");
        String pTxt = p == null ? "–" : (p > 0 ? "+" : "") + f(p, 1);
        rows.add(new Row.Builder()
                .setTitle("Güç " + pTxt + " kW  ·  " + f(num(aa, "volt"), 0) + " V  ·  " + f(num(aa, "cur"), 0) + " A")
                .addText("Hız " + f(num(aa, "vs"), 0) + " km/s  ·  " + f(num(aa, "rpm"), 0) + " d/dk  ·  " + f(num(aa, "tq"), 0) + " Nm")
                .build());

        // 3) Yolculuk
        JSONObject tr = aa.optJSONObject("trip");
        if (tr != null) {
            rows.add(new Row.Builder()
                    .setTitle("Yolculuk " + tr.optString("name", "") + ": " + f(num(tr, "cons"), 1) + " kWh/100km  ·  " + f(num(tr, "km"), 1) + " km")
                    .addText("Ort. hız " + f(num(tr, "avgS"), 0) + " km/s  ·  " + tr.optInt("min", 0) + " dk")
                    .addText("Harcanan " + f(num(tr, "out"), 2) + " kWh  ·  rejen " + f(num(tr, "reg"), 2) + " kWh")
                    .build());
        }

        // 4) Lastikler (psi)
        JSONObject tp = aa.optJSONObject("tp");
        if (tp != null && (num(tp, "fl") != null || num(tp, "rl") != null)) {
            rows.add(new Row.Builder()
                    .setTitle("Lastik  Ön " + f(num(tp, "fl"), 0) + " / " + f(num(tp, "fr"), 0) + "  ·  Arka " + f(num(tp, "rl"), 0) + " / " + f(num(tp, "rr"), 0) + " psi")
                    .build());
        }

        // 5) Dış hava / menzil
        Double out = num(aa, "out"), rng = num(aa, "rng");
        if (out != null || rng != null) {
            rows.add(new Row.Builder()
                    .setTitle("Dış hava " + f(out, 0) + "°C  ·  Menzil " + f(rng, 0) + " km")
                    .build());
        }

        // 6) Kayıt ya da veri yaşı
        long age = CarData.ageMs();
        String rec = d.isNull("rec") ? null : d.optString("rec", null);
        if (age > STALE_MS) {
            rows.add(0, new Row.Builder()
                    .setTitle("Veri " + (age / 1000) + " sn önce geldi")
                    .addText("Telefondaki uygulama açık ve adaptöre bağlı mı?")
                    .build());
        } else if (rec != null && !rec.isEmpty()) {
            rows.add(new Row.Builder().setTitle(rec).build());
        } else if (aa.length() == 0) {
            rows.add(new Row.Builder().setTitle("Daha fazla veri için telefonda Panel sekmesini açın").build());
        }

        int limit = 6;
        try {
            limit = getCarContext().getCarService(ConstraintManager.class)
                    .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
        } catch (Exception ignored) { }

        ItemList.Builder list = new ItemList.Builder();
        for (int i = 0; i < rows.size() && i < Math.max(1, limit); i++) list.addItem(rows.get(i));

        return new ListTemplate.Builder()
                .setSingleList(list.build())
                .setTitle("EX30 Reader")
                .setHeaderAction(Action.APP_ICON)
                .build();
    }

    private static Double num(JSONObject d, String k) {
        if (!d.has(k) || d.isNull(k)) return null;
        double v = d.optDouble(k, Double.NaN);
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    private static String f(Double v, int decimals) {
        if (v == null) return "–";
        return String.format(TR, "%." + decimals + "f", v);
    }

    private static CarIcon dot(int color) {
        Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        new Canvas(b).drawCircle(32, 32, 26, paint);
        return new CarIcon.Builder(IconCompat.createWithBitmap(b)).build();
    }
}
