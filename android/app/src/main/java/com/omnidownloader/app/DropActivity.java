package com.omnidownloader.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DowniDrop 2.0 — the invisible share door. Revives the v2.6.4 ShareReceiverActivity
 * pattern that v3.0 removed when shares moved into the Inspector: translucent,
 * noHistory, excluded from recents — the user NEVER leaves the platform app.
 *
 * v3.1.1 (owner ruling 2026-09-24): shares are ALWAYS the instant background grab —
 * the Instant / Ask-quality toggle was deleted after the instant column passed the
 * full device matrix. The Inspector remains for the in-app paste / Vortex flows.
 */
public class DropActivity extends Activity {
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = extractUrl(extractSharedText(getIntent()));

        if (url == null) {
            Toast.makeText(this, "DowniDrop: no link found in that share", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "⚡ DowniDrop: grabbing in background…", Toast.LENGTH_SHORT).show();
            DowniDownloadService.startShared(this, url);
        }
        finish();
    }

    private String extractSharedText(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            return intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            // Text-selection share (overflow menu → DOWNI). API 23+.
            CharSequence value = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (value == null) value = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY);
            return value != null ? value.toString() : null;
        }
        return null;
    }

    private String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }
}
