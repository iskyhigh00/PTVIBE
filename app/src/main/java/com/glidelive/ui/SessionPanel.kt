package com.glidelive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glidelive.ui.components.GradientButton
import com.glidelive.ui.components.TonalButton

/** Grabación de sesión (WAV + MIDI), escenas y estado de la entrada MIDI. */
@Composable
fun SessionPanel(session: SessionViewModel, engine: EngineViewModel, transport: TransportViewModel) {
    val s by session.ui.collectAsState()
    val stats by engine.stats.collectAsState()
    val t by transport.ui.collectAsState()
    val midiStatus by session.midi.status.collectAsState()
    val mgr = session.scenes
    val names by (mgr?.names ?: kotlinx.coroutines.flow.MutableStateFlow(List(8) { "" })).collectAsState()

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Grabar sesión", style = MaterialTheme.typography.titleSmall)
        GradientButton(
            if (s.recording) "Detener  %d:%02d".format(s.seconds / 60, s.seconds % 60) else "Grabar audio y MIDI",
            { session.toggleRecord(t.bpm, stats.sampleRate) },
            glyph = if (s.recording) "■" else "●",
        )
        if (stats.recDropped > 0) {
            Text("Se perdieron ${stats.recDropped / 2} muestras de audio en la grabación.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        s.last?.let { r ->
            Text("Guardado: ${r.wav.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TonalButton("Compartir audio", { session.share(r.wav, "audio/wav") })
                r.midi?.let { TonalButton("Compartir MIDI", { session.share(it, "audio/midi") }) }
            }
        }
        Text(
            "El audio (WAV) y la sesión en MIDI (un canal por voz con pitch bend para el glide y batería en el canal 10) " +
                "se guardan en la carpeta de música de la app.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Escenas", style = MaterialTheme.typography.titleSmall)
        for (i in 0 until 8) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TonalButton("${i + 1} · " + names.getOrElse(i) { "" }.take(18), { mgr?.load(i) })
                TextButton(onClick = { mgr?.save(i) }) { Text("Guardar aquí") }
                TextButton(onClick = { mgr?.resetSlot(i) }) { Text("Restaurar") }
            }
        }
        Text(
            "Una escena guarda tonalidad, acordes, instrumentos, kit, base de batería, BPM y ajustes. " +
                "Al cargarla el sonido no se corta.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("MIDI de entrada", style = MaterialTheme.typography.titleSmall)
        Text(midiStatus, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Teclado o pads USB/Bluetooth ya conectados: las notas tocan el solista (con pitch bend) y el canal 10 toca la batería.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
