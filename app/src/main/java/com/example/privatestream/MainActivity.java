package com.example.privatestream;

import android.app.UiModeManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

public class MainActivity extends ComponentActivity {
    private WebView webView;
    private FrameLayout container;
    private TvNativePlayback nativePlayback;
    private View fullScreenView;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private final Uri homeUri = Uri.parse(BuildConfig.STREAM_URL);
    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() {
            if (nativePlayback != null && nativePlayback.isActive()) {
                nativePlayback.close();
            } else if (fullScreenView != null) {
                exitFullscreen();
            } else if (webView.canGoBack()) {
                webView.goBack();
                webView.post(MainActivity.this::updateBackHandling);
            } else {
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, backCallback);
        if (!"https".equals(homeUri.getScheme()) || homeUri.getHost() == null
                || homeUri.getHost().trim().isEmpty()) {
            throw new IllegalArgumentException("Set STREAM_URL to a valid HTTPS URL in app/build.gradle.kts");
        }
        container = new FrameLayout(this);
        container.setBackgroundColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            container.setOnApplyWindowInsetsListener((view, insets) -> {
                // Size both the page and fullscreen video within the usable window.
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        } else {
            container.setFitsSystemWindows(true);
        }
        webView = new WebView(this);
        // Keep the initial blank page black until the website paints its content.
        webView.setBackgroundColor(Color.BLACK);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (nativePlayback != null) nativePlayback.close(false, false);
                super.onPageStarted(view, url, favicon);
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                view.post(MainActivity.this::updateBackHandling);
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (nativePlayback != null) nativePlayback.onPageFinished();
                updateBackHandling();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                boolean streaming = java.util.Objects.equals(uri.getHost(), homeUri.getHost())
                        && uri.getPort() == homeUri.getPort();
                boolean keycloak = "lutsch-eier.de".equals(uri.getHost())
                        && (uri.getPort() == -1 || uri.getPort() == 443);
                return !("https".equals(uri.getScheme()) && (streaming || keycloak));
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullScreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullScreenView = view;
                fullScreenCallback = callback;
                container.addView(view, new FrameLayout.LayoutParams(-1, -1));
                // Keep Chromium's viewport measured during fullscreen and rotation.
                webView.setVisibility(View.INVISIBLE);
                setFullscreenSystemBars(true);
                updateBackHandling();
            }
            @Override public void onHideCustomView() {
                exitFullscreen();
            }
        });
        container.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        boolean isTv = getSystemService(UiModeManager.class).getCurrentModeType()
                == Configuration.UI_MODE_TYPE_TELEVISION;
        if (isTv) {
            nativePlayback = new TvNativePlayback(this, webView, container, homeUri, active -> {
                if (active && fullScreenView != null) exitFullscreen();
                setFullscreenSystemBars(active);
                updateBackHandling();
            });
            nativePlayback.install();
        }
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(BuildConfig.STREAM_URL);
        }
        setContentView(container);
        container.requestApplyInsets();
        updateBackHandling();
    }

    private void exitFullscreen() {
        if (fullScreenView == null) return;
        container.removeView(fullScreenView);
        fullScreenView = null;
        webView.setVisibility(View.VISIBLE);
        setFullscreenSystemBars(false);
        if (fullScreenCallback != null) fullScreenCallback.onCustomViewHidden();
        fullScreenCallback = null;
        updateBackHandling();
    }

    @SuppressWarnings("deprecation")
    private void setFullscreenSystemBars(boolean fullscreen) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (fullscreen) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(fullscreen
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
        container.requestApplyInsets();
    }

    private boolean isNativePlaybackActive() {
        return nativePlayback != null && nativePlayback.isActive();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Refresh after the window has received its new bounds.
        container.post(() -> {
            webView.requestLayout();
            if (fullScreenView != null) fullScreenView.requestLayout();
            container.requestLayout();
            container.requestApplyInsets();
            if (fullScreenView != null || isNativePlaybackActive()) setFullscreenSystemBars(true);
        });
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && (fullScreenView != null || isNativePlaybackActive())) {
            setFullscreenSystemBars(true);
        }
    }

    private void pausePlayback() {
        webView.evaluateJavascript("(function(){document.querySelectorAll('video,audio').forEach(function(m){m.pause()});window.dispatchEvent(new Event('native-playback-pause'));})();", null);
    }

    @Override protected void onPause() {
        if (nativePlayback != null) nativePlayback.onPause();
        pausePlayback();
        webView.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        webView.onResume();
        if (nativePlayback != null) nativePlayback.onResume();
    }

    @Override protected void onStop() {
        if (nativePlayback != null) nativePlayback.onStop();
        super.onStop();
    }

    // Activity.dispatchKeyEvent is the framework callback for TV remote events.
    // AndroidX core annotates its intermediate implementation as library-restricted.
    @android.annotation.SuppressLint("RestrictedApi")
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (nativePlayback != null && nativePlayback.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    private void updateBackHandling() {
        // Let the system handle Back at the root, including back-to-home animation.
        backCallback.setEnabled(isNativePlaybackActive() || fullScreenView != null || webView.canGoBack());
    }

    @Override protected void onDestroy() {
        if (nativePlayback != null) nativePlayback.destroy();
        if (fullScreenView != null) exitFullscreen();
        container.removeView(webView);
        webView.destroy();
        super.onDestroy();
    }
}
