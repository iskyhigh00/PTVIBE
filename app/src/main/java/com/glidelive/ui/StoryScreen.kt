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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.zIndex
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.heading
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

internal val RED = Color(0xFFD32F2F)

/** «Mi cuento N» con el número siguiente al más alto que ya existe (no se repite aunque se borren cuentos). */
internal fun nextStoryNumber(projects: List<Project>): Int =
    (projects.mapNotNull { Regex("^Mi cuento (\\d+)$").find(it.title)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
internal fun clock(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0)
    return String.format(Locale.ROOT, "%d:%02d", s.toInt() / 60, s.toInt() % 60)
}

internal val ICON = mapOf(
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

internal enum class Panel { SOUNDS, MUSIC, TONE, VOLUME, MOVE, CURSOR }

/** Lo que se puede hacer con un audio elegido, además de los botones principales. */
internal enum class CueAction { TRIM_START, TRIM_END, CURSOR, REMOVE_CLOSE, DUPLICATE, MUTE, FADE_IN, FADE_OUT, RENAME }

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
    if (u.creating) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Creando tu cuento…", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    } else if (project == null || !inEditor) {
        StoryList(vm, u, onOpen = { vm.open(it); inEditor = true }, onNew = { vm.newProject("Mi cuento ${nextStoryNumber(u.projects)}"); inEditor = true },
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
            Text("Mis cuentos", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).semantics { heading() })
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
                            Text("${p.cues.size} ${if (p.cues.size == 1) "audio" else "audios"} · ${clock(DramaRenderer.totalSeconds(p))} · se guarda solo", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = { onOpen(p) }, modifier = Modifier.height(48.dp).semantics { contentDescription = "Abrir ${p.title}" }) { Text("Abrir") }
                        TextButton(onClick = { vm.duplicateProject(p) }, modifier = Modifier.height(48.dp).semantics { contentDescription = "Hacer una copia de ${p.title}" }) { Text("Copiar") }
                        TextButton(onClick = { deleting = p }, modifier = Modifier.height(48.dp).semantics { contentDescription = "Borrar ${p.title}" }) { Text("Borrar") }
                    }
                }
            }
            BigButton("＋ Nuevo cuento", RED, onNew)
        }
    }
}

