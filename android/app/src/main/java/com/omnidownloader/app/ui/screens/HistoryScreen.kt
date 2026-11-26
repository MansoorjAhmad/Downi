package com.omnidownloader.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidownloader.app.DowniNativeUtils
import com.omnidownloader.app.data.DownloadJobEntity
import com.omnidownloader.app.data.JobStatus
import com.omnidownloader.app.ui.components.DowniFilterChip
import com.omnidownloader.app.ui.components.DowniGlassCard
import com.omnidownloader.app.ui.components.MediaThumb
import com.omnidownloader.app.ui.components.StatusPill
import com.omnidownloader.app.ui.components.pressScale
import com.omnidownloader.app.ui.theme.DowniPalette
import com.omnidownloader.app.ui.theme.DowniSpacing

@Composable
fun HistoryScreen(
    jobs: List<DownloadJobEntity>,
    onOpenMedia: (String, String) -> Unit,
    onShareMedia: (String, String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onClearAll: () -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var sortBySize by remember { mutableStateOf(false) }
    var shieldDetailItem by remember { mutableStateOf<DownloadJobEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val completed = jobs.filter { it.status == JobStatus.COMPLETED }
    val totalBytes = completed.sumOf { it.fileBytes }
    val shieldedCount = completed.count { it.shieldMode.isNotBlank() && it.shieldMode != "skipped" }

    val filtered = completed.filter { item ->
        val matchesQuery = query.isBlank() ||
            item.title.contains(query, ignoreCase = true) ||
            item.platform.contains(query, ignoreCase = true)
        val matchesFilter = when (filter) {
            "Videos" -> item.mimeType.startsWith("video/")
            "Audio" -> item.mimeType.startsWith("audio/")
            "Shielded" -> item.shieldMode.isNotBlank() && item.shieldMode != "skipped"
            else -> true
        }
        matchesQuery && matchesFilter
    }.let { list ->
        if (sortBySize) list.sortedByDescending { it.fileBytes } else list
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = DowniSpacing.lg, vertical = DowniSpacing.md)) {
            if (completed.isNotEmpty()) {
                DowniGlassCard(highlighted = false) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("VAULT STORAGE", style = MaterialTheme.typography.labelSmall, color = DowniPalette.TextSecondary)
                            Spacer(Modifier.height(DowniSpacing.xs))
                            Text(
                                "${completed.size} files · ${DowniNativeUtils.formatBytes(totalBytes)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        StatusPill(
                            text = "$shieldedCount SHIELDED",
                            color = DowniPalette.Success,
                            background = DowniPalette.SuccessDim,
                            showDot = true
                        )
                    }
                }
                Spacer(Modifier.height(DowniSpacing.md))
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search vault…", color = DowniPalette.TextMuted) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = DowniPalette.TextSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DowniPalette.Lime,
                    unfocusedBorderColor = DowniPalette.BorderBright,
                    focusedContainerColor = DowniPalette.Surface,
                    unfocusedContainerColor = DowniPalette.Surface
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(DowniSpacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(DowniSpacing.sm)
                ) {
                    listOf("All", "Videos", "Audio", "Shielded").forEach { label ->
                        DowniFilterChip(
                            selected = filter == label,
                            onClick = { filter = label },
                            label = label
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { sortBySize = !sortBySize }) {
                        Text(if (sortBySize) "Largest" else "Newest", color = DowniPalette.Cyan)
                    }
                    if (completed.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) {
                            Text("Clear", color = DowniPalette.Rose)
                        }
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(DowniSpacing.xxl),
                contentAlignment = Alignment.Center
            ) {
                DowniGlassCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(DowniPalette.SurfaceHighlight),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.History,
                                contentDescription = null,
                                tint = DowniPalette.TextSecondary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(Modifier.height(DowniSpacing.md))
                        Text(
                            if (completed.isEmpty()) "Vault is empty" else "No matching downloads",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.height(DowniSpacing.xs))
                        Text(
                            if (completed.isEmpty()) {
                                "Completed downloads and their Anti-Repost Shield L3 reports appear here."
                            } else {
                                "Try clearing your search query or switching filter chips."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = DowniPalette.TextSecondary
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = DowniSpacing.lg,
                    top = DowniSpacing.sm,
                    end = DowniSpacing.lg,
                    bottom = DowniSpacing.sm + bottomContentPadding
                ),
                verticalArrangement = Arrangement.spacedBy(DowniSpacing.md)
            ) {
                items(filtered, key = { it.id }) { item ->
                    val isAudio = item.mimeType.startsWith("audio/")
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = DowniPalette.Surface,
                        border = BorderStroke(1.dp, DowniPalette.Border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale()
                            .clickable { onOpenMedia(item.mediaUri, item.mimeType) }
                    ) {
                        Column(modifier = Modifier.padding(DowniSpacing.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MediaThumb(
                                    thumbnailUrl = item.thumbnailUrl,
                                    isAudio = isAudio,
                                    size = 46.dp
                                )
                                Spacer(Modifier.width(DowniSpacing.md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.title.ifBlank { item.url },
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "${DowniNativeUtils.formatBytes(item.fileBytes)} · ${item.destinationLabel}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = DowniPalette.TextSecondary
                                    )
                                }
                                IconButton(onClick = { onShareMedia(item.mediaUri, item.mimeType) }) {
                                    Icon(Icons.Rounded.Share, contentDescription = "Share", tint = DowniPalette.Cyan)
                                }
                                IconButton(onClick = { onDeleteItem(item.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = DowniPalette.TextMuted)
                                }
                            }
                            if (item.shieldMode.isNotBlank()) {
                                Spacer(Modifier.height(DowniSpacing.sm))
                                val verified = item.shieldMode != "skipped"
                                StatusPill(
                                    text = if (verified) {
                                        "SHIELD VERIFIED (${item.shieldMode.uppercase()}) · L3"
                                    } else {
                                        "SHIELD SKIPPED · ORIGINAL SAVED"
                                    },
                                    color = if (verified) DowniPalette.Success else DowniPalette.Amber,
                                    background = if (verified) DowniPalette.SuccessDim else DowniPalette.AmberDim,
                                    showDot = verified,
                                    modifier = Modifier.clickable { shieldDetailItem = item }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = DowniPalette.Surface,
            titleContentColor = DowniPalette.TextPrimary,
            textContentColor = DowniPalette.TextSecondary,
            title = { Text("Clear Vault history?") },
            text = { Text("This removes download records from DOWNI. Your saved files in Gallery remain untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) {
                    Text("Clear", color = DowniPalette.Rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Cancel", color = DowniPalette.TextSecondary)
                }
            }
        )
    }

    shieldDetailItem?.let { item ->
        AlertDialog(
            onDismissRequest = { shieldDetailItem = null },
            containerColor = DowniPalette.Surface,
            titleContentColor = DowniPalette.TextPrimary,
            textContentColor = DowniPalette.TextSecondary,
            icon = { Icon(Icons.Rounded.Shield, contentDescription = null, tint = DowniPalette.Success) },
            title = { Text("Anti-Repost Shield L3 Report") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(DowniSpacing.xs)) {
                    Text("Mode: ${item.shieldMode}", style = MaterialTheme.typography.labelMedium, color = DowniPalette.Success)
                    if (item.shieldReason.isNotBlank()) {
                        Text("Detail: ${item.shieldReason}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (item.shieldInputSha.isNotBlank()) {
                        Text("Input SHA-256: ${item.shieldInputSha.take(24)}…", style = MaterialTheme.typography.labelSmall)
                    }
                    if (item.shieldOutputSha.isNotBlank()) {
                        Text("Output SHA-256: ${item.shieldOutputSha.take(24)}…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { shieldDetailItem = null }) {
                    Text("Close", color = DowniPalette.Lime)
                }
            }
        )
    }
}
