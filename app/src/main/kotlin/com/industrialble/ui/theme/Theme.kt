package com.industrialble.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = IndustrialBlue80,
    onPrimary = IndustrialBlue10,
    primaryContainer = IndustrialBlue20,
    onPrimaryContainer = IndustrialBlue80,
    secondary = IndustrialTeal80,
    onSecondary = IndustrialBlue10,
    secondaryContainer = IndustrialTeal20,
    onSecondaryContainer = IndustrialTeal80,
    tertiary = IndustrialAmber80,
    onTertiary = IndustrialBlue10,
    tertiaryContainer = IndustrialAmber20,
    onTertiaryContainer = IndustrialAmber80,
    error = IndustrialRed80,
    onError = IndustrialRed20,
    errorContainer = IndustrialRed20,
    onErrorContainer = IndustrialRed80,
    background = IndustrialDarkBackground,
    onBackground = IndustrialBlue80,
    surface = IndustrialDarkSurface,
    onSurface = IndustrialCyan80,
    surfaceVariant = IndustrialDarkSurfaceVariant,
    onSurfaceVariant = IndustrialTeal80,
    outline = IndustrialDarkOutline,
    outlineVariant = IndustrialDarkOutlineVariant
)

private val LightColorScheme = lightColorScheme(
    primary = IndustrialBlue40,
    onPrimary = IndustrialLightBackground,
    primaryContainer = IndustrialBlue80,
    onPrimaryContainer = IndustrialBlue10,
    secondary = IndustrialTeal40,
    onSecondary = IndustrialLightBackground,
    secondaryContainer = IndustrialTeal80,
    onSecondaryContainer = IndustrialTeal20,
    tertiary = IndustrialAmber40,
    onTertiary = IndustrialLightBackground,
    tertiaryContainer = IndustrialAmber80,
    onTertiaryContainer = IndustrialAmber20,
    error = IndustrialRed40,
    onError = IndustrialLightBackground,
    errorContainer = IndustrialRed80,
    onErrorContainer = IndustrialRed20,
    background = IndustrialLightBackground,
    onBackground = IndustrialBlue20,
    surface = IndustrialLightSurface,
    onSurface = IndustrialBlue20,
    surfaceVariant = IndustrialLightSurfaceVariant,
    onSurfaceVariant = IndustrialTeal20,
    outline = IndustrialLightOutline,
    outlineVariant = IndustrialLightOutlineVariant
)

@Composable
fun IndustrialBLETheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
