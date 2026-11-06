package com.glidelive.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import com.glidelive.audio.NativeEngine

class MainActivity : ComponentActivity() {
    private val vm: EngineViewModel by viewModels()
    private val updateVm: UpdateViewModel by viewModels()
    private val chordsVm: ChordsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AppBackground {
                    var mode by rememberSaveable { mutableStateOf(Mode.ACORDES) }
                    var panel by rememberSaveable { mutableStateOf(true) }
                    val kit by vm.kit.collectAsState()
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        TopBar(mode, { mode = it }, chordsVm, vm, panel) { panel = !panel }
                        Row(
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                if (mode != Mode.BATERIA) {
                                    ChordsScreen(chordsVm, compact = mode == Mode.MIXTO, Modifier.weight(1f))
                                }
                                if (mode != Mode.ACORDES) {
                                    AndroidView(
                                        factory = { DrumPadsView(it) },
                                        update = { it.pressureSensitivity = kit.pressure },
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                    )
                                }
                            }
                            if (panel) {
                                GlassCard(Modifier.width(300.dp).fillMaxHeight().padding(bottom = 8.dp), padding = 0.dp) {
                                    SidePanel(vm, updateVm, chordsVm)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NativeEngine.start()
    }

    override fun onPause() {
        chordsVm.stopAll()
        NativeEngine.stop()
        super.onPause()
    }
}

enum class Mode(val label: String) { ACORDES("Acordes"), BATERIA("Batería"), MIXTO("Mixto") }

@Composable
private fun TopBar(
    mode: Mode,
    onMode: (Mode) -> Unit,
    chords: ChordsViewModel,
    engine: EngineViewModel,
    panel: Boolean,
    onPanel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapsuleBar(Mode.entries, mode, { it.label }, onMode)
        if (mode != Mode.BATERIA) KeyControls(chords)
        EqBars(level = { engine.level.value })
        TextButton(onClick = onPanel) { Text(if (panel) "Ocultar ajustes" else "Ajustes") }
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
        Text(if (s.running) "Audio activo" else "Audio detenido")
        Text("Audio rápido: ${if (s.aaudio) "sí" else "no"} · Acceso directo: ${if (s.mmap) "sí" else "no"} · Exclusivo: ${if (s.exclusive) "sí" else "no"}")
        Text("Frecuencia %d Hz · bloque de %d muestras (%.1f ms)".format(s.sampleRate, s.framesPerBurst, s.burstMs))
        Text("Colchón: %d muestras (%d bloques) · cortes de audio: %d".format(s.bufferFrames, s.bursts, s.xruns))
        Text("Retraso de salida del teléfono: " + if (s.outputLatencyMs >= 0) "%.1f ms".format(s.outputLatencyMs) else "n/d")
        Text("Del toque al motor: último %.1f · medio %.1f · peor %.1f ms".format(s.lastTouchMs, s.avgTouchMs, s.maxTouchMs))
        Text(
            "Retraso total estimado (toque → sonido): " + if (s.totalMs >= 0) "%.1f ms".format(s.totalMs) else "n/d",
            style = MaterialTheme.typography.titleSmall,
            color = if (s.totalMs in 0.0..15.0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in 1..3) {
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
        Text("Toca los pads de la batería para medir. Colchón: 1 = menos retraso, 3 = más estable.")
    }
}

@Composable
private fun SidePanel(vm: EngineViewModel, updateVm: UpdateViewModel, chordsVm: ChordsViewModel) {
    val up by updateVm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sonido") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Batería") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Latencia") })
            Tab(
                selected = tab == 3, onClick = { tab = 3 },
                text = { Text(if (up is UpdateState.Available) "Actualizar •" else "Actualizar") },
            )
        }
        when (tab) {
            0 -> SoundPanel(chordsVm)
            1 -> KitPanel(vm)
            2 -> CalibrationPanel(vm)
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
        LabeledSlider("Volumen general", k.master, 0f..1f) { v -> vm.editKit { it.copy(master = v) } }
        LabeledSlider("Afinación de la caja (semitonos)", k.snareTune, -6f..6f) { v -> vm.editKit { it.copy(snareTune = v) } }
        LabeledSlider("Afinación de los toms (semitonos)", k.tomTune, -6f..6f) { v -> vm.editKit { it.copy(tomTune = v) } }
        LabeledSlider("Ambiente de sala", k.room, 0f..1f) { v -> vm.editKit { it.copy(room = v) } }
        LabeledSlider("Compresión (sonido más compacto)", k.comp, 0f..1f) { v -> vm.editKit { it.copy(comp = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sensible a la fuerza del toque")
            Switch(checked = k.pressure, onCheckedChange = { v -> vm.editKit { it.copy(pressure = v) } })
        }
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
