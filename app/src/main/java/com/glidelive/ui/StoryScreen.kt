package com.glidelive.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glidelive.princess.Cue
import com.glidelive.princess.CueMode
import com.glidelive.princess.CueType
import com.glidelive.princess.DramaRenderer
import com.glidelive.princess.Placement
import com.glidelive.princess.Project
import com.glidelive.princess.SoundCatalog
import com.glidelive.princess.SoundStore
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val RED = Color(0xFFE5334B)
private fun clock(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0)
    return String.format(Locale.ROOT, "%d:%02d", s.toInt() / 60, s.toInt() % 60)
}

private val ICON = mapOf(
    "dog" to "🐶", "cat" to "🐱", "rooster" to "🐓", "hen" to "🐔", "pig" to "🐷", "cow" to "🐮", "sheep" to "🐑", "frog" to "🐸",
    "crow" to "🐦", "chirping_birds" to "🐤", "insects" to "🦗", "crickets" to "🦗", "rain" to "🌧️", "thunderstorm" to "⛈️",
    "wind" to "💨", "sea_waves" to "🌊", "crackling_fire" to "🔥", "water_drops" to "💧", "pouring_water" to "🚰", "laughing" to "😄",
    "crying_baby" to "👶", "clapping" to "👏", "footsteps" to "👣", "coughing" to "😷", "sneezing" to "🤧", "breathing" to "😮",
    "snoring" to "😴", "drinking_sipping" to "🥤", "brushing_teeth" to "🪥", "door_wood_knock" to "🚪", "door_wood_creaks" to "🚪",
    "glass_breaking" to "💥", "clock_alarm" to "⏰", "clock_tick" to "🕰️", "can_opening" to "🥫", "keyboard_typing" to "⌨️",
    "mouse_click" to "🖱️", "washing_machine" to "🧺", "vacuum_cleaner" to "🧹", "toilet_flush" to "🚽", "church_bells" to "🔔",
    "siren" to "🚨", "car_horn" to "🚗", "engine" to "🚙", "train" to "🚂", "airplane" to "✈️", "helicopter" to "🚁",
    "fireworks" to "🎆", "chainsaw" to "🪚", "hand_saw" to "🪚",
)

private enum class Panel { SOUNDS, MUSIC, TONE, VOLUME }

/**
 * Cuentos: hacer el audio de un cuento. Pensada para quien la abre por primera vez: un botón rojo para grabar, sonidos y música
 * a un toque, y al tocar un audio aparecen cortar, cambiar la voz, volumen y quitar. Todo se puede deshacer.
 */
@Composable
fun StoryScreen(
    vm: PrincessViewModel,
    modifier: Modifier = Modifier,
    onNarrate: () -> Unit,
    onOtherMode: (() -> Unit)? = null,
    onUpdate: (() -> Unit)? = null,
) {
    val u by vm.ui.collectAsState()
    var inEditor by rememberSaveable { mutableStateOf(true) }
    val project = u.current
    if (project == null || !inEditor) {
        StoryList(vm, u, onOpen = { vm.open(it); inEditor = true }, onNew = { vm.newProject("Mi cuento ${u.projects.size + 1}"); inEditor = true },
            modifier, onOtherMode, onUpdate)
    } else {
        StoryEditor(vm, project, onBack = { vm.onLeave(); inEditor = false }, onNarrate = onNarrate, modifier = modifier, onOtherMode = onOtherMode, onUpdate = onUpdate)
    }
}

// ───────────────────────────── Mis cuentos ─────────────────────────────

