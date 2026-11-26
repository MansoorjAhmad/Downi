package com.omnidownloader.app.updater

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.omnidownloader.app.BuildConfig
import com.omnidownloader.app.DowniNativeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    val isNewer: Boolean
)

object AppUpdater {
    private const val GITHUB_REPO = "MansoorjAhmad/Downi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DOWNI/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").removePrefix("v").trim()
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                val dl = a.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && DowniNativeUtils.isAllowedUpdateUrl(dl)) {
                    apkUrl = dl
                    break
                }
            }
            if (tag.isBlank() || apkUrl.isBlank()) return@withContext null
            ReleaseInfo(
                versionName = tag,
                apkUrl = apkUrl,
                notes = json.optString("body", ""),
                isNewer = isNewerVersion(tag, BuildConfig.VERSION_NAME)
            )
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (!DowniNativeUtils.isAllowedUpdateUrl(apkUrl)) return@withContext false
        val outFile = File(context.cacheDir, "downi-update.apk")
        val req = Request.Builder().url(apkUrl).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext false
            val body = resp.body ?: return@withContext false
            val total = body.contentLength()
            var readTotal = 0L
            val buf = ByteArray(16 * 1024)
            body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        readTotal += n
                        if (total > 0) {
                            onProgress(((readTotal * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        true
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val l = local.split(".").mapNotNull { it.toIntOrNull() }
        val max = maxOf(r.size, l.size)
        for (i in 0 until max) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
