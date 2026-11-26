package com.omnidownloader.app.engine

import android.content.Context
import android.os.Build
import com.chaquo.python.Python
import com.omnidownloader.app.DowniNativeUtils
import com.omnidownloader.app.DownloadProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FormatOption(
    val formatId: String,
    val label: String,
    val detail: String,
    val sizeText: String,
    val isAudio: Boolean = false
)

data class InspectResult(
    val url: String,
    val title: String,
    val uploader: String,
    val platform: String,
    val thumbnail: String,
    val durationSec: Int,
    val isPlaylist: Boolean,
    val formats: List<FormatOption>,
    val subtitleLangs: List<String>
)

data class PlaylistEntry(
    val url: String,
    val title: String,
    val durationSec: Int,
    val thumbnail: String
)

data class PlaylistInspectResult(
    val title: String,
    val items: List<PlaylistEntry>,
    val totalCount: Int,
    val hasMore: Boolean,
    val nextOffset: Int
)

data class EngineInfo(
    val ytdlpVersion: String,
    val pythonVersion: String
)

data class DiagCheck(
    val name: String,
    val ok: Boolean,
    val detail: String
)

data class PythonDownloadResult(
    val title: String,
    val merge: Boolean,
    val videoPath: String,
    val audioPath: String,
    val singlePath: String,
    val ext: String,
    val thumbnailUrl: String,
    val subtitlePaths: List<String>,
    val extraPaths: List<String> = emptyList()
)

object PythonEngine {

    private fun ensureStarted(context: Context) {
        DowniNativeUtils.ensurePythonStarted(context.applicationContext)
    }

    suspend fun inspect(context: Context, url: String): InspectResult = withContext(Dispatchers.IO) {
        ensureStarted(context)
        val raw = Python.getInstance().getModule("downloader")
            .callAttr("inspect", url, Build.VERSION.SDK_INT)
            .toString()
        val obj = JSONObject(raw)
        val formatsArr = obj.optJSONArray("formats") ?: JSONArray()
        val formats = buildList {
            for (i in 0 until formatsArr.length()) {
                val f = formatsArr.optJSONObject(i) ?: continue
                val fid = f.optString("id", f.optString("format_id", ""))
                if (fid.isBlank()) continue
                val ext = f.optString("ext", "").uppercase()
                val badge = f.optString("badge", "")
                val detail = f.optString("detail", "").ifBlank {
                    listOf(badge, ext).filter { it.isNotBlank() }.joinToString(" · ")
                }
                val filesize = f.optLong("filesize", 0L)
                val sizeText = f.optString("size_text", f.optString("sizeText", "")).ifBlank {
                    if (filesize > 0L) DowniNativeUtils.formatBytes(filesize) else ""
                }
                add(
                    FormatOption(
                        formatId = fid,
                        label = f.optString("label", fid),
                        detail = detail,
                        sizeText = sizeText,
                        isAudio = fid.startsWith("audio")
                    )
                )
            }
        }
        val subsArr = obj.optJSONArray("subtitle_langs") ?: JSONArray()
        val subs = buildList {
            for (i in 0 until subsArr.length()) {
                val lang = subsArr.optString(i, "")
                if (lang.isNotBlank()) add(lang)
            }
        }
        InspectResult(
            url = obj.optString("webpage_url", obj.optString("url", url)),
            title = obj.optString("title", "Video"),
            uploader = obj.optString("uploader", ""),
            platform = obj.optString("platform", "other"),
            thumbnail = obj.optString("thumbnail", ""),
            durationSec = obj.optInt("duration", 0),
            isPlaylist = obj.optBoolean("is_playlist", false),
            formats = formats,
            subtitleLangs = subs
        )
    }

