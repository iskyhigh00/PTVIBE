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
import androidx.compose.material3.Surface
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
internal val storyControlShape = RoundedCornerShape(10.dp)

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
internal enum class CueAction { TRIM_START, TRIM_END, CURSOR, REMOVE_CLOSE, DUPLICATE, MUTE, FADE_IN, FADE_OUT, DUCK, RENAME, TONE, RERECORD, REPLACE_FILE }

/**
 * Cuentos: hacer el audio de un cuento. Pensada para quien la abre por primera vez: un botón rojo para grabar, sonidos y música
 * a un toque, y al tocar un audio aparecen cortar, cambiar la voz, volumen y quitar. Todo se puede deshacer.
 */
@Composable
fun StoryScreen(
    vm: PrincessViewModel,
    modifier: Modifier = Modifier,
    onNarrate: (String) -> Unit,
    onOtherMode: (() -> Unit)? = null,
    onUpdate: (() -> Unit)? = null,
) {
    val u by vm.ui.collectAsState()
    var inEditor by rememberSaveable { mutableStateOf(true) }
    val project = u.current
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (u.creating) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Creando tu cuento…", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        } else if (project == null || !inEditor) {
            StoryList(vm, u, onOpen = { vm.open(it); inEditor = true }, onNew = { vm.newProject("Mi cuento ${nextStoryNumber(u.projects)}"); inEditor = true },
                Modifier, onOtherMode, onUpdate)
        } else {
            StoryEditor(vm, project, onBack = { vm.onLeave(); inEditor = false }, onNarrate = onNarrate, modifier = Modifier, onOtherMode = onOtherMode, onUpdate = onUpdate)
        }
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
                Text("Graba tu voz o escribe el cuento. Agrega sonidos y música, y compártelo.", textAlign = TextAlign.Center,
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
    vm: PrincessViewModel, project: Project, onBack: () -> Unit, onNarrate: (String) -> Unit, modifier: Modifier,
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

    var pendingVoice by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var pendingReplacement by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var pendingStart by rememberSaveable(project.id) { mutableStateOf<Double?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            micDenied = false
            pendingVoice?.let { vm.startRecording(it, pendingStart, pendingReplacement) }
            pendingVoice = null
            pendingReplacement = null
            pendingStart = null
        } else micDenied = true
    }
    fun record(cue: Cue? = null) {
        val who = cue?.characterId ?: speakerId
        val start = if (cue == null) u.cursor.takeIf { it > 0.05 } else null
        micDenied = false
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startRecording(who, start, cue?.id)
        } else {
            pendingVoice = who
            pendingReplacement = cue?.id
            pendingStart = start
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val prefsApp = remember { ctx.getSharedPreferences("glide_app", android.content.Context.MODE_PRIVATE) }
    fun askNotifications() {   // para que el aviso «Tu cuento está sonando» se vea cuando se escucha con la pantalla apagada
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefsApp.getBoolean("asked_notif", false) &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefsApp.edit().putBoolean("asked_notif", true).apply()
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var fileType by rememberSaveable(project.id) { mutableStateOf(CueType.SFX) }
    var fileCharacter by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var fileReplacement by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri, fileType, fileCharacter, fileReplacement)
    }
    fun pickFile(type: CueType, who: String? = null, replacement: String? = null) {
        fileType = type
        fileCharacter = who
        fileReplacement = replacement
        filePicker.launch(arrayOf("audio/*"))
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
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // ── Barra de arriba (sin deslizar: todo a la vista) ──
            StoryTopBar(vm, project, u, clk.pos, playing, clk.playing && u.loaded, position, working, { askNotifications() }, onBack, { renaming = true }, { pickFile(CueType.VOICE, speakerId) }, { zoom = (zoom * 1.5f).coerceAtMost(120f) }, { zoom = (zoom / 1.5f).coerceAtLeast(8f) }, onUpdate, onOtherMode)
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
            }
            StoryNotices(vm, u, micDenied, { micPermission.launch(Manifest.permission.RECORD_AUDIO) })

            // ── Barra de abajo: cambia según lo que se esté haciendo ──
            StoryBottomBar(vm, project, u, selected, panel, { panel = it }, { selectedId = it }, playing, clk.pos, speakerId, { speaker = it }, { newCharacter = true }, { record() }, { confirmDiscard = true }, { renamingCue = it },
                { onNarrate(speakerId) }, { record(it) }, { pickFile(CueType.VOICE, it.characterId, it.id) })
        }

        // ── Hojas encima ──
        when (panel) {
            Panel.SOUNDS -> SoundsSheet(vm, u, onClose = { if (u.previewing) vm.stop(); panel = null }, onPickFile = { pickFile(CueType.SFX) })
            Panel.MUSIC -> MusicSheet(vm, u, onClose = { if (u.previewing) vm.stop(); panel = null }, onPickFile = { pickFile(CueType.MUSIC) })
            Panel.TONE, Panel.VOLUME, Panel.MOVE, Panel.CURSOR -> {}
            null -> {}
        }
    }
}

