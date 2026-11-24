package com.ex30.batarya;

import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.media.MediaBrowserService;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "EX30 Mini": Android Auto'nun medya ekranını gösterge olarak kullanır (NoteVitals Mini'deki yaklaşım).
 * Ses çalmaz; "Çalan" ekranındaki başlık/alt satırlara canlı değerleri yazar ve 2 sn'de bir günceller.
 */
public class EX30MediaService extends MediaBrowserService {
    private static final Locale TR = new Locale("tr", "TR");
    private MediaSession session;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean on;

    private final Runnable tick = new Runnable() {
        @Override public void run() { if (!on) return; publish(); h.postDelayed(this, 2000); }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        session = new MediaSession(this, "EX30Mini");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { start(); }
            @Override public void onPlayFromMediaId(String id, Bundle extras) { start(); }
            @Override public void onPause() { stop(); }
            @Override public void onStop() { stop(); }
        });
        setSessionToken(session.getSessionToken());
        state(PlaybackState.STATE_PAUSED);
        publish();
    }

    private void start() {
        on = true; session.setActive(true); state(PlaybackState.STATE_PLAYING);
        h.removeCallbacks(tick); h.post(tick);
        try { WebHost.onCarStart(getApplicationContext()); } catch (Throwable ignored) { }   // bağlı değilse bağlan
    }

    private void stop() { on = false; h.removeCallbacks(tick); state(PlaybackState.STATE_PAUSED); }

    private void state(int st) {
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                .setState(st, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());
    }

    private static String f(JSONObject o, String k, int d) {
        if (o == null || !o.has(k) || o.isNull(k)) return "–";
        double v = o.optDouble(k, Double.NaN);
        return Double.isNaN(v) ? "–" : String.format(TR, "%." + d + "f", v);
    }

    private void publish() {
        JSONObject d = CarData.latest();
        JSONObject aa = d == null ? null : d.optJSONObject("aa");
        String title, artist, album;
        if (d == null || !d.optBoolean("conn", false) || CarData.ageMs() > 20_000) {
            title = "EX30 Mini";
            artist = d == null || d.optString("status", "").isEmpty() ? "Bağlanmak için ▶ dokunun" : d.optString("status", "");
            album = "OBD adaptörü";
        } else {
            title = "Batarya " + f(d, "avg", 1) + "°C · %" + f(d, "soc", 0) + " · " + f(aa, "kw", 0) + " kW";
            artist = f(aa, "vs", 0) + " km/s · motor " + f(aa, "motor", 0) + "°C · soğutma " + f(d, "cool", 0) + "°C";
            JSONObject t = aa == null ? null : aa.optJSONObject("trip");
            album = t == null ? "maks hücre " + f(d, "max", 1) + "°C"
                    : "Yolculuk " + f(t, "cons", 1) + " kWh/100 · " + f(t, "km", 1) + " km";
        }
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, album)
                .build());
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot("ex30", null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        List<MediaBrowser.MediaItem> l = new ArrayList<>();
        l.add(new MediaBrowser.MediaItem(new MediaDescription.Builder().setMediaId("live")
                .setTitle("Canlı veriler").setSubtitle("Batarya, güç, hız, sıcaklıklar").build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
        result.sendResult(l);
    }

    @Override
    public void onDestroy() {
        stop();
        if (session != null) session.release();
        super.onDestroy();
    }
}
