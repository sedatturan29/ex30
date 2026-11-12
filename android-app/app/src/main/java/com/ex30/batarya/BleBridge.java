package com.ex30.batarya;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web Bluetooth'un küçük bir alt kümesini Android BLE ile sağlar.
 * JS tarafı: assets/ble-polyfill.js (navigator.bluetooth). Yalnızca OBD adaptörüyle
 * konuşmak için gereken işlemler var: cihaz seçme, bağlanma, bildirim açma, yazma.
 */
@SuppressWarnings({"deprecation", "MissingPermission"})
public class BleBridge {

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Klasik Bluetooth (SPP, ör. "Android-Vlink"): sayfaya sahte bir BLE servisi gibi görünür
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final String SPP_PREFIX = "spp:";
    private static final String SPP_SVC = "0000fff0-0000-1000-8000-00805f9b34fb";
    private static final String SPP_CHR = "0000fff1-0000-1000-8000-00805f9b34fb";
    private final Map<String, BluetoothSocket> sockets = new ConcurrentHashMap<>();
    private final ExecutorService sppWriter = Executors.newSingleThreadExecutor();

    /** Seçim listesindeki bir satır. */
    private static final class Entry {
        final String id, name, label;
        Entry(String id, String name, String label) { this.id = id; this.name = name; this.label = label; }
    }

    private final Activity act;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothGatt> gatts = new HashMap<>();

    private String connectCb, connectingId, notifyCb, writeCb;
    private Runnable afterPermission;
    private String permissionCb;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private AlertDialog dialog;

    BleBridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    // ---------------------------------------------------------------- JS yardımcıları
    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    private void js(final String code) { main.post(() -> web.evaluateJavascript(code, null)); }

    private void resolve(String cb, String jsonValue) {
        if (cb != null) js("window.__ble&&__ble._resolve(" + q(cb) + "," + jsonValue + ")");
    }

    private void reject(String cb, String name, String msg) {
        if (cb != null) js("window.__ble&&__ble._reject(" + q(cb) + "," + q(name) + "," + q(msg) + ")");
    }

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    private String[] permissions() {
        if (Build.VERSION.SDK_INT >= 31)
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermissions() {
        for (String p : permissions())
            if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    void onPermissionResult() {
        Runnable r = afterPermission;
        String cb = permissionCb;
        afterPermission = null;
        permissionCb = null;
        if (r == null) return;
        if (hasPermissions()) r.run();
        else reject(cb, "NotAllowedError", "Bluetooth izni verilmedi. Ayarlar > Uygulamalar > EX30 Batarya > İzinler'den 'Yakındaki cihazlar' iznini açın.");
    }

    // ---------------------------------------------------------------- JS'ten çağrılanlar
    @JavascriptInterface
    public boolean available() {
        BluetoothAdapter a = adapter();
        return a != null && a.isEnabled();
    }

    @JavascriptInterface
    public void requestDevice(final String opts, final String cb) {
        main.post(() -> {
            if (!hasPermissions()) {
                afterPermission = () -> startPicker(opts, cb);
                permissionCb = cb;
                act.requestPermissions(permissions(), MainActivity.REQ_BLE);
                return;
            }
            startPicker(opts, cb);
        });
    }

    @JavascriptInterface
    public void connect(final String id, final String cb) {
        if (id.startsWith(SPP_PREFIX)) { classicConnect(id, cb); return; }
        main.post(() -> {
            BluetoothAdapter a = adapter();
            if (a == null || !a.isEnabled()) { reject(cb, "NetworkError", "Bluetooth kapalı"); return; }
            BluetoothGatt old = gatts.remove(id);
            if (old != null) { try { old.close(); } catch (Exception ignored) { } }
            connectCb = cb;
            connectingId = id;
            final BluetoothGatt g = a.getRemoteDevice(id).connectGatt(act, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            gatts.put(id, g);
            main.postDelayed(() -> {
                if (cb.equals(connectCb)) {
                    connectCb = null;
                    gatts.remove(id);
                    try { g.disconnect(); g.close(); } catch (Exception ignored) { }
                    reject(cb, "NetworkError", "Bağlantı zaman aşımına uğradı. Adaptör başka bir uygulamaya bağlı olabilir.");
                }
            }, 15000);
        });
    }

    @JavascriptInterface
    public void startNotifications(final String id, final String svc, final String chr, final String cb) {
        if (id.startsWith(SPP_PREFIX)) { resolve(cb, "null"); return; }
        main.post(() -> {
            BluetoothGatt g = gatts.get(id);
            BluetoothGattCharacteristic c = find(g, svc, chr);
            if (g == null || c == null) { reject(cb, "NotFoundError", "Karakteristik bulunamadı"); return; }
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d == null) { resolve(cb, "null"); return; }
            byte[] val = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            notifyCb = cb;
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) ok = g.writeDescriptor(d, val) == BluetoothStatusCodes.SUCCESS;
            else { d.setValue(val); ok = g.writeDescriptor(d); }
            if (!ok) { notifyCb = null; reject(cb, "NetworkError", "Bildirimler açılamadı"); return; }
            main.postDelayed(() -> { if (cb.equals(notifyCb)) { notifyCb = null; resolve(cb, "null"); } }, 3000);
        });
    }

    @JavascriptInterface
    public void write(final String id, final String svc, final String chr, final String b64, final boolean withResponse, final String cb) {
        final byte[] data = Base64.decode(b64, Base64.DEFAULT);
        if (id.startsWith(SPP_PREFIX)) {
            sppWriter.execute(() -> {
                BluetoothSocket s = sockets.get(id);
                if (s == null) { reject(cb, "NetworkError", "Bağlı değil"); return; }
                try {
                    OutputStream out = s.getOutputStream();
                    out.write(data);
                    out.flush();
                    resolve(cb, "null");
                } catch (Exception e) {
                    reject(cb, "NetworkError", "Yazılamadı: " + e.getMessage());
                }
            });
            return;
        }
        main.post(() -> doWrite(id, svc, chr, data, withResponse, cb, 0));
    }

    @JavascriptInterface
    public void disconnect(final String id) {
        if (id.startsWith(SPP_PREFIX)) {
            BluetoothSocket s = sockets.remove(id);
            if (s != null) { try { s.close(); } catch (Exception ignored) { } }
            js("window.__ble&&__ble._disconnected(" + q(id) + ")");
            return;
        }
        main.post(() -> {
            BluetoothGatt g = gatts.remove(id);
            if (g != null) { try { g.disconnect(); g.close(); } catch (Exception ignored) { } }
            rejectPending("Bağlantı kesildi");
            js("window.__ble&&__ble._disconnected(" + q(id) + ")");
        });
    }

    /** Sayfa her ölçümde son değerleri gönderir; Android Auto ekranı bunları gösterir. */
    @JavascriptInterface
    public void publish(final String json) {
        CarData.update(json);
    }

    @JavascriptInterface
    public void copy(final String text) {
        main.post(() -> {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("EX30", text));
            Toast.makeText(act, "Panoya kopyalandı", Toast.LENGTH_SHORT).show();
        });
    }

