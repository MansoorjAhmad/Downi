package com.omnidownloader.app.ui.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.omnidownloader.app.DowniNativeUtils
import com.omnidownloader.app.data.DownloadJobEntity
import com.omnidownloader.app.ui.theme.DowniPalette
import com.omnidownloader.app.ui.theme.DowniSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultPlayerSheet(
    item: DownloadJobEntity?,
    actionBusy: Boolean,
    onDismiss: () -> Unit,
    onShare: (String, String) -> Unit,
    onOpenExternal: (String, String) -> Unit,
    onRerunShield: (DownloadJobEntity, String) -> Unit,
    onExtractAudio: (DownloadJobEntity) -> Unit
) {
    if (item == null) return

    val context = LocalContext.current
    var speed by remember(item.id) { mutableFloatStateOf(1.0f) }

    val player = remember(item.mediaUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(item.mediaUri)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DowniPalette.Surface,
        contentColor = DowniPalette.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DowniSpacing.lg, vertical = DowniSpacing.sm)
        ) {
            Text(
                item.title.ifBlank { "Saved Media" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${DowniNativeUtils.formatBytes(item.fileBytes)} · ${item.destinationLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = DowniPalette.TextSecondary
            )
            Spacer(Modifier.height(DowniSpacing.md))

            val isAudio = item.mimeType.startsWith("audio/")
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        controllerShowTimeoutMs = if (isAudio) 0 else 3000
                        controllerHideOnTouch = !isAudio
                    }
                },
                modifier = if (isAudio) {
                    Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .clip(RoundedCornerShape(16.dp))
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp))
                }
            )

            Spacer(Modifier.height(DowniSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(DowniSpacing.sm)) {
                listOf(1.0f to "1x", 1.5f to "1.5x", 2.0f to "2x").forEach { (spd, label) ->
                    DowniFilterChip(
                        selected = speed == spd,
                        onClick = {
                            speed = spd
                            player.setPlaybackSpeed(spd)
                        },
                        label = label
                    )
                }
            }

            if (item.mimeType.startsWith("video/")) {
                Spacer(Modifier.height(DowniSpacing.md))
                Text(
                    "VAULT ACTIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = DowniPalette.TextSecondary
                )
                Spacer(Modifier.height(DowniSpacing.xs))
                if (actionBusy) {
                    LinearProgressIndicator(
                        color = DowniPalette.Lime,
                        trackColor = DowniPalette.SurfaceHighlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                    Spacer(Modifier.height(DowniSpacing.xs))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(DowniSpacing.sm)
                ) {
                    OutlinedButton(
                        onClick = { onRerunShield(item, "standard") },
                        enabled = !actionBusy,
                        border = BorderStroke(1.dp, DowniPalette.BorderBright),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Standard Shield", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { onRerunShield(item, "deep") },
                        enabled = !actionBusy,
                        border = BorderStroke(1.dp, DowniPalette.Lime.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Deep Shield", color = DowniPalette.Lime, style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { onExtractAudio(item) },
                        enabled = !actionBusy,
                        border = BorderStroke(1.dp, DowniPalette.Cyan.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Extract Audio", color = DowniPalette.Cyan, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.height(DowniSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DowniSpacing.md)
            ) {
                OutlinedButton(
                    onClick = { onOpenExternal(item.mediaUri, item.mimeType) },
                    border = BorderStroke(1.dp, DowniPalette.BorderBright),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(DowniSpacing.xs))
                    Text("External")
                }
                Button(
                    onClick = { onShare(item.mediaUri, item.mimeType) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DowniPalette.Lime,
                        contentColor = DowniPalette.Obsidian
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1.2f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(DowniSpacing.xs))
                    Text("Share / Repost", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(DowniSpacing.lg))
        }
    }
}
