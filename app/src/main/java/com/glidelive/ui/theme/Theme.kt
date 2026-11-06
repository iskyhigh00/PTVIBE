package com.glidelive.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2060),
    onPrimaryContainer = Color(0xFFE4DCFF),
    secondary = Mint,
    onSecondary = Color(0xFF00241A),
    secondaryContainer = Color(0xFF0B3A2E),
    onSecondaryContainer = Color(0xFFBFF7E3),
    tertiary = Pink,
    onTertiary = Color(0xFF3A0A1D),
    background = Bg,
    onBackground = Color(0xFFF1F0FA),
    surface = SurfaceDark,
    onSurface = Color(0xFFF1F0FA),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceVariantDark,
    surfaceContainerHighest = SurfaceVariantDark,
    outline = Border.copy(alpha = 0.6f),
    outlineVariant = Border.copy(alpha = 0.3f),
    error = ErrorRed,
)

private val LightColors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DCFF),
    onPrimaryContainer = Color(0xFF1B0A66),
    secondary = Color(0xFF00A87A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF5E8),
    onSecondaryContainer = Color(0xFF00382A),
    tertiary = Pink,
    background = BgLight,
    onBackground = Color(0xFF14131F),
    surface = SurfaceLight,
    onSurface = Color(0xFF14131F),
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF5B6075),
    surfaceContainer = SurfaceLight,
    surfaceContainerHigh = SurfaceVariantLight,
    surfaceContainerHighest = SurfaceVariantLight,
    outline = Color(0xFFC9C6DD),
    error = Color(0xFFD64545),
)

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** Vista previa en tema oscuro y claro. */
@Preview(name = "Oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0A0A0F)
@Preview(name = "Claro", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF8F7FF)
annotation class DarkLightPreviews