@Composable
private fun StoryTopBar(
    vm: PrincessViewModel, project: Project, u: PrincessUi, clkPos: Double, playing: Boolean, sounding: Boolean, position: Double, working: Boolean, beforePlay: () -> Unit,
    onBack: () -> Unit, onRename: () -> Unit, onImportVoice: () -> Unit, onZoomIn: () -> Unit, onZoomOut: () -> Unit,
    onUpdate: (() -> Unit)?, onOtherMode: (() -> Unit)?,
) {
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.size(48.dp).semantics { contentDescription = "Volver a Mis cuentos" }, contentPadding = pad) { Text("←", fontSize = 19.sp) }
        TextButton(onClick = { onRename() }, modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = "Cambiar el nombre del cuento: ${project.title}" }, contentPadding = pad) {
            Text(project.title + " ✎", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        TextButton(onClick = vm::undo, enabled = u.canUndo && !working, modifier = Modifier.size(48.dp).semantics { contentDescription = "Deshacer" }, contentPadding = pad) { Text("↶", fontSize = 19.sp) }
        TextButton(onClick = vm::redo, enabled = u.canRedo && !working, modifier = Modifier.size(48.dp).semantics { contentDescription = "Rehacer" }, contentPadding = pad) { Text("↷", fontSize = 19.sp) }
        Button(
            onClick = { if (playing) { vm.setCursor(clkPos); vm.stop() } else if (sounding) vm.stop() else { beforePlay(); vm.playFromSeconds(u.cursor) } },
            enabled = (project.cues.isNotEmpty() || sounding) && u.busy == null && u.recordingFor == null,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6F5F), contentColor = Color.White),
            modifier = Modifier.height(48.dp).widthIn(min = 112.dp), contentPadding = pad, shape = storyControlShape,
        ) { Text(if (sounding) "■ Parar" else "▶ Escuchar ${clock(if (position >= DramaRenderer.totalSeconds(project) - 0.1) 0.0 else position)}", fontSize = 13.sp, maxLines = 1) }
        var exportMenu by remember { mutableStateOf(false) }
        Box {
            Button(onClick = { exportMenu = true }, enabled = project.cues.isNotEmpty() && !working,
                modifier = Modifier.height(48.dp).semantics { contentDescription = "Enviar o guardar el cuento" },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0), contentColor = Color.White),
                contentPadding = pad, shape = storyControlShape) { Text("📤 Enviar", fontSize = 13.sp, maxLines = 1) }
            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                DropdownMenuItem(text = { Text("Enviar por WhatsApp u otra app") }, onClick = { exportMenu = false; vm.export() })
                DropdownMenuItem(text = { Text("Guardar el cuento en mi teléfono") }, onClick = { exportMenu = false; vm.saveToPhone() })
            }
        }
        var more by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { more = true }, modifier = Modifier.height(48.dp).semantics { contentDescription = "Más opciones" }, contentPadding = pad) { Text("Más ▾", fontSize = 13.sp) }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text("⏮ Escuchar desde el principio") }, enabled = project.cues.isNotEmpty() && !working, onClick = { more = false; vm.playFromSeconds(0.0) })
                DropdownMenuItem(text = { Text("Importar una voz desde el teléfono") }, enabled = !working, onClick = { more = false; onImportVoice() })
                DropdownMenuItem(text = { Text("Alejar (ver todo el cuento)") }, onClick = { more = false; onZoomIn() })
                DropdownMenuItem(text = { Text("Acercar (ver más detalle)") }, onClick = { more = false; onZoomOut() })
                onUpdate?.let { DropdownMenuItem(text = { Text("Buscar una versión nueva de la app") }, onClick = { more = false; it() }) }
                onOtherMode?.let { DropdownMenuItem(text = { Text("🎸 Ir a la pantalla de hacer música") }, onClick = { more = false; it() }) }
            }
        }
    }
}

