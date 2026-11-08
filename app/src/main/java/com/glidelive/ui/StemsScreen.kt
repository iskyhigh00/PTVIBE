package com.glidelive.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glidelive.stems.StemKind
import com.glidelive.stems.StemLibrary
import com.glidelive.stems.StemTrack
import com.glidelive.ui.components.GradientButton

private fun fmt(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Pistas: importar canciones (o sus stems), mezclar cada instrumento, repetir pasajes y tocar encima. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StemsScreen(vm: StemsViewModel, modifier: Modifier = Modifier) {
    val u by vm.ui.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pending by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            pending = uris
            title = StemLibrary.suggestTitle(uris.map { StemLibrary.displayName(ctx, it) })
        }
    }
    if (pending.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pending = emptyList() },
            title = { Text("Nueva canción") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Nombre") }, singleLine = true)
                    Text(
                        if (pending.size == 1) "1 archivo: se agrega como una sola pista (mezcla)."
                        else "${pending.size} archivos: cada uno será una pista (el nombre dice cuál es: voz, batería, bajo, guitarra…).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.import(pending, title); pending = emptyList() }) { Text("Importar") } },
            dismissButton = { TextButton(onClick = { pending = emptyList() }) { Text("Cancelar") } },
        )
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientButton("Importar canción o pistas", { picker.launch(arrayOf("*/*")) }, glyph = "+")
            Picker("Canción: " + (u.current?.title ?: "—"), u.songs, { it.title + "  (${it.tracks.size})" }) { vm.open(it) }
            u.current?.let { s ->
                TextButton(onClick = { vm.delete(s) }) { Text("Borrar") }
                TextButton(onClick = vm::close) { Text("Cerrar") }
            }
        }
        u.error?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::dismissError) { Text("OK") }
            }
        }
        u.loading?.let { p ->
            Text(u.loadingName, style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        val song = u.current
        if (song == null) {
            if (u.loading == null) Text(
                "Importa una canción para tocar encima: puedes elegir varios archivos a la vez (uno por instrumento: voz, batería, bajo, " +
                    "guitarra…) o un solo archivo con la mezcla. Cada pista tiene su volumen, mudo, solo y paneo.\n\n" +
                    "Para separar una canción en instrumentos puedes usar StemKit en el computador y traer aquí las pistas.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        // Transporte
        val clock by vm.clock.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientButton(if (clock.playing) "Pausa" else "Tocar", vm::togglePlay, glyph = if (clock.playing) "■" else "▶")
            Text(fmt(clock.pos), fontWeight = FontWeight.Bold)
            Slider(
                value = if (clock.dur > 0) (clock.pos / clock.dur).toFloat().coerceIn(0f, 1f) else 0f,
                onValueChange = { vm.seek(it * clock.dur) },
                modifier = Modifier.weight(1f),
            )
            Text(fmt(clock.dur))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FilterChip(selected = u.loopA >= 0, onClick = vm::markA, label = { Text(if (u.loopA >= 0) "A ${fmt(u.loopA)}" else "Marcar A") })
            FilterChip(selected = u.loopB >= 0, onClick = vm::markB, label = { Text(if (u.loopB >= 0) "B ${fmt(u.loopB)}" else "Marcar B") })
            if (u.loopA >= 0 || u.loopB >= 0) TextButton(onClick = vm::clearLoop) { Text("Quitar bucle") }
            Text("Velocidad", Modifier.padding(start = 8.dp, top = 8.dp), style = MaterialTheme.typography.labelMedium)
            for (s in listOf(0.5f, 0.75f, 1f, 1.25f)) {
                FilterChip(selected = u.speed == s, onClick = { vm.setSpeed(s) }, label = { Text("${(s * 100).toInt()}%") })
            }
        }
        Text("Con la velocidad distinta de 100% también cambia el tono.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Presets
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (p in StemPreset.entries) FilterChip(selected = false, onClick = { vm.preset(p) }, label = { Text(p.label) })
        }

        // Pistas
        song.tracks.forEachIndexed { i, t -> StemLane(t, onEdit = { f -> vm.editTrack(i, f) }) }
        LabeledSlider("Volumen general de las pistas", u.master, 0f..1.2f) { vm.setMaster(it) }
    }
}

@Composable
private fun StemLane(t: StemTrack, onEdit: ((StemTrack) -> StemTrack) -> Unit) {
    val color = Color(t.kind.color)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().background(color.copy(alpha = if (t.mute) 0.05f else 0.12f), shape).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Text(t.name, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp), maxLines = 1)
            Slider(value = t.volume, onValueChange = { v -> onEdit { it.copy(volume = v) } }, valueRange = 0f..1.5f, modifier = Modifier.weight(1f))
            FilterChip(selected = t.mute, onClick = { onEdit { it.copy(mute = !it.mute) } }, label = { Text("M") })
            FilterChip(selected = t.solo, onClick = { onEdit { it.copy(solo = !it.solo) } }, label = { Text("S") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Paneo", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(118.dp))
            Slider(value = t.pan, onValueChange = { v -> onEdit { it.copy(pan = if (kotlin.math.abs(v) < 0.08f) 0f else v) } },
                valueRange = -1f..1f, modifier = Modifier.weight(1f).height(24.dp))
            Text(if (t.pan == 0f) "C" else if (t.pan < 0) "I${(-t.pan * 100).toInt()}" else "D${(t.pan * 100).toInt()}",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
        }
    }
}

/** Franja para tocar encima desde cualquier modo: pausa, tiempo y cada pista con un toque para silenciarla. */
@Composable
fun StemStrip(vm: StemsViewModel, onOpen: () -> Unit) {
    val u by vm.ui.collectAsState()
    val song = u.current ?: return
    val clock by vm.clock.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextButton(onClick = vm::togglePlay) { Text(if (clock.playing) "■ Pausa" else "▶ Tocar") }
        Text("${song.title}  ${fmt(clock.pos)} / ${fmt(clock.dur)}", style = MaterialTheme.typography.labelMedium)
        song.tracks.forEachIndexed { i, t ->
            FilterChip(
                selected = !t.mute, onClick = { vm.editTrack(i) { it.copy(mute = !it.mute) } },
                label = { Text(t.name, maxLines = 1) },
            )
        }
        TextButton(onClick = onOpen) { Text("Pistas") }
    }
}
