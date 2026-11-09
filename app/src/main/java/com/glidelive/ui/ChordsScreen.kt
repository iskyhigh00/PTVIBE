package com.glidelive.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.glidelive.audio.ExitMode
import com.glidelive.theory.Bank
import com.glidelive.viz.VizPrefs
import com.glidelive.viz.VizTheme
import com.glidelive.ui.components.GradientButton
import com.glidelive.theory.ChordType
import com.glidelive.theory.NoteNames
import com.glidelive.theory.Scale

@Composable
fun <T> Picker(label: String, items: List<T>, text: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(label) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        items.forEach { item ->
            DropdownMenuItem(text = { Text(text(item)) }, onClick = { open = false; onPick(item) })
        }
    }
}

/** Controles de tonalidad: tonalidad, escala, banco y transposición. */
@Composable
fun KeyControls(vm: ChordsViewModel) {
    val u by vm.ui.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Picker("Tonalidad: ${NoteNames.name(u.key)}", (0..11).toList(), { NoteNames.name(it) }) { k ->
            vm.edit { it.copy(key = k) }
        }
        Picker("Escala: ${u.scale.label}", Scale.entries, { it.label }) { s -> vm.edit { it.copy(scale = s) } }
        Picker("Acordes: ${u.bank.label}", Bank.entries, { it.label }) { b -> vm.edit { it.copy(bank = b) } }
        TextButton(onClick = { vm.edit { it.copy(transpose = (it.transpose - 1).coerceAtLeast(-12)) } }) { Text("−") }
        Text("Subir/bajar %+d".format(u.transpose))
        TextButton(onClick = { vm.edit { it.copy(transpose = (it.transpose + 1).coerceAtMost(12)) } }) { Text("+") }
    }
}

