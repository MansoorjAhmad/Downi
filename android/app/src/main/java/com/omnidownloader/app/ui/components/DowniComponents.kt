package com.omnidownloader.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.omnidownloader.app.ui.theme.DowniPalette
import com.omnidownloader.app.ui.theme.DowniSpacing
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild

fun Modifier.pressScale(enabled: Boolean = true): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.98f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "pressScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                }
            )
        }
}

@Composable
fun DowniGlassCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val borderBrush = if (highlighted) {
        Brush.linearGradient(
            listOf(
                DowniPalette.Lime.copy(alpha = 0.50f),
                DowniPalette.BorderBright,
                DowniPalette.Border
            )
        )
    } else {
        Brush.linearGradient(
            listOf(DowniPalette.BorderBright.copy(alpha = 0.55f), DowniPalette.Border)
        )
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = DowniPalette.Surface,
        border = BorderStroke(1.dp, borderBrush),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .background(
                    if (highlighted) {
                        Brush.verticalGradient(
                            listOf(DowniPalette.Lime.copy(alpha = 0.05f), Color.Transparent)
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(DowniPalette.SurfaceElevated.copy(alpha = 0.40f), Color.Transparent)
                        )
                    }
                )
                .padding(DowniSpacing.lg),
            content = content
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    background: Color,
    showDot: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = background,
        border = BorderStroke(1.dp, color.copy(alpha = 0.30f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
fun DowniFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = DowniPalette.SurfaceElevated,
            labelColor = DowniPalette.TextSecondary,
            selectedContainerColor = DowniPalette.LimeDim,
            selectedLabelColor = DowniPalette.Lime
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = DowniPalette.Border,
            selectedBorderColor = DowniPalette.Lime.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    )
}

@Composable
fun MediaThumb(
    thumbnailUrl: String,
    isAudio: Boolean,
    size: Dp = 44.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isAudio) DowniPalette.CyanDim else DowniPalette.SurfaceHighlight),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isAudio) Icons.Rounded.AudioFile else Icons.Rounded.Movie,
            contentDescription = null,
            tint = if (isAudio) DowniPalette.Cyan else DowniPalette.TextSecondary,
            modifier = Modifier.size(size * 0.48f)
        )
        if (thumbnailUrl.isNotBlank()) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
    }
}

data class DowniNavTab(
    val label: String,
    val icon: ImageVector,
    val badgeCount: Int = 0
)

