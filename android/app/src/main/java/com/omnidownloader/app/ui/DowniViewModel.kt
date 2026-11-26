package com.omnidownloader.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.withTimeout
import com.omnidownloader.app.DowniNativeUtils
import com.omnidownloader.app.EngineError
import com.omnidownloader.app.MediaStoreSaver
import com.omnidownloader.app.data.AppSettings
import com.omnidownloader.app.data.DownloadJobEntity
import com.omnidownloader.app.data.SettingsRepository
import com.omnidownloader.app.engine.DiagCheck
import com.omnidownloader.app.engine.DownloadCoordinator
import com.omnidownloader.app.engine.EngineInfo
import com.omnidownloader.app.engine.InspectResult
import com.omnidownloader.app.engine.PlaylistInspectResult
import com.omnidownloader.app.engine.PythonEngine
import com.omnidownloader.app.engine.VaultProcessor
import com.omnidownloader.app.updater.AppUpdater
import com.omnidownloader.app.updater.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface InspectUiState {
    data object Idle : InspectUiState
    data class Loading(val url: String) : InspectUiState
    data class Ready(val result: InspectResult) : InspectUiState
    data class PlaylistReady(val result: PlaylistInspectResult, val sourceUrl: String) : InspectUiState
    data class Error(val cause: String, val raw: String) : InspectUiState
}

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Downloading(val percent: Int) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

class DowniViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication<Application>().applicationContext
    private val settingsRepo = SettingsRepository(ctx)

    val jobs: StateFlow<List<DownloadJobEntity>> =
        DownloadCoordinator.observeJobs(ctx)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    val detectedUrls: StateFlow<List<String>> = _urlInput
        .map { extractUrls(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _clipboardUrl = MutableStateFlow<String?>(null)
    val clipboardUrl: StateFlow<String?> = _clipboardUrl.asStateFlow()

    private var dismissedClipboardUrl: String? = null

    private val _inspectState = MutableStateFlow<InspectUiState>(InspectUiState.Idle)
    val inspectState: StateFlow<InspectUiState> = _inspectState.asStateFlow()

    private val _engineInfo = MutableStateFlow<EngineInfo?>(null)
    val engineInfo: StateFlow<EngineInfo?> = _engineInfo.asStateFlow()

    private val _diagChecks = MutableStateFlow<List<DiagCheck>>(emptyList())
    val diagChecks: StateFlow<List<DiagCheck>> = _diagChecks.asStateFlow()

    private val _diagRunning = MutableStateFlow(false)
    val diagRunning: StateFlow<Boolean> = _diagRunning.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val inspectCache = mutableMapOf<String, Pair<Long, InspectResult>>()
    private val inspectTtlMs = 5 * 60 * 1000L
    private val inspectTimeoutMs = 90_000L

    private val _loadingMorePlaylist = MutableStateFlow(false)
    val loadingMorePlaylist: StateFlow<Boolean> = _loadingMorePlaylist.asStateFlow()

    private val _vaultActionBusy = MutableStateFlow(false)
    val vaultActionBusy: StateFlow<Boolean> = _vaultActionBusy.asStateFlow()

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        refreshClipboard()
    }

    init {
        DownloadCoordinator.init(ctx)
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.addPrimaryClipChangedListener(clipListener)
        }
        viewModelScope.launch {
            runCatching { PythonEngine.engineInfo(ctx) }
                .onSuccess { _engineInfo.value = it }
        }
    }

    override fun onCleared() {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.removePrimaryClipChangedListener(clipListener)
        }
        super.onCleared()
    }

    fun updateUrlInput(value: String) {
        _urlInput.value = value
    }

    fun clearUrlInput() {
        _urlInput.value = ""
    }

    companion object {
        private val URL_REGEX = Regex("""https?://\S+""")

        fun extractUrls(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            return URL_REGEX.findAll(text)
                .mapNotNull { DowniNativeUtils.extractAndCleanUrl(it.value) }
                .distinct()
                .toList()
        }

        fun isLikelyPlaylistUrl(url: String): Boolean {
            val lower = url.lowercase()
            return "/playlist" in lower ||
                "/sets/" in lower ||
                ("list=" in lower && "watch?v=" !in lower && "youtu.be/" !in lower)
        }
    }

    fun detectPlatform(url: String): String {
        val lower = url.lowercase()
        return when {
            "youtube.com" in lower || "youtu.be" in lower -> "YouTube"
            "instagram.com" in lower -> "Instagram"
            "tiktok.com" in lower -> "TikTok"
            "twitter.com" in lower || "x.com" in lower -> "X / Twitter"
            "facebook.com" in lower || "fb.watch" in lower -> "Facebook"
            else -> "Web"
        }
    }

    fun refreshClipboard() {
        if (!settings.value.clipboardDetect) {
            _clipboardUrl.value = null
            return
        }
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip ?: run {
                _clipboardUrl.value = null
                return
            }
            if (clip.itemCount == 0) {
                _clipboardUrl.value = null
                return
            }
            val text = clip.getItemAt(0)?.coerceToText(ctx)?.toString()
            val cleaned = DowniNativeUtils.extractAndCleanUrl(text)
            _clipboardUrl.value = cleaned?.takeIf {
                it != _urlInput.value.trim() && it != dismissedClipboardUrl
            }
        } catch (_: Exception) {
        }
    }

    fun dismissClipboard() {
        dismissedClipboardUrl = _clipboardUrl.value
        _clipboardUrl.value = null
    }

    fun pasteFromClipboard() {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            if (text.isNotBlank()) {
                val cleaned = DowniNativeUtils.extractAndCleanUrl(text) ?: text.trim()
                _urlInput.value = cleaned
                _clipboardUrl.value = null
            }
        } catch (_: Exception) {
        }
    }

    fun inspectUrl(targetUrl: String = _urlInput.value.trim()) {
        val clean = DowniNativeUtils.extractAndCleanUrl(targetUrl) ?: return
        val likelyPlaylist = isLikelyPlaylistUrl(clean)

        if (!likelyPlaylist) {
            val cached = inspectCache[clean]
            if (cached != null && (System.currentTimeMillis() - cached.first) < inspectTtlMs) {
                _inspectState.value = InspectUiState.Ready(cached.second)
                return
            }
        }
        _inspectState.value = InspectUiState.Loading(clean)
        viewModelScope.launch {
            try {
                if (likelyPlaylist) {
                    val pl = withTimeout(inspectTimeoutMs) {
                        PythonEngine.playlistInspect(ctx, clean, 0, 50)
                    }
                    if (pl.items.isNotEmpty()) {
                        _inspectState.value = InspectUiState.PlaylistReady(pl, clean)
                        return@launch
                    }
                }
                val res = withTimeout(inspectTimeoutMs) {
                    PythonEngine.inspect(ctx, clean)
                }
                if (res.isPlaylist) {
                    val pl = withTimeout(inspectTimeoutMs) {
                        PythonEngine.playlistInspect(ctx, clean, 0, 50)
                    }
                    _inspectState.value = InspectUiState.PlaylistReady(pl, clean)
                } else {
                    inspectCache[clean] = System.currentTimeMillis() to res
                    _inspectState.value = InspectUiState.Ready(res)
                }
            } catch (t: Throwable) {
                val msg = t.message ?: "Inspection failed"
                _inspectState.value = InspectUiState.Error(
                    cause = EngineError.cause(msg),
                    raw = EngineError.raw(msg).ifBlank { msg }
                )
            }
        }
    }

    fun loadMorePlaylist() {
        val current = _inspectState.value as? InspectUiState.PlaylistReady ?: return
        if (!current.result.hasMore || _loadingMorePlaylist.value) return
        _loadingMorePlaylist.value = true
        viewModelScope.launch {
            runCatching {
                withTimeout(inspectTimeoutMs) {
                    PythonEngine.playlistInspect(ctx, current.sourceUrl, current.result.nextOffset, 50)
                }
            }.onSuccess { next ->
                val merged = current.result.copy(
                    items = (current.result.items + next.items).distinctBy { it.url },
                    hasMore = next.hasMore,
                    nextOffset = next.nextOffset
                )
                _inspectState.value = InspectUiState.PlaylistReady(merged, current.sourceUrl)
            }
            _loadingMorePlaylist.value = false
        }
    }

    fun copyText(label: String, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    fun dismissInspector() {
        _inspectState.value = InspectUiState.Idle
    }

    fun grabQuick(url: String = _urlInput.value.trim()) {
        val clean = DowniNativeUtils.extractAndCleanUrl(url) ?: return
        val platform = detectPlatform(clean).lowercase()
        val quality = settings.value.qualityForPlatform(platform)
        DownloadCoordinator.enqueue(
            context = ctx,
            url = clean,
            formatId = quality,
            platform = platform
        )
        _clipboardUrl.value = null
        Toast.makeText(ctx, "Added to Queue", Toast.LENGTH_SHORT).show()
    }

    fun grabFromInspector(
        url: String,
        title: String,
        thumbnailUrl: String,
        platform: String,
        formatId: String,
        subtitles: Boolean,
        subLang: String
    ) {
        viewModelScope.launch {
            settingsRepo.rememberPlatformQuality(platform, formatId)
        }
        DownloadCoordinator.enqueue(
            context = ctx,
            url = url,
            formatId = formatId,
            title = title,
            thumbnailUrl = thumbnailUrl,
            platform = platform,
            subtitles = subtitles,
            subLang = subLang
        )
        _inspectState.value = InspectUiState.Idle
        Toast.makeText(ctx, "Grab started", Toast.LENGTH_SHORT).show()
    }

    fun grabBatch(urls: List<String>, formatId: String = settings.value.oneTapQuality) {
        urls.forEach { url ->
            DownloadCoordinator.enqueue(
                context = ctx,
                url = url,
                formatId = formatId,
                platform = detectPlatform(url).lowercase()
            )
        }
        _inspectState.value = InspectUiState.Idle
        Toast.makeText(ctx, "Queued ${urls.size} downloads", Toast.LENGTH_SHORT).show()
    }

    fun cancelJob(jobId: String) = DownloadCoordinator.cancel(ctx, jobId)

    fun cancelAll() = DownloadCoordinator.cancelAll(ctx)

    fun deleteHistoryItem(jobId: String) = DownloadCoordinator.deleteHistoryItem(ctx, jobId)

    fun clearHistory() = DownloadCoordinator.clearHistory(ctx)

    fun rerunVaultShield(item: DownloadJobEntity, tier: String, onDone: (String) -> Unit) {
        if (_vaultActionBusy.value) return
        _vaultActionBusy.value = true
        viewModelScope.launch {
            val res = VaultProcessor.rerunShield(ctx, item, tier)
            _vaultActionBusy.value = false
            res.onSuccess { saved ->
                val mode = saved.shieldMode.ifBlank { tier }
                onDone("Saved ${tier.replaceFirstChar { it.uppercase() }} Shield copy ($mode)")
            }.onFailure {
                onDone(it.message ?: "Shield re-run failed")
            }
        }
    }

    fun extractVaultAudio(item: DownloadJobEntity, onDone: (String) -> Unit) {
        if (_vaultActionBusy.value) return
        _vaultActionBusy.value = true
        viewModelScope.launch {
            val res = VaultProcessor.extractAudioM4a(ctx, item)
            _vaultActionBusy.value = false
            res.onSuccess {
                onDone("Extracted M4A audio to Gallery / Music")
            }.onFailure {
                onDone(it.message ?: "Audio extraction failed")
            }
        }
    }

    fun openSavedMedia(uriString: String, mimeType: String) {
        if (!DowniNativeUtils.isSupportedMediaUri(uriString)) return
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uriString), mimeType.ifBlank { "video/mp4" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(ctx, "No app found to open this file.", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareSavedMedia(uriString: String, mimeType: String) {
        if (!DowniNativeUtils.isSupportedMediaUri(uriString)) return
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "video/mp4" }
                putExtra(Intent.EXTRA_STREAM, Uri.parse(uriString))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share media").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(ctx, "Could not share this file.", Toast.LENGTH_SHORT).show()
        }
    }

    fun setAutoUnique(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoUnique(enabled) }
    }

    fun setShieldTier(tier: String) {
        viewModelScope.launch { settingsRepo.setShieldTier(tier) }
    }

    fun setClipboardDetect(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setClipboardDetect(enabled)
            if (!enabled) _clipboardUrl.value = null
        }
    }

    fun setOneTapQuality(quality: String) {
        viewModelScope.launch { settingsRepo.setOneTapQuality(quality) }
    }

    fun setSaveTreeUri(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val label = MediaStoreSaver.getSafFolderDisplayName(ctx, uri.toString())
                .ifBlank { "Custom folder" }
            settingsRepo.setSaveFolder(uri.toString(), label)
        }
    }

    fun clearSaveFolder() {
        viewModelScope.launch { settingsRepo.clearSaveFolder() }
    }

    fun clearCache() {
        viewModelScope.launch {
            inspectCache.clear()
            PythonEngine.clearCaches()
            val freed = withContext(Dispatchers.IO) {
                deleteDir(File(ctx.cacheDir, "DowniEngine"))
            }
            Toast.makeText(ctx, "Cleared ${DowniNativeUtils.formatBytes(freed)}", Toast.LENGTH_SHORT).show()
        }
    }

    fun runDiagnostics() {
        if (_diagRunning.value) return
        _diagRunning.value = true
        viewModelScope.launch {
            runCatching { PythonEngine.diagnose(ctx) }
                .onSuccess { _diagChecks.value = it }
                .onFailure {
                    _diagChecks.value = listOf(DiagCheck("Engine", false, it.message ?: "Failed"))
                }
            _diagRunning.value = false
        }
    }

    fun checkUpdates() {
        _updateState.value = UpdateUiState.Checking
        viewModelScope.launch {
            runCatching { AppUpdater.checkLatest() }
                .onSuccess { info ->
                    _updateState.value = when {
                        info == null -> UpdateUiState.Error("Could not read GitHub release.")
                        info.isNewer -> UpdateUiState.Available(info)
                        else -> UpdateUiState.UpToDate
                    }
                }
                .onFailure {
                    _updateState.value = UpdateUiState.Error(it.message ?: "Update check failed")
                }
        }
    }

    fun downloadUpdate(apkUrl: String) {
        _updateState.value = UpdateUiState.Downloading(0)
        viewModelScope.launch {
            runCatching {
                AppUpdater.downloadAndInstall(ctx, apkUrl) { pct ->
                    _updateState.value = UpdateUiState.Downloading(pct)
                }
            }.onSuccess { ok ->
                _updateState.value = if (ok) UpdateUiState.Idle else UpdateUiState.Error("Update download failed")
            }.onFailure {
                _updateState.value = UpdateUiState.Error(it.message ?: "Update failed")
            }
        }
    }

    private fun deleteDir(file: File): Long {
        if (!file.exists()) return 0L
        var bytes = 0L
        if (file.isDirectory) {
            file.listFiles()?.forEach { bytes += deleteDir(it) }
        } else {
            bytes += file.length()
        }
        file.delete()
        return bytes
    }
}
