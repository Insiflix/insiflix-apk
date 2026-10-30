package com.example.privatestream

import android.graphics.Color
import android.net.Uri
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** TV-only direct-stream playback. The bridge is available only to the trusted top-level site. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvNativePlayback(
    private val activity: ComponentActivity,
    private val webView: WebView,
    private val container: FrameLayout,
    private val homeUri: Uri,
    private val onActiveChanged: (Boolean) -> Unit,
) {
    private val origin = "${homeUri.scheme}://${homeUri.authority}"
    private val script = activity.assets.open("tv-native-playback.js").bufferedReader().use { it.readText() }
    private var request: JSONObject? = null
    private var playerView: PlayerView? = null
    private var player: ExoPlayer? = null
    private var positionMs = 0L
    private var bridgeInstalled = false
    val isActive: Boolean get() = request != null

    fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(
            webView, "NativePlayback", setOf(origin)
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame || !isHomeOrigin(sourceOrigin) || !isHomeOrigin(Uri.parse(webView.url ?: ""))) {
                return@addWebMessageListener
            }
            val data = message.data ?: return@addWebMessageListener
            if (data.length > 65536) return@addWebMessageListener
            val parsed = runCatching { JSONObject(data) }.getOrNull() ?: return@addWebMessageListener
            if (!isAllowedMedia(Uri.parse(parsed.optString("url"))) || parsed.optString("id").isBlank()) {
                return@addWebMessageListener
            }
            if (!isActive) open(parsed)
        }
        bridgeInstalled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
        }
    }

    fun onPageFinished() {
        if (bridgeInstalled && isHomeOrigin(Uri.parse(webView.url ?: ""))) {
            // Also covers providers without document-start injection.
            webView.evaluateJavascript(script, null)
        }
    }

    private fun isHomeOrigin(uri: Uri): Boolean =
        uri.scheme == homeUri.scheme && uri.host == homeUri.host &&
            effectivePort(uri) == effectivePort(homeUri)

    private fun effectivePort(uri: Uri): Int = if (uri.port == -1) 443 else uri.port

    private fun isAllowedMedia(uri: Uri): Boolean =
        uri.scheme == "https" && uri.userInfo == null && effectivePort(uri) == 443 &&
            (isHomeOrigin(uri) ||
                (uri.host == "api.insiflix.com" && uri.path?.startsWith("/videos/watch/") == true))

    private fun open(data: JSONObject) {
        request = data
        val seconds = data.optDouble("position", 0.0)
        positionMs = if (seconds.isFinite() && seconds >= 0) {
            (seconds.coerceAtMost(Long.MAX_VALUE / 1000.0) * 1000).toLong()
        } else 0
        val view = PlayerView(activity).apply {
            setBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowSubtitleButton(true)
            controllerAutoShow = true
            controllerShowTimeoutMs = 5000
            keepScreenOn = true
        }
        playerView = view
        container.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        onActiveChanged(true)
        // Keep the WebView laid out behind the opaque native video surface.
        // Hiding its window would also suspend the page used to restore playback.
        initializePlayer(play = true)
        view.showController()
        view.requestFocus()
    }

    private fun initializePlayer(play: Boolean) {
        val data = request ?: return
        if (player != null) return
        val source = DefaultHttpDataSource.Factory()
            .setUserAgent(webView.settings.userAgentString)
            .setDefaultRequestProperties(mapOf("Referer" to "$origin/", "Origin" to origin))
        val nativePlayer = ExoPlayer.Builder(
            activity, DefaultRenderersFactory(activity).setEnableDecoderFallback(true)
        )
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .build()
        player = nativePlayer
        nativePlayer.setAudioAttributes(AudioAttributes.DEFAULT, true)
        nativePlayer.setHandleAudioBecomingNoisy(true)
        nativePlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) close(ended = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    activity,
                    "Video could not be played (${error.errorCodeName}). Press Back to return.",
                    Toast.LENGTH_LONG
                ).show()
                playerView?.showController()
            }
        })
        val item = MediaItem.Builder().setUri(data.getString("url"))
        val tracks = data.optJSONArray("subtitles")
        val subtitles = mutableListOf<MediaItem.SubtitleConfiguration>()
        if (tracks != null) {
            for (index in 0 until tracks.length()) {
                val track = tracks.optJSONObject(index) ?: continue
                val uri = Uri.parse(track.optString("url"))
                // Subtitle files may live elsewhere on the same trusted API host.
                if (uri.scheme != "https" || uri.userInfo != null || effectivePort(uri) != 443 ||
                    (uri.host != "api.insiflix.com" && !isHomeOrigin(uri))
                ) continue
                val mime = when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
                    "vtt" -> MimeTypes.TEXT_VTT
                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                    else -> continue
                }
                subtitles += MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(mime)
                    .setLanguage(track.optString("language").ifBlank { null })
                    .setLabel(track.optString("label").ifBlank { null })
                    .setSelectionFlags(if (track.optBoolean("selected")) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
        }
        item.setSubtitleConfigurations(subtitles)
        playerView?.player = nativePlayer
        nativePlayer.setMediaItem(item.build(), positionMs)
        nativePlayer.prepare()
        nativePlayer.playWhenReady = play
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean =
        isActive && playerView?.dispatchKeyEvent(event) == true

    fun onPause() {
        player?.pause()
    }

    fun onStop() {
        releasePlayer()
    }

    fun onResume() {
        if (isActive && player == null) initializePlayer(play = false)
    }

    private fun releasePlayer() {
        player?.let {
            positionMs = it.currentPosition.coerceAtLeast(0)
            playerView?.player = null
            it.release()
        }
        player = null
    }

    fun close(ended: Boolean = false, restorePage: Boolean = true) {
        val data = request ?: return
        releasePlayer()
        request = null
        playerView?.keepScreenOn = false
        container.removeView(playerView)
        playerView = null
        onActiveChanged(false)
        if (restorePage && isHomeOrigin(Uri.parse(webView.url ?: ""))) {
            val id = JSONObject.quote(data.getString("id"))
            webView.evaluateJavascript(
                "window.NativeTVPlayback && window.NativeTVPlayback.complete($id, ${positionMs / 1000.0}, $ended);",
                null
            )
        }
        webView.requestFocus()
    }

    fun destroy() {
        close(restorePage = false)
        if (bridgeInstalled) WebViewCompat.removeWebMessageListener(webView, "NativePlayback")
    }
}
