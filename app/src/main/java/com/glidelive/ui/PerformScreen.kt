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
    padLayout: PadLayout = PadLayout.KIT,
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
                if (drums) { host.drums.pressureSensitivity = pressure; host.drums.padLayout = padLayout }
            },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}
