package com.glidelive.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.glidelive.viz.EventHub
import com.glidelive.viz.VizPrefs
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import com.glidelive.ui.components.AppBackground
import com.glidelive.ui.components.CapsuleBar
import com.glidelive.ui.components.EqBars
import com.glidelive.ui.components.GlassCard
import com.glidelive.ui.components.GradientButton
import com.glidelive.ui.components.TonalButton
import com.glidelive.ui.theme.AppTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.glidelive.audio.KitLoader
import com.glidelive.theory.Scale
import com.glidelive.theory.Bank
import com.glidelive.demo.TocataSong
import com.glidelive.audio.KitVoice
import com.glidelive.audio.KitState
import com.glidelive.audio.NativeEngine

class MainActivity : ComponentActivity() {
    private val vm: EngineViewModel by viewModels()
    private val updateVm: UpdateViewModel by viewModels()
    private val chordsVm: ChordsViewModel by viewModels()
    private val soloVm: SoloViewModel by viewModels()
    private val stemsVm: StemsViewModel by viewModels()
    private val princessVm: PrincessViewModel by viewModels()
    private val transportVm: TransportViewModel by viewModels()
    private val sessionVm: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        EventHub.start(lifecycleScope)
        VizPrefs.init(this)
        PadBindings.init(this)
        sessionVm.attach(chordsVm, soloVm, vm, transportVm)
        setContent {
            AppTheme {
                AppBackground {
                    var mode by rememberSaveable { mutableStateOf(Mode.ACORDES) }
                    val princessUi by princessVm.ui.collectAsState()
                    var voiceStudio by remember(princessUi.current?.id) { mutableStateOf(false) }
                    var voiceDocument by remember(princessUi.current?.id) { mutableStateOf(TextFieldValue()) }
                    val cfg = LocalConfiguration.current
                    // Los ajustes de cada pestaña se abren en una ventana encima (semitransparente, con X para cerrar).
                    var settingsOpen by rememberSaveable { mutableStateOf(false) }
                    var settingsGeneral by rememberSaveable { mutableStateOf(false) }
                    var demoOpen by remember { mutableStateOf(false) }
                    val startDemo: (Int) -> Unit = { i ->
                        // Las notas de la canción: Fa# menor en acordes y solista, banco C17 con sus acordes.
                        chordsVm.edit { it.copy(key = TocataSong.KEY, scale = Scale.NATURAL_MINOR, bank = Bank.C17, transpose = 0) }
                        soloVm.edit { it.copy(scaleOverride = null, followChord = false) }
                        transportVm.startDemo(i)
                    }
                    val demo by transportVm.demo.collectAsState()
                    LaunchedEffect(demo.active, demo.section) {
                        if (demo.active) {
                            val sec = TocataSong.sections[demo.section]
                            chordsVm.edit { it.copy(instrument = sec.guitar) }  // guitarra rítmica limpia o con distorsión según la parte
                            sec.soloInstrument?.let { inst -> soloVm.edit { it.copy(instrument = inst) } }
                        }
                    }
                    if (demoOpen) DemoDialog(transportVm, onDismiss = { demoOpen = false }, onStart = startDemo)
                    var keysOpen by remember { mutableStateOf(false) }
                    val capture by sessionVm.capture.collectAsState()
                    capture.pending?.let { file ->
                        var name by remember(file) { mutableStateOf("Mi sonido") }
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { sessionVm.discardCapture() },
                            title = { Text("Guardar como sonido de Princess") },
                            text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Nombre del sonido") }, singleLine = true) },
                            confirmButton = { TextButton(onClick = { princessVm.saveCaptured(file, name); sessionVm.captureHandled() }) { Text("Guardar") } },
                            dismissButton = { TextButton(onClick = { sessionVm.discardCapture() }) { Text("Descartar") } },
                        )
                    }
                    LaunchedEffect(mode) { PadBindings.keysEnabled = mode == Mode.BATERIA || mode == Mode.MIXTO }
                    val shortScreen = cfg.screenHeightDp < 480
                    var fullscreen by rememberSaveable { mutableStateOf(false) }
                    var soloLayer by rememberSaveable { mutableStateOf(false) }
                    val kit by vm.kit.collectAsState()
                    val view = LocalView.current
                    LaunchedEffect(fullscreen) {
                        val c = WindowCompat.getInsetsController(window, view)
                        if (fullscreen) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
                    }
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(playInsets)) {
                            if (!fullscreen) {
                                TopBar(mode, { mode = it }, chordsVm, soloVm, vm, sessionVm, transportVm,
                                    { settingsGeneral = false; settingsOpen = true }, { settingsGeneral = true; settingsOpen = true },
                                    { fullscreen = true }, { demoOpen = true }) {
                                    // Mixto: sin barra de base; solo lo mínimo en la fila de arriba para dejar el espacio a los pads.
                                    if (mode == Mode.MIXTO) {
                                        val tu by transportVm.ui.collectAsState()
                                        FilterChip(selected = tu.patternOn, onClick = transportVm::togglePattern, label = { Text(if (tu.patternOn) "■ Base" else "▶ Base") })
                                        FilterChip(selected = soloLayer, onClick = { soloLayer = !soloLayer }, label = { Text("Solista") })
                                    }
                                }
                                DemoStrip(transportVm) { demoOpen = true }
                                if (mode != Mode.PISTAS && mode != Mode.PRINCESS) StemStrip(stemsVm) { mode = Mode.PISTAS }
                                if (mode == Mode.BATERIA) {
                                    PatternBar(transportVm) {
                                        val k by vm.kit.collectAsState()
                                        Picker("Pads: ${k.padLayout.label}", PadLayout.entries, { it.label + " — " + it.hint }) { l ->
                                            vm.editKit { it.copy(padLayout = l) }
                                        }
                                        TextButton(onClick = { keysOpen = true }) { Text("Teclas de los pads…") }
                                    }
                                }
                            }
                            Row(
                                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f).fillMaxHeight()) {
                                    when (mode) {
                                        Mode.ACORDES -> ChordsScreen(chordsVm, compact = fullscreen, Modifier.weight(1f))
                                        Mode.VOCES -> {
                                            AndroidView(
                                                factory = { ChordVizView(it) },
                                                modifier = Modifier.weight(1.5f).fillMaxWidth().padding(bottom = 6.dp),
                                            )
                                            ChordsScreen(chordsVm, compact = true, Modifier.weight(1f))
                                        }
                                        Mode.SOLISTA -> SoloHost(soloVm, Modifier.weight(1f))
                                        Mode.BATERIA -> DrumsHost(kit.pressure, kit.padLayout, Modifier.weight(1f))
                                        Mode.PRINCESS -> {
                                            androidx.compose.runtime.LaunchedEffect(Unit) { princessVm.beforePlay = { stemsVm.close() } }
                                            PrincessScreen(princessVm, Modifier.weight(1f), onNarrate = {
                                                princessVm.stop()
                                                voiceStudio = true
                                            })
                                        }
                                        Mode.PISTAS -> {
                                            val soloUi by soloVm.ui.collectAsState()
                                            StemsScreen(stemsVm, Modifier.weight(1f), soloUi.instrument) { i -> soloVm.edit { it.copy(instrument = i) } }
                                        }
                                        Mode.DUO -> PerformScreen(
                                            chordsVm, soloVm, kit.pressure,
                                            viz = true, chords = true, solo = true, drums = false,
                                            modifier = Modifier.weight(1f),
                                            padLayout = kit.padLayout,
                                        )
                                        // Un solo contenedor multitouch; con poca altura, las superficies van lado a lado.
                                        Mode.MIXTO -> PerformScreen(
                                            chordsVm, soloVm, kit.pressure,
                                            viz = false, chords = true, solo = soloLayer, drums = true,
                                            modifier = Modifier.weight(1f),
                                            horizontal = shortScreen,
                                            showSoloControls = soloLayer,
                                            padLayout = kit.padLayout,
                                        )
                                    }
                                }
                            }
                        }
                        if (fullscreen) {
                            BpmBadge(transportVm, Modifier.align(Alignment.TopStart).windowInsetsPadding(playInsets).padding(4.dp))
                            TextButton(
                                onClick = { fullscreen = false },
                                modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(playInsets),
                            ) { Text("⤡ Salir") }
                        }
                        if (settingsOpen && !fullscreen) {
                            SettingsModal(mode, settingsGeneral, { settingsOpen = false }, vm, updateVm, chordsVm, soloVm, transportVm, sessionVm)
                        }
                        if (keysOpen) PadKeysPanel(sessionVm.midi.status, onClose = { keysOpen = false; PadBindings.startLearn(null) })
                        // Keep the editor in the Activity's measured viewport. A separate
                        // Android Dialog can have different landscape bounds on some phones.
                        if (voiceStudio) princessUi.current?.let { project ->
                            VoiceStudioScreen(project, princessVm, voiceDocument,
                                { voiceDocument = it }, onClose = { voiceStudio = false },
                                modifier = Modifier.matchParentSize())
                        }
                    }
                }
            }
        }
    }

    /** Teclado físico: cada tecla asignada toca su pad (y, si se está asignando, queda ligada al pad en espera). */
    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        val code = e.keyCode
        if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0 && e.isPrintingKey && PadBindings.learnKey(code)) return true
        if (PadBindings.keysEnabled && !e.isCtrlPressed && !e.isAltPressed && !e.isMetaPressed) {
            val pad = PadBindings.padForKey(code)
            if (pad != null) {
                if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                    for (m in Pads.members(pad)) NativeEngine.trigger(m, DrumHit.VELOCITY, e.eventTime)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onResume() {
        super.onResume()
        if (!NativeEngine.start()) {
            // El dispositivo de salida puede tardar en estar disponible: 3 reintentos espaciados.
            lifecycleScope.launch {
                for (i in 1..3) {
                    delay(400L * i)
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                    if (NativeEngine.start()) return@launch
                }
            }
        }
    }

    override fun onPause() {
        sessionVm.flush()
        sessionVm.stopIfRecording(transportVm.ui.value.bpm)
        chordsVm.stopAll(immediate = true)
        NativeEngine.stop()
        super.onPause()
    }
}

/** Zona segura para tocar: barras del sistema, recortes de pantalla y los bordes laterales reservados al gesto de volver. */
val playInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.union(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))

