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
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
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
 * Android Auto ekranı (PaneTemplate). Satırlar önem sırasına göre:
 * 1) Batarya sıcaklığı (ort./maks./soğutma sıvısı)
 * 2) Isıtma/soğutma durumu ve sıcaklık değişimi (renkli nokta)
 * 3) Şarj yüzdesi ve güç
 * 4) Süren kayıt (şarj/yolculuk/test) ya da verinin ne kadar eski olduğu
 * Araç sürüş sırasında daha az satıra izin verirse alttaki satırlar düşer.
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
                    "Telefonda EX30 Batarya uygulamasını açıp OBD adaptörüne bağlanın. Bağlanınca değerler burada görünür.")
                    .setTitle("EX30 Batarya")
                    .setHeaderAction(Action.APP_ICON)
                    .build();
        }

        List<Row> rows = new ArrayList<>();

        // 1) Sıcaklık
        Double avg = num(d, "avg"), max = num(d, "max"), cool = num(d, "cool");
        rows.add(new Row.Builder()
                .setTitle("Batarya " + f(avg, 1) + " °C")
                .addText("Maks. " + f(max, 1) + " °C  ·  Soğutma sıvısı " + f(cool, 0) + " °C")
                .build());

        // 2) Durum
        String cls = d.optString("stateCls", "");
        Double rate = num(d, "rate");
        int color = "heat".equals(cls) ? 0xFFFF7A45 : "cool".equals(cls) ? 0xFF4EA1FF : 0xFF8A94A6;
        String rateTxt = rate == null ? "Değişim hesaplanıyor" : (rate > 0 ? "+" : "") + f(rate, 2) + " °C/dk";
        rows.add(new Row.Builder()
                .setTitle(d.optString("state", "–"))
                .addText(rateTxt + "  ·  DC şarj için ideal ≈ 20–25 °C")
                .setImage(dot(color), Row.IMAGE_TYPE_SMALL)
                .build());

        // 3) Şarj ve güç
        Double soc = num(d, "soc"), p = num(d, "P");
        String powTxt = p == null ? "Güç –"
                : p < -0.3 ? "Şarj gücü " + f(-p, 1) + " kW"
                : p > 0.3 ? "Çekilen güç " + f(p, 1) + " kW"
                : "Güç ≈ 0 kW";
        rows.add(new Row.Builder()
                .setTitle("Şarj %" + f(soc, 1))
                .addText(powTxt)
                .build());

        // 4) Kayıt ya da veri yaşı
        long age = CarData.ageMs();
        String rec = d.isNull("rec") ? null : d.optString("rec", null);
        if (age > STALE_MS) {
            rows.add(new Row.Builder()
                    .setTitle("Veri " + (age / 1000) + " sn önce geldi")
                    .addText("Telefondaki uygulama açık ve adaptöre bağlı mı?")
                    .build());
        } else if (rec != null && !rec.isEmpty()) {
            rows.add(new Row.Builder().setTitle(rec).build());
        }

        int limit = 4;
        try {
            limit = getCarContext().getCarService(ConstraintManager.class)
                    .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE);
        } catch (Exception ignored) { }

        Pane.Builder pane = new Pane.Builder();
        for (int i = 0; i < rows.size() && i < Math.max(1, limit); i++) pane.addRow(rows.get(i));

        return new PaneTemplate.Builder(pane.build())
                .setTitle("EX30 Batarya")
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