    suspend fun playlistInspect(
        context: Context,
        url: String,
        offset: Int = 0,
        limit: Int = 50
    ): PlaylistInspectResult = withContext(Dispatchers.IO) {
        ensureStarted(context)
        val pyObj = Python.getInstance().getModule("downloader")
            .callAttr("playlist_inspect", url, offset, limit)
        val raw = Python.getInstance().getModule("json")
            .callAttr("dumps", pyObj)
            .toString()
        if (raw == "null" || raw.isBlank()) {
            return@withContext PlaylistInspectResult("Playlist", emptyList(), 0, false, 0)
        }
        val obj = JSONObject(raw)
        val arr = obj.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val itemUrl = item.optString("url", "")
                if (itemUrl.isBlank()) continue
                add(
                    PlaylistEntry(
                        url = itemUrl,
                        title = item.optString("title", "Video"),
                        durationSec = item.optInt("duration", 0),
                        thumbnail = item.optString("thumbnail", "")
                    )
                )
            }
        }
        PlaylistInspectResult(
            title = obj.optString("title", "Playlist"),
            items = items,
            totalCount = obj.optInt("total_count", items.size),
            hasMore = obj.optBoolean("has_more", false),
            nextOffset = obj.optInt("next_offset", offset + items.size)
        )
    }

    suspend fun engineInfo(context: Context): EngineInfo = withContext(Dispatchers.IO) {
        ensureStarted(context)
        val raw = Python.getInstance().getModule("downloader")
            .callAttr("engine_info")
            .toString()
        val obj = JSONObject(raw)
        EngineInfo(
            ytdlpVersion = obj.optString("yt_dlp", obj.optString("version", "unknown")),
            pythonVersion = obj.optString("python", "3.11")
        )
    }

    suspend fun diagnose(context: Context): List<DiagCheck> = withContext(Dispatchers.IO) {
        ensureStarted(context)
        val raw = Python.getInstance().getModule("downloader")
            .callAttr("diagnose")
            .toString()
        val obj = JSONObject(raw)
        val checks = obj.optJSONArray("checks") ?: JSONArray()
        buildList {
            for (i in 0 until checks.length()) {
                val c = checks.optJSONObject(i) ?: continue
                add(
                    DiagCheck(
                        name = c.optString("name", "Check"),
                        ok = c.optBoolean("ok", false),
                        detail = c.optString("detail", "")
                    )
                )
            }
        }
    }

    suspend fun clearCaches() = withContext(Dispatchers.IO) {
        if (Python.isStarted()) {
            try {
                Python.getInstance().getModule("downloader").callAttr("clear_caches")
            } catch (_: Exception) {
            }
        }
    }

    fun downloadBlocking(
        context: Context,
        url: String,
        workDir: File,
        formatId: String,
        optsJson: String,
        listener: DownloadProgressListener
    ): PythonDownloadResult {
        ensureStarted(context)
        workDir.mkdirs()
        val raw = Python.getInstance().getModule("downloader")
            .callAttr("download", url, workDir.absolutePath, formatId, listener, Build.VERSION.SDK_INT, optsJson)
            .toString()
        val obj = JSONObject(raw)
        val subsArr = obj.optJSONArray("subtitle_paths") ?: JSONArray()
        val subs = buildList {
            for (i in 0 until subsArr.length()) {
                val p = subsArr.optString(i, "")
                if (p.isNotBlank()) add(p)
            }
        }
        val extrasArr = obj.optJSONArray("extra_paths") ?: JSONArray()
        val extras = buildList {
            for (i in 0 until extrasArr.length()) {
                val p = extrasArr.optString(i, "")
                if (p.isNotBlank()) add(p)
            }
        }
        return PythonDownloadResult(
            title = obj.optString("title", "Video"),
            merge = obj.optBoolean("merge", false),
            videoPath = obj.optString("video_path", ""),
            audioPath = obj.optString("audio_path", ""),
            singlePath = obj.optString("path", ""),
            ext = obj.optString("ext", "mp4"),
            thumbnailUrl = obj.optString("thumbnail", ""),
            subtitlePaths = subs,
            extraPaths = extras
        )
    }
}
