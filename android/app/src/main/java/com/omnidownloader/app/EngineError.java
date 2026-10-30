package com.omnidownloader.app;

/**
 * The truth layer, Java side: read the engine's cause tag, keep the raw reason.
 *
 * <p>The engine tags every failure it raises ("[cause:blocked] ERROR: ..."), so this side never
 * has to guess. The rules live in one place - the engine's truth.py - and this class only strips
 * the tag and hands the raw reason back. It never rewrites the reason: the old friendlyError()
 * rewrote it, which is exactly why the web layer's specific messages could never match and
 * every failure landed on one catch-all.
 *
 * <p>The short phrase exists for the native notification, which is a native surface and needs
 * native-length text. The full sentences live in the web layer, which owns the app's voice.
 */
final class EngineError {

    private static final String PREFIX = "[cause:";

    private EngineError() {
    }

    /** The cause code the engine tagged, or "unknown" when it tagged nothing. */
    static String cause(String message) {
        if (message == null) return "unknown";
        String text = message.trim();
        if (text.startsWith(PREFIX)) {
            int end = text.indexOf(']');
            if (end > PREFIX.length()) {
                return text.substring(PREFIX.length(), end);
            }
        }
        return "unknown";
    }

    /** The engine's reason, exactly as the engine wrote it (the tag removed). */
    static String raw(String message) {
        if (message == null) {
            return "";
        }
        String text = message.trim();
        if (text.startsWith(PREFIX)) {
            int end = text.indexOf(']');
            if (end > PREFIX.length()) {
                return text.substring(end + 1).trim();
            }
        }
        return text;
    }

    /** A short phrase for the native notification - no guesses, no blame. */
    static String phrase(String cause) {
        if (cause == null) {
            return "DOWNI couldn't finish this grab.";
        }
        switch (cause) {
            case "blocked":
                return "The site refused DOWNI's request.";
            case "rate_limited":
                return "The site is rate-limiting this device.";
            case "private":
                return "That video needs an account, or it is private.";
            case "geo":
                return "That video isn't available in your country.";
            case "format":
                return "That quality isn't offered for this video.";
            case "link":
                return "DOWNI couldn't read a video from that link.";
            case "network":
                return "DOWNI couldn't reach the network.";
            case "cancelled":
                return "Grab canceled.";
            default:
                return "DOWNI's engine couldn't fetch this stream.";
        }
    }
}