@Composable
fun DowniBottomBar(
    tabs: List<DowniNavTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    if (tabs.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    val dockShape = RoundedCornerShape(31.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .clip(dockShape)
                .then(
                    if (hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            shape = dockShape,
                            style = HazeStyle(
                                tint = DowniPalette.Surface.copy(alpha = 0.56f),
                                blurRadius = 20.dp,
                                noiseFactor = 0f
                            )
                        )
                    } else {
                        Modifier.background(DowniPalette.SurfaceElevated.copy(alpha = 0.88f))
                    }
                )
                .background(
                    Brush.verticalGradient(
                        listOf(
                            DowniPalette.SurfaceHighlight.copy(alpha = 0.24f),
                            DowniPalette.SurfaceElevated.copy(alpha = 0.14f),
                            DowniPalette.Obsidian.copy(alpha = 0.28f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            DowniPalette.BorderBright.copy(alpha = 0.90f),
                            DowniPalette.Border.copy(alpha = 0.55f)
                        )
                    ),
                    shape = dockShape
                )
        ) {
            var trackWidthPx by remember { mutableIntStateOf(0) }
            var pressedTabIndex by remember { mutableIntStateOf(-1) }
            var hoverTabIndex by remember { mutableIntStateOf(safeIndex) }
            var isDragging by remember { mutableStateOf(false) }
            var dragOffsetPx by remember { mutableFloatStateOf(0f) }
            val currentSelectedIndex by rememberUpdatedState(safeIndex)
            val currentOnSelect by rememberUpdatedState(onSelect)
            val haptics = LocalHapticFeedback.current
            val scope = rememberCoroutineScope()
            val pillOffsetX = remember { Animatable(0f) }

            val pillWidthPx = if (trackWidthPx > 0) trackWidthPx.toFloat() / tabs.size else 0f
            val targetRestX = if (trackWidthPx > 0) pillWidthPx * safeIndex else 0f

            LaunchedEffect(targetRestX, isDragging, trackWidthPx) {
                if (!isDragging && trackWidthPx > 0) {
                    if (pillOffsetX.value == 0f && safeIndex == 0 && dragOffsetPx == 0f) {
                        pillOffsetX.snapTo(0f)
                    } else {
                        pillOffsetX.animateTo(
                            targetValue = targetRestX,
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = 1800f
                            )
                        )
                    }
                }
            }

            val dragLiftScale by animateFloatAsState(
                targetValue = if (isDragging) 1.04f else 1f,
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = 1800f
                ),
                label = "navDragLift"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .onSizeChanged { trackWidthPx = it.width }
                    .pointerInput(tabs.size) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val width = trackWidthPx.toFloat()
                            if (width <= 0f) return@awaitEachGesture

                            val localPillW = width / tabs.size

                            fun indexForX(x: Float): Int =
                                ((x / width) * tabs.size).toInt().coerceIn(0, tabs.lastIndex)

                            val downX = down.position.x
                            var draggingNow = false
                            var lastHoverIdx = currentSelectedIndex
                            hoverTabIndex = currentSelectedIndex
                            pressedTabIndex = indexForX(downX)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break

                                if (!change.pressed) {
                                    pressedTabIndex = -1
                                    if (draggingNow) {
                                        val releaseX = dragOffsetPx
                                        scope.launch { pillOffsetX.snapTo(releaseX) }
                                        isDragging = false
                                        val finalIdx = indexForX(change.position.x)
                                        if (finalIdx != currentSelectedIndex) {
                                            currentOnSelect(finalIdx)
                                        }
                                    } else {
                                        currentOnSelect(indexForX(change.position.x))
                                    }
                                    break
                                }

                                val dx = kotlin.math.abs(change.position.x - downX)
                                if (!draggingNow && dx > slop) {
                                    draggingNow = true
                                    dragOffsetPx = (change.position.x - localPillW / 2f)
                                        .coerceIn(0f, (width - localPillW).coerceAtLeast(0f))
                                    isDragging = true
                                }

                                if (draggingNow) {
                                    change.consume()
                                    dragOffsetPx = (change.position.x - localPillW / 2f)
                                        .coerceIn(0f, (width - localPillW).coerceAtLeast(0f))

                                    val targetIdx = indexForX(change.position.x)
                                    pressedTabIndex = targetIdx
                                    hoverTabIndex = targetIdx
                                    if (targetIdx != lastHoverIdx) {
                                        lastHoverIdx = targetIdx
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        }
                    }
            ) {
                // Continuous finger-tracking Electric Lime pill (zero-alloc layout-phase offset)
                if (pillWidthPx > 0f) {
                    Box(
                        modifier = Modifier
                            .offset {
                                val x = if (isDragging) dragOffsetPx else pillOffsetX.value
                                IntOffset(x.roundToInt(), 0)
                            }
                            .width(with(androidx.compose.ui.platform.LocalDensity.current) { pillWidthPx.toDp() })
                            .fillMaxHeight()
                            .graphicsLayer {
                                scaleX = dragLiftScale
                                scaleY = dragLiftScale
                            }
                            .clip(RoundedCornerShape(27.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFFD6FF33),
                                        DowniPalette.Lime,
                                        Color(0xFFB5E600)
                                    )
                                )
                            )
                    )
                }

                val visualActiveIndex = if (isDragging) hoverTabIndex else safeIndex
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val selected = index == visualActiveIndex
                        val pressed = index == pressedTabIndex

                        val pressScaleFactor by animateFloatAsState(
                            targetValue = if (pressed && !isDragging) 0.95f else 1f,
                            animationSpec = spring(
                                dampingRatio = 0.85f,
                                stiffness = 1800f
                            ),
                            label = "navPressScale"
                        )
                        val contentColor by animateColorAsState(
                            targetValue = if (selected) DowniPalette.Obsidian else DowniPalette.TextSecondary,
                            animationSpec = tween(65),
                            label = "navContentColor"
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .graphicsLayer {
                                    scaleX = pressScaleFactor
                                    scaleY = pressScaleFactor
                                }
                                .clip(RoundedCornerShape(27.dp)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = contentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                if (tab.badgeCount > 0) {
                                    val badgeBg = if (selected) DowniPalette.Obsidian else DowniPalette.Lime
                                    val badgeFg = if (selected) DowniPalette.Lime else DowniPalette.Obsidian
                                    val badgeBorder = if (selected) DowniPalette.Lime else DowniPalette.SurfaceElevated
                                    Box(
                                        modifier = Modifier
                                            .offset(x = 8.dp, y = (-4).dp)
                                            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                            .clip(CircleShape)
                                            .background(badgeBg)
                                            .border(1.5.dp, badgeBorder, CircleShape)
                                            .padding(horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (tab.badgeCount > 9) "9+" else tab.badgeCount.toString(),
                                            fontSize = 9.sp,
                                            lineHeight = 10.sp,
                                            color = badgeFg,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                letterSpacing = 0.15.sp,
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                color = contentColor,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

fun platformAccentColor(platform: String): Color = when (platform.lowercase()) {
    "youtube" -> DowniPalette.PlatformYouTube
    "instagram" -> DowniPalette.PlatformInstagram
    "tiktok" -> DowniPalette.PlatformTikTok
    "x / twitter", "twitter" -> DowniPalette.PlatformTwitter
    "facebook" -> DowniPalette.PlatformFacebook
    else -> DowniPalette.Cyan
}
