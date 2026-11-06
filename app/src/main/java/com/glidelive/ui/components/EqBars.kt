package com.glidelive.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/** Barras ecualizadoras en degradado rosa → violeta → menta que reaccionan al nivel de salida. */
@Composable
fun EqBars(level: () -> Float, modifier: Modifier = Modifier.size(width = 72.dp, height = 28.dp), bars: Int = 10) {
    val t by rememberInfiniteTransition(label = "eq").animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "eqT",
    )
    Canvas(modifier.semantics { contentDescription = "Visualizador del nivel de audio" }) {
        val lv = sqrt(level().coerceIn(0f, 1f))
        val slot = size.width / bars
        val bw = slot * 0.62f
        val brush = Brush.verticalGradient(listOf(Pink, Violet, Mint), startY = 0f, endY = size.height)
        for (i in 0 until bars) {
            val wob = 0.45f + 0.55f * abs(sin(t * (1 + (i % 3) * 0.5f) + i * 0.7f))
            val h = size.height * (0.10f + 0.90f * lv * wob)
            drawRoundRect(
                brush,
                topLeft = Offset(i * slot + (slot - bw) / 2, size.height - h),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2),
            )
        }
    }
}

@DarkLightPreviews
@Composable
private fun EqBarsPreview() = AppTheme {
    AppBackground { EqBars({ 0.8f }, Modifier.padding(24.dp).size(120.dp, 48.dp)) }
}