@Composable
internal fun BigButton(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp), shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun MoreMenu(onOtherMode: (() -> Unit)?, onUpdate: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.height(48.dp).semantics { contentDescription = "Más opciones" }) { Text("⋮", fontSize = 22.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onUpdate?.let { DropdownMenuItem(text = { Text("Buscar una versión nueva de la app") }, onClick = { open = false; it() }) }
            onOtherMode?.let { DropdownMenuItem(text = { Text("🎸 Ir a la pantalla de hacer música") }, onClick = { open = false; it() }) }
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
    var renamingCue by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(project.cues.map { it.file }) { vm.loadPeaks(project) }
    var micDenied by remember { mutableStateOf(false) }
    val placements = remember(project) { DramaRenderer.layout(project) }
    val selected = placements.firstOrNull { it.cue.id == selectedId }
    if (selectedId != null && selected == null) selectedId = null
    val speakerId = project.characters.firstOrNull { it.id == speaker }?.id ?: project.characters.firstOrNull()?.id ?: ""
    val working = u.busy != null || u.recordingFor != null
    val playing = clk.playing && u.loaded && !u.previewing
    val position = if (playing) clk.pos else u.cursor
    val keepOn = u.recordingFor != null   // al escuchar, el cuento sigue sonando con la pantalla apagada
    val screen = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect { screen.keepScreenOn = keepOn }   // la pantalla no se apaga mientras suena o graba
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { screen.keepScreenOn = false } }

    fun recordPoint(): Double? = if (u.cursor > 0.05) u.cursor else null
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { micDenied = false; vm.startRecording(speakerId, recordPoint()) } else micDenied = true
    }
    fun record() {
        micDenied = false
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording(speakerId, recordPoint())
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
            confirmButton = { TextButton(onClick = { vm.renameProject(name); renaming = false }, enabled = name.isNotBlank()) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancelar") } },
        )
    }
    renamingCue?.let { id ->
        var name by remember(id) { mutableStateOf(project.cues.firstOrNull { it.id == id }?.text ?: "") }
        AlertDialog(
            onDismissRequest = { renamingCue = null }, title = { Text("Nombre de este audio") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameCue(id, name); renamingCue = null }, enabled = name.isNotBlank()) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { renamingCue = null }) { Text("Cancelar") } },
        )
    }
    if (newCharacter) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newCharacter = false }, title = { Text("Nuevo personaje: ¿cómo se llama?") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre (la bruja, el lobo, mamá…)") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { speaker = vm.addCharacter(name); newCharacter = false }, enabled = name.isNotBlank()) { Text("Agregar") } },
            dismissButton = { TextButton(onClick = { newCharacter = false }) { Text("Cancelar") } },
        )
    }

    androidx.compose.runtime.LaunchedEffect(u.notice) { if (u.notice != null) { kotlinx.coroutines.delay(5000); vm.dismissNotice() } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) micDenied = false
        }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false }, title = { Text("¿Tirar esta grabación?") },
        text = { Text("Se borra lo que llevas grabado y no se puede recuperar.") },
        confirmButton = { TextButton(onClick = { vm.cancelRecording(); confirmDiscard = false }) { Text("Sí, tirarla") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("No, seguir") } },
    )
    androidx.compose.runtime.LaunchedEffect(selectedId) { if (panel == Panel.TONE || panel == Panel.VOLUME || panel == Panel.MOVE || panel == Panel.CURSOR) panel = null }
    androidx.activity.compose.BackHandler {
        when {
            panel != null -> { if (u.previewing) vm.stop(); panel = null }
            selectedId != null -> selectedId = null
            u.recordingFor != null -> vm.finishRecording()   // Atrás no debe tirar una grabación: se guarda
            else -> onBack()
        }
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // ── Barra de arriba (sin deslizar: todo a la vista) ──
            StoryTopBar(vm, project, u, clk.pos, playing, clk.playing && u.loaded, position, working, onBack, { renaming = true }, onNarrate, { zoom = (zoom * 1.5f).coerceAtMost(120f) }, { zoom = (zoom / 1.5f).coerceAtLeast(8f) }, onUpdate, onOtherMode)
            u.busy?.let { (label, p) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                        LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    if (label.startsWith("Preparando")) TextButton(onClick = vm::cancelWork, modifier = Modifier.height(48.dp)) { Text("Cancelar") }
                }
            }

            // ── La línea de tiempo ──
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                StoryTimeline(project, placements, position, playing, selectedId, zoom, enabled = u.busy == null && u.recordingFor == null,
                    onSelect = { selectedId = it }, onCursor = { vm.setCursor(it) }, onMove = { id, s, who -> vm.moveCueTo(id, s, who) },
                    onZoom = { f -> zoom = (zoom * f).coerceIn(8f, 120f) }, focusId = u.lastAddedId, peaks = u.peaks)
                if (project.cues.isEmpty()) StoryEmptyHint(Modifier.align(Alignment.Center))
                Column(Modifier.align(Alignment.BottomCenter)) { StoryNotices(vm, u, micDenied, { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) }
            }

            if (project.cues.isNotEmpty() && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.15f && u.recordingFor == null && u.busy == null && panel == null && u.notice == null && u.error == null && (selected == null || selected.cue.type == CueType.MUSIC))
                Text(
                    if (selected == null) "Para cortar: toca un audio y luego ✂ Cortar aquí. La línea roja marca dónde corta; muévela tocando la tira de colores."
                    else "La música de fondo no se corta: puedes moverla, bajarle el volumen o quitarla.",
                    Modifier.padding(horizontal = 12.dp), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            // ── Avisos ──
            // ── Barra de abajo: cambia según lo que se esté haciendo ──
            StoryBottomBar(vm, project, u, selected, panel, { panel = it }, { selectedId = it }, playing, clk.pos, speakerId, { speaker = it }, { newCharacter = true }, { record() }, { confirmDiscard = true }, { renamingCue = it })
        }

        // ── Hojas encima ──
        when (panel) {
            Panel.SOUNDS -> SoundsSheet(vm, u, onClose = { if (u.previewing) vm.stop(); panel = null }, onPickFile = { panelTypeForImport = CueType.SFX; filePicker.launch(arrayOf("audio/*")) })
            Panel.MUSIC -> MusicSheet(vm, u, onClose = { if (u.previewing) vm.stop(); panel = null }, onPickFile = { panelTypeForImport = CueType.MUSIC; filePicker.launch(arrayOf("audio/*")) })
            Panel.TONE, Panel.VOLUME, Panel.MOVE, Panel.CURSOR -> {}
            null -> {}
        }
    }
}

