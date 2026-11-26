package com.omnidownloader.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.omnidownloader.app.engine.InspectResult
import com.omnidownloader.app.engine.PlaylistInspectResult
import com.omnidownloader.app.ui.InspectUiState
import com.omnidownloader.app.ui.theme.DowniPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorModalHost(
    state: InspectUiState,
    loadingMorePlaylist: Boolean,
    onDismiss: () -> Unit,
    onLoadMorePlaylist: () -> Unit,
    onGrabFormat: (InspectResult, String, Boolean, String) -> Unit,
    onGrabBatch: (List<String>, String) -> Unit
) {
    if (state is InspectUiState.Idle) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DowniPalette.Surface,
        contentColor = DowniPalette.TextPrimary
    ) {
        when (state) {
            is InspectUiState.Loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = DowniPalette.Lime)
                    Spacer(Modifier.height(16.dp))
                    Text("Inspecting media…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = DowniPalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            is InspectUiState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        "COULD NOT INSPECT (${state.cause.uppercase()})",
                        style = MaterialTheme.typography.labelMedium,
                        color = DowniPalette.Rose
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(state.raw, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                }
            }

            is InspectUiState.Ready -> {
                SingleVideoSheetContent(
                    info = state.result,
                    onGrab = { fmt, subs, lang -> onGrabFormat(state.result, fmt, subs, lang) }
                )
            }

            is InspectUiState.PlaylistReady -> {
                PlaylistSheetContent(
                    playlist = state.result,
                    loadingMore = loadingMorePlaylist,
                    onLoadMore = onLoadMorePlaylist,
                    onGrabSelected = { urls, fmt -> onGrabBatch(urls, fmt) }
                )
            }

            InspectUiState.Idle -> Unit
        }
    }
}

@Composable
private fun SingleVideoSheetContent(
    info: InspectResult,
    onGrab: (formatId: String, subtitles: Boolean, subLang: String) -> Unit
) {
    val formats = info.formats
    var selectedFormat by remember(info.url) {
        mutableStateOf(formats.firstOrNull()?.formatId ?: "best")
    }
    var includeSubs by remember(info.url) { mutableStateOf(false) }
    val selectedLang = info.subtitleLangs.firstOrNull() ?: "en"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (info.thumbnail.isNotBlank()) {
                AsyncImage(
                    model = info.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    info.platform.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = DowniPalette.Lime
                )
                Text(
                    info.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (info.uploader.isNotBlank()) {
                    Text(
                        info.uploader,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DowniPalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("SELECT QUALITY", style = MaterialTheme.typography.labelMedium, color = DowniPalette.TextSecondary)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(formats, key = { it.formatId }) { fmt ->
                val selected = fmt.formatId == selectedFormat
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) DowniPalette.LimeDim else DowniPalette.SurfaceElevated,
                    border = BorderStroke(1.dp, if (selected) DowniPalette.Lime else DowniPalette.Border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedFormat = fmt.formatId }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                fmt.label,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) DowniPalette.Lime else DowniPalette.TextPrimary
                            )
                            if (fmt.detail.isNotBlank()) {
                                Text(
                                    fmt.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DowniPalette.TextSecondary
                                )
                            }
                        }
                        if (fmt.sizeText.isNotBlank()) {
                            Text(
                                fmt.sizeText,
                                style = MaterialTheme.typography.labelMedium,
                                color = DowniPalette.TextSecondary
                            )
                        }
                    }
                }
            }
        }

        if (info.subtitleLangs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Include subtitles ($selectedLang)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = includeSubs, onCheckedChange = { includeSubs = it })
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onGrab(selectedFormat, includeSubs, selectedLang) },
            colors = ButtonDefaults.buttonColors(
                containerColor = DowniPalette.Lime,
                contentColor = DowniPalette.Obsidian
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Grab Now", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PlaylistSheetContent(
    playlist: PlaylistInspectResult,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onGrabSelected: (List<String>, String) -> Unit
) {
    val selectedUrls = remember(playlist.title) {
        mutableStateListOf<String>().apply { addAll(playlist.items.map { it.url }) }
    }
    var search by remember { mutableStateOf("") }
    var batchQuality by remember { mutableStateOf("best") }

    val visibleItems = playlist.items.filter {
        search.isBlank() || it.title.contains(search, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            "PLAYLIST · ${playlist.items.size} / ${playlist.totalCount} LOADED",
            style = MaterialTheme.typography.labelSmall,
            color = DowniPalette.Lime
        )
        Text(
            playlist.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Filter playlist items…", color = DowniPalette.TextMuted) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = DowniPalette.Lime,
                unfocusedBorderColor = DowniPalette.Border,
                focusedContainerColor = DowniPalette.SurfaceElevated,
                unfocusedContainerColor = DowniPalette.SurfaceElevated
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("best" to "Best", "1080" to "1080p", "720" to "720p", "audio" to "Audio").forEach { (id, label) ->
                DowniFilterChip(
                    selected = batchQuality == id,
                    onClick = { batchQuality = id },
                    label = label
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                selectedUrls.clear()
                selectedUrls.addAll(visibleItems.map { it.url })
            }) { Text("Select All") }
            OutlinedButton(onClick = { selectedUrls.clear() }) { Text("Clear") }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(visibleItems, key = { it.url }) { item ->
                val checked = item.url in selectedUrls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (checked) selectedUrls.remove(item.url) else selectedUrls.add(item.url)
                        }
                        .padding(vertical = 2.dp)
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                if (item.url !in selectedUrls) selectedUrls.add(item.url)
                            } else {
                                selectedUrls.remove(item.url)
                            }
                        }
                    )
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (playlist.hasMore) {
                item {
                    TextButton(
                        onClick = onLoadMore,
                        enabled = !loadingMore,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (loadingMore) "Loading more…" else "Load More")
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onGrabSelected(selectedUrls.toList(), batchQuality) },
            enabled = selectedUrls.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = DowniPalette.Lime,
                contentColor = DowniPalette.Obsidian
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Grab ${selectedUrls.size} Selected", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
    }
}
