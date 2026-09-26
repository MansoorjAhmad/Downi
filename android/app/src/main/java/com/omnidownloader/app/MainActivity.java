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
        handleOpenQueue(getIntent());

        // The https://localhost page loads Vault media from the loopback
        // player server (http://127.0.0.1) — allow that mixed content pair.
        try {
            android.webkit.WebView webView = getBridge().getWebView();
            webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        } catch (Exception ignored) {}

        // Pre-warm Chaquopy runtime asynchronously - but AFTER the UI settles: Python.start
        // is CPU-hungry for a second or two on low-end devices, and starting it at t=0 stole
        // frames from the WebView during app open (owner: "feels laggy"). 1.2 s delay.
        new android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed(() -> new Thread(() -> {
                try {
                    if (!Python.isStarted()) {
                        Python.start(new AndroidPlatform(getApplicationContext()));
                    }
                    // Warm the engine too: importing downloader pulls in yt-dlp, so the
                    // first Grab starts seconds faster.
                    Python.getInstance().getModule("downloader").callAttr("engine_info");
                } catch (Exception ignored) {}
            }, "downi-engine-warmup").start(), 1200);

        // NOTE: POST_NOTIFICATIONS is requested on the user's first Grab (from the
        // web layer via DowniEngine.requestNotificationPermission), not at launch —
        // a permission prompt before the first feature is just noise.

        // Perceived speed: start the Fetcher recovery during the splash - the binding write
        // overlaps the WebView load instead of running after it (onResume still re-checks).
        ensureFetcherArmed();
    }

    @Override
    public void onNewIntent(android.content.Intent intent) {
        setIntent(intent);
        super.onNewIntent(intent);
        handleOpenQueue(intent);
        String action = intent.getAction();
        String sharedUrl = null;
        if (android.content.Intent.ACTION_SEND.equals(action)) {
            sharedUrl = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
        } else if (android.content.Intent.ACTION_PROCESS_TEXT.equals(action)) {
            // Text-selection share (overflow menu → DOWNI). API 23+.
            CharSequence value = intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT);
            if (value == null) value = intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY);
            if (value != null) sharedUrl = value.toString();
        }
        if (sharedUrl != null && !sharedUrl.trim().isEmpty()) {
            String payload = "{\"url\":" + org.json.JSONObject.quote(sharedUrl) + "}";
            // Best-effort delivery: if the WebView is mid-load, the JS listener is
            // not registered yet and the event is lost. Do NOT consume the intent
            // here — the boot block's getSharedUrl() then picks the share up (and
            // consumes it there). Consuming on the fly silently loses warm shares.
            getBridge().triggerJSEvent("onShareReceived", "window", payload);
        }
    }

    /**
     * v3.1.1 (defect N7): a tap on a grab notification carries openQueue=true — park it in
     * SharedPreferences so the web layer can consume it (cold start AND warm resume alike).
     */
    private void handleOpenQueue(android.content.Intent intent) {
        try {
            if (intent != null && intent.getBooleanExtra("openQueue", false)) {
                getSharedPreferences("downi_settings", MODE_PRIVATE)
                    .edit().putBoolean("openQueuePending", true).apply();
            }
        } catch (Exception ignored) {}
    }

    // ---------- Fetcher self-recovery (vivo ABE wipes the accessibility binding) ----------
    // Wave 3: the logic lives in FetcherRecovery so EVERY DOWNI entry point re-arms, and the
    // self-healing keep-alive job (D-V2-4) is scheduled alongside. Guards unchanged.

    private void ensureFetcherArmed() {
        String outcome = FetcherRecovery.ensureArmed(this);
        if (outcome.startsWith("ok-rearmed")) {
            android.util.Log.i("DOWNI", "fetcher re-armed after vendor wipe (wasArmed=true)");
        }
        FetcherRecovery.scheduleKeepAlive(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        ensureFetcherArmed();
    }
}
