package com.example.privatestream

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.KeyEvent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var container: FrameLayout
    private var nativePlayback: TvNativePlayback? = null
    private var fullScreenView: View? = null
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private val homeUri = Uri.parse(BuildConfig.STREAM_URL)
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                nativePlayback?.isActive == true -> nativePlayback?.close()
                fullScreenView != null -> exitFullscreen()
                webView.canGoBack() -> {
                    webView.goBack()
                    webView.post { updateBackHandling() }
                }
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)
        require(homeUri.scheme == "https" && !homeUri.host.isNullOrBlank()) {
            "Set STREAM_URL to a valid HTTPS URL in app/build.gradle.kts"
        }

        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            container.setOnApplyWindowInsetsListener { view, insets ->
                // Size both the page and fullscreen video within the usable window.
                val safeInsets = insets.getInsets(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime()
                )
                view.setPadding(
                    safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom
                )
                insets
            }
        } else {
            container.fitsSystemWindows = true
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    nativePlayback?.close(restorePage = false)
                    super.onPageStarted(view, url, favicon)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    view.post { updateBackHandling() }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    nativePlayback?.onPageFinished()
                    updateBackHandling()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    // Keep the streaming site and Keycloak login inside the WebView.
                    val isStreamingOrigin = uri.host == homeUri.host &&
                        uri.port == homeUri.port
                    val isKeycloakOrigin = uri.host == "lutsch-eier.de" &&
                        (uri.port == -1 || uri.port == 443)
                    return !(uri.scheme == "https" &&
                        (isStreamingOrigin || isKeycloakOrigin))
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(
                    view: View, callback: CustomViewCallback
                ) {
                    if (fullScreenView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    fullScreenView = view
                    fullScreenCallback = callback
                    container.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    // Keep Chromium's viewport measured during fullscreen and rotation.
                    this@MainActivity.webView.visibility = View.INVISIBLE
                    setFullscreenSystemBars(true)
                    updateBackHandling()
                }
                override fun onHideCustomView() {
                    exitFullscreen()
                }
            }
        }
        container.addView(webView, FrameLayout.LayoutParams(-1, -1))
        val isTv = getSystemService(UiModeManager::class.java).currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
        if (isTv) {
            nativePlayback = TvNativePlayback(this, webView, container, homeUri) { active ->
                if (active && fullScreenView != null) exitFullscreen()
                setFullscreenSystemBars(active)
                updateBackHandling()
            }.also { it.install() }
        }
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(BuildConfig.STREAM_URL)
        }
        setContentView(container)
        container.requestApplyInsets()
        updateBackHandling()
    }

    private fun exitFullscreen() {
        val view = fullScreenView ?: return
        container.removeView(view)
        fullScreenView = null
        webView.visibility = View.VISIBLE
        setFullscreenSystemBars(false)
        fullScreenCallback?.onCustomViewHidden()
        fullScreenCallback = null
        updateBackHandling()
    }

    @Suppress("DEPRECATION")
    private fun setFullscreenSystemBars(fullscreen: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                if (fullscreen) {
                    systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsets.Type.systemBars())
                } else {
                    show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            window.decorView.systemUiVisibility = if (fullscreen) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
        container.requestApplyInsets()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The Activity handles rotation itself; refresh the fullscreen viewport
        // after the window has received its new landscape/portrait bounds.
        container.post {
            webView.requestLayout()
            fullScreenView?.requestLayout()
            container.requestLayout()
            container.requestApplyInsets()
            if (fullScreenView != null || nativePlayback?.isActive == true) setFullscreenSystemBars(true)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && (fullScreenView != null || nativePlayback?.isActive == true)) {
            setFullscreenSystemBars(true)
        }
    }

    private fun pausePlayback() {
        // Works for native HTML media; Vue can listen to the custom event.
        webView.evaluateJavascript(
            """(function(){document.querySelectorAll('video,audio').forEach(function(m){m.pause()});window.dispatchEvent(new Event('native-playback-pause'));})();""",
            null
        )
    }

    override fun onPause() {
        nativePlayback?.onPause()
        pausePlayback()
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        nativePlayback?.onResume()
    }

    override fun onStop() {
        nativePlayback?.onStop()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (nativePlayback?.dispatchKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun updateBackHandling() {
        // Let the system handle Back at the root, including back-to-home animation.
        backCallback.isEnabled = nativePlayback?.isActive == true ||
            fullScreenView != null || webView.canGoBack()
    }

    override fun onDestroy() {
        nativePlayback?.destroy()
        if (fullScreenView != null) exitFullscreen()
        container.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
