package com.omnidownloader.app.engine

import android.content.Context
import android.util.Log
import com.omnidownloader.app.DowniNativeUtils
import com.omnidownloader.app.DownloadProgressListener
import com.omnidownloader.app.EngineError
import com.omnidownloader.app.IsoBmff
import com.omnidownloader.app.MediaStoreSaver
import com.omnidownloader.app.Mp4Merger
import com.omnidownloader.app.TitleSanitizer
import com.omnidownloader.app.data.DowniDatabase
import com.omnidownloader.app.data.DownloadJobEntity
import com.omnidownloader.app.data.JobStatus
import com.omnidownloader.app.data.SettingsRepository
import com.omnidownloader.app.service.DowniDownloadService
import com.omnidownloader.app.service.QueueRecoveryWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

object DownloadCoordinator {
    const val MAX_ACTIVE = 3
    private const val TAG = "DOWNI"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val concurrencySemaphore = Semaphore(MAX_ACTIVE)
    private val instagramMutex = Mutex()
    private val lastInstagramRequestMs = AtomicLong(0L)
    private val platformCooldownUntil = ConcurrentHashMap<String, Long>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val activeUrls = ConcurrentHashMap<String, String>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val initialized = AtomicBoolean(false)

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    fun isUrlActive(url: String): Boolean = activeUrls.containsValue(url.trim())

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        val dao = DowniDatabase.get(appCtx).downloadJobDao()
        scope.launch {
            dao.markInterruptedOnBoot()
            recoverQueuedJobs(appCtx)
        }
    }

    suspend fun recoverQueuedJobs(context: Context) {
        val appCtx = context.applicationContext
        val dao = DowniDatabase.get(appCtx).downloadJobDao()
        val queued = dao.getQueuedJobs()
        for (entity in queued) {
            if (!runningJobs.containsKey(entity.id)) {
                launchExistingEntity(appCtx, entity)
            }
        }
    }

    fun observeJobs(context: Context): Flow<List<DownloadJobEntity>> {
        init(context)
        return DowniDatabase.get(context).downloadJobDao().observeAll()
    }

    fun observeActiveJobs(context: Context): Flow<List<DownloadJobEntity>> {
        init(context)
        return DowniDatabase.get(context).downloadJobDao().observeActive()
    }

    fun enqueue(
        context: Context,
        url: String,
        formatId: String = "best",
        title: String = "",
        thumbnailUrl: String = "",
        platform: String = "other",
        subtitles: Boolean = false,
        subLang: String = "en",
        isDrop: Boolean = false
    ): String {
        val appCtx = context.applicationContext
        init(appCtx)
        val cleanUrl = url.trim()
        val prefix = if (isDrop) "drop_" else "job_"
        val jobId = DowniNativeUtils.nextJobId(prefix)
        val cancelFlag = AtomicBoolean(false)
        cancelFlags[jobId] = cancelFlag
        activeUrls[jobId] = cleanUrl

        val coroutineJob = scope.launch {
            val dao = DowniDatabase.get(appCtx).downloadJobDao()
            val settings = SettingsRepository(appCtx).current()
            val effectiveFormat = if (isDrop) settings.qualityForPlatform(platform) else formatId.ifBlank { "best" }
            val friendlyPlatform = platform.replaceFirstChar { it.uppercase() }.ifBlank { "Media" }
            val initialEntity = DownloadJobEntity(
                id = jobId,
                url = cleanUrl,
                title = title.ifBlank { if (isDrop) "Fetching $friendlyPlatform…" else cleanUrl },
                thumbnailUrl = thumbnailUrl,
                platform = platform,
                formatId = effectiveFormat,
                subtitles = subtitles,
                subLang = subLang,
                shieldTier = settings.shieldTier,
                makeUnique = settings.autoUnique,
                isDrop = isDrop,
                status = JobStatus.QUEUED,
                statusText = if (isDrop) "DowniDrop queued…" else "Queued"
            )
            dao.upsert(initialEntity)
            refreshActiveCount()
            QueueRecoveryWorker.schedule(appCtx)
            DowniDownloadService.ensureRunning(appCtx, jobId, initialEntity.title)

            try {
                awaitPlatformCooldownAndPace(appCtx, initialEntity, cancelFlag)
                concurrencySemaphore.withPermit {
                    if (cancelFlag.get()) throw CancellationException("Cancelled")
                    if (platform.equals("instagram", ignoreCase = true)) {
                        instagramMutex.withLock {
                            val elapsed = System.currentTimeMillis() - lastInstagramRequestMs.get()
                            val minGap = Random.nextLong(1500L, 2500L)
                            if (elapsed < minGap) delay(minGap - elapsed)
                            lastInstagramRequestMs.set(System.currentTimeMillis())
                            runPipeline(appCtx, initialEntity, cancelFlag)
                        }
                    } else {
                        runPipeline(appCtx, initialEntity, cancelFlag)
                    }
                }
            } catch (_: CancellationException) {
                markCancelled(appCtx, jobId, isDrop, initialEntity.title)
            } catch (t: Throwable) {
                val rawMsg = t.message ?: "Unknown download error"
                val cause = EngineError.cause(rawMsg)
                if (cause == "rate_limited") {
                    platformCooldownUntil[platform.lowercase()] = System.currentTimeMillis() + 60_000L
                }
                if (cancelFlag.get() || cause == "cancelled") {
                    markCancelled(appCtx, jobId, isDrop, initialEntity.title)
                } else {
                    val cleanRaw = EngineError.raw(rawMsg).ifBlank { rawMsg }
                    val existing = dao.getById(jobId) ?: initialEntity
                    dao.upsert(
                        existing.copy(
                            status = JobStatus.FAILED,
                            statusText = EngineError.phrase(cause),
                            errorCause = cause,
                            errorRaw = cleanRaw,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    if (isDrop) {
                        DowniDownloadService.postDropFailure(
                            context = appCtx,
                            title = existing.title,
                            cause = cause,
                            url = cleanUrl,
                            platform = platform
                        )
                    }
                }
            } finally {
                runningJobs.remove(jobId)
                activeUrls.remove(jobId)
                cancelFlags.remove(jobId)
                deleteTree(File(File(appCtx.cacheDir, "DowniEngine"), jobId))
                refreshActiveCount()
                if (runningJobs.isEmpty()) QueueRecoveryWorker.cancel(appCtx)
            }
        }

        runningJobs[jobId] = coroutineJob
        return jobId
    }

    private fun launchExistingEntity(appCtx: Context, entity: DownloadJobEntity) {
        val cancelFlag = AtomicBoolean(false)
        cancelFlags[entity.id] = cancelFlag
        activeUrls[entity.id] = entity.url
        val coroutineJob = scope.launch {
            val dao = DowniDatabase.get(appCtx).downloadJobDao()
            refreshActiveCount()
            DowniDownloadService.ensureRunning(appCtx, entity.id, entity.title)
            try {
                concurrencySemaphore.withPermit {
                    if (cancelFlag.get()) throw CancellationException("Cancelled")
                    runPipeline(appCtx, entity, cancelFlag)
                }
            } catch (_: CancellationException) {
                markCancelled(appCtx, entity.id, entity.isDrop, entity.title)
            } catch (t: Throwable) {
                val rawMsg = t.message ?: "Unknown download error"
                val cause = EngineError.cause(rawMsg)
                if (cancelFlag.get() || cause == "cancelled") {
                    markCancelled(appCtx, entity.id, entity.isDrop, entity.title)
                } else {
                    val cleanRaw = EngineError.raw(rawMsg).ifBlank { rawMsg }
                    val existing = dao.getById(entity.id) ?: entity
                    dao.upsert(
                        existing.copy(
                            status = JobStatus.FAILED,
                            statusText = EngineError.phrase(cause),
                            errorCause = cause,
                            errorRaw = cleanRaw,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            } finally {
                runningJobs.remove(entity.id)
                activeUrls.remove(entity.id)
                cancelFlags.remove(entity.id)
                deleteTree(File(File(appCtx.cacheDir, "DowniEngine"), entity.id))
                refreshActiveCount()
                if (runningJobs.isEmpty()) QueueRecoveryWorker.cancel(appCtx)
            }
        }
        runningJobs[entity.id] = coroutineJob
    }

    fun cancel(context: Context, jobId: String) {
        cancelFlags[jobId]?.set(true)
        runningJobs[jobId]?.cancel()
        scope.launch {
            val dao = DowniDatabase.get(context.applicationContext).downloadJobDao()
            val existing = dao.getById(jobId)
            if (existing != null && existing.status.isActive) {
                dao.upsert(
                    existing.copy(
                        status = JobStatus.CANCELLED,
                        statusText = "Cancelled",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            refreshActiveCount()
        }
    }

    fun cancelAll(context: Context) {
        cancelFlags.values.forEach { it.set(true) }
        runningJobs.values.forEach { it.cancel() }
        QueueRecoveryWorker.cancel(context.applicationContext)
        scope.launch {
            val dao = DowniDatabase.get(context.applicationContext).downloadJobDao()
            dao.markAllCancelled()
            refreshActiveCount()
        }
    }

    fun deleteHistoryItem(context: Context, jobId: String) {
        scope.launch {
            DowniDatabase.get(context.applicationContext).downloadJobDao().deleteById(jobId)
        }
    }

    fun clearHistory(context: Context) {
        scope.launch {
            DowniDatabase.get(context.applicationContext).downloadJobDao().clearTerminal()
        }
    }

    private suspend fun runPipeline(
        context: Context,
        initial: DownloadJobEntity,
        cancelFlag: AtomicBoolean
    ) {
        val dao = DowniDatabase.get(context).downloadJobDao()
        var current = initial.copy(
            status = JobStatus.RUNNING,
            progress = 1,
            statusText = "Starting engine…",
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(current)

        val workDir = File(File(context.cacheDir, "DowniEngine"), initial.id)
        var lastPersistMs = 0L

        val listener = object : DownloadProgressListener {
            override fun onProgress(
                percent: Double,
                downloadedBytes: Long,
                totalBytes: Long,
                speedBytesPerSec: Double,
                etaSeconds: Long
            ) {
                if (cancelFlag.get()) return
                val now = System.currentTimeMillis()
                if (now - lastPersistMs < 350L && percent < 95.0) return
                lastPersistMs = now
                current = current.copy(
                    status = JobStatus.RUNNING,
                    progress = percent.toInt().coerceIn(0, 95),
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    speedBps = speedBytesPerSec,
                    etaSec = etaSeconds,
                    statusText = "Downloading…",
                    updatedAt = now
                )
                scope.launch { dao.upsert(current) }
            }

            override fun isCancelled(): Boolean = cancelFlag.get()
        }

        val opts = JSONObject().apply {
            put("subtitles", initial.subtitles)
            if (initial.subLang.isNotBlank()) put("sub_lang", initial.subLang)
        }.toString()

        val result = PythonEngine.downloadBlocking(
            context = context,
            url = initial.url,
            workDir = workDir,
            formatId = initial.formatId,
            optsJson = opts,
            listener = listener
        )
        if (cancelFlag.get()) throw CancellationException("Cancelled")

        val title = result.title.ifBlank { initial.title }
        val thumb = result.thumbnailUrl.ifBlank { initial.thumbnailUrl }
        current = current.copy(title = title, thumbnailUrl = thumb)

        val toSave: File
        val ext: String
        val shieldOutcome: MediaStoreSaver.ShieldOutcome

        if (result.merge) {
            current = current.copy(
                status = JobStatus.MERGING,
                progress = 96,
                statusText = "Merging video + audio…",
                updatedAt = System.currentTimeMillis()
            )
            dao.upsert(current)

            val videoPart = File(result.videoPath)
            val audioPart = File(result.audioPath)
            val merged = File(videoPart.parentFile, "${TitleSanitizer.sanitize(title)}-merged.mp4")
            val mergedOk = Mp4Merger.merge(videoPart, audioPart, merged) { cancelFlag.get() }
            runCatching { videoPart.delete() }
            runCatching { audioPart.delete() }

            if (cancelFlag.get()) {
                runCatching { merged.delete() }
                throw CancellationException("Cancelled")
            }
            if (!mergedOk) {
                throw IllegalStateException("[cause:format] Could not merge video and audio on this device. Try 1080p Full HD or 720p HD.")
            }

            val mergedExt = if (IsoBmff.isWebm(merged)) "webm" else "mp4"
            if (initial.makeUnique) {
                current = current.copy(
                    status = JobStatus.SHIELDING,
                    progress = 97,
                    statusText = "Applying Anti-Repost Shield…",
                    updatedAt = System.currentTimeMillis()
                )
                dao.upsert(current)
            }
            shieldOutcome = MediaStoreSaver.applyShieldIfEnabled(
                merged, title, mergedExt, initial.makeUnique, initial.formatId, initial.shieldTier
            ) { cancelFlag.get() }
            toSave = shieldOutcome.fileToSave
            ext = shieldOutcome.ext
        } else {
            val single = File(result.singlePath)
            val isAudio = MediaStoreSaver.isAudioExtension(result.ext) ||
                initial.formatId.equals("audio", ignoreCase = true) ||
                initial.formatId.equals("audio_opus", ignoreCase = true)
            val isImage = result.ext.lowercase() in setOf("jpg", "jpeg", "png", "webp")

            if (initial.makeUnique && !isAudio && !isImage) {
                current = current.copy(
                    status = JobStatus.SHIELDING,
                    progress = 97,
                    statusText = "Applying Anti-Repost Shield…",
                    updatedAt = System.currentTimeMillis()
                )
                dao.upsert(current)
            }
            shieldOutcome = if (isImage) {
                MediaStoreSaver.ShieldOutcome(single, result.ext, null, false)
            } else {
                MediaStoreSaver.applyShieldIfEnabled(
                    single, title, result.ext, initial.makeUnique, initial.formatId, initial.shieldTier
                ) { cancelFlag.get() }
            }
            toSave = shieldOutcome.fileToSave
            ext = shieldOutcome.ext
        }

        if (cancelFlag.get()) throw CancellationException("Cancelled")

        current = current.copy(
            status = JobStatus.SAVING,
            progress = 98,
            statusText = "Saving to gallery…",
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(current)

        val finalBytes = toSave.length()
        val saveOutcome = MediaStoreSaver.saveToGallery(context, toSave, title, ext)

        // Save additional carousel slides if present
        var totalSavedBytes = finalBytes
        for ((idx, extraPath) in result.extraPaths.withIndex()) {
            val extraFile = File(extraPath)
            if (!extraFile.exists() || extraFile.length() == 0L) continue
            totalSavedBytes += extraFile.length()
            val extraExt = extraFile.extension.ifBlank { ext }
            runCatching {
                MediaStoreSaver.saveToGallery(context, extraFile, "$title (${idx + 2})", extraExt)
            }
        }

        // Save subtitles if present
        for (subPath in result.subtitlePaths) {
            val subFile = File(subPath)
            if (!subFile.exists() || subFile.length() == 0L) continue
            val subName = subFile.name
            val dot = subName.lastIndexOf('.')
            val subExt = if (dot >= 0 && dot < subName.length - 1) subName.substring(dot + 1) else "vtt"
            runCatching { MediaStoreSaver.saveToGallery(context, subFile, title, subExt) }
        }

        val uRes = shieldOutcome.result
        val itemCount = 1 + result.extraPaths.size
        val statusLabel = if (itemCount > 1) {
            "Saved $itemCount items to ${saveOutcome.destinationLabel}"
        } else {
            "Saved to ${saveOutcome.destinationLabel}"
        }
        val completed = current.copy(
            status = JobStatus.COMPLETED,
            progress = 100,
            statusText = statusLabel,
            destinationLabel = saveOutcome.destinationLabel,
            mediaUri = saveOutcome.mediaUri,
            mimeType = saveOutcome.mimeType,
            fileBytes = totalSavedBytes,
            shieldMode = uRes?.mode ?: "",
            shieldReason = uRes?.reason ?: "",
            shieldInputSha = uRes?.inputSha256 ?: "",
            shieldOutputSha = uRes?.outputSha256 ?: "",
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(completed)
        Log.i(TAG, "Saved ${initial.id} -> ${saveOutcome.destinationLabel}")

        if (initial.isDrop) {
            DowniDownloadService.postDropCompletion(
                context = context,
                title = title,
                saveOutcome = saveOutcome,
                fileBytes = finalBytes,
                shieldMode = uRes?.mode ?: ""
            )
        }
    }

    private suspend fun markCancelled(context: Context, jobId: String, isDrop: Boolean, title: String) {
        val dao = DowniDatabase.get(context).downloadJobDao()
        val existing = dao.getById(jobId)
        if (existing != null) {
            dao.upsert(
                existing.copy(
                    status = JobStatus.CANCELLED,
                    statusText = "Cancelled",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        if (isDrop) {
            DowniDownloadService.postDropCancelled(context, title)
        }
    }

    private suspend fun awaitPlatformCooldownAndPace(
        context: Context,
        entity: DownloadJobEntity,
        cancelFlag: AtomicBoolean
    ) {
        val key = entity.platform.lowercase()
        val dao = DowniDatabase.get(context).downloadJobDao()
        while (!cancelFlag.get()) {
            val until = platformCooldownUntil[key] ?: 0L
            val remaining = until - System.currentTimeMillis()
            if (remaining <= 0L) break
            val sec = ((remaining + 999L) / 1000L).coerceAtLeast(1L)
            val existing = dao.getById(entity.id) ?: entity
            dao.upsert(
                existing.copy(
                    status = JobStatus.QUEUED,
                    statusText = "Cooling down (${sec}s) — toggle Wi-Fi/Data to reset IP",
                    updatedAt = System.currentTimeMillis()
                )
            )
            delay(minOf(remaining, 1000L))
        }
    }

    private fun refreshActiveCount() {
        _activeCount.value = runningJobs.size
    }

    private fun deleteTree(file: File) {
        try {
            if (!file.exists()) return
            if (file.isDirectory) {
                file.listFiles()?.forEach { deleteTree(it) }
            }
            file.delete()
        } catch (_: Exception) {
        }
    }
}
