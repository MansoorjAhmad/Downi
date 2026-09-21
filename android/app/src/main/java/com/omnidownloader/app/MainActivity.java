package com.omnidownloader.app;

import com.getcapacitor.BridgeActivity;
import androidx.core.splashscreen.SplashScreen;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.Python;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        registerPlugin(DowniEnginePlugin.class);
        super.onCreate(savedInstanceState);

        // The https://localhost page loads Vault media from the loopback
        // player server (http://127.0.0.1) — allow that mixed content pair.
        try {
            android.webkit.WebView webView = getBridge().getWebView();
            webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        } catch (Exception ignored) {}

        // Pre-warm Chaquopy runtime asynchronously in background (0ms UI lag)
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(getApplicationContext()));
                }
                // Warm the engine too: importing downloader pulls in yt-dlp, so the
                // first Grab starts seconds faster.
                Python.getInstance().getModule("downloader").callAttr("engine_info");
            } catch (Exception ignored) {}
        }).start();

        // NOTE: POST_NOTIFICATIONS is requested on the user's first Grab (from the
        // web layer via DowniEngine.requestNotificationPermission), not at launch —
        // a permission prompt before the first feature is just noise.
    }

    @Override
    public void onNewIntent(android.content.Intent intent) {
        setIntent(intent);
        super.onNewIntent(intent);
        if (android.content.Intent.ACTION_SEND.equals(intent.getAction())) {
            String sharedUrl = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
            if (sharedUrl != null && !sharedUrl.trim().isEmpty()) {
                String payload = "{\"url\":" + org.json.JSONObject.quote(sharedUrl) + "}";
                getBridge().triggerJSEvent("onShareReceived", "window", payload);
            }
        }
    }
}
