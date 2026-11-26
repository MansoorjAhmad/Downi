package com.omnidownloader.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.omnidownloader.app.DowniNativeUtils
import com.omnidownloader.app.data.DownloadJobEntity
import com.omnidownloader.app.data.JobStatus
import com.omnidownloader.app.ui.components.DowniGlassCard
import com.omnidownloader.app.ui.components.MediaThumb
import com.omnidownloader.app.ui.components.StatusPill
import com.omnidownloader.app.ui.components.platformAccentColor
import com.omnidownloader.app.ui.components.pressScale
import com.omnidownloader.app.ui.theme.DowniPalette
import com.omnidownloader.app.ui.theme.DowniSpacing

@Composable
fun GrabScreen(
    urlInput: String,
    clipboardUrl: String?,
    detectedUrls: List<String>,
    recentJobs: List<DownloadJobEntity>,
    onUrlChange: (String) -> Unit,
    onClearUrl: () -> Unit,
    onPaste: () -> Unit,
    onDismissClipboard: () -> Unit,
    onInspect: (String) -> Unit,
    onQuickGrab: (String) -> Unit,
    onBatchGrab: (List<String>) -> Unit,
    onCancelJob: (String) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenMedia: (String, String) -> Unit,
    detectPlatform: (String) -> String,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val activeJobs = recentJobs.filter { it.status.isActive }
    val completedRecent = recentJobs.filter { it.status == JobStatus.COMPLETED }.take(5)

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
        item {
            AnimatedVisibility(
                visible = !clipboardUrl.isNullOrBlank(),
                enter = expandVertically(tween(200)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(140))
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DowniPalette.SurfaceElevated,
                    border = BorderStroke(1.dp, DowniPalette.Cyan.copy(alpha = 0.40f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = DowniSpacing.md, vertical = DowniSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val plat = detectPlatform(clipboardUrl.orEmpty())
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    clipboardUrl?.let {
                                        onUrlChange(it)
                                        onDismissClipboard()
                                    }
                                }
                                .padding(vertical = DowniSpacing.xs)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(DowniSpacing.sm)
                            ) {
                                Text(
                                    "COPIED · ${plat.uppercase()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = platformAccentColor(plat)
                                )
                                Text(
                                    "Tap to paste",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DowniPalette.TextMuted
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                clipboardUrl.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = DowniPalette.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(DowniSpacing.sm))
                        Button(
                            onClick = {
                                clipboardUrl?.let {
                                    onUrlChange(it)
                                    onDismissClipboard()
                                    onQuickGrab(it)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DowniPalette.Lime,
                                contentColor = DowniPalette.Obsidian
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .pressScale()
                        ) {
                            Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("Grab", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(
                            onClick = onDismissClipboard,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Dismiss",
                                tint = DowniPalette.TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            DowniGlassCard(highlighted = detectedUrls.isNotEmpty()) {
                Text(
                    text = buildAnnotatedString {
                        append("Paste a link. Get the ")
                        withStyle(SpanStyle(color = DowniPalette.Lime)) {
                            append("clean file.")
                        }
                    },
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(DowniSpacing.xs))
                Text(
                    "Videos, playlists, audio, or a list of links.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DowniPalette.TextSecondary
                )
                Spacer(Modifier.height(DowniSpacing.lg))

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = onUrlChange,
                    placeholder = {
                        Text(
                            "Paste video or playlist URL…",
                            color = DowniPalette.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Link, contentDescription = null, tint = DowniPalette.TextSecondary)
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (urlInput.isNotEmpty()) {
                                IconButton(onClick = onClearUrl) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = DowniPalette.TextSecondary)
                                }
                            } else {
                                TextButton(onClick = onPaste) {
                                    Icon(
                                        Icons.Rounded.ContentPaste,
                                        contentDescription = null,
                                        tint = DowniPalette.Lime,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Paste", color = DowniPalette.Lime, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            if (detectedUrls.size == 1) onInspect(detectedUrls.first())
                            else if (detectedUrls.size > 1) onBatchGrab(detectedUrls)
                        }
                    ),
                    maxLines = 3,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DowniPalette.Lime,
                        unfocusedBorderColor = DowniPalette.BorderBright,
                        focusedContainerColor = DowniPalette.SurfaceElevated,
                        unfocusedContainerColor = DowniPalette.SurfaceElevated
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (detectedUrls.size == 1) {
                    val plat = detectPlatform(detectedUrls.first())
                    Spacer(Modifier.height(DowniSpacing.md))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DowniSpacing.sm)
                    ) {
                        StatusPill(
                            text = plat.uppercase(),
                            color = platformAccentColor(plat),
                            background = DowniPalette.SurfaceHighlight,
                            showDot = true
                        )
                        Text(
                            "Ready to inspect or grab",
                            style = MaterialTheme.typography.labelSmall,
                            color = DowniPalette.TextSecondary
                        )
                    }
                } else if (detectedUrls.size > 1) {
                    Spacer(Modifier.height(DowniSpacing.md))
                    StatusPill(
                        text = "${detectedUrls.size} LINKS DETECTED · BATCH QUEUE",
                        color = DowniPalette.Cyan,
                        background = DowniPalette.CyanDim,
                        showDot = true
                    )
                }

                Spacer(Modifier.height(DowniSpacing.lg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DowniSpacing.md)
                ) {
                    OutlinedButton(
                        onClick = { detectedUrls.firstOrNull()?.let(onInspect) },
                        enabled = detectedUrls.isNotEmpty(),
                        border = BorderStroke(
                            1.dp,
                            if (detectedUrls.isNotEmpty()) DowniPalette.BorderBright else DowniPalette.Border
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .pressScale(enabled = detectedUrls.isNotEmpty())
                    ) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(DowniSpacing.sm))
                        Text("Inspect", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            if (detectedUrls.size > 1) onBatchGrab(detectedUrls)
                            else detectedUrls.firstOrNull()?.let(onQuickGrab)
                        },
                        enabled = detectedUrls.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DowniPalette.Lime,
                            contentColor = DowniPalette.Obsidian,
                            disabledContainerColor = DowniPalette.SurfaceHighlight,
                            disabledContentColor = DowniPalette.TextMuted
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1.25f)
                            .height(50.dp)
                            .pressScale(enabled = detectedUrls.isNotEmpty())
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(DowniSpacing.xs))
                        Text(
                            if (detectedUrls.size > 1) "Grab ${detectedUrls.size} All" else "1-Tap Grab",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (activeJobs.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        text = "DOWNLOADING · ${activeJobs.size}",
                        color = DowniPalette.Lime,
                        background = DowniPalette.LimeDim,
                        showDot = true
                    )
                    TextButton(onClick = onOpenQueue) {
                        Text("View Queue", color = DowniPalette.Cyan, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            items(activeJobs, key = { "active_${it.id}" }) { job ->
                val animatedProgress by animateFloatAsState(
                    targetValue = (job.progress / 100f).coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 220),
                    label = "grabActiveProgress"
                )
                val speed = DowniNativeUtils.formatSpeed(job.speedBps)
                val eta = DowniNativeUtils.formatEta(job.etaSec)
                val metrics = listOfNotNull(
                    if (job.totalBytes > 0) "${DowniNativeUtils.formatBytes(job.downloadedBytes)} / ${DowniNativeUtils.formatBytes(job.totalBytes)}" else job.statusText,
                    speed.ifBlank { null },
                    eta.ifBlank { null }
                ).joinToString(" · ")

                DowniGlassCard(highlighted = job.status != JobStatus.QUEUED) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(
                            text = if (job.isDrop) "DOWNIDROP · ${job.status.name}" else job.status.name,
                            color = DowniPalette.Cyan,
                            background = DowniPalette.CyanDim,
                            showDot = job.status != JobStatus.QUEUED
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${job.progress}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = DowniPalette.Lime,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(DowniSpacing.xs))
                            IconButton(
                                onClick = { onCancelJob(job.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Cancel download",
                                    tint = DowniPalette.Rose,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(DowniSpacing.sm))
                    Text(
                        job.title.ifBlank { job.url },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(DowniSpacing.sm))
                    if (job.progress > 0) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            color = DowniPalette.Lime,
                            trackColor = DowniPalette.SurfaceHighlight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            color = DowniPalette.Lime,
                            trackColor = DowniPalette.SurfaceHighlight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                        )
                    }
                    Spacer(Modifier.height(DowniSpacing.xs))
                    Text(
                        metrics,
                        style = MaterialTheme.typography.labelSmall,
                        color = DowniPalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (completedRecent.isEmpty() && activeJobs.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DowniPalette.Surface,
                    border = BorderStroke(1.dp, DowniPalette.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(DowniSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = DowniPalette.Cyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(DowniSpacing.md))
                        Column {
                            Text("Tip: Share from any app with DowniDrop", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Share a reel or video to DOWNI to grab it silently in the background.",
                                style = MaterialTheme.typography.labelSmall,
                                color = DowniPalette.TextSecondary
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Text(
                    "RECENT GRABS",
                    style = MaterialTheme.typography.labelMedium,
                    color = DowniPalette.TextSecondary,
                    modifier = Modifier.padding(top = DowniSpacing.xs)
                )
            }
            items(completedRecent, key = { it.id }) { item ->
                val isAudio = item.mimeType.startsWith("audio/")
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DowniPalette.Surface,
                    border = BorderStroke(1.dp, DowniPalette.Border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale()
                        .clickable { onOpenMedia(item.mediaUri, item.mimeType) }
                ) {
                    Row(
                        modifier = Modifier.padding(DowniSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MediaThumb(
                            thumbnailUrl = item.thumbnailUrl,
                            isAudio = isAudio,
                            size = 44.dp
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
                        if (item.shieldMode.isNotBlank() && item.shieldMode != "skipped") {
                            Icon(
                                Icons.Rounded.Shield,
                                contentDescription = "Shield verified",
                                tint = DowniPalette.Success,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
