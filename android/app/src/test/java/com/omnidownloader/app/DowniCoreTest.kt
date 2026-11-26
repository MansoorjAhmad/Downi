package com.omnidownloader.app

import com.omnidownloader.app.data.AppSettings
import com.omnidownloader.app.data.JobStatus
import com.omnidownloader.app.ui.DowniViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DowniCoreTest {

    @Test
    fun appSettingsPlatformQualityFallsBackToOneTapQuality() {
        val settings = AppSettings(
            oneTapQuality = "1080",
            platformQualityMap = mapOf("youtube" to "720", "instagram" to "best")
        )
        assertEquals("720", settings.qualityForPlatform("youtube"))
        assertEquals("best", settings.qualityForPlatform("INSTAGRAM"))
        assertEquals("1080", settings.qualityForPlatform("tiktok"))
    }

    @Test
    fun jobStatusTerminalAndActiveFlagsAreMutuallyExclusive() {
        JobStatus.entries.forEach { status ->
            assertEquals(!status.isTerminal, status.isActive)
        }
        assertTrue(JobStatus.COMPLETED.isTerminal)
        assertTrue(JobStatus.FAILED.isTerminal)
        assertTrue(JobStatus.CANCELLED.isTerminal)
        assertFalse(JobStatus.RUNNING.isTerminal)
        assertTrue(JobStatus.SHIELDING.isActive)
    }

    @Test
    fun extractUrlsDetectsInstagramAndBatchLinks() {
        val single = DowniViewModel.extractUrls("https://www.instagram.com/reel/DgKyWkpzgJh/?fqnt=eGFwZnVxMTd4bWVq")
        assertEquals(listOf("https://www.instagram.com/reel/DgKyWkpzgJh/?fqnt=eGFwZnVxMTd4bWVq"), single)

        val multi = DowniViewModel.extractUrls("https://youtu.be/abc?si=123\nhttps://www.tiktok.com/@a/video/1")
        assertEquals(listOf("https://youtu.be/abc", "https://www.tiktok.com/@a/video/1"), multi)
    }

    @Test
    fun playlistUrlDetectionDistinguishesPlaylistsFromSingleVideos() {
        assertTrue(DowniViewModel.isLikelyPlaylistUrl("https://www.youtube.com/playlist?list=PL12345"))
        assertTrue(DowniViewModel.isLikelyPlaylistUrl("https://soundcloud.com/user/sets/mix"))
        assertFalse(DowniViewModel.isLikelyPlaylistUrl("https://www.youtube.com/watch?v=abc&list=PL12345"))
        assertFalse(DowniViewModel.isLikelyPlaylistUrl("https://www.instagram.com/reel/DgKyWkpzgJh/"))
    }

    @Test
    fun mediaStoreSaverMapsCarouselImageExtensions() {
        assertEquals("image/jpeg", MediaStoreSaver.mimeForExtension("jpg"))
        assertEquals("image/png", MediaStoreSaver.mimeForExtension("png"))
        assertEquals("image/webp", MediaStoreSaver.mimeForExtension("webp"))
    }
}
