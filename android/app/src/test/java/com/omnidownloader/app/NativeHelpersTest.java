package com.omnidownloader.app;

import android.media.MediaFormat;

import org.junit.Test;

import static org.junit.Assert.*;

public class NativeHelpersTest {

    @Test
    public void cleansSharedUrlsAndStripsTrackingParams() {
        assertEquals(
                "https://www.instagram.com/reel/ABC123/",
                DowniNativeUtils.extractAndCleanUrl("Watch this! https://www.instagram.com/reel/ABC123/?igsh=xyz123&utm_source=ig_web_copy_link)")
        );
        assertEquals(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
                DowniNativeUtils.extractAndCleanUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=abcdef&t=42s.")
        );
        assertNull(DowniNativeUtils.extractAndCleanUrl("no link here"));
    }

    @Test
    public void restrictsUpdaterToAllowedHttpsGitHubHosts() {
        assertTrue(DowniNativeUtils.isAllowedUpdateUrl("https://github.com/owner/repo/releases/download/v3.8.1/DOWNI-v3.8.1.apk"));
        assertTrue(DowniNativeUtils.isAllowedUpdateUrl("https://objects.githubusercontent.com/github-production-release-asset/123"));
        assertTrue(DowniNativeUtils.isAllowedUpdateUrl("https://release-assets.githubusercontent.com/assets/app.apk"));
        assertFalse(DowniNativeUtils.isAllowedUpdateUrl("http://github.com/owner/repo/releases/download/v3.8.1/app.apk"));
        assertFalse(DowniNativeUtils.isAllowedUpdateUrl("https://evil.example.com/update.apk"));
        assertFalse(DowniNativeUtils.isAllowedUpdateUrl("https://raw.githubusercontent.com/owner/repo/main/app.apk"));
    }

    @Test
    public void formatsBytesSpeedAndGeneratesUniqueJobIds() {
        assertEquals("0 MB", DowniNativeUtils.formatBytes(0));
        assertEquals("5.0 MB", DowniNativeUtils.formatBytes(5L * 1024 * 1024));
        assertEquals("120 MB", DowniNativeUtils.formatBytes(120L * 1024 * 1024));
        assertEquals("1.50 GB", DowniNativeUtils.formatBytes((long) (1.5 * 1024 * 1024 * 1024)));
        assertEquals("500 KB/s", DowniNativeUtils.formatSpeed(500 * 1024));
        assertEquals("2.5 MB/s", DowniNativeUtils.formatSpeed(2.5 * 1024 * 1024));
        assertEquals("12s left", DowniNativeUtils.formatEta(12));
        assertEquals("", DowniNativeUtils.formatEta(0));

        String a = DowniNativeUtils.nextJobId("drop");
        String b = DowniNativeUtils.nextJobId("drop");
        assertNotEquals(a, b);
        assertTrue(a.startsWith("drop"));
    }

    @Test
    public void mapsAudioAndVideoMimesAccurately() {
        assertEquals("audio/opus", MediaStoreSaver.mimeForExtension("opus"));
        assertEquals("audio/ogg", MediaStoreSaver.mimeForExtension(".ogg"));
        assertEquals("audio/mp4", MediaStoreSaver.mimeForExtension("m4a"));
        assertEquals("audio/mpeg", MediaStoreSaver.mimeForExtension("mp3"));
        assertEquals("video/webm", MediaStoreSaver.mimeForExtension("webm"));
        assertEquals("video/x-matroska", MediaStoreSaver.mimeForExtension("mkv"));
        assertEquals("video/mp4", MediaStoreSaver.mimeForExtension("mp4"));
        assertTrue(MediaStoreSaver.isAudioExtension("opus"));
        assertTrue(MediaStoreSaver.isAudioExtension("m4a"));
        assertFalse(MediaStoreSaver.isAudioExtension("webm"));
        assertEquals("text/vtt", MediaStoreSaver.mimeForExtension("vtt"));
        assertEquals("application/x-subrip", MediaStoreSaver.mimeForExtension(".srt"));
        assertEquals("text/x-ssa", MediaStoreSaver.mimeForExtension("ass"));
        assertEquals("application/ttml+xml", MediaStoreSaver.mimeForExtension("ttml"));
        assertFalse(MediaStoreSaver.isAudioExtension("vtt"));
        assertFalse(MediaStoreSaver.isAudioExtension("srt"));
    }

