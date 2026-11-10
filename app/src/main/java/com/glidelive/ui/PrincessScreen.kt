package com.glidelive.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glidelive.princess.Character
import com.glidelive.princess.Cue
import com.glidelive.princess.CueMode
import com.glidelive.princess.CueType
import com.glidelive.princess.DramaRenderer
import com.glidelive.ui.components.GradientButton

private fun fmtTime(sec: Double): String {
    val s = sec.coerceAtLeast(0.0)
    return "%d:%02d".format(s.toInt() / 60, s.toInt() % 60) + if (s < 60) ".%d".format(((s - s.toInt()) * 10).toInt()) else ""
}

private fun secs(v: Float) = "%.1f s".format(v)

/** Princess: la pista de audio de una obra dramática. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrincessScreen(vm: PrincessViewModel, modifier: Modifier = Modifier) {
    val u by vm.ui.collectAsState()
    val clock by vm.clock.collectAsState()
    val ctx = LocalContext.current
    var newProject by remember { mutableStateOf(false) }
    var newCharacter by remember { mutableStateOf(false) }
    var editingCharacter by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<Pair<CueType, String?>?>(null) }
    var pendingRecord by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val req = pendingImport
        if (uri != null && req != null) vm.importAudio(uri, req.first, req.second)
        pendingImport = null
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val who = pendingRecord
        if (granted && who != null) vm.startRecording(who)
        pendingRecord = null
    }
    fun record(characterId: String) {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording(characterId)
        else { pendingRecord = characterId; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    if (newProject || u.current == null) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (u.current != null) newProject = false },
            title = { Text("Nueva obra") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre de la obra") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.newProject(name); newProject = false }) { Text("Crear") } },
            dismissButton = { if (u.current != null) TextButton(onClick = { newProject = false }) { Text("Cancelar") } },
        )
    }
    if (newCharacter) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newCharacter = false },
            title = { Text("Nuevo personaje") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre (princesa, bruja, narrador…)") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.addCharacter(name); newCharacter = false }) { Text("Agregar") } },
            dismissButton = { TextButton(onClick = { newCharacter = false }) { Text("Cancelar") } },
        )
    }

    val p = u.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p == null) return@Column
        // Obra y reproducción
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker("Obra: ${p.title}", u.projects, { it.title }) { vm.open(it) }
            TextButton(onClick = { newProject = true }) { Text("+ Nueva obra") }
            GradientButton(if (clock.playing) "Parar" else "Escuchar todo", { if (clock.playing) vm.stop() else vm.playAll(0) }, glyph = if (clock.playing) "■" else "▶")
            TextButton(onClick = vm::export) { Text("Exportar WAV") }
            TextButton(onClick = { vm.deleteProject(p) }) { Text("Borrar obra") }
            Text("Duración: ${fmtTime(DramaRenderer.totalSeconds(p))}", style = MaterialTheme.typography.labelMedium)
        }
        u.busy?.let { (label, pr) ->
            Text(label, style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { pr.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        u.error?.let { Row(verticalAlignment = Alignment.CenterVertically) { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f)); TextButton(onClick = vm::dismissError) { Text("OK") } } }
        if (u.loaded && clock.dur > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fmtTime(clock.pos), fontWeight = FontWeight.Bold)
                Slider((clock.pos / clock.dur).toFloat().coerceIn(0f, 1f), { vm.seek(it * clock.dur) }, modifier = Modifier.weight(1f))
                Text(fmtTime(clock.dur))
            }
        }

        // Grabando
        u.recordingFor?.let { id ->
            val who = p.characters.firstOrNull { it.id == id }
            Column(
                Modifier.fillMaxWidth().background(Color(0x33FF3B5C), RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("● Grabando a ${who?.name ?: "personaje"}  ·  ${secs(u.recSeconds)}", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(progress = { (u.recLevel * 2f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("Habla cerca del micrófono. Al terminar se recorta el silencio del principio y del final.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton("Terminar", vm::finishRecording, glyph = "■")
                    TextButton(onClick = vm::cancelRecording) { Text("Descartar") }
                }
            }
        }

        // Personajes
        Text("Personajes", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (c in p.characters) {
                FilterChip(
                    selected = editingCharacter == c.id,
                    onClick = { editingCharacter = if (editingCharacter == c.id) null else c.id },
                    label = { Text(c.name) },
                    leadingIcon = { Box(Modifier.size(10.dp).background(Color(c.color), CircleShape)) },
                )
            }
            TextButton(onClick = { newCharacter = true }) { Text("+ Personaje") }
        }
        p.characters.firstOrNull { it.id == editingCharacter }?.let { c -> CharacterEditor(c, vm, canDelete = p.characters.size > 1 && p.cues.none { it.characterId == c.id }) }

        // Agregar
        Text("Agregar a la obra", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (c in p.characters) FilterChip(selected = false, onClick = { record(c.id) }, label = { Text("● Grabar a ${c.name}") })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FilterChip(selected = false, onClick = { pendingImport = CueType.MUSIC to null; picker.launch(arrayOf("*/*")) }, label = { Text("+ Música de fondo") })
            FilterChip(selected = false, onClick = { pendingImport = CueType.SFX to null; picker.launch(arrayOf("*/*")) }, label = { Text("+ Sonido (animal, puerta…)") })
            for (c in p.characters) FilterChip(selected = false, onClick = { pendingImport = CueType.VOICE to c.id; picker.launch(arrayOf("*/*")) }, label = { Text("+ Voz de ${c.name} (archivo)") })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Silencio:", style = MaterialTheme.typography.labelMedium)
            for (s in listOf(1f, 2f, 3f, 5f)) FilterChip(selected = false, onClick = { vm.addSilence(s) }, label = { Text("${s.toInt()} s") })
        }

        SoundBoard(u, vm)

        // Lista de pistas
        Text("La obra (en orden)", style = MaterialTheme.typography.titleSmall)
        if (p.cues.isEmpty()) {
            Text(
                "Todavía no hay nada. Graba la voz de un personaje, trae una música de fondo o un sonido, y ordénalos aquí. " +
                    "Cada pista entra después de la anterior, con el espacio que le pongas; también puede entrar junto a la anterior " +
                    "(un ladrido mientras alguien habla) o quedar de fondo.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        p.cues.forEachIndexed { i, c -> CueCard(i, c, p.characters, playing = u.playingCue == i, vm = vm, last = i == p.cues.size - 1) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = p.normalizeVoices, onClick = { vm.edit { it.copy(normalizeVoices = !it.normalizeVoices) } }, label = { Text("Igualar el volumen de las voces") })
            FilterChip(selected = p.duckMusic, onClick = { vm.edit { it.copy(duckMusic = !it.duckMusic) } }, label = { Text("Bajar la música cuando hablan") })
        }
    }
}

