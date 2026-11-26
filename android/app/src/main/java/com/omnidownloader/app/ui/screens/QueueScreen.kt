package com.omnidownloader.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidownloader.app.DowniNativeUtils
import com.omnidownloader.app.data.DownloadJobEntity
import com.omnidownloader.app.data.JobStatus
import com.omnidownloader.app.ui.components.DowniGlassCard
import com.omnidownloader.app.ui.components.StatusPill
import com.omnidownloader.app.ui.components.pressScale
import com.omnidownloader.app.ui.theme.DowniPalette
import com.omnidownloader.app.ui.theme.DowniSpacing

@Composable
fun QueueScreen(
    jobs: List<DownloadJobEntity>,
    onCancelJob: (String) -> Unit,
    onCancelAll: () -> Unit,
    onRetry: (DownloadJobEntity) -> Unit,
    onDismissJob: (String) -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val active = jobs.filter { it.status.isActive }
    val failedOrCancelled = jobs.filter { it.status == JobStatus.FAILED || it.status == JobStatus.CANCELLED }

    if (active.isEmpty() && failedOrCancelled.isEmpty()) {
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
                            Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = DowniPalette.Lime,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.height(DowniSpacing.md))
                    Text("Queue is clear", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(DowniSpacing.xs))
                    Text(
                        "Paste a link in Grab, or share directly from YouTube, Instagram, or TikTok using DowniDrop.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DowniPalette.TextSecondary
                    )
                }
            }
        }
        return
    }

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
        if (active.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        text = "ACTIVE TRANSFERS · ${active.size}",
                        color = DowniPalette.Cyan,
                        background = DowniPalette.CyanDim,
                        showDot = true
                    )
                    TextButton(onClick = onCancelAll) {
                        Text("Cancel All", color = DowniPalette.Rose)
                    }
                }
            }
            items(active, key = { it.id }) { job ->
                ActiveJobCard(job = job, onCancel = { onCancelJob(job.id) })
            }
        }

        if (failedOrCancelled.isNotEmpty()) {
            item {
                Text(
                    "ISSUES & CANCELLED",
                    style = MaterialTheme.typography.labelMedium,
                    color = DowniPalette.TextSecondary,
                    modifier = Modifier.padding(top = DowniSpacing.sm)
                )
            }
            items(failedOrCancelled, key = { it.id }) { job ->
                FailedJobCard(
                    job = job,
                    onRetry = { onRetry(job) },
                    onDismiss = { onDismissJob(job.id) }
                )
            }
        }
    }
}

@Composable
private fun ActiveJobCard(job: DownloadJobEntity, onCancel: () -> Unit) {
    val animatedProgress by animateFloatAsState(
        targetValue = (job.progress / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220),
        label = "jobProgress"
    )
    val speed = DowniNativeUtils.formatSpeed(job.speedBps)
    val eta = DowniNativeUtils.formatEta(job.etaSec)
    val sizeText = if (job.totalBytes > 0) {
        "${DowniNativeUtils.formatBytes(job.downloadedBytes)} / ${DowniNativeUtils.formatBytes(job.totalBytes)}"
    } else {
        job.statusText
    }

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
            Text(
                "${job.progress}%",
                style = MaterialTheme.typography.titleMedium,
                color = DowniPalette.Lime,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(DowniSpacing.md))
        Text(
            job.title.ifBlank { job.url },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(DowniSpacing.md))
        if (job.progress > 0) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                color = DowniPalette.Lime,
                trackColor = DowniPalette.SurfaceHighlight,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(999.dp))
            )
        } else {
            LinearProgressIndicator(
                color = DowniPalette.Lime,
                trackColor = DowniPalette.SurfaceHighlight,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(999.dp))
            )
        }
        Spacer(Modifier.height(DowniSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    sizeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = DowniPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (speed.isNotBlank() || eta.isNotBlank()) {
                    Text(
                        listOf(speed, eta).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = DowniPalette.Cyan
                    )
                }
            }
            OutlinedButton(
                onClick = onCancel,
                border = BorderStroke(1.dp, DowniPalette.Rose.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.pressScale()
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = DowniPalette.Rose, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(DowniSpacing.xs))
                Text("Cancel", color = DowniPalette.Rose)
            }
        }
    }
}

@Composable
private fun FailedJobCard(
    job: DownloadJobEntity,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = DowniPalette.Surface,
        border = BorderStroke(1.dp, DowniPalette.Rose.copy(alpha = 0.40f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(DowniSpacing.lg)) {
            StatusPill(
                text = job.errorCause.ifBlank { job.status.name }.uppercase(),
                color = DowniPalette.Rose,
                background = DowniPalette.RoseDim
            )
            Spacer(Modifier.height(DowniSpacing.sm))
            Text(
                job.title.ifBlank { job.url },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (job.errorRaw.isNotBlank()) {
                Spacer(Modifier.height(DowniSpacing.xs))
                Text(
                    job.errorRaw,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DowniPalette.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(DowniSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(DowniSpacing.sm)) {
                OutlinedButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DowniPalette.Lime),
                    border = BorderStroke(1.dp, DowniPalette.Lime),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.pressScale()
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(DowniSpacing.xs))
                    Text("Retry")
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = DowniPalette.TextSecondary)
                }
            }
        }
    }
}
