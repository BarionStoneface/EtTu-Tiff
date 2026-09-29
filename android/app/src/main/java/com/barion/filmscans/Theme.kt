package com.barion.filmscans

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class AppTheme(val label: String, val dark: Boolean) {
    STUDIO("Studio — slate, steel blue, tangerine", true),
    DAYLIGHT("Daylight — light grey, blue, tangerine", false),
    DARKROOM("Darkroom — deep brown, amber safelight", true),
    SYSTEM("Match my phone's wallpaper colours", true);

    fun colors(ctx: Context): ColorScheme = when (this) {
        STUDIO -> Studio
        DAYLIGHT -> Daylight
        DARKROOM -> Darkroom
        SYSTEM -> if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(ctx) else Studio
    }
}

private val Tangerine = Color(0xFFFF8C42)

/** The default: blue-tinted charcoal greys, steel blue, and a tangerine highlight. */
private val Studio = darkColorScheme(
    primary = Tangerine,
    onPrimary = Color(0xFF2E1400),
    primaryContainer = Color(0xFF5C2C0A),
    onPrimaryContainer = Color(0xFFFFDBC7),
    secondary = Color(0xFF8FB4E3),
    onSecondary = Color(0xFF0B1E36),
    secondaryContainer = Color(0xFF26405F),
    onSecondaryContainer = Color(0xFFD6E5FA),
    tertiary = Color(0xFFB7C8DE),
    background = Color(0xFF14181D),
    onBackground = Color(0xFFE4E8EE),
    surface = Color(0xFF14181D),
    onSurface = Color(0xFFE4E8EE),
    surfaceVariant = Color(0xFF2A333E),
    onSurfaceVariant = Color(0xFFA9B4C1),
    surfaceContainerLowest = Color(0xFF101318),
    surfaceContainerLow = Color(0xFF191E24),
    surfaceContainer = Color(0xFF1D232A),
    surfaceContainerHigh = Color(0xFF232A33),
    surfaceContainerHighest = Color(0xFF29313B),
    outline = Color(0xFF55616F),
    outlineVariant = Color(0xFF36404B),
    error = Color(0xFFFF8A80),
)

private val Daylight = lightColorScheme(
    primary = Color(0xFFD9560B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC7),
    onPrimaryContainer = Color(0xFF3A1600),
    secondary = Color(0xFF2E5E96),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E4F7),
    onSecondaryContainer = Color(0xFF0B2442),
    tertiary = Color(0xFF4A6480),
    background = Color(0xFFF2F4F7),
    onBackground = Color(0xFF1A2129),
    surface = Color(0xFFF2F4F7),
    onSurface = Color(0xFF1A2129),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF475361),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FB),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    outline = Color(0xFF8793A1),
    outlineVariant = Color(0xFFCBD3DC),
    error = Color(0xFFBA1A1A),
)

private val Darkroom = darkColorScheme(
    primary = Color(0xFFE8A33D),
    onPrimary = Color(0xFF2A1A00),
    primaryContainer = Color(0xFF5A3A08),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFD9C2A6),
    secondaryContainer = Color(0xFF4A3B2B),
    onSecondaryContainer = Color(0xFFF5E1C8),
    background = Color(0xFF1B1714),
    surface = Color(0xFF1B1714),
    surfaceVariant = Color(0xFF2B2520),
    surfaceContainerLow = Color(0xFF221D19),
    surfaceContainer = Color(0xFF26211D),
    surfaceContainerHigh = Color(0xFF2E2823),
    surfaceContainerHighest = Color(0xFF342D27),
    outline = Color(0xFF6B5E52),
    error = Color(0xFFFF8A80),
)
