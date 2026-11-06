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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
                        AndroidView(
                            factory = { DrumPadsView(it) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        CalibrationPanel(vm)
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
        Modifier.width(280.dp).fillMaxHeight().padding(12.dp).verticalScroll(rememberScrollState()),
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
