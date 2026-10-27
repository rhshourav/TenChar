package com.rhshourav.tenchar.ui

import android.provider.Settings
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The TenChar palette, taken from the logo (colors.md). The T is mint to
 * lavender, the C is peach to indigo, and everything sits on deep space blue.
 * The app is dark only: the palette is defined for dark, and a private
 * messenger that glows on a dark screen is also easier to use unnoticed.
 */
object Tc {
    val Space = Color(0xFF13141C)
    val Plum = Color(0xFF1E1B38)
    val Mint = Color(0xFF84F5B7)
    val Lavender = Color(0xFFB491FE)
    val LavenderSoft = Color(0xFFD2B8FF)
    val Peach = Color(0xFFFFB385)
    val Indigo = Color(0xFF5A33D3)

    /** Text. Ink on the glass plates is above 12:1; muted is above 6:1. */
    val Ink = Color(0xFFF4F2FF)
    val InkMuted = Ink.copy(alpha = 0.72f)
    val InkFaint = Ink.copy(alpha = 0.56f)

    val TGradient: Brush = Brush.linearGradient(listOf(Mint, Lavender))
    val CGradient: Brush = Brush.linearGradient(listOf(Peach, Indigo))

    /** The signature fill for primary actions and key numbers. */
    val Brand: Brush = Brush.linearGradient(listOf(Mint, Lavender))
}

/** True when the person turned animations off in system settings. */
val LocalReduceMotion = compositionLocalOf { false }

private val TcColorScheme = darkColorScheme(
    primary = Tc.Mint,
    onPrimary = Tc.Space,
    primaryContainer = Tc.Indigo,
    onPrimaryContainer = Tc.Ink,
    secondary = Tc.Peach,
    onSecondary = Tc.Space,
    secondaryContainer = Tc.Plum,
    onSecondaryContainer = Tc.Ink,
    tertiary = Tc.Lavender,
    onTertiary = Tc.Space,
    background = Tc.Space,
    onBackground = Tc.Ink,
    surface = Tc.Space,
    onSurface = Tc.Ink,
    surfaceVariant = Tc.Plum,
    onSurfaceVariant = Tc.InkMuted,
    outline = Color.White.copy(alpha = 0.32f),
    outlineVariant = Color.White.copy(alpha = 0.15f),
    error = Tc.Peach,
    onError = Tc.Space,
    errorContainer = Tc.Plum,
    onErrorContainer = Tc.Peach,
)

// Line heights are generous and tracking is left at zero on purpose: Bangla
// conjuncts collide when letter spacing goes negative.
private val TcTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 42.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp),
)

/** Pairing codes and the verification number: fixed width so characters line up. */
val CodeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 30.sp,
    lineHeight = 42.sp,
    letterSpacing = 2.sp,
)

@Composable
fun TenCharTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    MaterialTheme(colorScheme = TcColorScheme, typography = TcTypography) {
        // Text outside a Surface would otherwise default to black.
        CompositionLocalProvider(
            LocalContentColor provides Tc.Ink,
            LocalReduceMotion provides reduceMotion,
            content = content,
        )
    }
}