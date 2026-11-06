package com.glidelive.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.glidelive.audio.NativeEngine

class MainActivity : ComponentActivity() {
    private val vm: EngineViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF9A3C))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF140E0A)) {
                    Row(Modifier.fillMaxSize()) {
                        val kit by vm.kit.collectAsState()
                        AndroidView(
                            factory = { DrumPadsView(it) },
                            update = { it.pressureSensitivity = kit.pressure },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        SidePanel(vm)
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
        NativeEngine.stop()
        super.onPause()
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
        Text("Calibración / tap test", style = MaterialTheme.typography.titleMedium)
        Text(if (s.running) "Audio activo" else "Audio detenido")
        Text("API: ${if (s.aaudio) "AAudio" else "OpenSL ES"}  MMAP: ${if (s.mmap) "sí" else "no"}  " +
            "Exclusivo: ${if (s.exclusive) "sí" else "no"}")
        Text("%d Hz · burst %d fr (%.1f ms)".format(s.sampleRate, s.framesPerBurst, s.burstMs))
        Text("Buffer %d fr · %d burst(s) · xruns %d".format(s.bufferFrames, s.bursts, s.xruns))
        Text("Salida hw/buffer: " + if (s.outputLatencyMs >= 0) "%.1f ms".format(s.outputLatencyMs) else "n/d")
        Text("Toque→callback: último %.1f · prom %.1f · máx %.1f ms".format(s.lastTouchMs, s.avgTouchMs, s.maxTouchMs))
        Text(
            "Toque→sonido (est.): " + if (s.totalMs >= 0) "%.1f ms".format(s.totalMs) else "n/d",
            style = MaterialTheme.typography.titleSmall,
            color = if (s.totalMs in 0.0..15.0) Color(0xFF7CD67C) else Color(0xFFFFB74D),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in 1..3) {
                FilterChip(selected = !auto && s.bursts == n, onClick = { vm.setBursts(n) },
                    label = { Text("$n") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Auto")
            Switch(checked = auto, onCheckedChange = vm::setAuto)
        }
        Button(onClick = vm::reset) { Text("Reiniciar medición") }
        if (bt) {
            Text(
                "Auriculares Bluetooth detectados: añaden 100–250 ms de retraso. " +
                    "Usa cable o USB.",
                color = Color(0xFFFF6E6E),
            )
        } else {
            Text("Recomendado: auriculares con cable o USB.")
        }
        Text("Toca los pads para medir.")
    }
}

@Composable
private fun SidePanel(vm: EngineViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.width(280.dp).fillMaxHeight()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Kit") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Latencia") })
        }
        if (tab == 0) KitPanel(vm) else CalibrationPanel(vm)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label: %.2f".format(value), style = MaterialTheme.typography.labelMedium)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

@Composable
private fun KitPanel(vm: EngineViewModel) {
    val k by vm.kit.collectAsState()
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        LabeledSlider("Master", k.master, 0f..1f) { v -> vm.editKit { it.copy(master = v) } }
        LabeledSlider("Afinación caja (st)", k.snareTune, -6f..6f) { v -> vm.editKit { it.copy(snareTune = v) } }
        LabeledSlider("Afinación toms (st)", k.tomTune, -6f..6f) { v -> vm.editKit { it.copy(tomTune = v) } }
        LabeledSlider("Ambiente (room)", k.room, 0f..1f) { v -> vm.editKit { it.copy(room = v) } }
        LabeledSlider("Compresión", k.comp, 0f..1f) { v -> vm.editKit { it.copy(comp = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sensib. por presión")
            Switch(checked = k.pressure, onCheckedChange = { v -> vm.editKit { it.copy(pressure = v) } })
        }
        Text("Volumen por pad", style = MaterialTheme.typography.titleSmall)
        for (i in 0 until Pads.COUNT) {
            LabeledSlider(Pads.names[i], k.padVolume[i], 0f..1.5f) { v ->
                vm.editKit { it.copy(padVolume = it.padVolume.toMutableList().also { l -> l[i] = v }) }
            }
        }
    }
}
