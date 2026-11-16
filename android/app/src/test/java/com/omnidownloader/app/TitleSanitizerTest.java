package com.omnidownloader.app;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class TitleSanitizerTest {

    @Test
    public void fallsBackToVideoOnEmptyOrPunctuationOnly() {
        assertEquals("Video", TitleSanitizer.sanitize(null));
        assertEquals("Video", TitleSanitizer.sanitize("   "));
        assertEquals("Video", TitleSanitizer.sanitize("...---///???"));
    }

    @Test
    public void stripsBidiControlsAndIllegalFilesystemChars() {
        String dirty = "\u202Eevil\u200F:video*name?<>\u0007|clip";
        String clean = TitleSanitizer.sanitize(dirty);
        assertFalse(clean.contains("\u202E"));
        assertFalse(clean.contains("\u200F"));
        assertFalse(clean.matches(".*[\\\\/:*?\"<>|].*"));
        assertEquals("evil video name clip", clean);
    }

    @Test
    public void disambiguatesWindowsReservedDeviceNames() {
        assertEquals("Video_CON", TitleSanitizer.sanitize("CON"));
        assertEquals("Video_nul", TitleSanitizer.sanitize("nul"));
        assertEquals("Video_COM1", TitleSanitizer.sanitize("COM1"));
        assertEquals("Video_LPT9", TitleSanitizer.sanitize("LPT9"));
    }

    @Test
    public void enforcesByteAndSurrogatePairBounds() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("\uD83D\uDE80"); // 4-byte UTF-8 emoji (2 UTF-16 chars)
        }
        String result = TitleSanitizer.sanitize(sb.toString());
        assertTrue(result.length() <= 80);
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= 180);
        assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 1)));
    }

    @Test
    public void normalizesUnicodeAndSanitizesExtension() {
        // Decomposed e + combining acute -> NFC
        String nfd = "Cafe\u0301";
        assertEquals("Caf\u00e9", TitleSanitizer.sanitize(nfd));
        assertEquals("My Clip.mp4", TitleSanitizer.safeFileName("My Clip.mp4", "..MP4/"));
        assertEquals("Audio.opus", TitleSanitizer.safeFileName("Audio", ".opus"));
    }
}
