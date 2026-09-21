package com.omnidownloader.app;

import com.getcapacitor.BridgeActivity;
import androidx.core.splashscreen.SplashScreen;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.Python;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        registerPlugin(OmniEnginePlugin.class);
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
            } catch (Exception ignored) {}
        }).start();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4103);
        }
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
