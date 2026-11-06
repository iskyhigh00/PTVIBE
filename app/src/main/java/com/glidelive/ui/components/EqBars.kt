package com.glidelive.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glidelive.ui.theme.AppTheme
import com.glidelive.ui.theme.DarkLightPreviews
import com.glidelive.ui.theme.Mint
import com.glidelive.ui.theme.Pink
import com.glidelive.ui.theme.Violet
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Barras ecualizadoras en degradado rosa → violeta → menta que reaccionan al nivel de salida.
 * Solo anima (un fotograma por vsync) mientras suena algo; en silencio dibuja barras mínimas estáticas.
 */
@Composable
fun EqBars(level: () -> Float, modifier: Modifier = Modifier.size(width = 72.dp, height = 28.dp), bars: Int = 10) {
    var phase by remember { mutableFloatStateOf(0f) }
    var lv by remember { mutableFloatStateOf(0f) }
    val currentLevel by rememberUpdatedState(level)
    LaunchedEffect(Unit) {
        while (true) {
            val l = currentLevel()
            if (l > 0.01f) {
                lv = sqrt(l.coerceIn(0f, 1f))
                withFrameNanos { ns -> phase = ((ns / 1_000_000_000.0 * (TWO_PI / 1.8)) % TWO_PI).toFloat() }
            } else {
                if (lv != 0f) lv = 0f
                delay(100)
            }
        }
    }
    // El degradado es relativo al tamaño de la barra (endY infinito): se crea una sola vez.
    val brush = remember { Brush.verticalGradient(listOf(Pink, Violet, Mint)) }
    Canvas(modifier.graphicsLayer { }.semantics { contentDescription = "Visualizador del nivel de audio" }) {
        val slot = size.width / bars
        val bw = slot * 0.62f
        val corner = CornerRadius(bw / 2)
        val t = phase
        val l = lv
        for (i in 0 until bars) {
            val wob = if (l > 0f) 0.45f + 0.55f * abs(sin(t * (1 + (i % 3) * 0.5f) + i * 0.7f)) else 0f
            val h = size.height * (0.10f + 0.90f * l * wob)
            drawRoundRect(
                brush,
                topLeft = Offset(i * slot + (slot - bw) / 2, size.height - h),
                size = Size(bw, h),
                cornerRadius = corner,
            )
        }
    }
}

private const val TWO_PI = 6.283185307179586

@DarkLightPreviews
@Composable
private fun EqBarsPreview() = AppTheme {
    AppBackground { EqBars({ 0.8f }, Modifier.padding(24.dp).size(120.dp, 48.dp)) }
}