    // ---------------------------------------------------------------- iç işler
    private void startPicker(final String opts, final String cb) {
        BluetoothAdapter a = adapter();
        if (a == null) { reject(cb, "NotFoundError", "Bu cihazda Bluetooth yok"); return; }
        if (!a.isEnabled()) { reject(cb, "NotFoundError", "Bluetooth kapalı. Telefonun Bluetooth'unu açın."); return; }

        boolean all = false;
        final List<String> prefixes = new ArrayList<>();
        final List<String> services = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(opts);
            all = o.optBoolean("acceptAllDevices", false);
            JSONArray f = o.optJSONArray("filters");
            if (f != null) for (int i = 0; i < f.length(); i++) {
                JSONObject fi = f.getJSONObject(i);
                if (fi.has("namePrefix")) prefixes.add(fi.getString("namePrefix").toLowerCase());
                if (fi.has("name")) prefixes.add(fi.getString("name").toLowerCase());
                JSONArray s = fi.optJSONArray("services");
                if (s != null) for (int j = 0; j < s.length(); j++) services.add(s.getString(j).toLowerCase());
            }
        } catch (Exception e) { all = true; }
        final boolean acceptAll = all || (prefixes.isEmpty() && services.isEmpty());

        final List<Entry> found = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final ArrayAdapter<String> listAdapter = new ArrayAdapter<>(act, android.R.layout.simple_list_item_1, labels);