@Composable
private fun StoryList(vm: PrincessViewModel, u: PrincessUi, onOpen: (Project) -> Unit, onNew: () -> Unit, modifier: Modifier,
                      onOtherMode: (() -> Unit)?, onUpdate: (() -> Unit)?) {
    var deleting by remember { mutableStateOf<Project?>(null) }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Borrar «${p.title}»?") },
            text = { Text("Se borrará este cuento y todos sus audios. No se puede deshacer.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(p); deleting = null }) { Text("Sí, borrar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("No, dejarlo") } },
        )
    }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mis cuentos", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            MoreMenu(onOtherMode, onUpdate)
        }
        if (u.projects.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("¡Hagamos tu primer cuento!", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("Graba tu voz, agrega sonidos y música, y envíalo por WhatsApp.", textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                BigButton("＋ Empezar un cuento", RED, onNew)
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                u.projects.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .clickable { onOpen(p) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("${p.cues.size} audios · ${clock(DramaRenderer.totalSeconds(p))}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = { onOpen(p) }) { Text("Abrir") }
                        TextButton(onClick = { deleting = p }) { Text("Borrar") }
                    }
                }
            }
            BigButton("＋ Nuevo cuento", RED, onNew)
        }
    }
}

@Composable
private fun BigButton(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp), shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun MoreMenu(onOtherMode: (() -> Unit)?, onUpdate: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("⋮", fontSize = 22.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onUpdate?.let { DropdownMenuItem(text = { Text("Buscar una versión nueva de la app") }, onClick = { open = false; it() }) }
            onOtherMode?.let { DropdownMenuItem(text = { Text("Cambiar a música (acordes, batería…)") }, onClick = { open = false; it() }) }
        }
    }
}

