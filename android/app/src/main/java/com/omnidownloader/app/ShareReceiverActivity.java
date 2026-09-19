package com.omnidownloader.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.widget.Toast;

/**
 * Receives Android shares without launching the full web interface.
 * Silent background delegation to OmniDownloadService.
 */
public class ShareReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent source = getIntent();
        String text = sharedText(source);
        if (text != null && !text.trim().isEmpty()) {
            Toast.makeText(this, "OmniDrop: Downloading in background…", Toast.LENGTH_SHORT).show();
            OmniDownloadService.startShared(this, text.trim());
        }
        finish();
    }

    private String sharedText(Intent source) {
        if (source == null) return "";
        CharSequence direct = source.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (direct == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            direct = source.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        }
        if (direct != null && direct.length() > 0) return direct.toString();
        ClipData clip = source.getClipData();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence value = clip.getItemAt(0).coerceToText(this);
            if (value != null) return value.toString();
        }
        return "";
    }
}
