package com.glidelive.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
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
    private val transportVm: TransportViewModel by viewModels()
    private val sessionVm: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        EventHub.start(lifecycleScope)
        VizPrefs.init(this)
        sessionVm.attach(chordsVm, soloVm, vm, transportVm)
        setContent {
            AppTheme {
                AppBackground {
                    var mode by rememberSaveable { mutableStateOf(Mode.ACORDES) }
                    val cfg = LocalConfiguration.current
                    // En pantallas estrechas el panel de ajustes empieza cerrado para dejar sitio a los pads.
                    var panel by rememberSaveable { mutableStateOf(cfg.screenWidthDp >= 900) }
                    var demoOpen by remember { mutableStateOf(false) }
                    val startDemo: (Int) -> Unit = { i ->
                        // Las notas de la canción: Fa# menor en acordes y solista, banco C17 con sus acordes.
                        chordsVm.edit { it.copy(key = TocataSong.KEY, scale = Scale.NATURAL_MINOR, bank = Bank.C17, transpose = 0) }
                        soloVm.edit { it.copy(scaleOverride = null, followChord = false) }
                        transportVm.startDemo(i)
                    }
                    val demo by transportVm.demo.collectAsState()
                    LaunchedEffect(demo.active, demo.section) {
                        if (demo.active) TocataSong.sections[demo.section].soloInstrument?.let { inst ->
                            soloVm.edit { it.copy(instrument = inst) }
                        }
                    }
                    if (demoOpen) DemoDialog(transportVm, onDismiss = { demoOpen = false }, onStart = startDemo)
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
                                TopBar(mode, { mode = it }, chordsVm, soloVm, vm, sessionVm, transportVm, panel, { panel = !panel },
                                    { fullscreen = true }, { demoOpen = true })
                                DemoStrip(transportVm) { demoOpen = true }
                                if (mode != Mode.PISTAS) StemStrip(stemsVm) { mode = Mode.PISTAS }
                                if (mode == Mode.BATERIA || mode == Mode.MIXTO) {
                                    PatternBar(transportVm) {
                                        val k by vm.kit.collectAsState()
                                        Picker("Pads: ${k.padLayout.label}", PadLayout.entries, { it.label + " — " + it.hint }) { l ->
                                            vm.editKit { it.copy(padLayout = l) }
                                        }
                                    }
                                }
                                if (mode == Mode.MIXTO) {
                                    Row(Modifier.padding(horizontal = 16.dp)) {
                                        FilterChip(selected = soloLayer, onClick = { soloLayer = !soloLayer },
                                            label = { Text("Capa de solista") })
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
                                if (panel && !fullscreen) {
                                    GlassCard(Modifier.width(300.dp).fillMaxHeight().padding(bottom = 8.dp), padding = 0.dp) {
                                        SidePanel(vm, updateVm, chordsVm, soloVm, transportVm, sessionVm)
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
                    }
                }
            }
        }
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
    ACORDES("Acordes"), VOCES("Voces"), DUO("Voces + Solista"), SOLISTA("Solista"), BATERIA("Batería"), MIXTO("Mixto"), PISTAS("Pistas")
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
    panel: Boolean,
    onPanel: () -> Unit,
    onFullscreen: () -> Unit,
    onDemo: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapsuleBar(Mode.entries, mode, { it.label }, onMode)
        QuickTransport(transport)
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
                        TextButton(onClick = { more = false; onPanel() }) { Text(if (panel) "Ocultar ajustes" else "Ajustes") }
                        TextButton(onClick = { more = false; onFullscreen() }) { Text("⤢ Pantalla completa") }
                    }
                }
            }
        }
        if (mode != Mode.BATERIA && mode != Mode.PISTAS) KeyControls(chords)
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

@Composable
private fun SidePanel(vm: EngineViewModel, updateVm: UpdateViewModel, chordsVm: ChordsViewModel, soloVm: SoloViewModel, transportVm: TransportViewModel, sessionVm: SessionViewModel) {
    val up by updateVm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Acordes") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Solista") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Batería") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Bucles") })
            Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("Sesión") })
            Tab(selected = tab == 5, onClick = { tab = 5 }, text = { Text("Latencia") })
            Tab(
                selected = tab == 6, onClick = { tab = 6 },
                text = { Text(if (up is UpdateState.Available) "Actualizar •" else "Actualizar") },
            )
        }
        when (tab) {
            0 -> SoundPanel(chordsVm)
            1 -> SoloPanel(soloVm)
            2 -> KitPanel(vm)
            3 -> LoopsPanel(transportVm)
            4 -> SessionPanel(sessionVm, vm, transportVm)
            5 -> CalibrationPanel(vm)
            else -> UpdatePanel(updateVm)
        }
    }
}

@Composable
internal fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label: %.2f".format(value), style = MaterialTheme.typography.labelMedium)
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