// ───────────────────────────── Editor ─────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoryEditor(
    vm: PrincessViewModel, project: Project, onBack: () -> Unit, onNarrate: () -> Unit, modifier: Modifier,
    onOtherMode: (() -> Unit)?, onUpdate: (() -> Unit)?,
) {
    val u by vm.ui.collectAsState()
    val clk by vm.clock.collectAsState()
    val ctx = LocalContext.current
    var panel by remember(project.id) { mutableStateOf<Panel?>(null) }
    var selectedId by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var zoom by rememberSaveable(project.id) { mutableStateOf(30f) }
    var renaming by remember { mutableStateOf(false) }
    var speaker by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var newCharacter by remember { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }
    val placements = remember(project) { DramaRenderer.layout(project) }
    val selected = placements.firstOrNull { it.cue.id == selectedId }
    if (selectedId != null && selected == null) selectedId = null
    val speakerId = project.characters.firstOrNull { it.id == speaker }?.id ?: project.characters.firstOrNull()?.id ?: ""
    val working = u.busy != null || u.recordingFor != null
    val playing = clk.playing && u.loaded
    val position = if (playing) clk.pos else u.cursor

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { micDenied = false; vm.startRecording(speakerId) } else micDenied = true
    }
    fun record() {
        micDenied = false
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording(speakerId)
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri, if (panelTypeForImport == CueType.MUSIC) CueType.MUSIC else CueType.SFX, null)
    }

    if (renaming) {
        var name by remember { mutableStateOf(project.title) }
        AlertDialog(
            onDismissRequest = { renaming = false }, title = { Text("Nombre del cuento") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameProject(name); renaming = false }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancelar") } },
        )
    }
    if (newCharacter) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newCharacter = false }, title = { Text("¿Quién habla?") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre (la bruja, el lobo, mamá…)") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { speaker = vm.addCharacter(name); newCharacter = false }) { Text("Agregar") } },
            dismissButton = { TextButton(onClick = { newCharacter = false }) { Text("Cancelar") } },
        )
    }

    androidx.activity.compose.BackHandler {
        when {
            panel != null -> panel = null
            selectedId != null -> selectedId = null
            u.recordingFor != null -> vm.cancelRecording()
            else -> onBack()
        }
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // ── Barra de arriba (sin deslizar: todo a la vista) ──
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBack) { Text("← Mis cuentos") }
                TextButton(onClick = { renaming = true }, modifier = Modifier.weight(1f)) { Text(project.title + "  ✎", fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1) }
                var more by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { more = true }) { Text("Más ▾", fontSize = 15.sp) }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("🗣 Voz de computadora (prueba)") }, onClick = { more = false; onNarrate() })
                        DropdownMenuItem(text = { Text("↷ Rehacer") }, enabled = u.canRedo && !working, onClick = { more = false; vm.redo() })
                        DropdownMenuItem(text = { Text("Ver más tiempo en pantalla") }, onClick = { more = false; zoom = (zoom * 1.5f).coerceAtMost(120f) })
                        DropdownMenuItem(text = { Text("Ver menos tiempo en pantalla") }, onClick = { more = false; zoom = (zoom / 1.5f).coerceAtLeast(8f) })
                        onUpdate?.let { DropdownMenuItem(text = { Text("Buscar una versión nueva de la app") }, onClick = { more = false; it() }) }
                        onOtherMode?.let { DropdownMenuItem(text = { Text("Cambiar a música (acordes, batería…)") }, onClick = { more = false; it() }) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { vm.undo() }, enabled = u.canUndo && !working, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) { Text("↶ Deshacer", fontSize = 14.sp, maxLines = 1) }
                Button(
                    onClick = { if (playing) { vm.setCursor(clk.pos); vm.stop() } else vm.playFromSeconds(u.cursor) },
                    enabled = project.cues.isNotEmpty() && u.busy == null && u.recordingFor == null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D6E), contentColor = Color.White),
                    modifier = Modifier.height(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                ) { Text(if (playing) "■ Parar" else "▶ Escuchar", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                Text(clock(position), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Button(onClick = { vm.export() }, enabled = project.cues.isNotEmpty() && !working, modifier = Modifier.height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5), contentColor = Color.White),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text("📤 Enviar mi cuento", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            }
            u.busy?.let { (label, p) ->
                Text(label, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            }

            // ── La línea de tiempo ──
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                StoryTimeline(project, placements, position, playing, selectedId, zoom, enabled = u.busy == null && u.recordingFor == null,
                    onSelect = { selectedId = it }, onCursor = { vm.setCursor(it) }, onMove = { id, s -> vm.moveCueTo(id, s) })
                if (project.cues.isEmpty()) {
                    Column(Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(16.dp)).padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Tu cuento empieza aquí", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("1. Toca el botón rojo y cuenta una frase.", style = MaterialTheme.typography.bodyLarge)
                        Text("2. Agrega sonidos y música con los botones de abajo.", style = MaterialTheme.typography.bodyLarge)
                        Text("3. Toca «Enviar mi cuento» para mandarlo por WhatsApp.", style = MaterialTheme.typography.bodyLarge)
                        Text("Si te equivocas, toca «↶ Deshacer».", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Avisos ──
            val msg = u.error ?: u.notice
            if (msg != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(
                    if (u.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::dismissError) { Text("OK") }
                }
            }
            if (micDenied) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Para grabar tu voz necesito usar el micrófono.", Modifier.weight(1f).padding(vertical = 8.dp))
                    TextButton(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Dar permiso") }
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Abrir ajustes") }
                }
            }

            // ── Barra de abajo: cambia según lo que se esté haciendo ──
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                when {
                    u.recordingFor != null -> RecordingBar(u, onStop = { vm.finishRecording(if (u.cursor > 0.05) u.cursor else null) }, onDiscard = { vm.cancelRecording() })
                    selected != null -> SelectionBar(
                        selected.cue,
                        onCut = { if (playing) { vm.setCursor(clk.pos); vm.stop() }; vm.cutAtCursor(selected.cue.id) },
                        onTone = { panel = Panel.TONE }, onVolume = { panel = Panel.VOLUME },
                        onListen = { vm.previewCue(selected.cue.id) },
                        onRemove = { vm.removeCue(selected.cue.id); selectedId = null; vm.setNotice("Quité ese audio. Si te equivocaste, toca «↶ Deshacer».") },
                        onDone = { selectedId = null },
                        canTone = selected.cue.type != CueType.SILENCE && selected.cue.file != null,
                        busy = u.busy != null,
                    )
                    else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BigButton("● Grabar mi voz", RED, { record() }, Modifier.weight(1.4f), enabled = u.busy == null)
                        SpeakerMenu(project, speakerId, onPick = { speaker = it }, onNew = { newCharacter = true }, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = { panel = Panel.SOUNDS }, modifier = Modifier.height(52.dp).weight(1f), enabled = u.busy == null,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text("🔊 Sonido", fontSize = 16.sp, maxLines = 1) }
                        FilledTonalButton(onClick = { panel = Panel.MUSIC }, modifier = Modifier.height(52.dp).weight(1f), enabled = u.busy == null,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text("🎵 Música", fontSize = 16.sp, maxLines = 1) }
                    }
                }
            }
        }

        // ── Hojas encima ──
        when (panel) {
            Panel.SOUNDS -> SoundsSheet(vm, u, onClose = { panel = null }, onPickFile = { panelTypeForImport = CueType.SFX; filePicker.launch(arrayOf("audio/*")) })
            Panel.MUSIC -> MusicSheet(vm, u, onClose = { panel = null }, onPickFile = { panelTypeForImport = CueType.MUSIC; filePicker.launch(arrayOf("audio/*")) })
            Panel.TONE -> selected?.let { ToneSheet(it.cue, onPick = { steps -> vm.setTone(it.cue.id, steps) }, onClose = { panel = null }, onListen = { vm.previewCue(it.cue.id) }) } ?: run { panel = null }
            Panel.VOLUME -> selected?.let { VolumeSheet(it.cue, onSet = { v -> vm.editTimelineCue(it.cue.id) { c -> c.copy(volume = v) } }, onClose = { panel = null }) } ?: run { panel = null }
            null -> {}
        }
    }
}

