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

    private static final String A11Y_COMPONENT =
            "com.omnidownloader.app/com.omnidownloader.app.FetchSpikeService";

    /**
     * The vivo Application Behavior Engine force-stops the Fetcher and CLEARS
     * `enabled_accessibility_services` with it — the Core then never comes back until the
     * binding is re-applied (measured 2026-09-25: process dead, setting null, no unbind marker).
     *
     * With the one-time adb grant `WRITE_SECURE_SETTINGS`, DOWNI can re-apply its own binding
     * the moment the user opens the app: recovery becomes "open DOWNI" instead of a manual
     * Settings walk. Guarded three ways so it can never surprise anyone:
     *   1. only if the user ever armed the Fetcher (`wasArmed`, set by the service itself),
     *   2. only with the grant present (absent -> silent no-op),
     *   3. only when the binding is actually missing.
     */
    private void ensureFetcherArmed() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("downi_fetcher", MODE_PRIVATE);
            if (!prefs.getBoolean("wasArmed", false)) return;
            if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            android.content.ContentResolver cr = getContentResolver();
            String current = android.provider.Settings.Secure.getString(
                    cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current != null && current.contains(A11Y_COMPONENT)) {
                if (android.provider.Settings.Secure.getInt(
                        cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                    android.provider.Settings.Secure.putInt(
                            cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                }
                return;
            }
            String next = (current == null || current.trim().isEmpty())
                    ? A11Y_COMPONENT : current + ":" + A11Y_COMPONENT;
            android.provider.Settings.Secure.putString(
                    cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            android.provider.Settings.Secure.putInt(
                    cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            android.util.Log.i("DOWNI", "fetcher re-armed after vendor wipe (wasArmed=true)");
        } catch (Throwable t) {
            android.util.Log.i("DOWNI", "fetcher arm check failed: " + t);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        ensureFetcherArmed();
    }
}
