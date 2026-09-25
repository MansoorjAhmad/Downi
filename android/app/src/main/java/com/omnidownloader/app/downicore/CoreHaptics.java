package com.omnidownloader.app.downicore;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * The Core's haptic voice (§A1: the object speaks through touch).
 *
 * A deliberate design rule: the Core NEVER makes sound — it would compete with the video's own
 * audio. Haptics only, and only while the Core is actually on screen (never from a pocket):
 *
 *   detected   one soft tick            "I see it"
 *   complete   quick double tick        "yours"
 *   failed     one low, dull pulse      "it didn't work"
 *   unsupported two short dull taps     "not this kind of thing"
 *
 * Gentle amplitudes throughout — a whisper through the finger, never a buzz.
 */
public final class CoreHaptics {

    private CoreHaptics() {}

    public static void detected(Context c)  { waveform(c, new long[]{0, 14}, new int[]{0, 120}); }
    public static void complete(Context c)  { waveform(c, new long[]{0, 12, 70, 18}, new int[]{0, 130, 0, 170}); }
    public static void failed(Context c)    { waveform(c, new long[]{0, 46}, new int[]{0, 90}); }
    public static void unsupported(Context c) { waveform(c, new long[]{0, 20, 90, 20}, new int[]{0, 90, 0, 90}); }

    private static Vibrator vibrator(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return vm != null ? vm.getDefaultVibrator() : null;
            }
            return (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void waveform(Context c, long[] timings, int[] amplitudes) {
        try {
            Vibrator v = vibrator(c);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
            } else {
                // Pre-O: amplitude control doesn't exist — only the timing pattern.
                v.vibrate(timings, -1);
            }
        } catch (Throwable ignored) {}
    }
}
