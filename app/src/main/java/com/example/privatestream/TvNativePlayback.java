package com.example.privatestream;

import android.graphics.Color;
import android.net.Uri;
import android.view.KeyEvent;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** TV-only direct-stream playback, with a bridge restricted to the trusted top-level site. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class TvNativePlayback {
    public interface ActiveChangedListener {
        void onActiveChanged(boolean active);
    }

    private final ComponentActivity activity;
    private final WebView webView;
    private final FrameLayout container;
    private final Uri homeUri;
    private final ActiveChangedListener onActiveChanged;
    private final String origin;
    private final String script;
    private JSONObject request;
    private PlayerView playerView;
    private ExoPlayer player;
    private long positionMs;
    private boolean bridgeInstalled;

    public TvNativePlayback(ComponentActivity activity, WebView webView, FrameLayout container,
            Uri homeUri, ActiveChangedListener onActiveChanged) {
        this.activity = activity;
        this.webView = webView;
        this.container = container;
        this.homeUri = homeUri;
        this.onActiveChanged = onActiveChanged;
        origin = homeUri.getScheme() + "://" + homeUri.getAuthority();
        try (InputStream input = activity.getAssets().open("tv-native-playback.js");
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            script = new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load TV playback bridge", error);
        }
    }

    public boolean isActive() {
        return request != null;
    }

    public void install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        WebViewCompat.addWebMessageListener(webView, "NativePlayback", Collections.singleton(origin),
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!isMainFrame || !isHomeOrigin(sourceOrigin) || !isHomeOrigin(currentPage())) return;
                    String data = message.getData();
                    if (data == null || data.length() > 65536) return;
                    JSONObject parsed;
                    try {
                        parsed = new JSONObject(data);
                    } catch (JSONException error) {
                        return;
                    }
                    if (!isAllowedMedia(Uri.parse(parsed.optString("url")))
                            || parsed.optString("id").trim().isEmpty()) return;
                    if (!isActive()) open(parsed);
                });
        bridgeInstalled = true;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, Collections.singleton(origin));
        }
    }

    public void onPageFinished() {
        if (bridgeInstalled && isHomeOrigin(currentPage())) {
            // Also covers providers without document-start injection.
            webView.evaluateJavascript(script, null);
        }
    }

    private Uri currentPage() {
        String url = webView.getUrl();
        return Uri.parse(url == null ? "" : url);
    }

    private boolean isHomeOrigin(Uri uri) {
        return Objects.equals(uri.getScheme(), homeUri.getScheme())
                && Objects.equals(uri.getHost(), homeUri.getHost())
                && effectivePort(uri) == effectivePort(homeUri);
    }

    private static int effectivePort(Uri uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }

    private boolean isAllowedMedia(Uri uri) {
        return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                && effectivePort(uri) == 443
                && (isHomeOrigin(uri) || ("api.insiflix.com".equals(uri.getHost())
                    && uri.getPath() != null && uri.getPath().startsWith("/videos/watch/")));
    }

    private void open(JSONObject data) {
        request = data;
        double seconds = data.optDouble("position", 0.0);
        positionMs = Double.isFinite(seconds) && seconds >= 0
                ? (long) (Math.min(seconds, Long.MAX_VALUE / 1000.0) * 1000) : 0;
        PlayerView view = new PlayerView(activity);
        view.setBackgroundColor(Color.BLACK);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setShowSubtitleButton(true);
        view.setControllerAutoShow(true);
        view.setControllerShowTimeoutMs(5000);
        view.setKeepScreenOn(true);
        playerView = view;
        container.addView(view, new FrameLayout.LayoutParams(-1, -1));
        onActiveChanged.onActiveChanged(true);
        // Keep the WebView laid out behind the opaque video surface for restoration.
        initializePlayer(true);
        view.showController();
        view.requestFocus();
    }

    private void initializePlayer(boolean play) {
        JSONObject data = request;
        if (data == null || player != null) return;
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", origin + "/");
        headers.put("Origin", origin);
        DefaultHttpDataSource.Factory source = new DefaultHttpDataSource.Factory()
                .setUserAgent(webView.getSettings().getUserAgentString())
                .setDefaultRequestProperties(headers);
        ExoPlayer nativePlayer = new ExoPlayer.Builder(activity,
                new DefaultRenderersFactory(activity).setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(source)).build();
        player = nativePlayer;
        nativePlayer.setAudioAttributes(AudioAttributes.DEFAULT, true);
        nativePlayer.setHandleAudioBecomingNoisy(true);
        nativePlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) close(true, true);
            }
            @Override public void onPlayerError(PlaybackException error) {
                Toast.makeText(activity, "Video could not be played (" + error.getErrorCodeName()
                        + "). Press Back to return.", Toast.LENGTH_LONG).show();
                if (playerView != null) playerView.showController();
            }
        });
        MediaItem.Builder item = new MediaItem.Builder().setUri(data.optString("url"));
        JSONArray tracks = data.optJSONArray("subtitles");
        List<MediaItem.SubtitleConfiguration> subtitles = new ArrayList<>();
        if (tracks != null) {
            for (int index = 0; index < tracks.length(); index++) {
                JSONObject track = tracks.optJSONObject(index);
                if (track == null) continue;
                Uri uri = Uri.parse(track.optString("url"));
                // Subtitle files may live elsewhere on the same trusted API host.
                if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                        || effectivePort(uri) != 443
                        || (!"api.insiflix.com".equals(uri.getHost()) && !isHomeOrigin(uri))) continue;
                String segment = uri.getLastPathSegment();
                String extension = segment == null ? ""
                        : segment.substring(segment.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                String mime;
                if ("vtt".equals(extension)) mime = MimeTypes.TEXT_VTT;
                else if ("srt".equals(extension)) mime = MimeTypes.APPLICATION_SUBRIP;
                else continue;
                subtitles.add(new MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(mime)
                        .setLanguage(blankToNull(track.optString("language")))
                        .setLabel(blankToNull(track.optString("label")))
                        .setSelectionFlags(track.optBoolean("selected") ? C.SELECTION_FLAG_DEFAULT : 0)
                        .build());
            }
        }
        item.setSubtitleConfigurations(subtitles);
        if (playerView != null) playerView.setPlayer(nativePlayer);
        nativePlayer.setMediaItem(item.build(), positionMs);
        nativePlayer.prepare();
        nativePlayer.setPlayWhenReady(play);
    }

    private static String blankToNull(String value) {
        return value.trim().isEmpty() ? null : value;
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        return isActive() && playerView != null && playerView.dispatchKeyEvent(event);
    }

    public void onPause() {
        if (player != null) player.pause();
    }

    public void onStop() {
        releasePlayer();
    }

    public void onResume() {
        if (isActive() && player == null) initializePlayer(false);
    }

    private void releasePlayer() {
        if (player != null) {
            positionMs = Math.max(player.getCurrentPosition(), 0);
            if (playerView != null) playerView.setPlayer(null);
            player.release();
        }
        player = null;
    }

    public void close() {
        close(false, true);
    }

    public void close(boolean ended, boolean restorePage) {
        JSONObject data = request;
        if (data == null) return;
        releasePlayer();
        request = null;
        if (playerView != null) {
            playerView.setKeepScreenOn(false);
            container.removeView(playerView);
        }
        playerView = null;
        onActiveChanged.onActiveChanged(false);
        if (restorePage && isHomeOrigin(currentPage())) {
            String id = JSONObject.quote(data.optString("id"));
            webView.evaluateJavascript("window.NativeTVPlayback && window.NativeTVPlayback.complete("
                    + id + ", " + positionMs / 1000.0 + ", " + ended + ");", null);
        }
        webView.requestFocus();
    }

    public void destroy() {
        close(false, false);
        if (bridgeInstalled && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, "NativePlayback");
        }
    }
}