@Composable
private fun StoryTopBar(
    vm: PrincessViewModel, project: Project, u: PrincessUi, clkPos: Double, playing: Boolean, sounding: Boolean, position: Double, working: Boolean,
    onBack: () -> Unit, onRename: () -> Unit, onNarrate: () -> Unit, onZoomIn: () -> Unit, onZoomOut: () -> Unit,
    onUpdate: (() -> Unit)?, onOtherMode: (() -> Unit)?,
) {
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.height(48.dp).semantics { contentDescription = "Volver a Mis cuentos" }, contentPadding = pad) { Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        TextButton(onClick = { onRename() }, modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = "Cambiar el nombre del cuento: ${project.title}" }, contentPadding = pad) {
            Text(project.title + " ✎", fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        FilledTonalButton(onClick = { vm.undo() }, enabled = u.canUndo && !working, modifier = Modifier.height(48.dp).widthIn(min = 48.dp).semantics { contentDescription = "Deshacer" }, contentPadding = pad) { Text(if (androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.15f) "↶ Deshacer" else "↶", fontSize = if (androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.15f) 15.sp else 20.sp, maxLines = 1) }
        FilledTonalButton(onClick = { vm.redo() }, enabled = u.canRedo && !working, modifier = Modifier.height(48.dp).widthIn(min = 48.dp).semantics { contentDescription = "Rehacer" }, contentPadding = pad) { Text("↷", fontSize = 20.sp, maxLines = 1) }
        Button(
            onClick = { if (playing) { vm.setCursor(clkPos); vm.stop() } else if (sounding) vm.stop() else vm.playFromSeconds(u.cursor) },
            enabled = (project.cues.isNotEmpty() || sounding) && u.busy == null && u.recordingFor == null,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6F5F), contentColor = Color.White),
            modifier = Modifier.height(48.dp).widthIn(min = 150.dp), contentPadding = pad,
        ) { Text(if (sounding) "■ Parar" else "▶ Escuchar ${clock(if (position >= DramaRenderer.totalSeconds(project) - 0.1) 0.0 else position)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        Button(onClick = { vm.export() }, enabled = project.cues.isNotEmpty() && !working, modifier = Modifier.height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0), contentColor = Color.White), contentPadding = pad) { Text("📤 Enviar", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        var more by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { more = true }, modifier = Modifier.height(48.dp).semantics { contentDescription = "Más opciones" }, contentPadding = pad) { Text("Más ▾", fontSize = 15.sp) }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text("💾 Guardar el cuento en mi teléfono") }, enabled = project.cues.isNotEmpty() && !working, onClick = { more = false; vm.saveToPhone() })
                DropdownMenuItem(text = { Text("⏮ Escuchar desde el principio") }, enabled = project.cues.isNotEmpty() && !working, onClick = { more = false; vm.playFromSeconds(0.0) })
                DropdownMenuItem(text = { Text("🗣 Escribir y que una voz de computadora lo lea") }, onClick = { more = false; onNarrate() })
                DropdownMenuItem(text = { Text("Alejar (ver todo el cuento)") }, onClick = { more = false; onZoomIn() })
                DropdownMenuItem(text = { Text("Acercar (ver más detalle)") }, onClick = { more = false; onZoomOut() })
                onUpdate?.let { DropdownMenuItem(text = { Text("Buscar una versión nueva de la app") }, onClick = { more = false; it() }) }
                onOtherMode?.let { DropdownMenuItem(text = { Text("🎸 Ir a la pantalla de hacer música") }, onClick = { more = false; it() }) }
            }
        }
    }
}

