package com.tote.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Tote's branded Material3 color scheme.
 *
 * Two layers:
 * 1. **Branded base** — Tote orange (#FF5722) primary, deep teal (#00695C)
 *    secondary. Applied on Android < 12 (no dynamic color) and is the
 *    fallback when dynamic color is disabled.
 * 2. **Material You dynamic color** — on Android 12+ we overlay the user's
 *    wallpaper-derived palette via [dynamicLightColorScheme] /
 *    [dynamicDarkColorScheme]. Per Material3 guidance the user's wallpaper
 *    wins over our brand palette when present.
 *
 * Per ADR-018 (branding): the orange/teal pair carries through the launcher
 * icon, the profile pill colors, and the Send/Pair icon buttons.
 */

// Branded Tote orange / deep teal palette
private val ToteOrange = Color(0xFFFF5722)
private val ToteOrangeContainer = Color(0xFFE64A19)
private val ToteTeal = Color(0xFF00695C)
private val ToteTealLight = Color(0xFF4DB6AC)
private val ToteError = Color(0xFFB00020)
private val ToteBackground = Color(0xFFFAFAFA)
private val ToteSurface = Color(0xFFFFFFFF)
private val ToteOnBackground = Color(0xFF1A1A1A)

private val ToteOrangeDark = Color(0xFFFF7043)
private val ToteTealDark = Color(0xFF80CBC4)
private val ToteBackgroundDark = Color(0xFF121212)
private val ToteSurfaceDark = Color(0xFF1E1E1E)
private val ToteOnBackgroundDark = Color(0xFFFAFAFA)

private val LightColors = lightColorScheme(
    primary = ToteOrange,
    onPrimary = Color.White,
    primaryContainer = ToteOrangeContainer,
    onPrimaryContainer = Color.White,
    secondary = ToteTeal,
    onSecondary = Color.White,
    secondaryContainer = ToteTealLight,
    onSecondaryContainer = Color.Black,
    background = ToteBackground,
    onBackground = ToteOnBackground,
    surface = ToteSurface,
    onSurface = ToteOnBackground,
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF424242),
    error = ToteError,
    onError = Color.White,
    outline = Color(0xFFBDBDBD),
)

private val DarkColors = darkColorScheme(
    primary = ToteOrangeDark,
    onPrimary = Color.Black,
    primaryContainer = ToteOrange,
    onPrimaryContainer = Color.White,
    secondary = ToteTealDark,
    onSecondary = Color.Black,
    secondaryContainer = ToteTeal,
    onSecondaryContainer = Color.White,
    background = ToteBackgroundDark,
    onBackground = ToteOnBackgroundDark,
    surface = ToteSurfaceDark,
    onSurface = ToteOnBackgroundDark,
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFBDBDBD),
    error = Color(0xFFCF6679),
    onError = Color.Black,
    outline = Color(0xFF616161),
)

/**
 * The Tote Material3 theme. Use as the root of every Compose hierarchy:
 *
 * ```
 * ToteTheme {
 *     // screen content
 * }
 * ```
 *
 * @param dynamicColor Whether to use Material You dynamic color (default true on Android 12+).
 * @param darkTheme Whether to use dark theme (defaults to system setting).
 */
@Composable
fun ToteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Material You: Android 12+ only. Wallpaper-derived palette supersedes brand.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ToteTypography,
        shapes = ToteShapes,
        content = content,
    )
}