@Composable
fun ChordsScreen(vm: ChordsViewModel, compact: Boolean, modifier: Modifier = Modifier) {
    val u by vm.ui.collectAsState()
    Column(modifier) {
        if (!compact && u.bank == Bank.DIATONIC) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Variante:", Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(selected = u.quality == null, onClick = { vm.edit { it.copy(quality = null) } },
                    label = { Text("Normal") })
                ChordType.extensions.forEach { t ->
                    FilterChip(selected = u.quality == t, onClick = { vm.edit { it.copy(quality = t) } },
                        label = { Text(t.short) })
                }
            }
        }

        val allRows = u.rows
        // Compacto (Voces / Mixto): con más de dos filas se muestra una sola, más grande, y se elige cuál.
        var rowSel by rememberSaveable { mutableIntStateOf(1) }
        val oneRow = compact && (allRows.size > 2 || (u.stairs && allRows.size > 1))
        val padRows = if (oneRow) listOf(allRows[rowSel.coerceIn(0, allRows.size - 1)]) else allRows
        val rows = remember(padRows, u.edits) {
            padRows.map { row -> row.map { PadUi(it.id, u.chordOf(it).name(), it.roman + " · " + u.chordOf(it).type.plain) } }
        }
        val edited = u.edits.keys
        val pending by vm.pending.collectAsState()
        Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = 64.dp)) {
        AndroidView(
            factory = { ChordPadsView(it) },
            update = {
                it.onDown = vm::padDown
                it.onUp = vm::padUp
                it.onEdit = vm::openEditor
                it.editMode = u.editMode
                it.stairs = u.stairs && oneRow
                it.setData(rows, u.activeId, edited)
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (pending) {
            Text("acorde en espera…", Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 2.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (oneRow) {
                val names = if (u.bank == Bank.DIATONIC) listOf("Tríadas", "Séptimas", "Prestados")
                    else List(allRows.size) { "Fila ${it + 1}" }
                names.forEachIndexed { i, n ->
                    FilterChip(selected = rowSel == i, onClick = { rowSel = i }, label = { Text(n) })
                }
            }
            if (compact) FilterChip(selected = u.stairs, onClick = { vm.edit { it.copy(stairs = !it.stairs) } }, label = { Text("Escalera") })
            Text("Deslizar ${u.glideMs} ms", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = u.glideMs.toFloat(),
                onValueChange = { v -> vm.edit { it.copy(glideMs = v.toInt()) } },
                valueRange = 0f..2000f,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = u.editMode,
                onClick = { vm.edit { it.copy(editMode = !it.editMode) } },
                label = { Text("✎ Editar acordes") },
            )
            GradientButton("Silenciar", { vm.stopAll() }, glyph = "■")
        }
        if (!compact) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Curva:", Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GlideCurve.entries.forEach { c ->
                    FilterChip(selected = u.curve == c, onClick = { vm.edit { it.copy(curve = c) } },
                        label = { Text(c.label) })
                }
                Text("Al tocar:", Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TriggerMode.entries.forEach { t ->
                    FilterChip(selected = u.trigger == t, onClick = { vm.edit { it.copy(trigger = t) } },
                        label = { Text(t.label) })
                }
                Text("Salida:", Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ExitMode.entries.forEach { m ->
                    FilterChip(selected = u.exit == m, onClick = { vm.edit { it.copy(exit = m) } },
                        label = { Text(m.label) })
                }
            }
        }
    }
    if (u.editing != null) PadEditDialog(vm)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PadEditDialog(vm: ChordsViewModel) {
    val u by vm.ui.collectAsState()
    val id = u.editing ?: return
    val def = u.rows.flatten().firstOrNull { it.id == id } ?: run { vm.closeEditor(); return }
    val edit = u.edits[id] ?: PadEdit()
    val chord = u.chordOf(def)
    val preview = vm.preview(id)

    AlertDialog(
        onDismissRequest = vm::closeEditor,
        confirmButton = { TextButton(onClick = vm::closeEditor) { Text("Cerrar") } },
        dismissButton = {
            TextButton(onClick = { vm.updateEdit(id) { PadEdit() } }) { Text("Restablecer") }
        },
        title = { Text("Editar ${chord.name()}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tipo de acorde", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChordType.entries.forEach { t ->
                        FilterChip(
                            selected = chord.type == t,
                            onClick = { vm.updateEdit(id) { it.copy(type = t, fixed = emptyMap(), inversion = 0) } },
                            label = { Text(t.friendly) },
                        )
                    }
                }
                Text("Inversión (qué nota va en el bajo)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until minOf(chord.tones.size, 4)) {
                        FilterChip(selected = edit.inversion == i,
                            onClick = { vm.updateEdit(id) { it.copy(inversion = i) } },
                            label = { Text(when (i) { 0 -> "Base"; 1 -> "1ª"; 2 -> "2ª"; else -> "3ª" }) })
                    }
                }
                Text("Altura del acorde", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (o in -1..1) {
                        FilterChip(selected = edit.octave == o,
                            onClick = { vm.updateEdit(id) { it.copy(octave = o) } },
                            label = { Text(when (o) { -1 -> "Más grave"; 0 -> "Normal"; else -> "Más agudo" }) })
                    }
                }
                Text("Notas: ajusta cada una; el candado la deja fija", style = MaterialTheme.typography.labelLarge)
                preview?.let { (notes, pcs) ->
                    notes.forEachIndexed { i, note ->
                        val pc = pcs[i]
                        val locked = edit.fixed.containsKey(pc)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(NoteNames.midiName(note), Modifier.padding(end = 8.dp))
                            TextButton(onClick = { vm.updateEdit(id) { it.copy(fixed = it.fixed + (pc to note - 12)) } }) { Text("−oct") }
                            TextButton(onClick = { vm.updateEdit(id) { it.copy(fixed = it.fixed + (pc to note - 1)) } }) { Text("−½") }
                            TextButton(onClick = { vm.updateEdit(id) { it.copy(fixed = it.fixed + (pc to note + 1)) } }) { Text("+½") }
                            TextButton(onClick = { vm.updateEdit(id) { it.copy(fixed = it.fixed + (pc to note + 12)) } }) { Text("+oct") }
                            TextButton(onClick = {
                                vm.updateEdit(id) { it.copy(fixed = if (locked) it.fixed - pc else it.fixed + (pc to note)) }
                            }) { Text(if (locked) "🔒" else "🔓") }
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundPanel(vm: ChordsViewModel) {
    val u by vm.ui.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val vizTheme by VizPrefs.theme.collectAsState()
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Text("Estilo de las voces (pestaña Voces)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            VizTheme.entries.forEach { t ->
                FilterChip(selected = vizTheme == t, onClick = { VizPrefs.set(ctx, t) }, label = { Text(t.label) })
            }
        }
        Text("Instrumento", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            INSTRUMENTS.forEachIndexed { i, n ->
                FilterChip(selected = u.instrument == i, onClick = { vm.edit { it.copy(instrument = i) } },
                    label = { Text(n) })
            }
        }
        LabeledSlider("Volumen de acordes", u.volume, 0f..1f) { v -> vm.edit { it.copy(volume = v) } }
        LabeledSlider("Brillo (filtro)", u.cutoff, 0f..1f) { v -> vm.edit { it.copy(cutoff = v) } }
        LabeledSlider("Énfasis del filtro", u.resonance, 0f..1f) { v -> vm.edit { it.copy(resonance = v) } }
        LabeledSlider("Chorus (sonido más ancho)", u.chorus, 0f..1f) { v -> vm.edit { it.copy(chorus = v) } }
        LabeledSlider("Reverb (efecto de espacio)", u.room, 0f..1f) { v -> vm.edit { it.copy(room = v) } }
        Text("Cola de salida: ${u.tailMs} ms", style = MaterialTheme.typography.labelMedium)
        Slider(value = u.tailMs.toFloat(), valueRange = 200f..4000f,
            onValueChange = { v -> vm.edit { it.copy(tailMs = v.toInt()) } })
        Text("Desvanecer al final: ${"%.1f".format(u.fadeMs / 1000f)} s", style = MaterialTheme.typography.labelMedium)
        Slider(value = u.fadeMs.toFloat(), valueRange = 500f..8000f,
            onValueChange = { v -> vm.edit { it.copy(fadeMs = v.toInt()) } })
        Text("Rango de notas: ${NoteNames.midiName(u.rangeLo)} – ${NoteNames.midiName(u.rangeHi)}",
            style = MaterialTheme.typography.labelMedium)
        Slider(value = u.rangeLo.toFloat(), valueRange = 28f..60f,
            onValueChange = { v -> vm.edit { it.copy(rangeLo = v.toInt().coerceAtMost(it.rangeHi - 12)) } })
        Slider(value = u.rangeHi.toFloat(), valueRange = 60f..96f,
            onValueChange = { v -> vm.edit { it.copy(rangeHi = v.toInt().coerceAtLeast(it.rangeLo + 12)) } })
        Text("Pulsa «Editar acordes» y toca un pad para cambiarlo. «½» = medio tono, «oct» = una octava.", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}
