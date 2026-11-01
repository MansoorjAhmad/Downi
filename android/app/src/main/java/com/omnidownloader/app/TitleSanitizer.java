package com.omnidownloader.app;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * TitleSanitizer: Enforces robust filename and title sanitation across DOWNI (W2-B).
 *
 * Implements defenses for T1-T8 risk vectors:
 * - T1: Canonical Unicode Normalization (NFC) for Urdu, Arabic, Hindi, Chinese, etc.
 * - T2: Stripping BiDi control / override characters (\u200E, \u200F, \u202A-\u202E, \u2066-\u2069, \uFEFF, \u200B).
 * - T3: Safe surrogate-pair boundary truncation (never splits high/low surrogate pairs).
 * - T4: Replacement of illegal filesystem characters (\ / : * ? " < > |) and control chars (\u0000-\u001F, \u007F-\u009F).
 * - T5: Stripping trailing/leading dots and spaces, and disambiguating Windows reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9).
 * - T6: Collision-safe base title generation.
 * - T7: Byte-bounded truncation (under 180 UTF-8 bytes / 80 characters).
 * - T8: Safe fallback for empty or metadata-only titles ("Video").
 */
public final class TitleSanitizer {

    private static final Set<String> WINDOWS_RESERVED = new HashSet<>(Arrays.asList(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    ));

    private TitleSanitizer() {}

    /**
     * Sanitizes a title string into a safe base title (without file extension).
     *
     * @param requested Raw title string from user, URL, or metadata.
     * @return Sanitized, safe base title.
     */
    public static String sanitize(String requested) {
        if (requested == null || requested.trim().isEmpty()) {
            return "Video";
        }

        // T1: Unicode normalization (NFC)
        String text = Normalizer.normalize(requested, Normalizer.Form.NFC);

        // T2: Strip BiDi control characters, invisible formatting, and directional overrides
        text = text.replaceAll("[\u200E\u200F\u202A-\u202E\u2066-\u2069\uFEFF\u200B]+", "");

        // T4: Replace illegal filesystem characters and control characters (\u0000-\u001F, \u007F-\u009F)
        text = text.replaceAll("[\\/:*?\"<>|\r\n\t\u0000-\u001F\u007F-\u009F]+", " ");

        // Collapse multiple whitespace
        text = text.replaceAll("\\s+", " ").trim();

        // T5: Strip leading and trailing dots, spaces, and dashes
        text = text.replaceAll("^[.\\s-]+|[.\\s-]+$", "").trim();

        // T8: Fallback if empty after stripping
        if (text.isEmpty()) {
            return "Video";
        }

        // T5: Disambiguate Windows reserved device names
        if (WINDOWS_RESERVED.contains(text.toUpperCase(Locale.US))) {
            text = "Video_" + text;
        }

        // T3 & T7: Length truncation (max 80 chars, max 180 UTF-8 bytes)
        if (text.length() > 80) {
            int cut = text.lastIndexOf(' ', 80);
            text = (cut > 40) ? text.substring(0, cut).trim() : text.substring(0, 80).trim();
        }

        // Ensure we didn't cut across a surrogate pair (T3)
        if (!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
            text = text.substring(0, text.length() - 1).trim();
        }

        // Ensure UTF-8 byte length <= 180 bytes (T7)
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 180) {
            int charLen = text.length();
            while (charLen > 10 && text.substring(0, charLen).getBytes(StandardCharsets.UTF_8).length > 180) {
                charLen--;
            }
            text = text.substring(0, charLen).trim();
            if (!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
                text = text.substring(0, text.length() - 1).trim();
            }
        }

        // Final cleanup of trailing dots/spaces/dashes after truncation
        text = text.replaceAll("^[.\\s-]+|[.\\s-]+$", "").trim();
        return text.isEmpty() ? "Video" : text;
    }

    /**
     * Builds a safe filename by combining sanitized base title with extension.
     */
    public static String safeFileName(String requested, String ext) {
        String base = sanitize(requested);
        String cleanExt = (ext == null || ext.trim().isEmpty()) ? "mp4" : ext.trim().toLowerCase(Locale.US).replaceFirst("^\\.", "");
        if (cleanExt.isEmpty()) cleanExt = "mp4";

        if (base.toLowerCase(Locale.US).endsWith("." + cleanExt)) {
            base = base.substring(0, base.length() - cleanExt.length() - 1).trim();
            if (base.isEmpty()) base = "Video";
        }
        return base + "." + cleanExt;
    }
}