@Composable
private fun CharacterEditor(c: Character, vm: PrincessViewModel, canDelete: Boolean) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (canDelete) TextButton(onClick = { vm.removeCharacter(c.id) }) { Text("Quitar") }
        }
        LabeledSlider("Tono de la voz: ${if (c.pitch > 0) "+" else ""}${c.pitch.toInt()} (más grave ← → más aguda y rápida)", c.pitch, -12f..12f) { v -> vm.editCharacter(c.id) { it.copy(pitch = v.toInt().toFloat()) } }
        LabeledSlider("Volumen", c.volume, 0f..1.5f) { v -> vm.editCharacter(c.id) { it.copy(volume = v) } }
        LabeledSlider("Posición (izquierda ← → derecha)", c.pan, -1f..1f) { v -> vm.editCharacter(c.id) { it.copy(pan = if (kotlin.math.abs(v) < 0.08f) 0f else v) } }
        Text("El tono cambia la voz grabada: úsalo para hacer personajes distintos con tu misma voz.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CueCard(index: Int, c: Cue, characters: List<Character>, playing: Boolean, vm: PrincessViewModel, last: Boolean) {
    val who = characters.firstOrNull { it.id == c.characterId }
    val color = when (c.type) {
        CueType.VOICE -> Color(who?.color ?: 0xFF9CA3AF)
        CueType.MUSIC -> Color(0xFFA78BFA)
        CueType.SFX -> Color(0xFF34D399)
        CueType.SILENCE -> Color(0xFF6B7280)
    }
    val shape = RoundedCornerShape(12.dp)
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(color.copy(alpha = if (playing) 0.28f else 0.12f), shape)
            .border(if (playing) 2.dp else 0.dp, if (playing) color else Color.Transparent, shape).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${index + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (c.type) { CueType.VOICE -> who?.name ?: "Voz"; CueType.MUSIC -> "Música"; CueType.SFX -> "Sonido"; CueType.SILENCE -> "Silencio" } +
                        "  ·  " + (if (c.mode == CueMode.BED) "de fondo" else secs(c.seconds)) +
                        (if (c.mode == CueMode.WITH_PREV) "  ·  junto a la anterior" else if (c.mode == CueMode.AT) "  ·  encima, en ${fmtTime(c.gap.toDouble())}" else ""),
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = color,
                )
                if (c.text.isNotBlank() && c.type != CueType.SILENCE) Text(c.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
            if (c.type != CueType.SILENCE) TextButton(onClick = { vm.preview(index) }) { Text("▶") }
            TextButton(onClick = { vm.playAll(index) }) { Text("Desde aquí") }
            TextButton(onClick = { vm.moveCue(index, -1) }, enabled = index > 0) { Text("↑") }
            TextButton(onClick = { vm.moveCue(index, 1) }, enabled = !last) { Text("↓") }
            TextButton(onClick = { open = !open }) { Text(if (open) "▴" else "Editar") }
        }
        if (open) {
            if (c.type != CueType.SILENCE) OutlinedTextField(
                c.text, { t -> vm.updateCue(index) { it.copy(text = t) } }, singleLine = true,
                label = { Text(if (c.type == CueType.VOICE) "Texto de la línea" else "Nombre") }, modifier = Modifier.fillMaxWidth(),
            )
            if (c.type == CueType.SILENCE) Stepper("Duración", c.seconds) { v -> vm.updateCue(index) { it.copy(seconds = v.coerceAtLeast(0.5f)) } }
            else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in CueMode.entries) {
                        if (m == CueMode.BED && c.type != CueType.MUSIC) continue
                        if (m != CueMode.BED && c.type == CueType.MUSIC && false) continue
                        FilterChip(selected = c.mode == m, onClick = { vm.updateCue(index) { it.copy(mode = m) } }, label = { Text(m.label) })
                    }
                }
                Stepper(when (c.mode) { CueMode.WITH_PREV -> "Entra con un desfase de"; CueMode.AT -> "Empieza en el segundo"; else -> "Espacio antes" }, c.gap) { v -> vm.updateCue(index) { it.copy(gap = v.coerceAtLeast(0f)) } }
                LabeledSlider("Volumen", c.volume, 0f..1.5f) { v -> vm.updateCue(index) { it.copy(volume = v) } }
                if (c.mode == CueMode.BED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Suena por debajo de: ", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { vm.updateCue(index) { it.copy(span = (it.span - 1).coerceAtLeast(0)) } }) { Text("−") }
                        Text(if (c.span == 0) "toda la obra" else "${c.span} pistas", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { vm.updateCue(index) { it.copy(span = it.span + 1) } }) { Text("+") }
                    }
                    Stepper("Entrada suave", c.fadeIn) { v -> vm.updateCue(index) { it.copy(fadeIn = v.coerceAtLeast(0f)) } }
                    Stepper("Salida suave", c.fadeOut) { v -> vm.updateCue(index) { it.copy(fadeOut = v.coerceAtLeast(0f)) } }
                }
            }
            TextButton(onClick = { vm.deleteCue(index) }) { Text("Quitar esta pista") }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { onChange(value - 0.5f) }) { Text("−") }
        Text(secs(value), fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
        TextButton(onClick = { onChange(value + 0.5f) }) { Text("+") }
    }
}

