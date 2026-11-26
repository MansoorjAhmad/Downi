package com.omnidownloader.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.omnidownloader.app.BuildConfig
import com.omnidownloader.app.data.AppSettings
import com.omnidownloader.app.engine.DiagCheck
import com.omnidownloader.app.engine.EngineInfo
import com.omnidownloader.app.ui.UpdateUiState
import com.omnidownloader.app.ui.components.DowniFilterChip
import com.omnidownloader.app.ui.components.DowniGlassCard
import com.omnidownloader.app.ui.components.StatusPill
import com.omnidownloader.app.ui.components.pressScale
import com.omnidownloader.app.ui.theme.DowniPalette
import com.omnidownloader.app.ui.theme.DowniSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    engineInfo: EngineInfo?,
    diagChecks: List<DiagCheck>,
    diagRunning: Boolean,
    updateState: UpdateUiState,
    onToggleAutoUnique: (Boolean) -> Unit,
    onSelectShieldTier: (String) -> Unit,
    onToggleClipboard: (Boolean) -> Unit,
    onSelectOneTapQuality: (String) -> Unit,
    onChooseFolder: () -> Unit,
    onResetFolder: () -> Unit,
    onClearCache: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    onCopyVersion: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onDownloadUpdate: (String) -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val context = LocalContext.current
    val notificationsBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = DowniPalette.Obsidian,
        checkedTrackColor = DowniPalette.Lime,
        uncheckedThumbColor = DowniPalette.TextSecondary,
        uncheckedTrackColor = DowniPalette.SurfaceHighlight
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = DowniSpacing.lg,
            top = DowniSpacing.lg,
            end = DowniSpacing.lg,
            bottom = DowniSpacing.lg + bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(DowniSpacing.md)
    ) {
        if (notificationsBlocked) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = DowniPalette.AmberDim,
                    border = BorderStroke(1.dp, DowniPalette.Amber.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(DowniSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Icon(Icons.Rounded.NotificationsOff, contentDescription = null, tint = DowniPalette.Amber)
                        Spacer(Modifier.width(DowniSpacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Notifications disabled", style = MaterialTheme.typography.titleMedium, color = DowniPalette.Amber)
                            Text(
                                "Enable notifications to track DowniDrop progress.",
                                style = MaterialTheme.typography.labelSmall,
                                color = DowniPalette.TextSecondary
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            },
                            border = BorderStroke(1.dp, DowniPalette.Amber),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Enable", color = DowniPalette.Amber)
                        }
                    }
                }
            }
        }

        item {
            DowniGlassCard(highlighted = settings.autoUnique) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Shield, contentDescription = null, tint = DowniPalette.Success, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(DowniSpacing.sm))
                        Text("ANTI-REPOST SHIELD", style = MaterialTheme.typography.labelSmall, color = DowniPalette.Success)
                    }
                    Switch(checked = settings.autoUnique, onCheckedChange = onToggleAutoUnique, colors = switchColors)
                }
                Spacer(Modifier.height(DowniSpacing.xs))
                Text(
                    "Standard S2+ guarantees unique file hash & metadata. Deep Creator adds hardware bitstream transcode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DowniPalette.TextSecondary
                )
                if (settings.autoUnique) {
                    Spacer(Modifier.height(DowniSpacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(DowniSpacing.md)) {
                        listOf(
                            "standard" to "Standard S2+",
                            "deep" to "Deep Creator"
                        ).forEach { (tier, label) ->
                            val selected = settings.shieldTier == tier
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) DowniPalette.LimeDim else DowniPalette.SurfaceElevated,
                                border = BorderStroke(1.dp, if (selected) DowniPalette.Lime else DowniPalette.Border),
                                modifier = Modifier
                                    .weight(1f)
                                    .pressScale()
                                    .clickable { onSelectShieldTier(tier) }
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) DowniPalette.Lime else DowniPalette.TextPrimary,
                                    modifier = Modifier.padding(vertical = DowniSpacing.md, horizontal = DowniSpacing.md)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            DowniGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = DowniPalette.Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(DowniSpacing.sm))
                    Text("DOWNLOADS & STORAGE", style = MaterialTheme.typography.labelSmall, color = DowniPalette.Cyan)
                }
                Spacer(Modifier.height(DowniSpacing.md))
                Text("Default 1-Tap & DowniDrop Quality", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(DowniSpacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(DowniSpacing.sm)
                ) {
                    listOf("best" to "Best", "1080" to "1080p", "720" to "720p", "audio" to "Audio").forEach { (id, label) ->
                        DowniFilterChip(
                            selected = settings.oneTapQuality == id,
                            onClick = { onSelectOneTapQuality(id) },
                            label = label
                        )
                    }
                }
                Spacer(Modifier.height(DowniSpacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Clipboard Link Detection", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = settings.clipboardDetect, onCheckedChange = onToggleClipboard, colors = switchColors)
                }
                Spacer(Modifier.height(DowniSpacing.md))
                Text("Save Location: ${settings.saveTreeLabel}", style = MaterialTheme.typography.bodyMedium, color = DowniPalette.TextSecondary)
                Spacer(Modifier.height(DowniSpacing.md))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(DowniSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(DowniSpacing.sm)
                ) {
                    OutlinedButton(onClick = onChooseFolder, shape = RoundedCornerShape(10.dp)) { Text("Choose Folder") }
                    if (settings.saveTreeUri.isNotBlank()) {
                        OutlinedButton(onClick = onResetFolder, shape = RoundedCornerShape(10.dp)) { Text("Gallery") }
                    }
                    OutlinedButton(onClick = onClearCache, shape = RoundedCornerShape(10.dp)) { Text("Clear Cache") }
                }
            }
        }

        item {
            val versionString = "DOWNI v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            DowniGlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Memory, contentDescription = null, tint = DowniPalette.TextSecondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(DowniSpacing.sm))
                        Text("ENGINE & UPDATES", style = MaterialTheme.typography.labelSmall, color = DowniPalette.TextSecondary)
                    }
                    StatusPill(
                        text = "v${BuildConfig.VERSION_NAME}",
                        color = DowniPalette.Cyan,
                        background = DowniPalette.CyanDim,
                        modifier = Modifier.clickable { onCopyVersion(versionString) }
                    )
                }
                Spacer(Modifier.height(DowniSpacing.md))
                Text(
                    "yt-dlp ${engineInfo?.ytdlpVersion ?: "warming…"} · Python ${engineInfo?.pythonVersion ?: "3.11"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = DowniPalette.TextSecondary
                )
                Spacer(Modifier.height(DowniSpacing.md))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(DowniSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(DowniSpacing.sm)
                ) {
                    OutlinedButton(onClick = onRunDiagnostics, enabled = !diagRunning, shape = RoundedCornerShape(10.dp)) {
                        Text(if (diagRunning) "Running…" else "Diagnostics")
                    }
                    OutlinedButton(onClick = onCheckUpdates, shape = RoundedCornerShape(10.dp)) {
                        Text("Check Updates")
                    }
                    if (diagChecks.isNotEmpty()) {
                        OutlinedButton(
                            shape = RoundedCornerShape(10.dp),
                            onClick = {
                                val report = diagChecks.joinToString("\n") {
                                    "${if (it.ok) "OK" else "FAIL"} ${it.name}: ${it.detail}"
                                }
                                onCopyDiagnostics(report)
                            }
                        ) {
                            Text("Copy")
                        }
                    }
                }

                when (updateState) {
                    is UpdateUiState.Checking -> Text("Checking GitHub Releases…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = DowniSpacing.sm))
                    is UpdateUiState.UpToDate -> Text("You're on the latest version.", style = MaterialTheme.typography.labelSmall, color = DowniPalette.Success, modifier = Modifier.padding(top = DowniSpacing.sm))
                    is UpdateUiState.Available -> {
                        Spacer(Modifier.height(DowniSpacing.md))
                        Button(
                            onClick = { onDownloadUpdate(updateState.release.apkUrl) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DowniPalette.Lime,
                                contentColor = DowniPalette.Obsidian
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Install v${updateState.release.versionName}", fontWeight = FontWeight.Bold)
                        }
                    }
                    is UpdateUiState.Downloading -> Text("Downloading update: ${updateState.percent}%", style = MaterialTheme.typography.labelSmall, color = DowniPalette.Cyan, modifier = Modifier.padding(top = DowniSpacing.sm))
                    is UpdateUiState.Error -> Text(updateState.message, style = MaterialTheme.typography.labelSmall, color = DowniPalette.Rose, modifier = Modifier.padding(top = DowniSpacing.sm))
                    UpdateUiState.Idle -> Unit
                }

                if (diagChecks.isNotEmpty()) {
                    Spacer(Modifier.height(DowniSpacing.md))
                    diagChecks.forEach { check ->
                        Text(
                            "${if (check.ok) "[OK]" else "[FAIL]"} ${check.name}: ${check.detail}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (check.ok) DowniPalette.Success else DowniPalette.Rose
                        )
                    }
                }
            }
        }
    }
}
