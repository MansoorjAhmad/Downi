package com.omnidownloader.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.omnidownloader.app.engine.DownloadCoordinator

/**
 * DowniDrop — invisible share receiver (taskAffinity="", noHistory, excluded from recents)
 * so the user never leaves the source app when sharing a link to DOWNI.
 */
class DropActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = DowniNativeUtils.extractAndCleanUrl(extractSharedText(intent))

        if (url.isNullOrBlank()) {
            Toast.makeText(this, "DowniDrop: no link found in that share", Toast.LENGTH_SHORT).show()
        } else if (DownloadCoordinator.isUrlActive(url)) {
            Toast.makeText(this, "⚡ DowniDrop: already grabbing this link", Toast.LENGTH_SHORT).show()
        } else {
            val platform = detectPlatform(url)
            val label = platformLabel(platform)
            val activeNow = DownloadCoordinator.activeCount.value
            try {
                DownloadCoordinator.enqueue(
                    context = applicationContext,
                    url = url,
                    platform = platform,
                    isDrop = true
                )
                val msg = if (activeNow >= DownloadCoordinator.MAX_ACTIVE) {
                    "⚡ DowniDrop: queued $label ($activeNow active)"
                } else {
                    "⚡ DowniDrop: grabbing $label…"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "DowniDrop couldn't start background grab.", Toast.LENGTH_LONG).show()
            }
        }
        finishAndRemoveTask()
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)?.toString()
            }
            else -> null
        }
    }

    private fun detectPlatform(url: String): String {
        val lower = url.lowercase()
        return when {
            "youtube.com" in lower || "youtu.be" in lower -> "youtube"
            "instagram.com" in lower -> "instagram"
            "tiktok.com" in lower -> "tiktok"
            "twitter.com" in lower || "x.com" in lower -> "twitter"
            "facebook.com" in lower || "fb.watch" in lower -> "facebook"
            else -> "other"
        }
    }

    private fun platformLabel(platform: String): String = when (platform) {
        "youtube" -> "YouTube"
        "instagram" -> "Instagram"
        "tiktok" -> "TikTok"
        "twitter" -> "X"
        "facebook" -> "Facebook"
        else -> "video"
    }
}
