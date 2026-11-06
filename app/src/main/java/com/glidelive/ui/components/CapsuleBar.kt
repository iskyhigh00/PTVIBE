package com.glidelive.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glidelive.ui.theme.AppTheme
import com.glidelive.ui.theme.DarkLightPreviews

/** Barra de navegación en cápsula de vidrio; la pastilla del ítem activo se anima con resorte. */
@Composable
fun <T> CapsuleBar(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isDarkSurface
    val primary = MaterialTheme.colorScheme.primary
    val edge = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            .background(if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.02f))
            .border(1.dp, Brush.linearGradient(listOf(primary.copy(alpha = 0.5f), edge)), CircleShape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val sel = item == selected
            val bg by animateColorAsState(if (sel) primary else Color.Transparent, spring(), label = "pill")
            val fg by animateColorAsState(
                if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, spring(), label = "pillText",
            )
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(bg)
                    .selectable(selected = sel, role = Role.Tab, onClick = { onSelect(item) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(item), color = fg, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@DarkLightPreviews
@Composable
private fun CapsuleBarPreview() = AppTheme {
    AppBackground {
        CapsuleBar(listOf("Acordes", "Batería", "Mixto"), "Batería", { it }, {}, Modifier.padding(24.dp))
    }
}
