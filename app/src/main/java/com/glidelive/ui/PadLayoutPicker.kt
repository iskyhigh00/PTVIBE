package com.glidelive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val PINK = Color(0xFFFF6B9D)
private val GOLD = Color(0xFFFFC857)
private val MINT = Color(0xFF00E5A0)
private val VIOLET = Color(0xFF6B4EFF)

private fun padColor(p: Int) = when {
    Pads.isPercussion(p) -> Color(0xFFFF8A3D)
    Pads.isCymbal(p) -> GOLD
    Pads.isHat(p) -> MINT
    p == Pads.KICK -> PINK
    else -> VIOLET
}

/** Vista previa de cada distribución de pads; tocar una la aplica al instante. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PadLayoutPicker(selected: PadLayout, onSelect: (PadLayout) -> Unit) {
    Text("Distribución de los pads", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PadLayout.entries.forEach { l ->
            val on = l == selected
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier
                    .width(124.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (on) 0.9f else 0.45f), shape)
                    .border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
                    .clickable { onSelect(l) }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.fillMaxWidth().height(62.dp).semantics { contentDescription = "Vista previa: ${l.label}" }) {
                    val cw = size.width / l.cols
                    val ch = size.height / l.rows
                    val g = 1.5.dp.toPx()
                    for (p in 0 until Pads.COUNT) {
                        val c = l.cells[p] ?: continue
                        drawRoundRect(
                            padColor(p).copy(alpha = 0.85f),
                            topLeft = Offset(c[0] * cw + g, c[1] * ch + g),
                            size = Size(c[2] * cw - 2 * g, c[3] * ch - 2 * g),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                        )
                    }
                }
                Text(l.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                Text(l.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
