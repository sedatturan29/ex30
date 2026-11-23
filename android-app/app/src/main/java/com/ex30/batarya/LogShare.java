package com.ex30.batarya;

import android.graphics.Bitmap;
import android.util.Base64;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Orta ekrandan telefona kayıt aktarma: aynı Wi-Fi'deki (ör. telefonun hotspot'u) telefon,
 * ekrandaki QR kodu okutunca dosyayı indirir. Küçük bir HTTP sunucusu 10 dakika açık kalır.
 */
final class LogShare {
    private static final int PORT = 8730;
    private static ServerSocket server;
    private static volatile byte[] data;
    private static volatile String fileName;
    private static volatile long until;

    /** Dosyayı yayınlar; {"url":..,"qr":"data:image/png;base64,.."} ya da {"error":..} döner. */
    static synchronized String serve(String name, byte[] bytes) {
        try {
            String ip = localIp();
            if (ip == null) return "{\"error\":\"Araç bir Wi-Fi ağına bağlı değil. Telefonun hotspot'unu açıp aracı ona bağlayın.\"}";
            data = bytes; fileName = name; until = System.currentTimeMillis() + 10 * 60_000L;
            if (server == null || server.isClosed()) start();
            String url = "http://" + ip + ":" + PORT + "/" + URLEncoder.encode(name, "UTF-8");
            return "{\"url\":\"" + url + "\",\"qr\":\"" + qr(url) + "\",\"name\":\"" + name.replace("\"", "") + "\"}";
        } catch (Throwable e) {
            return "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    private static void start() throws Exception {
        server = new ServerSocket(PORT);
        Thread t = new Thread(() -> {
            while (server != null && !server.isClosed()) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(5000);
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[2048]; int n = in.read(buf);   // isteği oku (içeriği önemli değil)
                    OutputStream out = s.getOutputStream();
                    if (n <= 0) continue;
                    byte[] d = data;
                    if (d == null || System.currentTimeMillis() > until) {
                        out.write("HTTP/1.1 410 Gone\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\nSüre doldu; araçta tekrar İndir'e basın.".getBytes(StandardCharsets.UTF_8));
                    } else {
                        String h = "HTTP/1.1 200 OK\r\nContent-Type: text/csv; charset=utf-8\r\nContent-Length: " + d.length
                                + "\r\nContent-Disposition: attachment; filename=\"" + fileName + "\"\r\nConnection: close\r\n\r\n";
                        out.write(h.getBytes(StandardCharsets.UTF_8));
                        out.write(d);
                    }
                    out.flush();
                } catch (Throwable ignored) { }
                if (System.currentTimeMillis() > until + 60_000L) stop();
            }
        }, "ex30-logshare");
        t.setDaemon(true);
        t.start();
    }

    static synchronized void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null; data = null;
    }

    private static String localIp() throws Exception {
        String fallback = null;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || !a.isSiteLocalAddress()) continue;
                String ip = a.getHostAddress();
                if (ni.getName().startsWith("wlan")) return ip;
                if (fallback == null) fallback = ip;
            }
        }
        return fallback;
    }

    private static String qr(String text) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 360, 360, hints);
        int w = m.getWidth(), h = m.getHeight();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
        Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
    }
}