enum class Mode(val label: String) {
    ACORDES("Acordes"), VOCES("Voces"), DUO("Voces + Solista"), SOLISTA("Solista"), BATERIA("Batería"), MIXTO("Mixto"), PISTAS("Pistas"), PRINCESS("Princess")
}

@Composable
private fun DrumsHost(pressure: Boolean, padLayout: PadLayout, modifier: Modifier) {
    AndroidView(
        factory = { DrumPadsView(it) },
        update = { it.pressureSensitivity = pressure; it.padLayout = padLayout },
        modifier = modifier.fillMaxWidth().heightIn(min = 160.dp),
    )
}

@Composable
private fun TopBar(
    mode: Mode,
    onMode: (Mode) -> Unit,
    chords: ChordsViewModel,
    solo: SoloViewModel,
    engine: EngineViewModel,
    session: SessionViewModel,
    transport: TransportViewModel,
    onSettings: () -> Unit,
    onGeneral: () -> Unit,
    onFullscreen: () -> Unit,
    onDemo: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapsuleBar(Mode.entries, mode, { it.label }, onMode)
        val tu by transport.ui.collectAsState()
        if (mode != Mode.PRINCESS || tu.playing) QuickTransport(transport)
        extra()
        val cap by session.capture.collectAsState()
        val st by engine.stats.collectAsState()
        if (mode != Mode.PRINCESS || cap.recording) FilterChip(
            selected = cap.recording, onClick = { session.toggleCapture(tu.bpm, st.sampleRate) },
            label = { Text(if (cap.recording) "■ ${cap.seconds / 60}:${"%02d".format(cap.seconds % 60)}" else "● Sonido") },
        )
        TextButton(onClick = onSettings) { Text("⚙ Ajustes") }
        Box {
            TextButton(onClick = { more = true }) { Text("☰ Más") }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                Column(Modifier.width(340.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Reloj y grabación", style = MaterialTheme.typography.titleSmall)
                    TransportMore(transport) { more = false; onDemo() }
                    Text("Sonido y escenas", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MixerMenu(engine, chords, solo)
                        ScenesMenu(session)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { more = false; onGeneral() }) { Text("General (sesión, latencia, actualizar)") }
                        TextButton(onClick = { more = false; onFullscreen() }) { Text("⤢ Pantalla completa") }
                    }
                }
            }
        }
        if (mode != Mode.BATERIA && mode != Mode.PISTAS && mode != Mode.PRINCESS) KeyControls(chords)
        EqBars(level = { engine.level.value })
    }
}

