package com.glidelive.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.glidelive.theory.NoteNames
import com.glidelive.theory.ScaleChoice
import com.glidelive.theory.SoloPitch

@Composable
fun SoloScreen(vm: SoloViewModel, modifier: Modifier = Modifier) {
    val u by vm.ui.collectAsState()
    val h by SharedHarmony.state.collectAsState()
    val cfg = SoloConfig(
        allowed = SoloPitch.allowed(h, u.followChord, u.scaleOverride),
        tonic = h.tonic,
        lock = u.lock,
        loMidi = u.loMidi,
        octaves = u.octaves,
        expression = u.expression,
        bendRange = u.bendRange,
        strings = u.tuning.strings,
    )
    Column(modifier) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SoloLayout.entries.forEach { l ->
                FilterChip(selected = u.layout == l, onClick = { vm.edit { it.copy(layout = l) } },
                    label = { Text(l.label) })
            }
            if (u.layout == SoloLayout.SURFACE) {
                Text("Arriba/abajo:", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Expression.entries.forEach { e ->
                    FilterChip(selected = u.expression == e, onClick = { vm.edit { it.copy(expression = e) } },
                        label = { Text(e.label) })
                }
            }
            Text("Imán de escala ${(u.lock * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = u.lock, onValueChange = { v -> vm.edit { it.copy(lock = v) } },
                modifier = Modifier.width(150.dp),
            )
            FilterChip(selected = u.followChord, onClick = { vm.edit { it.copy(followChord = !it.followChord) } },
                label = { Text("Seguir el acorde") })
        }
        AndroidView(
            factory = { ctx -> if (u.layout == SoloLayout.SURFACE) SoloSurfaceView(ctx) else FingerboardView(ctx) },
            update = { v -> (v as SoloTouchView).config = cfg },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

/** Si cambia el tipo de superficie hay que recrear la vista. */
@Composable
fun SoloHost(vm: SoloViewModel, modifier: Modifier = Modifier) {
    val u by vm.ui.collectAsState()
    androidx.compose.runtime.key(u.layout) { SoloScreen(vm, modifier) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoloPanel(vm: SoloViewModel) {
    val u by vm.ui.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Instrumento del solista", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SOLO_INSTRUMENTS.forEachIndexed { i, n ->
                FilterChip(selected = u.instrument == i, onClick = { vm.edit { it.copy(instrument = i) } },
                    label = { Text(n) })
            }
        }
        if (u.instrument == 6) {
            Text("Wah: con la expresión «Filtro», subir o bajar el dedo mueve el pedal.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LabeledSlider("Volumen del solista", u.volume, 0f..1f) { v -> vm.edit { it.copy(volume = v) } }
        Text("Suavidad del deslizamiento: ${u.smoothMs} ms", style = MaterialTheme.typography.labelMedium)
        Slider(value = u.smoothMs.toFloat(), valueRange = 1f..200f,
            onValueChange = { v -> vm.edit { it.copy(smoothMs = v.toInt()) } })

        Text("Notas permitidas", style = MaterialTheme.typography.labelLarge)
        Picker(u.scaleOverride?.label ?: "La escala de los acordes",
            listOf<ScaleChoice?>(null) + ScaleChoice.all, { it?.label ?: "La escala de los acordes" }) { s ->
            vm.edit { it.copy(scaleOverride = s) }
        }

        Text("Superficie: nota más grave ${NoteNames.midiName(u.loMidi)}", style = MaterialTheme.typography.labelMedium)
        Slider(value = u.loMidi.toFloat(), valueRange = 24f..72f,
            onValueChange = { v -> vm.edit { it.copy(loMidi = v.toInt()) } })
        Text("Superficie: octavas visibles", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (o in 2..4) FilterChip(selected = u.octaves == o, onClick = { vm.edit { it.copy(octaves = o) } },
                label = { Text("$o") })
        }

        Text("Diapasón: afinación", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Tuning.entries.forEach { t ->
                FilterChip(selected = u.tuning == t, onClick = { vm.edit { it.copy(tuning = t) } },
                    label = { Text(t.label) })
            }
        }
        Text("Diapasón: alcance del bend (empujar la cuerda): ${"%.1f".format(u.bendRange)} semitonos",
            style = MaterialTheme.typography.labelMedium)
        Slider(value = u.bendRange, valueRange = 0.5f..12f,
            onValueChange = { v -> vm.edit { it.copy(bendRange = v) } })
        Text(
            "Superficie: izquierda–derecha = altura; arriba–abajo = filtro, vibrato o volumen. " +
                "Diapasón: desliza por la cuerda; sube o baja el dedo para doblar la nota. " +
                "Un temblor leve del dedo añade vibrato.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
    }
}
