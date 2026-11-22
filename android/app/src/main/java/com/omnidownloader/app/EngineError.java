package com.omnidownloader.app;

import java.util.regex.Pattern;

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
    /** Cause codes are lowercase identifiers; anything else is not a tag we wrote. */
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]*");

    private EngineError() {
    }

    /** The cause code the engine tagged, or "unknown" when it tagged nothing. */
    static String cause(String message) {
        Tag tag = parse(message);
        return tag == null ? "unknown" : tag.cause;
    }

    /** The engine's reason, exactly as the engine wrote it (the tag removed). */
    static String raw(String message) {
        if (message == null) {
            return "";
        }
        Tag tag = parse(message);
        return tag == null ? message.trim() : tag.raw;
    }

    /**
     * Read the tag wherever it landed.
     *
     * <p>The engine raises Python exceptions, and Chaquopy hands those to Java with the Python
     * exception type in front of the message ("RuntimeError: [cause:rate_limited] ERROR: ...").
     * The tag is therefore not always at index 0. Searching for it (instead of requiring it at
     * the start) is what keeps the truth layer alive on-device: the old startsWith() check
     * matched nothing that crossed the bridge, so every engine failure surfaced as "unknown".
     */
    private static Tag parse(String message) {
        if (message == null) {
            return null;
        }
        String text = message.trim();
        int start = text.indexOf(PREFIX);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf(']', start + PREFIX.length());
        if (end < 0) {
            return null;
        }
        String code = text.substring(start + PREFIX.length(), end).trim();
        if (!CODE.matcher(code).matches()) {
            return null;
        }
        return new Tag(code, text.substring(end + 1).trim());
    }

    private static final class Tag {
        final String cause;
        final String raw;

        Tag(String cause, String raw) {
            this.cause = cause;
            this.raw = raw;
        }
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