@Composable
private fun ScenesMenu(session: SessionViewModel) {
    val names by (session.scenes?.names ?: kotlinx.coroutines.flow.MutableStateFlow(List(8) { "" })).collectAsState()
    Picker("Escenas", (0..7).toList(), { "${it + 1} · " + names.getOrElse(it) { "" } }) { session.scenes?.load(it) }
}

/** Mezclador rápido: volumen de cada instrumento y general. */
@Composable
private fun MixerMenu(engine: EngineViewModel, chords: ChordsViewModel, solo: SoloViewModel) {
    var open by remember { mutableStateOf(false) }
    val kit by engine.kit.collectAsState()
    val c by chords.ui.collectAsState()
    val s by solo.ui.collectAsState()
    TextButton(onClick = { open = true }) { Text("Mezcla") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Column(Modifier.width(280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Volumen de cada instrumento", style = MaterialTheme.typography.titleSmall)
            LabeledSlider("Acordes", c.volume, 0f..1f) { v -> chords.edit { it.copy(volume = v) } }
            LabeledSlider("Solista", s.volume, 0f..1f) { v -> solo.edit { it.copy(volume = v) } }
            LabeledSlider("Batería", kit.drumVol, 0f..1.5f) { v -> engine.editKit { it.copy(drumVol = v) } }
            LabeledSlider("General", kit.master, 0f..1f) { v -> engine.editKit { it.copy(master = v) } }
        }
    }
}

@Composable
private fun CalibrationPanel(vm: EngineViewModel) {
    val s by vm.stats.collectAsState()
    val auto by vm.auto.collectAsState()
    val bt by vm.bluetooth.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Latencia (retraso del sonido)", style = MaterialTheme.typography.titleMedium)
        if (s.running) Text("Audio activo")
        else Text("Audio detenido", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
        Text("Audio rápido: ${if (s.aaudio) "sí" else "no"} · Acceso directo: ${if (s.mmap) "sí" else "no"} · Exclusivo: ${if (s.exclusive) "sí" else "no"}")
        Text("Frecuencia %d Hz · bloque de %d muestras (%.1f ms)".format(s.sampleRate, s.framesPerBurst, s.burstMs))
        Text("Colchón: %d muestras (%d bloques) · cortes de audio: %d".format(s.bufferFrames, s.bursts, s.xruns))
        if (s.dropped > 0) Text("Eventos descartados: ${s.dropped}", color = MaterialTheme.colorScheme.tertiary)
        Text("Retraso de salida del teléfono: " + if (s.outputLatencyMs >= 0) "%.1f ms".format(s.outputLatencyMs) else "n/d")
        Text("Del toque al motor: último %.1f · medio %.1f · peor %.1f ms".format(s.lastTouchMs, s.avgTouchMs, s.maxTouchMs))
        Text(
            "Retraso total estimado (toque → sonido): " + if (s.totalMs >= 0) "%.1f ms".format(s.totalMs) else "n/d",
            style = MaterialTheme.typography.titleSmall,
            color = if (s.totalMs in 0.0..15.0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in 1..4) {
                FilterChip(selected = !auto && s.bursts == n, onClick = { vm.setBursts(n) },
                    label = { Text("$n") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ajuste automático del colchón")
            Switch(checked = auto, onCheckedChange = vm::setAuto)
        }
        Button(onClick = vm::reset) { Text("Reiniciar medición") }
        if (bt) {
            Text(
                "Auriculares Bluetooth detectados: añaden 100–250 ms de retraso. " +
                    "Usa cable o USB.",
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text("Recomendado: auriculares con cable o USB.")
        }
        Text("Toca los pads de la batería para medir. Colchón: 1 = menos retraso, 4 = más estable.")
    }
}

/** Ajustes en una ventana encima de la pantalla: semitransparente, con las opciones de la pestaña actual y una X para cerrar. */
@Composable
private fun SettingsModal(
    mode: Mode, general: Boolean, onClose: () -> Unit,
    vm: EngineViewModel, updateVm: UpdateViewModel, chordsVm: ChordsViewModel, soloVm: SoloViewModel,
    transportVm: TransportViewModel, sessionVm: SessionViewModel,
) {
    val up by updateVm.state.collectAsState()
    val tabs: List<String> = if (general) listOf("Sesión", "Latencia", if (up is UpdateState.Available) "Actualizar •" else "Actualizar") else when (mode) {
        Mode.ACORDES, Mode.VOCES -> listOf("Sonido", "Bucles")
        Mode.DUO -> listOf("Sonido", "Solista", "Bucles")
        Mode.SOLISTA -> listOf("Solista")
        Mode.BATERIA -> listOf("Batería", "Bucles")
        Mode.MIXTO -> listOf("Batería", "Sonido", "Solista", "Bucles")
        Mode.PISTAS, Mode.PRINCESS -> listOf("Sesión", "Latencia", if (up is UpdateState.Available) "Actualizar •" else "Actualizar")
    }
    var tab by remember(general, mode) { mutableIntStateOf(0) }
    val scrim = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(playInsets).background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = scrim, indication = null) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        val card = remember { MutableInteractionSource() }
        Column(
            Modifier.fillMaxWidth(0.78f).fillMaxHeight(0.9f)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(22.dp))
                .clickable(interactionSource = card, indication = null) {}  // tocar dentro no cierra
                .padding(top = 6.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (general) "General" else "Ajustes · ${mode.label}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("✕", style = MaterialTheme.typography.titleLarge) }
            }
            if (tabs.size > 1) {
                ScrollableTabRow(selectedTabIndex = tab.coerceIn(0, tabs.size - 1), edgePadding = 8.dp, containerColor = Color.Transparent) {
                    tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tabs.getOrElse(tab) { tabs[0] }.removeSuffix(" •")) {
                    "Sonido" -> SoundPanel(chordsVm)
                    "Solista" -> SoloPanel(soloVm)
                    "Batería" -> KitPanel(vm)
                    "Bucles" -> LoopsPanel(transportVm)
                    "Sesión" -> SessionPanel(sessionVm, vm, transportVm)
                    "Latencia" -> CalibrationPanel(vm)
                    else -> UpdatePanel(updateVm)
                }
            }
        }
    }
}

@Composable
internal fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label: " + "%.2f".format(value), style = MaterialTheme.typography.labelMedium)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

@Composable
private fun KitPanel(vm: EngineViewModel) {
    val k by vm.kit.collectAsState()
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        PadLayoutPicker(k.padLayout) { l -> vm.editKit { it.copy(padLayout = l) } }
        Text("Sonido del kit", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitVoice.entries.forEach { v ->
                FilterChip(selected = k.voice == v, onClick = { vm.editKit { it.copy(voice = v) } }, label = { Text(v.label) })
            }
        }
        LabeledSlider("Volumen general", k.master, 0f..1f) { v -> vm.editKit { it.copy(master = v) } }
        LabeledSlider("Afinación de la caja (semitonos)", k.snareTune, -6f..6f) { v -> vm.editKit { it.copy(snareTune = v) } }
        LabeledSlider("Afinación de los toms (semitonos)", k.tomTune, -6f..6f) { v -> vm.editKit { it.copy(tomTune = v) } }
        LabeledSlider("Ambiente de sala", k.room, 0f..1f) { v -> vm.editKit { it.copy(room = v) } }
        LabeledSlider("Compresión (sonido más compacto)", k.comp, 0f..1f) { v -> vm.editKit { it.copy(comp = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sensible a la fuerza del toque")
            Switch(checked = k.pressure, onCheckedChange = { v -> vm.editKit { it.copy(pressure = v) } })
        }
        val kitState by KitLoader.state.collectAsState()
        Text(
            when (val ks = kitState) {
                is KitState.Loading -> "Cargando kit acústico… ${ks.percent}%"
                KitState.Ready -> "Kit acústico cargado"
                KitState.Missing -> "Kit sintético (no se encontraron muestras)"
                KitState.Idle -> ""
            },
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            "Muestras: MuldjordKit de Lars Muldjord (licencia CC BY 4.0), versión SFZ de kinwie. " +
                "Recortadas y mezcladas a estéreo para esta app. Percusión de cumbia: Versilian Community Sample Library (CC0).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Volumen de cada pad", style = MaterialTheme.typography.titleSmall)
        for (i in 0 until Pads.COUNT) {
            LabeledSlider(Pads.names[i].replace("\n", " "), k.padVolume[i], 0f..1.5f) { v ->
                vm.editKit { it.copy(padVolume = it.padVolume.toMutableList().also { l -> l[i] = v }) }
            }
        }
    }
}

@Composable
private fun UpdatePanel(vm: UpdateViewModel) {
    val st by vm.state.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Versión instalada: build ${vm.currentBuild}")
        when (val s = st) {
            UpdateState.Idle -> {}
            UpdateState.Checking -> Text("Buscando actualización…")
            UpdateState.UpToDate -> Text("Estás en la última versión.")
            is UpdateState.Available -> {
                Text("Nueva versión: build ${s.build}")
                GradientButton("Descargar e instalar", vm::downloadAndInstall, glyph = "↓")
            }
            is UpdateState.Downloading -> Text("Descargando… ${s.percent}%")
            UpdateState.NeedsPermission ->
                Text("Activa \"Permitir de esta fuente\" y vuelve a pulsar instalar.")
            is UpdateState.Error -> Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error)
        }
        TonalButton("Buscar actualización", vm::check)
        if (st is UpdateState.NeedsPermission) TonalButton("Reintentar", vm::check)
    }
}
