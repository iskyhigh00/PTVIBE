package com.glidelive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glidelive.drums.DrumKeys
import com.glidelive.ui.components.GradientButton
import com.glidelive.ui.components.TonalButton

/** Reloj maestro: Play/Stop, BPM, tap tempo, compás, metrónomo y grabación de bucles. */
@Composable
fun TransportBar(vm: TransportViewModel) {
    val u by vm.ui.collectAsState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        GradientButton(if (u.playing) "Parar" else "Tocar", vm::togglePlay, glyph = if (u.playing) "■" else "▶")
        TextButton(onClick = { vm.setBpm(u.bpm - 1) }) { Text("−") }
        Text("${u.bpm} BPM", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { vm.setBpm(u.bpm + 1) }) { Text("+") }
        TonalButton("Tap", vm::tap)
        Picker("Compás ${u.meter.label}", Meter.all, { it.label }) { m -> vm.edit { it.copy(meter = m) } }
        FilterChip(selected = u.metro, onClick = { vm.edit { it.copy(metro = !it.metro) } }, label = { Text("Metrónomo") })
        if (u.playing) Text("Compás ${u.bar} · tiempo ${u.beat}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilterChip(
            selected = u.recDrum, onClick = vm::toggleRecDrum,
            label = { Text(if (u.recDrum) "● Grabando batería (${u.drumEvents})" else "● Grabar batería") },
        )
        FilterChip(
            selected = u.recChord, onClick = vm::toggleRecChord,
            label = { Text(if (u.recChord) "● Grabando acordes (${u.chordEvents})" else "● Grabar acordes") },
        )
    }
}