        stopScan();
        scanner = a.getBluetoothLeScanner();
        if (scanner == null) { reject(cb, "NotFoundError", "Bluetooth tarayıcı kullanılamıyor"); return; }
        scanCb = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult r) {
                final BluetoothDevice d = r.getDevice();
                String name = d.getName();
                if (name == null && r.getScanRecord() != null) name = r.getScanRecord().getDeviceName();
                boolean match = acceptAll;
                if (!match && name != null) {
                    String ln = name.toLowerCase();
                    for (String p : prefixes) if (ln.startsWith(p)) { match = true; break; }
                }
                if (!match && r.getScanRecord() != null && r.getScanRecord().getServiceUuids() != null) {
                    for (ParcelUuid u : r.getScanRecord().getServiceUuids())
                        if (services.contains(u.getUuid().toString().toLowerCase())) { match = true; break; }
                }
                if (!match) return;
                final String nm = name;
                final String label = (name != null ? name : "(isimsiz)") + "   Bluetooth LE";
                main.post(() -> {
                    for (Entry x : found) if (x.id.equals(d.getAddress())) return;
                    found.add(new Entry(d.getAddress(), nm, label));
                    labels.add(label);
                    listAdapter.notifyDataSetChanged();
                    if (dialog != null) dialog.setTitle("OBD adaptörünü seçin");
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                main.post(() -> { if (dialog != null) dialog.setTitle("Tarama başarısız (kod " + errorCode + ")"); });
            }
        };
        scanner.startScan(scanCb);

        // Telefonda eşleşmiş klasik Bluetooth adaptörleri (ör. Android-Vlink)
        try {
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded != null) for (BluetoothDevice d : bonded) {
                if (d.getType() == BluetoothDevice.DEVICE_TYPE_LE) continue;
                String name = d.getName();
                boolean match = acceptAll;
                if (!match && name != null) {
                    String ln = name.toLowerCase();
                    for (String p : prefixes) if (ln.startsWith(p)) { match = true; break; }
                }
                if (!match) continue;
                String label = (name != null ? name : d.getAddress()) + "   klasik Bluetooth (eşleşmiş)";
                found.add(new Entry(SPP_PREFIX + d.getAddress(), name, label));
                labels.add(label);
            }
        } catch (Exception ignored) { }

        final boolean[] picked = {false};
        dialog = new AlertDialog.Builder(act)
                .setTitle(found.isEmpty() ? "Aranıyor… (adaptör takılı, araç açık olmalı)" : "OBD adaptörünü seçin")
                .setAdapter(listAdapter, (dlg, which) -> {
                    picked[0] = true;
                    stopScan();
                    Entry d = found.get(which);
                    try {
                        JSONObject res = new JSONObject();
                        res.put("id", d.id);
                        res.put("name", d.name == null ? JSONObject.NULL : d.name);
                        resolve(cb, res.toString());
                    } catch (Exception e) {
                        reject(cb, "NotFoundError", String.valueOf(e.getMessage()));
                    }
                })
                .setNegativeButton("İptal", (dlg, w) -> { })
                .setOnDismissListener(dlg -> {
                    stopScan();
                    dialog = null;
                    if (!picked[0]) reject(cb, "NotFoundError", "Cihaz seçilmedi");
                })
                .create();
        dialog.show();
        main.postDelayed(this::stopScan, 30000);
    }

    private void classicConnect(final String id, final String cb) {
        final String addr = id.substring(SPP_PREFIX.length());
        new Thread(() -> {
            BluetoothAdapter a = adapter();
            if (a == null || !a.isEnabled()) { reject(cb, "NetworkError", "Bluetooth kapalı"); return; }
            try { a.cancelDiscovery(); } catch (Exception ignored) { }
            BluetoothDevice d = a.getRemoteDevice(addr);
            BluetoothSocket s = null;
            String error = null;
            for (int attempt = 0; attempt < 2 && s == null; attempt++) {
                BluetoothSocket t = null;
                try {
                    t = attempt == 0 ? d.createRfcommSocketToServiceRecord(SPP) : d.createInsecureRfcommSocketToServiceRecord(SPP);
                    t.connect();
                    s = t;
                } catch (Exception e) {
                    error = e.getMessage();
                    if (t != null) { try { t.close(); } catch (Exception ignored) { } }
                }
            }
            if (s == null) {
                reject(cb, "NetworkError", "Klasik Bluetooth bağlantısı kurulamadı (" + error + "). Car Scanner gibi başka bir uygulama adaptöre bağlı olabilir.");
                return;
            }
            BluetoothSocket old = sockets.put(id, s);
            if (old != null) { try { old.close(); } catch (Exception ignored) { } }
            startReader(id, s);
            resolve(cb, "[{\"uuid\":\"" + SPP_SVC + "\",\"chars\":[{\"uuid\":\"" + SPP_CHR
                    + "\",\"props\":{\"read\":false,\"write\":true,\"writeWithoutResponse\":true,\"notify\":true,\"indicate\":false}}]}]");
        }).start();
    }

    private void startReader(final String id, final BluetoothSocket s) {
        new Thread(() -> {
            byte[] buf = new byte[512];
            try {
                InputStream in = s.getInputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    byte[] chunk = Arrays.copyOf(buf, n);
                    js("window.__ble&&__ble._notify(" + q(id) + "," + q(SPP_SVC) + "," + q(SPP_CHR) + ","
                            + q(Base64.encodeToString(chunk, Base64.NO_WRAP)) + ")");
                }
            } catch (Exception ignored) { }
            if (sockets.remove(id, s)) {
                try { s.close(); } catch (Exception ignored) { }
                js("window.__ble&&__ble._disconnected(" + q(id) + ")");
            }
        }).start();
    }

    private void stopScan() {
        if (scanner != null && scanCb != null) {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) { }
        }
        scanCb = null;
    }

    private BluetoothGattCharacteristic find(BluetoothGatt g, String svc, String chr) {
        if (g == null) return null;
        try {
            BluetoothGattService s = g.getService(UUID.fromString(svc));
            return s == null ? null : s.getCharacteristic(UUID.fromString(chr));
        } catch (Exception e) {
            return null;
        }
    }

    private void doWrite(final String id, final String svc, final String chr, final byte[] data,
                         final boolean withResponse, final String cb, final int attempt) {
        BluetoothGatt g = gatts.get(id);
        BluetoothGattCharacteristic c = find(g, svc, chr);
        if (g == null || c == null) { reject(cb, "NetworkError", "Bağlı değil"); return; }
        int type = withResponse ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        writeCb = cb;
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) ok = g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS;
        else { c.setWriteType(type); c.setValue(data); ok = g.writeCharacteristic(c); }
        if (!ok) {
            writeCb = null;
            if (attempt < 15) main.postDelayed(() -> doWrite(id, svc, chr, data, withResponse, cb, attempt + 1), 20);
            else reject(cb, "NetworkError", "Adaptör meşgul, yazılamadı");
            return;
        }
        main.postDelayed(() -> { if (cb.equals(writeCb)) { writeCb = null; resolve(cb, "null"); } }, 1000);
    }

    private void rejectPending(String msg) {
        String n = notifyCb, w = writeCb;
        notifyCb = null;
        writeCb = null;
        reject(n, "NetworkError", msg);
        reject(w, "NetworkError", msg);
    }

    private void onNotify(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
        if (v == null) return;
        js("window.__ble&&__ble._notify(" + q(g.getDevice().getAddress()) + "," + q(c.getService().getUuid().toString())
                + "," + q(c.getUuid().toString()) + "," + q(Base64.encodeToString(v, Base64.NO_WRAP)) + ")");
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, final int status, int newState) {
            final String id = g.getDevice().getAddress();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                main.postDelayed(g::discoverServices, 300);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post(() -> {
                    String cb = connectCb;
                    if (cb != null && id.equals(connectingId)) {
                        connectCb = null;
                        reject(cb, "NetworkError", "Bağlanamadı (kod " + status + "). Car Scanner gibi başka bir uygulama adaptöre bağlı olabilir.");
                    } else if (gatts.get(id) == g) {
                        js("window.__ble&&__ble._disconnected(" + q(id) + ")");
                    }
                    rejectPending("Bağlantı koptu");
                    if (gatts.get(id) == g) gatts.remove(id);
                    try { g.close(); } catch (Exception ignored) { }
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            String json;
            try {
                JSONArray arr = new JSONArray();
                for (BluetoothGattService s : g.getServices()) {
                    JSONObject so = new JSONObject();
                    so.put("uuid", s.getUuid().toString().toLowerCase());
                    JSONArray chars = new JSONArray();
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        int p = c.getProperties();
                        JSONObject pr = new JSONObject();
                        pr.put("read", (p & BluetoothGattCharacteristic.PROPERTY_READ) != 0);
                        pr.put("write", (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0);
                        pr.put("writeWithoutResponse", (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0);
                        pr.put("notify", (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0);
                        pr.put("indicate", (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0);
                        JSONObject co = new JSONObject();
                        co.put("uuid", c.getUuid().toString().toLowerCase());
                        co.put("props", pr);
                        chars.put(co);
                    }
                    so.put("chars", chars);
                    arr.put(so);
                }
                json = arr.toString();
            } catch (Exception e) {
                json = null;
            }
            final String result = json;
            main.post(() -> {
                String cb = connectCb;
                connectCb = null;
                if (cb == null) return;
                if (result != null) resolve(cb, result);
                else reject(cb, "NetworkError", "Servisler okunamadı");
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, final int status) {
            main.post(() -> {
                String cb = notifyCb;
                notifyCb = null;
                if (cb == null) return;
                if (status == BluetoothGatt.GATT_SUCCESS) resolve(cb, "null");
                else reject(cb, "NetworkError", "Bildirimler açılamadı (kod " + status + ")");
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, final int status) {
            main.post(() -> {
                String cb = writeCb;
                writeCb = null;
                if (cb == null) return;
                if (status == BluetoothGatt.GATT_SUCCESS) resolve(cb, "null");
                else reject(cb, "NetworkError", "Yazılamadı (kod " + status + ")");
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onNotify(g, c, value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) onNotify(g, c, c.getValue());
        }
    };

    void shutdown() {
        stopScan();
        for (BluetoothGatt g : gatts.values()) {
            try { g.disconnect(); g.close(); } catch (Exception ignored) { }
        }
        gatts.clear();
        for (BluetoothSocket s : sockets.values()) {
            try { s.close(); } catch (Exception ignored) { }
        }
        sockets.clear();
        sppWriter.shutdownNow();
    }
}
