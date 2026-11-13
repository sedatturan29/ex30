package com.ex30.batarya;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * EX30 Reader telefon ekranı. Asıl iş WebHost'taki tek WebView'da döner (HTML uygulaması +
 * Bluetooth köprüsü); bu ekran onu gösterir. Ekran kapansa da bağlantı sürüyorsa okuma devam eder.
 */
public class MainActivity extends Activity {

    static final int REQ_BLE = 10;
    static final int REQ_GEO = 11;

    private WebHost host;

    /** ReaderService'ten: bağlantı açık/kapalı. */
    static void setKeepAlive(boolean on) { WebHost.setKeepAlive(on); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF0E1116);
        getWindow().setNavigationBarColor(0xFF0E1116);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1116);
        setContentView(root);
        // Sistem çubuklarının altında kalmamak için kenar boşlukları
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        host = WebHost.get(this);
        host.attach(this, root);
    }

    @Override
    public void onBackPressed() {
        if (host != null && host.canGoBack()) host.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (host != null) host.onPermissionResult(requestCode);
    }

    @Override
    protected void onDestroy() {
        if (host != null) host.detach(this);
        host = null;
        super.onDestroy();
    }
}