/** Bases de batería: elegir base, variación (A/B/C), relleno, final, silenciar por instrumento. */
@Composable
fun PatternBar(vm: TransportViewModel) {
    val u by vm.ui.collectAsState()
    val p = u.pattern
    var muteMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Picker("Base: " + (p?.name ?: "—"), u.patterns, { (if (it.factory) "" else "★ ") + it.name + "  (${it.meter})" }) {
            vm.selectPattern(it)
        }
        p?.variations?.forEachIndexed { i, v ->
            FilterChip(selected = u.variation == i && !u.fillOn, onClick = { vm.selectVariation(i) }, label = { Text("Variación ${v.name}") })
        }
        FilterChip(selected = u.fillOn, onClick = vm::fill, label = { Text("Relleno") })
        FilterChip(selected = u.endPending, onClick = vm::finish, label = { Text("Final") })
        GradientButton(if (u.patternOn) "Base" else "Base", vm::togglePattern, glyph = if (u.patternOn) "■" else "▶")
        TextButton(onClick = { muteMenu = true }) {
            Text(if (u.muted.isNotEmpty() || u.solo != null) "Silenciar ●" else "Silenciar…")
        }
        DropdownMenu(expanded = muteMenu, onDismissRequest = { muteMenu = false }) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                DrumKeys.labels.forEachIndexed { i, name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.width(120.dp))
                        FilterChip(selected = i in u.muted, onClick = { vm.toggleMute(i) }, label = { Text("Mudo") })
                        Box(Modifier.width(4.dp))
                        FilterChip(selected = u.solo == i, onClick = { vm.toggleSolo(i) }, label = { Text("Solo") })
                    }
                }
            }
        }
        TextButton(onClick = { p?.let(vm::openEditor) }) { Text(if (p?.factory == false) "Editar base" else "Duplicar y editar") }
        if (p != null && !p.factory) TextButton(onClick = { vm.deleteUserPattern(p.id) }) { Text("Borrar base") }
        if (p != null) Text("${p.meter} · ${p.bpmMin}–${p.bpmMax} BPM · inspirada en el estilo de Gavin Harrison",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (u.editor != null) SequencerDialog(vm)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoopsPanel(vm: TransportViewModel) {
    val u by vm.ui.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Reloj", style = MaterialTheme.typography.titleSmall)
        LabeledSlider("Swing (ritmo bamboleado)", u.swing, 0f..0.5f) { v -> vm.edit { it.copy(swing = v) } }
        LabeledSlider("Volumen del metrónomo", u.metroVol, 0f..1f) { v -> vm.edit { it.copy(metroVol = v) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Adoptar tempo y compás de la base")
            androidx.compose.material3.Switch(checked = u.adoptPattern, onCheckedChange = { v -> vm.edit { it.copy(adoptPattern = v) } })
        }

        Text("Grabar batería: ajustar a la rejilla", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RecQuant.entries.forEach { q ->
                FilterChip(selected = u.recQuant == q, onClick = { vm.edit { it.copy(recQuant = q) } }, label = { Text(q.label) })
            }
        }
        Text("Bucle de batería: ${u.drumBars} × ${u.drumMeter.label}", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Picker("Compás", Meter.all, { it.label }) { m -> vm.setDrumLoop(u.drumBars, m) }
            for (b in listOf(1, 2, 4, 8)) FilterChip(selected = u.drumBars == b, onClick = { vm.setDrumLoop(b, u.drumMeter) }, label = { Text("$b") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TonalButton("Deshacer pase", vm::undoDrum)
            TonalButton("Borrar", vm::clearDrum)
            FilterChip(selected = u.drumLoopOn, onClick = vm::toggleDrumLoop, label = { Text(if (u.drumLoopOn) "Suena" else "Mudo") })
        }

        Text("Acordes: cambio de acorde", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ChordQuant.entries.forEach { q ->
                FilterChip(selected = u.chordQuant == q, onClick = { vm.edit { it.copy(chordQuant = q) } }, label = { Text(q.label) })
            }
        }
        Text("Bucle de acordes: ${u.chordBars} × ${u.chordMeter.label}", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Picker("Compás", Meter.all, { it.label }) { m -> vm.setChordLoop(u.chordBars, m) }
            for (b in listOf(1, 2, 4, 8)) FilterChip(selected = u.chordBars == b, onClick = { vm.setChordLoop(b, u.chordMeter) }, label = { Text("$b") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TonalButton("Deshacer pase", vm::undoChord)
            TonalButton("Borrar", vm::clearChord)
            FilterChip(selected = u.chordLoopOn, onClick = vm::toggleChordLoop, label = { Text(if (u.chordLoopOn) "Suena" else "Mudo") })
        }
        Text(
            "Los bucles de batería y de acordes tienen largos independientes: por ejemplo batería en 7/8 sobre acordes en 4/4.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val UNITS = listOf(48 to "Corcheas", 24 to "Semicorcheas", 32 to "Tresillos", 12 to "Fusas")

/** Secuenciador por pasos: longitud libre, compases irregulares, A/B/C, relleno y final. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SequencerDialog(vm: TransportViewModel) {
    val u by vm.ui.collectAsState()
    val ed = u.editor ?: return
    val p = ed.pattern
    Dialog(onDismissRequest = vm::closeEditor, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(8.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    BasicTextField(
                        value = p.name, onValueChange = vm::editorName, singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = MaterialTheme.typography.titleMedium.fontSize),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.width(240.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(10.dp),
                    )
                    EditPart.entries.forEachIndexed { i, part ->
                        if (i < 3 && i >= p.variations.size) return@forEachIndexed
                        FilterChip(selected = ed.part == i, onClick = { vm.editorPart(i) }, label = { Text(part.label) })
                    }
                    if (p.variations.size < 3) TextButton(onClick = vm::editorAddVariation) { Text("+ variación") }
                    TonalButton("Probar", vm::editorTry)
                    TonalButton("Guardar", vm::editorSave)
                    TextButton(onClick = vm::closeEditor) { Text("Cerrar") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Text("Pasos por compás: ${p.barSteps}")
                    TextButton(onClick = { vm.editorResize(p.barSteps - 1, p.bars) }) { Text("−") }
                    TextButton(onClick = { vm.editorResize(p.barSteps + 1, p.bars) }) { Text("+") }
                    Text("Compases: ${p.bars}")
                    TextButton(onClick = { vm.editorResize(p.barSteps, p.bars - 1) }) { Text("−") }
                    TextButton(onClick = { vm.editorResize(p.barSteps, p.bars + 1) }) { Text("+") }
                    UNITS.forEach { (t, name) ->
                        FilterChip(selected = p.unit == t, onClick = { vm.editorUnit(t) }, label = { Text(name) })
                    }
                }
                val part = ed.part
                val len = if (part >= 3) p.barSteps else p.steps
                val tracks = when (part) {
                    0, 1, 2 -> p.variations[part.coerceAtMost(p.variations.size - 1)].tracks
                    3 -> p.fill
                    else -> p.end
                }
                Box(Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState())) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        DrumKeys.keys.forEachIndexed { r, key ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(DrumKeys.labels[r], Modifier.width(110.dp), style = MaterialTheme.typography.labelMedium)
                                val str = (tracks[key] ?: "").padEnd(len, '.')
                                for (i in 0 until len) {
                                    val c = str[i]
                                    val strong = i % p.barSteps == 0
                                    Box(
                                        Modifier.padding(1.dp).size(30.dp)
                                            .background(
                                                when (c) {
                                                    'X' -> MaterialTheme.colorScheme.primary
                                                    'x' -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                                    'o' -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
                                                    'g' -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                                                    else -> if (strong) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                                },
                                                RoundedCornerShape(8.dp),
                                            )
                                            .clickable { vm.editorToggle(key, i) },
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Toca una celda: acento (fuerte), normal, suave, ghost (muy suave) o vacía.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
