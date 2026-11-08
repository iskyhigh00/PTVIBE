package com.glidelive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glidelive.theory.NoteNames
import kotlinx.coroutines.flow.StateFlow

private fun keyName(code: Int): String = when (code) {
    62 -> "Espacio"
    else -> android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * Asignar teclas a pads. Es una capa sobre la pantalla (no una ventana aparte) para que el teclado del teléfono
 * llegue a la app mientras se asigna: se aprieta "Asignar" y se toca una tecla del teclado MIDI o del teclado físico.
 */
@Composable
fun PadKeysPanel(midiStatus: StateFlow<String>, onClose: () -> Unit) {
    val s by PadBindings.state.collectAsState()
    val status by midiStatus.collectAsState()
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(playInsets).padding(8.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Teclas de los pads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { PadBindings.reset() }) { Text("Restaurar") }
            TextButton(onClick = onClose) { Text("Cerrar") }
        }
        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Teclado MIDI toca:", style = MaterialTheme.typography.labelMedium)
            FilterChip(selected = s.midiToPads, onClick = { PadBindings.setMidiToPads(true) }, label = { Text("Los pads") })
            FilterChip(selected = !s.midiToPads, onClick = { PadBindings.setMidiToPads(false) }, label = { Text("El solista") })
        }
        Text(
            "Aprieta «Asignar» en un pad y toca la tecla que quieras: una tecla del teclado MIDI o del teclado del teléfono (USB o " +
                "Bluetooth). Un pad puede tener varias teclas. El teclado del teléfono toca los pads en Batería y Mixto.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (pad in 0 until Pads.TOTAL) {
                val learning = s.learnPad == pad
                val notes = s.midi.filterValues { it == pad }.keys.sorted()
                val keys = s.keys.filterValues { it == pad }.keys.sorted()
                Row(
                    Modifier.fillMaxWidth().background(
                        if (learning) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(10.dp),
                    ).padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(Pads.names[pad].replace("\n", " "), fontWeight = FontWeight.Bold, modifier = Modifier.width(170.dp), maxLines = 1)
                    Text(
                        if (learning) "Toca una tecla…" else buildString {
                            append(if (notes.isEmpty()) "MIDI: —" else "MIDI: " + notes.joinToString(", ") { NoteNames.midiName(it, true) })
                            append("   Teclado: ")
                            append(if (keys.isEmpty()) "—" else keys.joinToString(", ") { keyName(it) })
                        },
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2,
                    )
                    TextButton(onClick = { PadBindings.startLearn(if (learning) null else pad) }) { Text(if (learning) "Cancelar" else "Asignar") }
                    TextButton(onClick = { PadBindings.clear(pad) }) { Text("Borrar") }
                }
            }
        }
    }
}