@Composable
private fun StoryNotices(vm: PrincessViewModel, u: PrincessUi, micDenied: Boolean, askPermission: () -> Unit) {
    val ctx = LocalContext.current
    (u.error ?: u.notice)?.let { message ->
        val error = u.error != null
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .background(if (error) scheme.errorContainer else scheme.surfaceVariant, storyControlShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message, Modifier.weight(1f).padding(vertical = 4.dp)
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = if (error) scheme.onErrorContainer else scheme.onSurfaceVariant,
            )
            if (error) {
                TextButton(onClick = vm::dismissError, modifier = Modifier.height(48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onErrorContainer)) {
                    Text("Entendido", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    if (micDenied) {
        val color = MaterialTheme.colorScheme.onTertiaryContainer
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .background(MaterialTheme.colorScheme.tertiaryContainer, storyControlShape).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Se necesita permiso de micrófono.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = color)
            TextButton(onClick = askPermission, modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = color)) { Text("Dar permiso", fontSize = 12.sp) }
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.height(48.dp), colors = ButtonDefaults.textButtonColors(contentColor = color)) { Text("Abrir ajustes", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun StoryBottomBar(
    vm: PrincessViewModel, project: Project, u: PrincessUi, selected: Placement?, panel: Panel?, setPanel: (Panel?) -> Unit, select: (String?) -> Unit,
    playing: Boolean, clkPos: Double, speakerId: String, setSpeaker: (String) -> Unit, newCharacter: () -> Unit, record: () -> Unit, discard: () -> Unit, rename: (String) -> Unit,
    narrate: () -> Unit, rerecord: (Cue) -> Unit, replaceFile: (Cue) -> Unit,
) {
    val pos = if (playing) clkPos else u.cursor
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
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
            selected != null && panel == Panel.VOLUME -> InlineVolume(
                selected.cue,
                onSet = { v -> vm.editTimelineCue(selected.cue.id) { c -> c.copy(volume = v) } },
                onMix = { vm.listenAround(if (pos in selected.start..selected.end) pos else selected.start) },
                onSolo = { vm.previewCue(selected.cue.id) },
                onDone = { setPanel(null) },
                busy = u.busy != null,
            )
            selected != null -> SelectionBar(
                selected.cue,
                onCut = { if (playing) { vm.setCursor(clkPos); vm.stop() } else if (u.previewing) vm.stop(); vm.cutAtCursor(selected.cue.id) },
                onMove = { setPanel(Panel.MOVE) }, onVolume = { setPanel(Panel.VOLUME) },
                onListen = { vm.previewCue(selected.cue.id) },
                onRemove = { vm.removeCue(selected.cue.id); select(null) },
                onDone = { select(null) },
                onAction = { a ->
                    val id = selected.cue.id
                    when (a) {
                        CueAction.TRIM_START -> vm.trimStartAtCursor(id)
                        CueAction.TRIM_END -> vm.trimEndAtCursor(id)
                        CueAction.CURSOR -> setPanel(Panel.CURSOR)
                        CueAction.REMOVE_CLOSE -> { vm.removeAndClose(id); select(null) }
                        CueAction.DUPLICATE -> vm.duplicateCue(id)
                        CueAction.MUTE -> vm.toggleMute(id)
                        CueAction.FADE_IN -> vm.toggleFade(id, true)
                        CueAction.FADE_OUT -> vm.toggleFade(id, false)
                        CueAction.DUCK -> vm.editTimelineCue(id) { it.copy(duckUnderVoice = !it.duckUnderVoice) }
                        CueAction.RENAME -> rename(id)
                        CueAction.TONE -> setPanel(Panel.TONE)
                        CueAction.RERECORD -> rerecord(selected.cue)
                        CueAction.REPLACE_FILE -> replaceFile(selected.cue)
                    }
                },
                canTone = selected.cue.type != CueType.SILENCE && selected.cue.file != null,
                busy = u.busy != null,
            )
            else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = record, enabled = u.busy == null, shape = storyControlShape,
                    colors = ButtonDefaults.buttonColors(containerColor = RED, contentColor = Color.White),
                    modifier = Modifier.height(48.dp).weight(1f).semantics {
                        contentDescription = if (pos > 0.05) "Grabar mi voz desde ${clock(pos)}" else "Grabar mi voz"
                    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                ) { Text("● Grabar", fontSize = 14.sp, maxLines = 1) }
                FilledTonalButton(onClick = narrate, modifier = Modifier.height(48.dp).weight(1f), enabled = u.busy == null, shape = storyControlShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) { Text("✎ Escribir", fontSize = 13.sp, maxLines = 1) }
                SpeakerMenu(project, speakerId, onPick = { setSpeaker(it) }, onNew = { newCharacter() }, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { setPanel(Panel.SOUNDS) }, modifier = Modifier.height(48.dp).weight(1f), enabled = u.busy == null, shape = storyControlShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) { Text("🔊 Sonido", fontSize = 13.sp, maxLines = 1) }
                FilledTonalButton(onClick = { setPanel(Panel.MUSIC) }, modifier = Modifier.height(48.dp).weight(1f), enabled = u.busy == null, shape = storyControlShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) { Text("🎵 Música", fontSize = 13.sp, maxLines = 1) }
            }
        }
    }
}

/** Qué se va a traer del teléfono con el selector de archivos (se recuerda entre la hoja y el resultado). */
@Composable
private fun SpeakerMenu(project: Project, current: String, onPick: (String) -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val name = project.characters.firstOrNull { it.id == current }?.name ?: "Yo"
    Box(modifier) {
        FilledTonalButton(onClick = { open = true }, modifier = Modifier.height(48.dp).fillMaxWidth().semantics { contentDescription = "Quién habla: $name. Toca para cambiar" }, shape = storyControlShape, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) { Text("👤 $name ▾", fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            project.characters.forEach { c -> DropdownMenuItem(text = { Text((if (c.id == current) "✓ " else "") + c.name) }, onClick = { open = false; onPick(c.id) }) }
            DropdownMenuItem(text = { Text("＋ Personaje nuevo") }, onClick = { open = false; onNew() })
        }
    }
}

@Composable
private fun RecordingBar(u: PrincessUi, onStop: () -> Unit, onDiscard: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0x33E5334B), storyControlShape).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(RED, CircleShape))
        Column(Modifier.weight(1f)) {
            Text("Grabando · ${clock(u.recSeconds.toDouble())}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            LinearProgressIndicator(progress = { (u.recLevel * 2.5f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            if (u.recSeconds > 4f && u.recPeak < 0.09f) {
                Text("Nivel bajo. Acércate al micrófono.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = onStop, modifier = Modifier.height(48.dp), shape = storyControlShape,
            colors = ButtonDefaults.buttonColors(containerColor = RED, contentColor = Color.White)) { Text("■ Terminar", fontSize = 13.sp) }
        TextButton(onClick = onDiscard, modifier = Modifier.height(48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Descartar", fontSize = 13.sp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(cue: Cue, onCut: () -> Unit, onMove: () -> Unit, onVolume: () -> Unit, onListen: () -> Unit, onRemove: () -> Unit, onDone: () -> Unit,
                         onAction: (CueAction) -> Unit, canTone: Boolean, busy: Boolean) {
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    @Composable fun Btn(text: String, enabled: Boolean, onClick: () -> Unit) =
        FilledTonalButton(onClick = onClick, modifier = Modifier.height(48.dp).widthIn(min = 48.dp), enabled = enabled, contentPadding = pad, shape = storyControlShape) { Text(text, fontSize = 13.sp, maxLines = 1) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Btn("✂ Cortar aquí", !busy && cue.type != CueType.MUSIC, onCut)
        Btn("↔ Mover", !busy, onMove)
        Btn("🔉 Volumen", !busy, onVolume)
        Btn("▶ Oír", !busy, onListen)
        Btn("🗑 Quitar", !busy, onRemove)
        var more by remember { mutableStateOf(false) }
        Box {
            Btn("⋯ Más", !busy) { more = true }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                val cuttable = cue.type != CueType.MUSIC
                fun item(text: String, action: CueAction, enabled: Boolean = true) =
                    @Composable { DropdownMenuItem(text = { Text(text) }, enabled = enabled, onClick = { more = false; onAction(action) }) }
                if (cue.type == CueType.VOICE) {
                    item("Volver a grabar", CueAction.RERECORD, cue.characterId != null)()
                    item("Cambiar por un archivo…", CueAction.REPLACE_FILE)()
                }
                if (canTone) item("🎚 Voz graciosa", CueAction.TONE)()
                item("⇤ Borrar lo de antes de la línea roja" + (if (cuttable) "" else " (no en música)"), CueAction.TRIM_START, cuttable)()
                item("⇥ Borrar lo de después de la línea roja" + (if (cuttable) "" else " (no en música)"), CueAction.TRIM_END, cuttable)()
                item("🔍 Ajustar la línea roja con precisión", CueAction.CURSOR)()
                item("⤵ Quitar este pedacito y juntar el resto" + (if (cue.type == CueType.VOICE) "" else " (solo en pedacitos de voz)"), CueAction.REMOVE_CLOSE, cue.type == CueType.VOICE)()
                item("⧉ Hacer una copia", CueAction.DUPLICATE)()
                item(if (cue.muted) "🔊 Activar este audio" else "🔇 Silenciar este audio (sin borrarlo)", CueAction.MUTE)()
                if (cue.type == CueType.SFX) {
                    item((if (cue.duckUnderVoice) "✓ " else "") + "Bajar mientras hay voz", CueAction.DUCK)()
                }
                item((if (cue.fadeIn > 0f) "✓ " else "") + "Que empiece bajito y suba", CueAction.FADE_IN)()
                item((if (cue.fadeOut > 0f) "✓ " else "") + "Que termine bajito", CueAction.FADE_OUT)()
                item("✎ Cambiar el nombre", CueAction.RENAME)()
            }
        }
        Button(onClick = onDone, modifier = Modifier.height(48.dp).widthIn(min = 48.dp), contentPadding = pad, shape = storyControlShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6F5F), contentColor = Color.White)) { Text("✓ Listo", fontSize = 13.sp, maxLines = 1) }
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
        if (selectedId != null && idx >= 0) vScroll.animateScrollTo((laneTops[idx] * density).toInt())
    }
    androidx.compose.runtime.LaunchedEffect(focusId) {   // el audio recién agregado se trae a la vista (sin pisar lo que el usuario deselecciona)
        val idx = lanes.indexOfFirst { l -> l.clips.any { it.cue.id == focusId } }
        if (focusId != null && idx >= 0) vScroll.animateScrollTo((laneTops[idx] * density).toInt())
    }
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(6.dp)) {
        Column(Modifier.width(104.dp)) {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onZoom(1f / 1.5f) }, enabled = zoom > 8.5f, modifier = Modifier.size(48.dp).semantics { contentDescription = "Ver más tiempo en pantalla" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−", fontSize = 20.sp) }
                TextButton(onClick = { onZoom(1.5f) }, enabled = zoom < 119f, modifier = Modifier.size(48.dp).semantics { contentDescription = "Acercar: ver menos tiempo, más grande" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+", fontSize = 20.sp) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(vScroll)) {
                lanes.forEach { lane ->
                    val h = (packRows(lane.clips).size.coerceAtLeast(1) * 56).dp
                    Box(Modifier.height(h).padding(end = 6.dp), contentAlignment = Alignment.CenterStart) {
                        Text(androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = lane.color, fontWeight = FontWeight.Bold)) { append("● ") }
                            append(lane.label)
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
                for (s in 0..duration.toInt() step step) Text(clock(s.toDouble()), Modifier.align(Alignment.CenterStart).offset(x = (s * scale).dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(vScroll)) {
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
                                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
                                drawLine(RED, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
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
                                        fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = Color(0xFF0B1020), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
