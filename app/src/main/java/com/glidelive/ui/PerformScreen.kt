package com.glidelive.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.glidelive.theory.SoloPitch
import com.glidelive.ui.components.GradientButton

/**
 * Varias superficies tocables a la vez dentro de un mismo contenedor multitouch ([PerformHost]).
 * Cada dedo va a la superficie sobre la que empezó (acordes, solista o batería).
 * La edición de acordes ("Editar") queda solo en el modo Acordes.
 */
@Composable
fun PerformScreen(
    chordsVm: ChordsViewModel,
    soloVm: SoloViewModel,
    pressure: Boolean,
    viz: Boolean,
    chords: Boolean,
    solo: Boolean,
    drums: Boolean,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
    showSoloControls: Boolean = solo,
) {
    val c by chordsVm.ui.collectAsState()
    val s by soloVm.ui.collectAsState()
    val h by SharedHarmony.state.collectAsState()

    val cfg = SoloConfig(
        allowed = SoloPitch.allowed(h, s.followChord, s.scaleOverride),
        tonic = h.tonic, lock = s.lock, loMidi = s.loMidi, octaves = s.octaves,
        expression = s.expression, bendRange = s.bendRange, strings = s.tuning.strings,
    )
    val padRows = c.rows
    val rows = remember(padRows, c.edits) {
        padRows.map { row -> row.map { PadUi(it.id, c.chordOf(it).name(), it.roman + " · " + c.chordOf(it).type.plain) } }
    }

    Column(modifier) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showSoloControls) {
                SoloLayout.entries.forEach { l ->
                    FilterChip(selected = s.layout == l, onClick = { soloVm.edit { it.copy(layout = l) } }, label = { Text(l.label) })
                }
                if (s.layout == SoloLayout.SURFACE) {
                    Text("Arriba/abajo:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Expression.entries.forEach { e ->
                        FilterChip(selected = s.expression == e, onClick = { soloVm.edit { it.copy(expression = e) } }, label = { Text(e.label) })
                    }
                }
                Text("Imán ${(s.lock * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(value = s.lock, onValueChange = { v -> soloVm.edit { it.copy(lock = v) } }, modifier = Modifier.width(130.dp))
                FilterChip(selected = s.followChord, onClick = { soloVm.edit { it.copy(followChord = !it.followChord) } }, label = { Text("Seguir el acorde") })
            }
            Text("Deslizar ${c.glideMs} ms", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = c.glideMs.toFloat(), onValueChange = { v -> chordsVm.edit { it.copy(glideMs = v.toInt()) } },
                valueRange = 0f..2000f, modifier = Modifier.width(130.dp),
            )
            GradientButton("Silenciar", chordsVm::stopAll, glyph = "■")
        }
        AndroidView(
            factory = { ctx -> PerformHost(ctx) },
            update = { host ->
                host.configure(viz, chords, solo, drums, horizontal)
                if (chords) {
                    host.chords.onDown = chordsVm::padDown
                    host.chords.onUp = chordsVm::padUp
                    host.chords.editMode = false
                    host.chords.setData(rows, c.activeId, c.edits.keys)
                }
                if (solo) host.setSolo(s.layout, cfg)
                if (drums) host.drums.pressureSensitivity = pressure
            },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}
