package com.ex30.batarya;

import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.media.MediaBrowserService;

import java.util.ArrayList;
import java.util.List;

/**
 * Boş medya servisi: Android Auto medya uygulamalarını ayrı listelediği için uygulamanın
 * Android Auto'da görünmesine yardım eder (NoteVitals'taki yaklaşım). Ses çalmaz.
 */
public class EX30MediaService extends MediaBrowserService {
    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        session = new MediaSession(this, "EX30Reader");
        setSessionToken(session.getSessionToken());
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot("ex30", null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        result.sendResult(new ArrayList<>());
    }

    @Override
    public void onDestroy() {
        if (session != null) session.release();
        super.onDestroy();
    }
}
