package com.omnidownloader.app;

import com.getcapacitor.BridgeActivity;
import androidx.core.splashscreen.SplashScreen;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.Python;

public class MainActivity extends BridgeActivity {
    /**
     * v3.6.0: the native splash hands over to the web.
     *
     * The splash is NOT held: on Android 12+ it IS the window's starting
     * window, so a hold stops the WebView from drawing (and from even loading
     * the page) and delays the whole startup by exactly the length of the hold.
     * The brand moment the user sees is the web's own lockup, and the web
     * starts it on its first contentful paint - the first frame that is really
     * on screen.
     */
    private boolean engineWarm = false;

    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        androidx.core.splashscreen.SplashScreen splash = SplashScreen.installSplashScreen(this);
        splash.setOnExitAnimationListener(new androidx.core.splashscreen.SplashScreen.OnExitAnimationListener() {
            @Override public void onSplashScreenExit(androidx.core.splashscreen.SplashScreenViewProvider provider) {
                try { provider.remove(); } catch (Exception ignored) {}
                // The engine warms up after the handover: Python.start is
                // CPU-hungry for a second or two and would steal the frames the
                // WebView needs to paint the app.
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(MainActivity.this::warmUpEngine, 1500);
            }
        });
        registerPlugin(DowniEnginePlugin.class);
        super.onCreate(savedInstanceState);
        handleOpenQueue(getIntent());

        // v3.6.0: the WebView paints Obsidian from its first frame. The window
        // behind it is brand, not the platform's default grey.
        try {
            getBridge().getWebView().setBackgroundColor(android.graphics.Color.parseColor("#090B0D"));
        } catch (Exception ignored) {}

        // Safety net: if the splash never reports an exit, the engine still warms.
        // It is deliberately late - Python.start steals the frames the WebView
        // needs to paint the app, and the app's first paint is what the whole
        // startup is waiting on.
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::warmUpEngine, 6000);

        // NOTE: POST_NOTIFICATIONS is requested on the user's first Grab (from the
        // web layer via DowniEngine.requestNotificationPermission), not at launch —
        // a permission prompt before the first feature is just noise.

    }

    /**
     * Pre-warm Chaquopy runtime asynchronously - but AFTER the UI settles: Python.start
     * is CPU-hungry for a second or two on low-end devices, and starting it at t=0 stole
     * frames from the WebView during app open (owner: "feels laggy").
     */
    private void warmUpEngine() {
        synchronized (this) {
            if (engineWarm) return;
            engineWarm = true;
        }
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(getApplicationContext()));
                }
                // Warm the engine too: importing downloader pulls in yt-dlp, so the
                // first Grab starts seconds faster.
                Python.getInstance().getModule("downloader").callAttr("engine_info");
            } catch (Exception ignored) {}
        }, "downi-engine-warmup").start();
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
}
