package com.omnidownloader.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omnidownloader.app.DowniNativeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.downiDataStore by preferencesDataStore(name = "downi_prefs")

data class AppSettings(
    val autoUnique: Boolean = true,
    val shieldTier: String = "standard",
    val clipboardDetect: Boolean = true,
    val oneTapQuality: String = "best",
    val saveTreeUri: String = "",
    val saveTreeLabel: String = "Gallery / Movies & Music",
    val platformQualityMap: Map<String, String> = emptyMap()
) {
    fun qualityForPlatform(platform: String): String {
        val key = platform.trim().lowercase()
        return platformQualityMap[key]?.takeIf { it.isNotBlank() } ?: oneTapQuality
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val AUTO_UNIQUE = booleanPreferencesKey("auto_unique")
        val SHIELD_TIER = stringPreferencesKey("shield_tier")
        val CLIPBOARD_DETECT = booleanPreferencesKey("clipboard_detect")
        val ONE_TAP_QUALITY = stringPreferencesKey("one_tap_quality")
        val SAVE_TREE_URI = stringPreferencesKey("save_tree_uri")
        val SAVE_TREE_LABEL = stringPreferencesKey("save_tree_label")
        val PLATFORM_QUALITY_JSON = stringPreferencesKey("platform_quality_json")
    }

    val settings: Flow<AppSettings> = context.downiDataStore.data.map { prefs ->
        AppSettings(
            autoUnique = prefs[Keys.AUTO_UNIQUE] ?: true,
            shieldTier = DowniNativeUtils.normalizeShieldTier(prefs[Keys.SHIELD_TIER] ?: "standard"),
            clipboardDetect = prefs[Keys.CLIPBOARD_DETECT] ?: true,
            oneTapQuality = prefs[Keys.ONE_TAP_QUALITY] ?: "best",
            saveTreeUri = prefs[Keys.SAVE_TREE_URI] ?: "",
            saveTreeLabel = prefs[Keys.SAVE_TREE_LABEL] ?: "Gallery / Movies & Music",
            platformQualityMap = parseMap(prefs[Keys.PLATFORM_QUALITY_JSON].orEmpty())
        )
    }

    private fun parseMap(raw: String): Map<String, String> {
        if (raw.isBlank() || raw == "{}") return emptyMap()
        return raw.split('\n')
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null
                else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
            }
            .toMap()
    }

    private fun serializeMap(map: Map<String, String>): String =
        map.entries.joinToString("\n") { "${it.key}=${it.value}" }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setAutoUnique(enabled: Boolean) {
        context.downiDataStore.edit { it[Keys.AUTO_UNIQUE] = enabled }
    }

    suspend fun setShieldTier(tier: String) {
        context.downiDataStore.edit {
            it[Keys.SHIELD_TIER] = DowniNativeUtils.normalizeShieldTier(tier)
        }
    }

    suspend fun setClipboardDetect(enabled: Boolean) {
        context.downiDataStore.edit { it[Keys.CLIPBOARD_DETECT] = enabled }
    }

    suspend fun setOneTapQuality(quality: String) {
        context.downiDataStore.edit { it[Keys.ONE_TAP_QUALITY] = quality.ifBlank { "best" } }
    }

    suspend fun rememberPlatformQuality(platform: String, quality: String) {
        if (platform.isBlank() || quality.isBlank()) return
        context.downiDataStore.edit { prefs ->
            val map = parseMap(prefs[Keys.PLATFORM_QUALITY_JSON].orEmpty()).toMutableMap()
            map[platform.trim().lowercase()] = quality.trim()
            prefs[Keys.PLATFORM_QUALITY_JSON] = serializeMap(map)
        }
    }

    suspend fun setSaveFolder(uri: String, label: String) {
        context.downiDataStore.edit {
            it[Keys.SAVE_TREE_URI] = uri
            it[Keys.SAVE_TREE_LABEL] = label.ifBlank { "Custom folder" }
        }
        context.getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit().putString("treeUri", uri).apply()
    }

    suspend fun clearSaveFolder() {
        context.downiDataStore.edit {
            it.remove(Keys.SAVE_TREE_URI)
            it.remove(Keys.SAVE_TREE_LABEL)
        }
        context.getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit().remove("treeUri").apply()
    }
}