@Composable
private fun StoryEmptyHint(modifier: Modifier) {
                    Column(modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(16.dp)).padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Tu cuento empieza aquí", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("1. Toca el botón rojo y cuenta una parte del cuento.", style = MaterialTheme.typography.bodyLarge)
                        Text("2. Con 👤 elige quién habla (el lobo, mamá…) y graba otra parte.", style = MaterialTheme.typography.bodyLarge)
                        Text("3. Toca 🔊 Sonido o 🎵 Música para decorarlo.", style = MaterialTheme.typography.bodyLarge)
                        Text("4. Toca un audio para cortarlo o moverlo.", style = MaterialTheme.typography.bodyLarge)
                        Text("5. Toca ▶ Escuchar y después 📤 Enviar.", style = MaterialTheme.typography.bodyLarge)
                        Text("Si te equivocas, toca ↶ para volver atrás. Tu cuento se guarda solo.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
}

@Composable
private fun StoryNotices(vm: PrincessViewModel, u: PrincessUi, micDenied: Boolean, askPermission: () -> Unit) {
    val ctx = LocalContext.current
            val msg = u.error ?: u.notice
            if (msg != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(
                    if (u.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f).padding(vertical = 8.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = if (u.error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
                    TextButton(onClick = vm::dismissError, modifier = Modifier.height(48.dp), colors = ButtonDefaults.textButtonColors(contentColor = if (u.error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)) { Text("Entendido", fontWeight = FontWeight.Bold) }
                }
            }
            if (micDenied) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Para grabar tu voz necesito usar el micrófono.", Modifier.weight(1f).padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                    TextButton(onClick = { askPermission() }, modifier = Modifier.height(48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) { Text("Dar permiso", fontWeight = FontWeight.Bold) }
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, modifier = Modifier.height(48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) { Text("Abrir ajustes", fontWeight = FontWeight.Bold) }
                }
            }
}

@Composable
private fun StoryBottomBar(
    vm: PrincessViewModel, project: Project, u: PrincessUi, selected: Placement?, panel: Panel?, setPanel: (Panel?) -> Unit, select: (String?) -> Unit,
    playing: Boolean, clkPos: Double, speakerId: String, setSpeaker: (String) -> Unit, newCharacter: () -> Unit, record: () -> Unit, discard: () -> Unit, rename: (String) -> Unit,
) {
    val pos = if (playing) clkPos else u.cursor
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        when {
            u.recordingFor != null -> RecordingBar(u, onStop = { vm.finishRecording() }, onDiscard = discard)
            selected != null && panel == Panel.MOVE -> InlineMove(selected.cue, selected.start, onMove = { delta -> vm.moveCueTo(selected.cue.id, (selected.start + delta).coerceAtLeast(0.0)) }, onDone = { setPanel(null) },
                onVoice = if (selected.cue.type == CueType.VOICE && project.characters.size > 1) { dir ->
                    val ids = project.characters.map { it.id }
                    val i = ids.indexOf(selected.cue.characterId).let { if (it < 0) 0 else it }
                    vm.moveCueTo(selected.cue.id, selected.start, ids[(i + dir + ids.size) % ids.size])
                } else null)
            selected != null && panel == Panel.CURSOR -> InlineCursor(position = pos, onNudge = { vm.nudgeCursor(it) }, onListen = { vm.listenAround() }, onDone = { setPanel(null) })
            selected != null && panel == Panel.TONE -> InlineTone(selected.cue, onPick = { vm.setTone(selected.cue.id, it) }, onListen = { vm.previewCue(selected.cue.id) }, onDone = { setPanel(null) }, busy = u.busy != null)
            selected != null && panel == Panel.VOLUME -> InlineVolume(selected.cue, onSet = { v -> vm.editTimelineCue(selected.cue.id) { c -> c.copy(volume = v) } }, onListen = { vm.previewCue(selected.cue.id) }, onDone = { setPanel(null) })
            selected != null -> SelectionBar(
                selected.cue,
                onCut = { if (playing) { vm.setCursor(clkPos); vm.stop() } else if (u.previewing) vm.stop(); vm.cutAtCursor(selected.cue.id) },
                onMove = { setPanel(Panel.MOVE) }, onTone = { setPanel(Panel.TONE) }, onVolume = { setPanel(Panel.VOLUME) },
                onListen = { vm.previewCue(selected.cue.id) },
                onRemove = { vm.removeCue(selected.cue.id); select(null); vm.setNotice("Quité ese audio. Si fue sin querer, toca ↶ para recuperarlo.") },
                onDone = { select(null) },
                onAction = { a ->
                    val id = selected.cue.id
                    when (a) {
                        CueAction.TRIM_START -> vm.trimStartAtCursor(id)
                        CueAction.TRIM_END -> vm.trimEndAtCursor(id)
                        CueAction.CURSOR -> setPanel(Panel.CURSOR)
                        CueAction.REMOVE_CLOSE -> { vm.removeAndClose(id); select(null); vm.setNotice("Quité ese audio y junté lo que seguía. Si fue sin querer, toca ↶.") }
                        CueAction.DUPLICATE -> vm.duplicateCue(id)
                        CueAction.MUTE -> vm.toggleMute(id)
                        CueAction.FADE_IN -> vm.toggleFade(id, true)
                        CueAction.FADE_OUT -> vm.toggleFade(id, false)
                        CueAction.RENAME -> rename(id)
                    }
                },
                canTone = selected.cue.type != CueType.SILENCE && selected.cue.file != null,
                busy = u.busy != null,
            )
            else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BigButton(if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f) "● Grabar" else if (pos > 0.05) "● Grabar ${clock(pos)}" else "● Grabar mi voz", RED, record, Modifier.weight(1.4f), enabled = u.busy == null)
                SpeakerMenu(project, speakerId, onPick = { setSpeaker(it) }, onNew = { newCharacter() }, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { setPanel(Panel.SOUNDS) }, modifier = Modifier.height(52.dp).weight(1f), enabled = u.busy == null,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text("🔊 Sonido", fontSize = 16.sp, maxLines = 1) }
                FilledTonalButton(onClick = { setPanel(Panel.MUSIC) }, modifier = Modifier.height(52.dp).weight(1f), enabled = u.busy == null,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text("🎵 Música", fontSize = 16.sp, maxLines = 1) }
            }
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
        FilledTonalButton(onClick = { open = true }, modifier = Modifier.height(52.dp).fillMaxWidth().semantics { contentDescription = "Quién habla: $name. Toca para cambiar" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text("👤 $name ▾", fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            project.characters.forEach { c -> DropdownMenuItem(text = { Text((if (c.id == current) "✓ " else "") + c.name) }, onClick = { open = false; onPick(c.id) }) }
            DropdownMenuItem(text = { Text("＋ Personaje nuevo") }, onClick = { open = false; onNew() })
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
            Text(if (u.recSeconds > 3f && u.recLevel < 0.02f) "La barra no se mueve: el micrófono no te oye. Acércate o revisa que no esté tapado." else "Habla. Toca ■ Terminar al acabar.", style = MaterialTheme.typography.bodySmall)
        }
        BigButton("■ Terminar", RED, onStop)
        TextButton(onClick = onDiscard, modifier = Modifier.height(48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Descartar", fontWeight = FontWeight.Bold) }
    }
}

private fun Modifier.size16() = this.then(Modifier.width(16.dp).height(16.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(cue: Cue, onCut: () -> Unit, onMove: () -> Unit, onTone: () -> Unit, onVolume: () -> Unit, onListen: () -> Unit, onRemove: () -> Unit, onDone: () -> Unit,
                         onAction: (CueAction) -> Unit, canTone: Boolean, busy: Boolean) {
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    @Composable fun Btn(text: String, enabled: Boolean, onClick: () -> Unit) =
        FilledTonalButton(onClick = onClick, modifier = Modifier.height(48.dp).widthIn(min = 84.dp), enabled = enabled, contentPadding = pad) { Text(text, fontSize = 15.sp, maxLines = 1) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Btn("✂ Cortar aquí", !busy && cue.type != CueType.MUSIC, onCut)
        Btn("↔ Mover", !busy, onMove)
        Btn("🎚 Voz graciosa", !busy && canTone, onTone)
        Btn("🔉 Volumen", !busy, onVolume)
        Btn("▶ Oír este", !busy, onListen)
        Btn("🗑 Quitar", !busy, onRemove)
        var more by remember { mutableStateOf(false) }
        Box {
            Btn("⋯ Más", !busy) { more = true }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                val cuttable = cue.type != CueType.MUSIC
                fun item(text: String, action: CueAction, enabled: Boolean = true) =
                    @Composable { DropdownMenuItem(text = { Text(text) }, enabled = enabled, onClick = { more = false; onAction(action) }) }
                item("⇤ Borrar lo de antes de la línea roja", CueAction.TRIM_START, cuttable)()
                item("⇥ Borrar lo de después de la línea roja", CueAction.TRIM_END, cuttable)()
                item("🔍 Ajustar la línea roja con precisión", CueAction.CURSOR)()
                item("⤵ Quitar y juntar lo que sigue", CueAction.REMOVE_CLOSE, cue.type == CueType.VOICE)()
                item("⧉ Hacer una copia", CueAction.DUPLICATE)()
                item(if (cue.muted) "🔊 Activar este audio" else "🔇 Silenciar este audio (sin borrarlo)", CueAction.MUTE)()
                item((if (cue.fadeIn > 0f) "✓ " else "") + "Que empiece bajito y suba", CueAction.FADE_IN)()
                item((if (cue.fadeOut > 0f) "✓ " else "") + "Que termine bajito", CueAction.FADE_OUT)()
                item("✎ Cambiar el nombre", CueAction.RENAME)()
            }
        }
        Button(onClick = onDone, modifier = Modifier.height(48.dp).widthIn(min = 84.dp), contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6F5F), contentColor = Color.White)) { Text("✓ Listo", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}

// ───────────────────────────── Línea de tiempo ─────────────────────────────

private data class Lane(val label: String, val color: Color, val clips: List<Placement>, val characterId: String? = null)

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
    onSelect: (String?) -> Unit, onCursor: (Double) -> Unit, onMove: (String, Double, String?) -> Unit, onZoom: (Float) -> Unit, focusId: String? = null, peaks: Map<String, FloatArray> = emptyMap(),
) {
    val lanes = buildList {
        project.characters.forEach { ch -> add(Lane(ch.name, Color(ch.color), placements.filter { it.cue.type == CueType.VOICE && it.cue.characterId == ch.id }, ch.id)) }
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
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var draggingId by remember { mutableStateOf<String?>(null) }
    var viewportPx by remember { mutableStateOf(0) }
    val vScroll = rememberScrollState()
    var manualUntil by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(hScroll.isScrollInProgress) { if (hScroll.isScrollInProgress) manualUntil = System.currentTimeMillis() + 4000 }
    androidx.compose.runtime.LaunchedEffect((position * 5).toInt(), playing, viewportPx) {
        if (viewportPx > 0 && System.currentTimeMillis() > manualUntil) {   // la línea roja no se sale de la pantalla mientras suena
            val x = (position * scale * density).toInt()
            if (x < hScroll.value || x > hScroll.value + viewportPx - 80) hScroll.animateScrollTo((x - viewportPx / 4).coerceAtLeast(0))
        }
    }
    val laneTops = remember(lanes.map { it.label to packRows(it.clips).size }) {
        var y = 0f
        lanes.map { l -> y.also { y += max(1, packRows(l.clips).size) * 56f } }
    }
    // Al elegir un audio, su línea se sube a la vista (con poca altura quedaría escondida debajo de los botones).
    androidx.compose.runtime.LaunchedEffect(selectedId) {
        val idx = lanes.indexOfFirst { l -> l.clips.any { it.cue.id == selectedId } }
        if (selectedId != null && idx >= 0) vScroll.animateScrollTo(((laneTops[idx] + 48f) * density).toInt() - 8)
    }
    androidx.compose.runtime.LaunchedEffect(focusId) {   // el audio recién agregado se trae a la vista (sin pisar lo que el usuario deselecciona)
        val idx = lanes.indexOfFirst { l -> l.clips.any { it.cue.id == focusId } }
        if (focusId != null && idx >= 0) vScroll.animateScrollTo(((laneTops[idx] + 48f) * density).toInt() - 8)
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).padding(6.dp).verticalScroll(vScroll)) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(104.dp)) {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onZoom(1f / 1.5f) }, enabled = zoom > 8.5f, modifier = Modifier.size(48.dp).semantics { contentDescription = "Ver más tiempo en pantalla" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                TextButton(onClick = { onZoom(1.5f) }, enabled = zoom < 119f, modifier = Modifier.size(48.dp).semantics { contentDescription = "Acercar: ver menos tiempo, más grande" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            }
            Column {
                lanes.forEach { lane ->
                    val h = (packRows(lane.clips).size.coerceAtLeast(1) * 56).dp
                    Box(Modifier.height(h).padding(end = 6.dp), contentAlignment = Alignment.CenterStart) {
                        Text(androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = lane.color, fontWeight = FontWeight.Bold)) { append("● ") }
                            append(lane.label)
                        }, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).onSizeChanged { viewportPx = it.width }.horizontalScroll(hScroll)) {
            Box(Modifier.width(widthDp.dp).height(48.dp)
                .semantics {
                    contentDescription = "Regla de tiempo. La línea roja está en ${clock(position)}"
                    customActions = listOf(
                        androidx.compose.ui.semantics.CustomAccessibilityAction("Línea roja un segundo después") { onCursor(position + 1.0); true },
                        androidx.compose.ui.semantics.CustomAccessibilityAction("Línea roja un segundo antes") { onCursor((position - 1.0).coerceAtLeast(0.0)); true },
                        androidx.compose.ui.semantics.CustomAccessibilityAction("Línea roja al principio") { onCursor(0.0); true },
                    )
                }
                .pointerInput(scale, enabled) { detectTapGestures { at -> if (enabled) onCursor((at.x / density / scale).toDouble()) } }) {
                val step = listOf(1, 2, 5, 10, 15, 30, 60, 120, 300).firstOrNull { it * scale >= 72f } ?: 600
                for (s in 0..duration.toInt() step step) Text(clock(s.toDouble()), Modifier.offset(x = (s * scale).dp).padding(top = 12.dp), fontSize = 15.sp)
            }
            Column {
                lanes.forEach { lane ->
                    val laneIdx = lanes.indexOf(lane)
                    packRows(lane.clips).ifEmpty { listOf(emptyList()) }.forEachIndexed { rowIdx, row ->
                        Box(
                            Modifier.width(widthDp.dp).height(56.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .zIndex(if (row.any { it.cue.id == draggingId }) 1f else 0f)
                                .pointerInput(scale, enabled) { detectTapGestures { at -> if (enabled) { onCursor((at.x / density / scale).toDouble()); onSelect(null) } } },
                        ) {
                            Canvas(Modifier.fillMaxSize().zIndex(1f)) {
                                val x = (position * scale * density).toFloat()
                                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 6.dp.toPx())
                                drawLine(RED, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
                            }
                            row.forEach { place ->
                                var dragX by remember(place.cue.id, place.start) { mutableStateOf(0f) }
                                var dragY by remember(place.cue.id, place.start) { mutableStateOf(0f) }
                                val isSel = place.cue.id == selectedId
                                val lifted = draggingId == place.cue.id
                                val start = (place.start + dragX).coerceAtLeast(0.0)
                                val curPlace by androidx.compose.runtime.rememberUpdatedState(place)
                                val curScale by androidx.compose.runtime.rememberUpdatedState(scale)
                                Box(
                                    Modifier.offset(x = (start * scale).toFloat().dp, y = 4.dp + dragY.dp)
                                        .width(max(48f, ((place.end - place.start) * scale).toFloat()).dp).height(48.dp)
                                        .background(lane.color.copy(alpha = if (place.cue.muted) 0.7f else if (lifted || isSel) 1f else 0.85f), RoundedCornerShape(8.dp))
                                        .border(if (isSel || lifted) 3.dp else 1.dp, if (isSel || lifted) Color.White else lane.color, RoundedCornerShape(8.dp))
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = (if (place.cue.muted) "Silenciado. " else "") + "${place.cue.type.label} ${place.cue.text.ifBlank { "" }}, empieza en ${clock(place.start)}, dura ${"%.0f".format(place.end - place.start)} segundos"
                                            selected = isSel
                                            role = androidx.compose.ui.semantics.Role.Button
                                            onClick(label = "Elegir") { onSelect(place.cue.id); true }
                                            customActions = listOf(
                                                androidx.compose.ui.semantics.CustomAccessibilityAction("Poner la línea roja al inicio de este audio") { onCursor(place.start); true },
                                                androidx.compose.ui.semantics.CustomAccessibilityAction("Mover un segundo después") { onMove(place.cue.id, place.start + 1.0, null); true },
                                                androidx.compose.ui.semantics.CustomAccessibilityAction("Mover un segundo antes") { onMove(place.cue.id, (place.start - 1.0).coerceAtLeast(0.0), null); true },
                                            )
                                        }
                                        .pointerInput(place.cue.id, enabled) {
                                            if (!enabled) return@pointerInput
                                            detectTapGestures { at -> onSelect(place.cue.id); onCursor(curPlace.start + (at.x / density / curScale).toDouble()) }
                                        }
                                        .pointerInput(place.cue.id, enabled) {
                                            if (!enabled) return@pointerInput
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); draggingId = curPlace.cue.id; onSelect(curPlace.cue.id) },
                                                onDragEnd = {
                                                    val target = (curPlace.start + dragX).coerceAtLeast(0.0)
                                                    val yCenter = laneTops[laneIdx] + rowIdx * 56f + 28f + dragY
                                                    val toLane = laneTops.indexOfLast { it <= yCenter }.coerceAtLeast(0)
                                                    val who = if (curPlace.cue.type == CueType.VOICE && toLane != laneIdx) lanes.getOrNull(toLane)?.characterId else null
                                                    dragX = 0f; dragY = 0f; draggingId = null
                                                    onMove(curPlace.cue.id, target, who)
                                                },
                                                onDragCancel = { dragX = 0f; dragY = 0f; draggingId = null },
                                                onDrag = { change, amount -> change.consume(); dragX += amount.x / density / curScale; dragY += amount.y / density },
                                            )
                                        }.padding(6.dp),
                                ) {
                                    val pk = place.cue.file?.let { peaks[it] }
                                    if (pk != null && pk.isNotEmpty() && place.cue.seconds > 0f) {
                                        Canvas(Modifier.matchParentSize()) {
                                            val total = place.cue.seconds
                                            val from = place.cue.trimStart / total
                                            val span = ((place.end - place.start).toFloat() / total).coerceAtMost(1f - from)
                                            val barW = 3.dp.toPx()
                                            var x = 0f
                                            while (x < size.width) {
                                                val idx = ((from + span * (x / size.width)) * pk.size).toInt().coerceIn(0, pk.size - 1)
                                                val h = (pk[idx] * size.height * 0.8f).coerceAtLeast(2.dp.toPx())
                                                drawRect(Color(0x550B1020), Offset(x, (size.height - h) / 2f), androidx.compose.ui.geometry.Size(barW - 1.dp.toPx(), h))
                                                x += barW
                                            }
                                        }
                                    }
                                    Text((if (isSel) "✓ " else "") + (if (place.cue.muted) "🔇 " else "") + (if (place.cue.toneSteps != 0) "🎚 " else "") + place.cue.text.ifBlank { place.cue.type.label },
                                        fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = Color(0xFF0B1020), fontWeight = FontWeight.Bold)
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
