package com.rhshourav.tenchar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Warm paper and deep teal. No dynamic colour, so the app looks the same on
// every phone. Text/background pairs are all well above 4.5:1 contrast.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5F5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE5E1),
    onPrimaryContainer = Color(0xFF0F2E2C),
    secondary = Color(0xFF5B5448),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7E2D9),
    onSecondaryContainer = Color(0xFF272319),
    background = Color(0xFFF6F3EE),
    onBackground = Color(0xFF1F2528),
    surface = Color(0xFFF6F3EE),
    onSurface = Color(0xFF1F2528),
    surfaceVariant = Color(0xFFEAE6DE),
    onSurfaceVariant = Color(0xFF4C5557),
    outline = Color(0xFF7A7F80),
    outlineVariant = Color(0xFFCDC8BE),
    error = Color(0xFFA33A2B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6DAD5),
    onErrorContainer = Color(0xFF4A150E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CCBC4),
    onPrimary = Color(0xFF00322E),
    primaryContainer = Color(0xFF1F4A46),
    onPrimaryContainer = Color(0xFFCFE5E1),
    secondary = Color(0xFFCFC6B6),
    onSecondary = Color(0xFF352F24),
    secondaryContainer = Color(0xFF2A3135),
    onSecondaryContainer = Color(0xFFE3E6E5),
    background = Color(0xFF14181A),
    onBackground = Color(0xFFE3E6E5),
    surface = Color(0xFF14181A),
    onSurface = Color(0xFFE3E6E5),
    surfaceVariant = Color(0xFF222A2D),
    onSurfaceVariant = Color(0xFFB4BBBB),
    outline = Color(0xFF8B9293),
    outlineVariant = Color(0xFF3C4548),
    error = Color(0xFFF0A89D),
    onError = Color(0xFF4A150E),
    errorContainer = Color(0xFF5A211A),
    onErrorContainer = Color(0xFFF6DAD5),
)

private val BaseTypography = Typography()

private val TenCharTypography = BaseTypography.copy(
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontSize = 22.sp),
)

/** Pairing codes and the verification number: fixed width so digits line up. */
val CodeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 30.sp,
    lineHeight = 40.sp,
    letterSpacing = 2.sp,
)

@Composable
fun TenCharTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = TenCharTypography,
        content = content,
    )
}
