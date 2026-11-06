package com.glidelive.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glidelive.ui.theme.AppTheme
import com.glidelive.ui.theme.DarkLightPreviews
import com.glidelive.ui.theme.Mint
import com.glidelive.ui.theme.Pink
import com.glidelive.ui.theme.Violet

val isDarkSurface: Boolean
    @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Tarjeta de vidrio: superficie translúcida, velo, degradado, borde degradado y sombra teñida. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 20.dp,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isDarkSurface
    val primary = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val elevation by animateDpAsState(if (pressed) 2.dp else 8.dp, spring(), label = "elevation")
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "scale",
    )
    val veil = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.02f)
    val borderEnd = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation, shape, ambientColor = primary.copy(alpha = 0.12f), spotColor = primary.copy(alpha = 0.15f))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            .background(veil)
            .background(Brush.verticalGradient(listOf(primary.copy(alpha = 0.14f), Color.Transparent)))
            .border(1.dp, Brush.linearGradient(listOf(primary.copy(alpha = 0.5f), borderEnd)), shape)
            .then(if (onClick != null) Modifier.clickable(interaction, null, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Fondo con manchas de luz difusas (violeta, rosa y menta) sobre el color base. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = isDarkSurface
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Canvas(Modifier.fillMaxSize()) {
            val a = if (dark) 1f else 0.5f
            fun blob(c: Color, center: Offset, r: Float) = drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = 0.20f * a), Color.Transparent), center, r), r, center,
            )
            blob(Violet, Offset(size.width * 0.12f, size.height * 0.05f), size.maxDimension * 0.55f)
            blob(Pink, Offset(size.width * 0.95f, size.height * 0.95f), size.maxDimension * 0.5f)
            blob(Mint, Offset(size.width * 0.75f, size.height * 0.1f), size.maxDimension * 0.3f)
        }
        content()
    }
}

@DarkLightPreviews
@Composable
private fun GlassCardPreview() = AppTheme {
    AppBackground {
        GlassCard(Modifier.padding(24.dp), onClick = {}) {
            Text("Tarjeta de vidrio", style = MaterialTheme.typography.titleMedium)
        }
    }
}
