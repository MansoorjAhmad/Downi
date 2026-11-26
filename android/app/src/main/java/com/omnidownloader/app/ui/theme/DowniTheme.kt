package com.omnidownloader.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Cohesive Obsidian + Electric Lime design tokens.
 * Contrast ratios on Obsidian (#090B0D) & Surface (#11151B):
 * - TextPrimary (#F4F7FA): ~17.5:1 (AAA)
 * - TextSecondary (#A4B1BF): ~8.2:1 (AAA)
 * - TextMuted (#7D8B99): ~5.1:1 (AA)
 */
object DowniPalette {
    // Core Neutrals
    val Obsidian = Color(0xFF090B0D)
    const val ObsidianHex = 0xFF090B0D.toInt()
    val Surface = Color(0xFF11151B)
    val SurfaceElevated = Color(0xFF171D26)
    val SurfaceHighlight = Color(0xFF1F2733)
    val Border = Color(0xFF232C38)
    val BorderBright = Color(0xFF334050)

    // Primary Accent (sparingly used for primary CTAs & active states)
    val Lime = Color(0xFFC8FF00)
    val LimeDim = Color(0xFF1D2608)

    // Secondary & Semantic
    val Cyan = Color(0xFF38BDF8)
    val CyanDim = Color(0xFF0D2533)
    val Success = Color(0xFF34D399)
    val SuccessDim = Color(0xFF0D2920)
    val Amber = Color(0xFFFBBF24)
    val AmberDim = Color(0xFF2B2108)
    val Rose = Color(0xFFFB7185)
    val RoseDim = Color(0xFF2E1219)

    // Typography Colors (WCAG AA+)
    val TextPrimary = Color(0xFFF4F7FA)
    val TextSecondary = Color(0xFFA4B1BF)
    val TextMuted = Color(0xFF7D8B99)

    // Platform Tokens
    val PlatformYouTube = Color(0xFFFF4E45)
    val PlatformInstagram = Color(0xFFE1306C)
    val PlatformTikTok = Color(0xFF25F4EE)
    val PlatformTwitter = Color(0xFFE7E9EA)
    val PlatformFacebook = Color(0xFF3B82F6)
}

object DowniSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

private val DowniDarkColorScheme = darkColorScheme(
    primary = DowniPalette.Lime,
    onPrimary = DowniPalette.Obsidian,
    primaryContainer = DowniPalette.LimeDim,
    onPrimaryContainer = DowniPalette.Lime,
    secondary = DowniPalette.Cyan,
    onSecondary = DowniPalette.Obsidian,
    secondaryContainer = DowniPalette.CyanDim,
    onSecondaryContainer = DowniPalette.Cyan,
    tertiary = DowniPalette.Success,
    onTertiary = DowniPalette.Obsidian,
    background = DowniPalette.Obsidian,
    onBackground = DowniPalette.TextPrimary,
    surface = DowniPalette.Surface,
    onSurface = DowniPalette.TextPrimary,
    surfaceVariant = DowniPalette.SurfaceElevated,
    onSurfaceVariant = DowniPalette.TextSecondary,
    surfaceContainerLowest = DowniPalette.Obsidian,
    surfaceContainerLow = DowniPalette.Surface,
    surfaceContainer = DowniPalette.SurfaceElevated,
    surfaceContainerHigh = DowniPalette.SurfaceHighlight,
    surfaceContainerHighest = DowniPalette.SurfaceHighlight,
    outline = DowniPalette.BorderBright,
    outlineVariant = DowniPalette.Border,
    error = DowniPalette.Rose,
    onError = DowniPalette.Obsidian,
    errorContainer = DowniPalette.RoseDim,
    onErrorContainer = DowniPalette.Rose
)

private val DowniTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 25.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
        fontFeatureSettings = "tnum"
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
        fontFeatureSettings = "tnum"
    )
)

@Composable
fun DowniTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DowniDarkColorScheme,
        typography = DowniTypography,
        content = content
    )
}