/** Botones con los sonidos del pack (animales, ambiente, música…) y los propios: tocar uno lo agrega al final de la obra. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundBoard(u: PrincessUi, vm: PrincessViewModel) {
    val ctx = LocalContext.current
    var group by remember { mutableStateOf("Animales") }
    val groups = com.glidelive.princess.SoundCatalog.groups.map { it.name } + listOf("Música", "Mis sonidos")
    val counts = remember(u.hasSfx, u.storageMb) {
        com.glidelive.princess.SoundCatalog.groups.flatMap { it.sounds }.associate { it.id to com.glidelive.princess.SoundStore.clips(ctx, it.id).size }
    }
    var overlay by remember { mutableStateOf(false) }
    Text("Sonidos para agregar", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Al tocar un sonido:", style = MaterialTheme.typography.labelMedium)
        FilterChip(selected = !overlay, onClick = { overlay = false }, label = { Text("Va al final") })
        FilterChip(selected = overlay, onClick = { overlay = true }, label = { Text("Encima, en el cursor") })
    }
    if (overlay) Text(
        "Escucha la obra, pausa donde quieras o mueve la barra de tiempo, y toca un sonido: entra en ese instante, encima de lo que ya suena " +
            "(Cursor: ${fmtTime(vm.overlayTime())}).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val at: () -> Double? = { if (overlay) vm.overlayTime() else null }
    u.download?.let { (label, p) ->
        Text("$label  ·  ${(p * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = vm::cancelDownload) { Text("Cancelar") }
    }
    if (u.download == null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!u.hasSfx) GradientButton("Bajar sonidos (≈180 MB)", { vm.downloadSfx(10) }, glyph = "⬇")
            else FilterChip(selected = false, onClick = { vm.downloadSfx(50) }, label = { Text("Bajar todas las variantes (≈880 MB)") })
            if (!u.hasSfx) FilterChip(selected = false, onClick = { vm.downloadSfx(50) }, label = { Text("Todos (≈880 MB)") })
            if (u.musicCount < com.glidelive.princess.SoundCatalog.music.size) {
                FilterChip(selected = false, onClick = vm::downloadMusic, label = { Text("Bajar música de fondo (≈50 MB)") })
            }
        }
        Text(
            "Los sonidos se bajan una sola vez y quedan guardados en el teléfono hasta que borres la app" +
                (if (u.storageMb > 0) " (ahora ocupan ${u.storageMb} MB)" else "") + ".",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (g in groups) FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) })
    }
    when (group) {
        "Música" -> {
            val have = remember(u.musicCount) { com.glidelive.princess.SoundStore.musicFiles(ctx) }
            if (have.isEmpty()) Text("Todavía no hay música: usa «Bajar música de fondo».", style = MaterialTheme.typography.bodyMedium)
            for ((t, f) in have) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.label, Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { vm.previewFile(f) }) { Text("▶") }
                FilterChip(selected = false, onClick = { vm.addSoundFile(f, t.label.substringBefore(" ·"), CueType.MUSIC) }, label = { Text("+ De fondo") })
            }
            Text(com.glidelive.princess.SoundCatalog.MUSIC_CREDIT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "Mis sonidos" -> {
            if (u.mine.isEmpty()) Text(
                "Aquí aparecen los sonidos que guardes desde cualquier pestaña con el botón «● Sonido» de la barra de arriba: " +
                    "toca lo que quieras y guárdalo para usarlo en la obra.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (m in u.mine) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${m.name}  ·  ${secs(m.seconds)}", Modifier.weight(1f), maxLines = 1)
                val f = java.io.File(com.glidelive.princess.SoundStore.mineDir(ctx), m.file)
                TextButton(onClick = { vm.previewFile(f) }) { Text("▶") }
                FilterChip(selected = false, onClick = { vm.addSoundFile(f, m.name, CueType.SFX, at()) }, label = { Text("+ Agregar") })
                TextButton(onClick = { vm.deleteMine(m.id) }) { Text("Borrar") }
            }
        }
        else -> {
            val sounds = com.glidelive.princess.SoundCatalog.groups.firstOrNull { it.name == group }?.sounds ?: emptyList()
            if (!u.hasSfx) Text("Bájalos con «Bajar sonidos» para ver los botones disponibles.", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in sounds) {
                    val n = counts[s.id] ?: 0
                    if (n == 0) continue
                    Row(
                        Modifier.background(Color(0x3322D3EE), RoundedCornerShape(12.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { vm.clipFor(s.id)?.let { vm.previewFile(it) } }) { Text("▶") }
                        TextButton(onClick = { vm.clipFor(s.id)?.let { vm.addSoundFile(it, s.label, CueType.SFX, at()) } }) { Text("+ ${s.label}", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Text(com.glidelive.princess.SoundCatalog.SFX_CREDIT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
