package com.omnidownloader.app.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.omnidownloader.app.DowniNativeUtils
import com.omnidownloader.app.MediaStoreSaver
import com.omnidownloader.app.data.DowniDatabase
import com.omnidownloader.app.data.DownloadJobEntity
import com.omnidownloader.app.data.JobStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

object VaultProcessor {

    suspend fun rerunShield(
        context: Context,
        source: DownloadJobEntity,
        tier: String
    ): Result<DownloadJobEntity> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val workDir = File(File(appCtx.cacheDir, "DowniVault"), DowniNativeUtils.nextJobId("shield_"))
        workDir.mkdirs()
        try {
            val normalizedTier = DowniNativeUtils.normalizeShieldTier(tier)
            val ext = extFromMime(source.mimeType)
            val inputFile = File(workDir, "input.$ext")
            copyUriToFile(appCtx, Uri.parse(source.mediaUri), inputFile)

            val outcome = MediaStoreSaver.applyShieldIfEnabled(
                inputFile,
                source.title.ifBlank { "Video" },
                ext,
                true,
                source.formatId.ifBlank { "best" },
                normalizedTier
            ) { false }

            val outBytes = outcome.fileToSave.length()
            val labelSuffix = if (normalizedTier == "deep") "Deep" else "Shield"
            val newTitle = "${source.title.ifBlank { "Video" }} ($labelSuffix)"
            val saveOutcome = MediaStoreSaver.saveToGallery(appCtx, outcome.fileToSave, newTitle, outcome.ext)

            val newJob = DownloadJobEntity(
                id = DowniNativeUtils.nextJobId("vault_"),
                url = source.url,
                title = newTitle,
                thumbnailUrl = source.thumbnailUrl,
                platform = source.platform,
                formatId = source.formatId,
                shieldTier = normalizedTier,
                makeUnique = true,
                status = JobStatus.COMPLETED,
                progress = 100,
                statusText = "Saved to ${saveOutcome.destinationLabel}",
                destinationLabel = saveOutcome.destinationLabel,
                mediaUri = saveOutcome.mediaUri,
                mimeType = saveOutcome.mimeType,
                fileBytes = outBytes,
                shieldMode = outcome.result?.mode ?: "",
                shieldReason = outcome.result?.reason ?: "",
                shieldInputSha = outcome.result?.inputSha256 ?: "",
                shieldOutputSha = outcome.result?.outputSha256 ?: ""
            )
            DowniDatabase.get(appCtx).downloadJobDao().upsert(newJob)
            Result.success(newJob)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            workDir.deleteRecursively()
        }
    }

    suspend fun extractAudioM4a(
        context: Context,
        source: DownloadJobEntity
    ): Result<DownloadJobEntity> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val workDir = File(File(appCtx.cacheDir, "DowniVault"), DowniNativeUtils.nextJobId("audio_"))
        workDir.mkdirs()
        try {
            val ext = extFromMime(source.mimeType)
            val inputFile = File(workDir, "input.$ext")
            copyUriToFile(appCtx, Uri.parse(source.mediaUri), inputFile)

            val outM4a = File(workDir, "audio.m4a")
            if (!remuxAudioTrackToM4a(inputFile, outM4a)) {
                return@withContext Result.failure(IllegalStateException("No AAC/MP4-compatible audio track found in this video."))
            }

            val audioBytes = outM4a.length()
            val newTitle = "${source.title.ifBlank { "Audio" }} (Audio)"
            val saveOutcome = MediaStoreSaver.saveToGallery(appCtx, outM4a, newTitle, "m4a")

            val newJob = DownloadJobEntity(
                id = DowniNativeUtils.nextJobId("audio_"),
                url = source.url,
                title = newTitle,
                thumbnailUrl = source.thumbnailUrl,
                platform = source.platform,
                formatId = "audio",
                makeUnique = false,
                status = JobStatus.COMPLETED,
                progress = 100,
                statusText = "Saved to ${saveOutcome.destinationLabel}",
                destinationLabel = saveOutcome.destinationLabel,
                mediaUri = saveOutcome.mediaUri,
                mimeType = saveOutcome.mimeType,
                fileBytes = audioBytes
            )
            DowniDatabase.get(appCtx).downloadJobDao().upsert(newJob)
            Result.success(newJob)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun remuxAudioTrackToM4a(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        return try {
            extractor.setDataSource(input.absolutePath)
            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrack < 0 || audioFormat == null) return false

            extractor.selectTrack(audioTrack)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(audioFormat)
            muxer.start()
            started = true

            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            started = false
            output.exists() && output.length() > 0L
        } catch (_: Exception) {
            false
        } finally {
            runCatching {
                if (muxer != null) {
                    if (started) muxer.stop()
                    muxer.release()
                }
            }
            runCatching { extractor.release() }
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        } ?: error("Could not read saved file")
    }

    private fun extFromMime(mime: String): String = when {
        mime.contains("webm", ignoreCase = true) -> "webm"
        mime.contains("matroska", ignoreCase = true) -> "mkv"
        mime.contains("mp4", ignoreCase = true) -> "mp4"
        else -> "mp4"
    }
}