    @Test
    public void validatesMergerAndShieldCodecGates() {
        assertTrue(Mp4Merger.isVideoMimeSupported("video/avc", 24));
        assertFalse(Mp4Merger.isVideoMimeSupported("video/x-vnd.on2.vp9", 28));
        assertTrue(Mp4Merger.isVideoMimeSupported("video/x-vnd.on2.vp9", 29));
        assertFalse(Mp4Merger.isVideoMimeSupported("video/av01", 33));
        assertTrue(Mp4Merger.isVideoMimeSupported("video/av01", 34));

        assertTrue(Mp4Merger.isAudioMimeSupported(MediaFormat.MIMETYPE_AUDIO_AAC));
        assertFalse(Mp4Merger.isAudioMimeSupported("audio/opus"));
        assertFalse(Mp4Merger.isAudioMimeSupported("audio/vorbis"));
        assertTrue(Mp4Merger.isWebmPairSupported("video/x-vnd.on2.vp9", "audio/opus"));
        assertTrue(Mp4Merger.isWebmPairSupported("video/x-vnd.on2.vp8", "audio/vorbis"));
        assertFalse(Mp4Merger.isWebmPairSupported("video/avc", "audio/opus"));
        assertFalse(Mp4Merger.isWebmPairSupported("video/x-vnd.on2.vp9", MediaFormat.MIMETYPE_AUDIO_AAC));

        assertTrue(VideoUniquifier.isVideoCodecRemuxSafe(MediaFormat.MIMETYPE_VIDEO_AVC, true, 8, false, 0));
        assertFalse(VideoUniquifier.isVideoCodecRemuxSafe(MediaFormat.MIMETYPE_VIDEO_AVC, true, 16, false, 0));
        assertTrue(VideoUniquifier.isVideoCodecRemuxSafe(MediaFormat.MIMETYPE_VIDEO_HEVC, true, 1, false, 0));
        assertFalse(VideoUniquifier.isVideoCodecRemuxSafe(MediaFormat.MIMETYPE_VIDEO_HEVC, true, 2, false, 0));
        assertFalse(VideoUniquifier.isVideoCodecRemuxSafe(MediaFormat.MIMETYPE_VIDEO_HEVC, true, 1, true, 6));
    }

    @Test
    public void notificationIdsNeverCollideWithCompletionIds() {
        int id = DowniDownloadService.jobNotificationId("drop_12345");
        assertTrue(id > 4811 && id < 5811);
        // Even hashCode == Integer.MIN_VALUE stays strictly inside [4812..5810]
        int edge = DowniDownloadService.jobNotificationId("polygenelubricants");
        assertTrue(edge > 4811 && edge < 5811);
        assertEquals(20_000L, JobSnapshot.TERMINAL_TTL_MS);
        assertEquals(8, JobSnapshot.MAX_ROWS);
    }

    @Test
    public void buildsSaveOutcomeAndParsesSafFolderNames() {
        MediaStoreSaver.SaveOutcome outcome = new MediaStoreSaver.SaveOutcome(
                "Custom folder (Reels)",
                "content://com.android.externalstorage.documents/tree/primary%3AMovies%2FReels/document/1",
                "video/mp4",
                0L,
                true
        );
        assertEquals("Custom folder (Reels)", outcome.destinationLabel);
        assertTrue(DowniNativeUtils.isSupportedMediaUri(outcome.mediaUri));
        assertEquals("video/mp4", outcome.mimeType);

        MediaStoreSaver.SaveOutcome fallback = new MediaStoreSaver.SaveOutcome(null, null, null, 0L, false);
        assertEquals("Gallery", fallback.destinationLabel);
        assertEquals("", fallback.mediaUri);
        assertEquals("video/mp4", fallback.mimeType);
        assertFalse(DowniNativeUtils.isSupportedMediaUri(fallback.mediaUri));
        assertFalse(DowniNativeUtils.isSupportedMediaUri("https://example.com/video.mp4"));
        assertTrue(DowniNativeUtils.isSupportedMediaUri("file:///sdcard/Movies/clip.mp4"));

        assertEquals("DOWNI", MediaStoreSaver.folderNameFromDocumentId("primary:Movies/DOWNI"));
        assertEquals("Reels", MediaStoreSaver.folderNameFromDocumentId("1234-5678:DCIM/Reels/"));
        assertEquals("Internal storage", MediaStoreSaver.folderNameFromDocumentId("primary:"));
        assertEquals("", MediaStoreSaver.folderNameFromDocumentId(""));
    }
}