/** Qué se va a traer del teléfono con el selector de archivos (se recuerda entre la hoja y el resultado). */
private var panelTypeForImport: CueType = CueType.SFX

@Composable
private fun SpeakerMenu(project: Project, current: String, onPick: (String) -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val name = project.characters.firstOrNull { it.id == current }?.name ?: "Yo"
    Box(modifier) {
        FilledTonalButton(onClick = { open = true }, modifier = Modifier.height(52.dp).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text("Habla: $name ▾", fontSize = 14.sp, maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            project.characters.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { open = false; onPick(c.id) }) }
            DropdownMenuItem(text = { Text("＋ Otra voz (la bruja, el lobo…)") }, onClick = { open = false; onNew() })
        }
    }
}

@Composable
private fun RecordingBar(u: PrincessUi, onStop: () -> Unit, onDiscard: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0x33E5334B), RoundedCornerShape(16.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size16().background(RED, CircleShape))
        Column(Modifier.weight(1f)) {
            Text("Grabando…  ${"%.0f".format(u.recSeconds)} s", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            LinearProgressIndicator(progress = { (u.recLevel * 2.5f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Text("Habla cerca del teléfono. Si no se mueve la barra, el micrófono no te oye.", style = MaterialTheme.typography.bodySmall)
        }
        BigButton("■ Terminar", RED, onStop)
        TextButton(onClick = onDiscard) { Text("Descartar") }
    }
}

private fun Modifier.size16() = this.then(Modifier.width(16.dp).height(16.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(cue: Cue, onCut: () -> Unit, onTone: () -> Unit, onVolume: () -> Unit, onListen: () -> Unit, onRemove: () -> Unit, onDone: () -> Unit,
                         canTone: Boolean, busy: Boolean) {
    Column {
        Text("«${cue.text.ifBlank { cue.type.label }}» está elegido. Arrástralo para moverlo. Para cortar: toca la regla de arriba donde quieras cortar y luego «✂ Cortar aquí».",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = onCut, modifier = Modifier.height(52.dp), enabled = !busy && cue.type != CueType.MUSIC) { Text("✂ Cortar aquí", fontSize = 16.sp) }
            FilledTonalButton(onClick = onTone, modifier = Modifier.height(52.dp), enabled = !busy && canTone) { Text("🎚 Más grave o aguda", fontSize = 16.sp) }
            FilledTonalButton(onClick = onVolume, modifier = Modifier.height(52.dp), enabled = !busy) { Text("🔉 Volumen", fontSize = 16.sp) }
            FilledTonalButton(onClick = onListen, modifier = Modifier.height(52.dp), enabled = !busy) { Text("▶ Escuchar solo este", fontSize = 16.sp) }
            FilledTonalButton(onClick = onRemove, modifier = Modifier.height(52.dp), enabled = !busy) { Text("🗑 Quitar", fontSize = 16.sp) }
            BigButton("✓ Listo", Color(0xFF2E7D6E), onDone)
        }
    }
}

// ───────────────────────────── Línea de tiempo ─────────────────────────────

private data class Lane(val label: String, val color: Color, val clips: List<Placement>)

private fun packRows(clips: List<Placement>): List<List<Placement>> {
    val rows = mutableListOf<MutableList<Placement>>()
    for (c in clips.sortedBy { it.start }) {
        val row = rows.firstOrNull { it.last().end <= c.start } ?: mutableListOf<Placement>().also { rows += it }
        row += c
    }
    return rows
}

@Composable
private fun StoryTimeline(
    project: Project, placements: List<Placement>, position: Double, playing: Boolean, selectedId: String?, zoom: Float, enabled: Boolean,
    onSelect: (String?) -> Unit, onCursor: (Double) -> Unit, onMove: (String, Double) -> Unit,
) {
    val lanes = buildList {
        project.characters.forEach { ch -> add(Lane(ch.name, Color(ch.color), placements.filter { it.cue.type == CueType.VOICE && it.cue.characterId == ch.id })) }
        val orphan = placements.filter { it.cue.type == CueType.VOICE && project.characters.none { c -> c.id == it.cue.characterId } }
        if (orphan.isNotEmpty()) add(Lane("Otras voces", Color(0xFF60A5FA), orphan))
        add(Lane("Sonidos", Color(0xFF34D399), placements.filter { it.cue.type == CueType.SFX }))
        add(Lane("Música de fondo", Color(0xFFA78BFA), placements.filter { it.cue.type == CueType.MUSIC }))
    }.filter { it.clips.isNotEmpty() || it.label == "Sonidos" || it.label == "Música de fondo" || project.characters.size <= 3 }
    val duration = max(30.0, DramaRenderer.totalSeconds(project) + 10.0)
    val widthDp = (duration * zoom).coerceAtMost(30000.0).toFloat()
    val scale = (widthDp / duration).toFloat()
    val density = LocalDensity.current.density
    val hScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).padding(6.dp).verticalScroll(rememberScrollState())) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(92.dp)) {
            Spacer(Modifier.height(26.dp))
            Column {
                lanes.forEach { lane ->
                    val h = (packRows(lane.clips).size.coerceAtLeast(1) * 56).dp
                    Box(Modifier.height(h).padding(end = 6.dp), contentAlignment = Alignment.CenterStart) {
                        Text(lane.label, color = lane.color, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).horizontalScroll(hScroll)) {
            Box(Modifier.width(widthDp.dp).height(26.dp).pointerInput(scale, enabled) { detectTapGestures { at -> if (enabled) onCursor((at.x / density / scale).toDouble()) } }) {
                val step = when { scale >= 60 -> 1; scale >= 24 -> 5; else -> 10 }
                for (s in 0..duration.toInt() step step) Text(clock(s.toDouble()), Modifier.offset(x = (s * scale).dp), fontSize = 13.sp)
            }
            Column {
                lanes.forEach { lane ->
                    packRows(lane.clips).ifEmpty { listOf(emptyList()) }.forEach { row ->
                        Box(
                            Modifier.width(widthDp.dp).height(56.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .pointerInput(scale, enabled) { detectTapGestures { at -> if (enabled) { onCursor((at.x / density / scale).toDouble()); onSelect(null) } } },
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                val x = (position * scale * density).toFloat()
                                drawLine(RED, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
                            }
                            row.forEach { place ->
                                var drag by remember(place.cue.id, place.start) { mutableStateOf(0f) }
                                val isSel = place.cue.id == selectedId
                                val start = (place.start + drag).coerceAtLeast(0.0)
                                Box(
                                    Modifier.offset(x = (start * scale).toFloat().dp, y = 4.dp)
                                        .width(max(34f, ((place.end - place.start) * scale).toFloat()).dp).height(48.dp)
                                        .background(lane.color.copy(alpha = if (place.cue.muted) 0.15f else if (isSel) 0.75f else 0.5f), RoundedCornerShape(8.dp))
                                        .border(if (isSel) 3.dp else 1.dp, if (isSel) Color.White else lane.color, RoundedCornerShape(8.dp))
                                        .pointerInput(place.cue.id, place.start, isSel, enabled, scale) {
                                            if (!enabled) return@pointerInput
                                            if (!isSel) detectTapGestures { at -> onSelect(place.cue.id); onCursor(place.start + (at.x / density / scale).toDouble()) }
                                            else detectDragGestures(
                                                onDragEnd = { val target = (place.start + drag).coerceAtLeast(0.0); drag = 0f; onMove(place.cue.id, target) },
                                                onDragCancel = { drag = 0f },
                                                onDrag = { change, amount -> change.consume(); drag += amount.x / density / scale },
                                            )
                                        }.padding(6.dp),
                                ) {
                                    Text((if (place.cue.toneSteps != 0) "🎚 " else "") + place.cue.text.ifBlank { place.cue.type.label },
                                        fontSize = 14.sp, maxLines = 2, color = Color.White, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

// ───────────────────────────── Hojas ─────────────────────────────

@Composable
private fun Sheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val scrim = remember { MutableInteractionSource() }
    val card = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(interactionSource = scrim, indication = null) { onClose() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.96f).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), RoundedCornerShape(20.dp))
                .clickable(interactionSource = card, indication = null) {}.padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                BigButton("Listo", Color(0xFF2E7D6E), onClose, Modifier.height(46.dp))
            }
            content()
        }
    }
}

@Composable
private fun DownloadCard(vm: PrincessViewModel, u: PrincessUi, what: String) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val d = u.download
        if (d != null) {
            Text("${d.first}  ${(d.second * 100).toInt()} %", fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { d.second.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = vm::cancelDownload) { Text("Cancelar") }
        } else {
            Text(if (u.sfxPartial) "La descarga quedó a medias." else "Para tener $what hay que descargarlos una sola vez.", fontWeight = FontWeight.Bold)
            Text("Conviene usar WiFi. Después quedan guardados en tu teléfono y funcionan sin internet.", style = MaterialTheme.typography.bodyMedium)
            BigButton(if (u.sfxPartial) "Completar la descarga" else "⬇ Descargar", RED, { vm.downloadAll() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundsSheet(vm: PrincessViewModel, u: PrincessUi, onClose: () -> Unit, onPickFile: () -> Unit) {
    val ctx = LocalContext.current
    var group by rememberSaveable { mutableStateOf("Animales") }
    val groups = SoundCatalog.groups.map { it.name } + "Mis sonidos"
    Sheet("Poner un sonido en la línea roja", onClose) {
        if (!u.hasSfx) DownloadCard(vm, u, "los sonidos")
        else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { g -> FilledTonalButton(onClick = { group = g }, colors = if (group == g) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()) { Text(g) } }
            }
            Text("Toca un sonido y se pone donde está la línea roja. El altavoz ▶ es para oírlo antes.", style = MaterialTheme.typography.bodyMedium)
            u.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (group == "Mis sonidos") {
                    if (u.mine.isEmpty()) Text("Aquí aparecen los sonidos que guardes tú.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    u.mine.forEach { m ->
                        val f = java.io.File(SoundStore.mineDir(ctx), m.file)
                        SoundRow("🎙", m.name, onListen = { vm.previewFile(f) }, onAdd = { vm.addSoundFile(f, m.name, CueType.SFX, vm.overlayTime()) })
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoundCatalog.groups.firstOrNull { it.name == group }?.sounds.orEmpty().forEach { s ->
                            if (SoundStore.clips(ctx, s.id).isNotEmpty()) SoundTile(ICON[s.id] ?: "🔊", s.label,
                                onListen = { vm.clipFor(s.id)?.let(vm::previewFile) }, onAdd = { vm.clipFor(s.id)?.let { vm.addSoundFile(it, s.label, CueType.SFX, vm.overlayTime()) } })
                        }
                    }
                }
            }
            TextButton(onClick = onPickFile) { Text("Elegir un sonido de mi teléfono…") }
            Text(SoundCatalog.SFX_CREDIT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SoundTile(icon: String, label: String, onListen: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier.width(108.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)).clickable(onClick = onAdd).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, fontSize = 34.sp)
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, textAlign = TextAlign.Center)
        TextButton(onClick = onListen, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("▶ Oír", fontSize = 13.sp) }
    }
}

@Composable
private fun SoundRow(icon: String, label: String, onListen: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$icon  $label", Modifier.weight(1f), fontSize = 16.sp)
        TextButton(onClick = onListen) { Text("▶ Oír") }
        FilledTonalButton(onClick = onAdd) { Text("＋ Poner") }
    }
}

@Composable
private fun MusicSheet(vm: PrincessViewModel, u: PrincessUi, onClose: () -> Unit, onPickFile: () -> Unit) {
    val ctx = LocalContext.current
    val have = remember(u.musicCount) { SoundStore.musicFiles(ctx) }
    Sheet("Poner música de fondo", onClose) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (have.isEmpty()) DownloadCard(vm, u, "la música")
            else {
                Text("La música suena suave por debajo y baja sola cuando alguien habla. Empieza en la línea roja.", style = MaterialTheme.typography.bodyMedium)
                u.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    have.forEach { (t, f) -> SoundRow("🎵", t.label, onListen = { vm.previewFile(f) }, onAdd = { vm.addSoundFile(f, t.label.substringBefore(" ·"), CueType.MUSIC, vm.overlayTime()) }) }
                }
            }
            TextButton(onClick = onPickFile) { Text("Elegir una música de mi teléfono…") }
            Text(SoundCatalog.MUSIC_CREDIT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToneSheet(cue: Cue, onPick: (Int) -> Unit, onClose: () -> Unit, onListen: () -> Unit) {
    val labels = listOf(-2 to "Muy grave", -1 to "Grave", 0 to "Normal", 1 to "Aguda", 2 to "Muy aguda")
    Sheet("Más grave o más aguda", onClose) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Elige qué tan grave o aguda suena esta grabación. No cambia la velocidad. Con un paso chico suele bastar.",
                style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEach { (steps, label) ->
                    val on = cue.toneSteps == steps
                    Button(onClick = { onPick(steps) }, modifier = Modifier.height(60.dp), shape = RoundedCornerShape(16.dp),
                        colors = if (on) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                }
            }
            FilledTonalButton(onClick = onListen, modifier = Modifier.height(52.dp)) { Text("▶ Escuchar cómo suena", fontSize = 16.sp) }
            Text("Si no te gusta, elige «Normal» y vuelve a como estaba.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VolumeSheet(cue: Cue, onSet: (Float) -> Unit, onClose: () -> Unit) {
    var v by remember(cue.id) { mutableStateOf(cue.volume) }
    Sheet("Volumen", onClose) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${(v * 100).roundToInt()} %", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Slider(v, { v = it }, valueRange = 0f..1.5f, onValueChangeFinished = { onSet(v) })
            Row { Text("Más bajito", Modifier.weight(1f)); Text("Más fuerte") }
            Text("Toca «Escuchar» arriba para oír cómo quedó.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
