package com.glidelive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glidelive.demo.BAR
import com.glidelive.demo.TocataSong
import com.glidelive.theory.NoteNames
import com.glidelive.ui.components.GradientButton
import com.glidelive.viz.EventHub

private val DEMO_ORANGE = Color(0xFFFF8A3D)
private val DEMO_MINT = Color(0xFF5CE1C6)
private val DEMO_VIOLET = Color(0xFF9B7BFF)

private fun chordLabel(sym: String) = sym.replace("#", "♯")

/** Lo que suena ahora y lo que viene, calculado desde la sección y la posición. */
private data class DemoNow(
    val sectionName: String, val bar: Int, val bars: Int, val beat: Int, val chord: String, val next: String?,
    val beatsToNext: Int, val bass: Int, val solo: Int?, val soloInstrument: Int?, val variation: Int,
)

private fun demoNow(d: DemoUi): DemoNow {
    val sections = TocataSong.sections
    val s = sections[d.section.coerceIn(0, sections.size - 1)]
    val bar = (d.tick / BAR).coerceIn(0, s.bars - 1)
    val chord = s.chords[bar]
    var next: String? = null
    var beats = 0
    var b = bar + 1
    while (b < s.bars && s.chords[b] == chord) b++
    if (b < s.bars) next = s.chords[b]
    else if (d.section + 1 < sections.size) next = sections[d.section + 1].chords.first().takeIf { it != chord }
    if (next != null) beats = ((b * BAR - d.tick) / 96).coerceAtLeast(0)
    return DemoNow(
        s.name, bar + 1, s.bars, (d.tick % BAR) / 96 + 1, chord, next, beats,
        TocataSong.bassRoot(chord), TocataSong.soloAt(s, d.tick), s.soloInstrument, s.variation,
    )
}

/** Barra compacta de la demo: se ve encima de cualquier modo para tocar encima. */
@Composable
fun DemoStrip(vm: TransportViewModel, onOpen: () -> Unit) {
    val d by vm.demo.collectAsState()
    if (!d.active) return
    val n = demoNow(d)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Demo · ${n.sectionName} ${n.bar}/${n.bars}", style = MaterialTheme.typography.labelMedium)
        Text("Acorde ${chordLabel(n.chord)}", fontWeight = FontWeight.Bold, color = DEMO_VIOLET)
        n.next?.let { Text("→ ${chordLabel(it)} en ${n.beatsToNext}", style = MaterialTheme.typography.labelMedium) }
        Text("Bajo ${NoteNames.name(n.bass, true)}", color = DEMO_ORANGE)
        Text("Solo ${n.solo?.let { NoteNames.midiName(it, true) } ?: "—"}", color = DEMO_MINT, fontWeight = FontWeight.Bold)
        TextButton(onClick = vm::stopDemo) { Text("■ Parar") }
    }
}

/** Pantalla de la demo: elegir sección y ver qué se toca en cada instrumento mientras suena. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoDialog(vm: TransportViewModel, onDismiss: () -> Unit, onStart: (Int) -> Unit) {
    val d by vm.demo.collectAsState()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.96f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                .padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Demo · Última tocata", style = MaterialTheme.typography.titleMedium)
                    Text("Fa# menor · ${TocataSong.BPM} BPM · batería, bajo, acordes y los dos solos de la canción",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (d.active) GradientButton("Parar", vm::stopDemo, glyph = "■")
                else GradientButton("Tocar", { onStart(0) }, glyph = "▶")
                TextButton(onClick = onDismiss) { Text(if (d.active) "Tocar encima" else "Cerrar") }
            }
            Text("Secciones (toca una para empezar desde ahí)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TocataSong.sections.forEachIndexed { i, s ->
                    FilterChip(selected = d.active && d.section == i, onClick = { onStart(i) }, label = { Text(s.name) })
                }
            }
            if (!d.active) {
                Text(
                    "Al tocar, la app pone la base \"Última tocata\", el bajo, el banco de acordes C17 y la escala de Fa# menor " +
                        "en el solista: así puedes tocar encima con las mismas notas de la canción.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val n = demoNow(d)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${n.sectionName} · compás ${n.bar} de ${n.bars} · tiempo ${n.beat}", style = MaterialTheme.typography.labelLarge)
                BeatDots(n.beat)
            }
            LinearProgressIndicator(progress = { d.tick.toFloat() / (n.bars * BAR) }, modifier = Modifier.fillMaxWidth())

            // Acordes: qué pad del banco C17 tocar.
            Text("Acordes (banco C17 en Acordes)", style = MaterialTheme.typography.labelLarge, color = DEMO_VIOLET)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(chordLabel(n.chord), fontSize = 40.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, color = DEMO_VIOLET)
                n.next?.let {
                    Text("después ${chordLabel(it)}\nen ${n.beatsToNext} tiempos", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            BankPads(n.chord, n.next)

            // Bajo y batería.
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Bajo", style = MaterialTheme.typography.labelLarge, color = DEMO_ORANGE)
                    Text(NoteNames.name(n.bass, true), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = DEMO_ORANGE)
                }
                Column {
                    Text("Batería · variación ${"ABCD"[n.variation.coerceIn(0, 3)]}", style = MaterialTheme.typography.labelLarge)
                    DrumLights()
                }
            }

            // Solista.
            val inst = n.soloInstrument?.let { SOLO_INSTRUMENTS[it] } ?: "Solista libre (Fa# menor)"
            Text("Solista · $inst", style = MaterialTheme.typography.labelLarge, color = DEMO_MINT)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(n.solo?.let { NoteNames.midiName(it, true) } ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    color = DEMO_MINT, modifier = Modifier.width(80.dp))
                SoloKeys(n.solo, Modifier.weight(1f).height(64.dp))
            }
            Text(
                "Para tocar encima: en Acordes toca el pad iluminado; en Solista las notas ya están en Fa# menor " +
                    "(las teclas marcadas son las que usa el solo).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BeatDots(beat: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in 1..4) Box(
            Modifier.size(10.dp).background(if (b == beat) DEMO_ORANGE else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape),
        )
    }
}

/** Los 8 pads del banco C17: el acorde que suena lleno, el siguiente con borde. */
@Composable
private fun BankPads(now: String, next: String?) {
    val order = TocataSong.PAD_OF.entries.sortedBy { it.value }.map { it.key }
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in order.chunked(4)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (sym in row) {
                val on = sym == now
                val nx = sym == next
                Box(
                    Modifier.width(64.dp).height(38.dp)
                        .background(if (on) DEMO_VIOLET else MaterialTheme.colorScheme.surfaceVariant, shape)
                        .border(if (nx) 2.dp else 0.dp, if (nx) DEMO_VIOLET else Color.Transparent, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(chordLabel(sym), fontWeight = FontWeight.Bold,
                        color = if (on) Color.White else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** Luces de la batería que se encienden con cada golpe real del motor. */
@Composable
private fun DrumLights() {
    val groups = listOf("Bombo" to setOf(0), "Caja" to setOf(1, 2), "Hi-hat" to setOf(3, 4, 5), "Toms" to setOf(6, 7, 8), "Platos" to setOf(9, 10, 11, 12))
    val last = remember { LongArray(groups.size) }
    val frame = remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) {
        val l: (com.glidelive.viz.EngineEvent) -> Unit = { e ->
            if (e.type == 0) groups.forEachIndexed { i, g -> if (e.a in g.second) last[i] = System.currentTimeMillis() }
        }
        EventHub.addListener(l)
        onDispose { EventHub.removeListener(l) }
    }
    LaunchedEffect(Unit) { while (true) withFrameMillis { frame.longValue = it } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        @Suppress("UNUSED_VARIABLE") val tick = frame.longValue
        val now = System.currentTimeMillis()
        groups.forEachIndexed { i, (name, _) ->
            val a = (1f - (now - last[i]) / 220f).coerceIn(0f, 1f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(22.dp).background(DEMO_ORANGE.copy(alpha = 0.15f + 0.85f * a), CircleShape))
                Text(name, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Teclado de Fa#3 a Fa#5: marcadas las notas del solo; encendida la que suena. */
@Composable
private fun SoloKeys(current: Int?, modifier: Modifier) {
    val lo = 54
    val hi = 78
    val used = remember { (TocataSong.SOLO1 + TocataSong.SOLO2).map { it.midi }.toSet() }
    val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val whites = (lo..hi).filter { it % 12 !in setOf(1, 3, 6, 8, 10) }
        val w = size.width / whites.size
        whites.forEachIndexed { i, m ->
            val c = when (m) { current -> DEMO_MINT; in used -> Color(0xFFDDF7F1); else -> Color(0xFFF2F2F2) }
            drawRoundRect(c, Offset(i * w + 1, 0f), Size(w - 2, size.height), CornerRadius(4f))
        }
        for (m in lo..hi) {
            if (m % 12 !in setOf(1, 3, 6, 8, 10)) continue
            val leftWhites = whites.count { it < m }
            val x = leftWhites * w - w * 0.3f
            val c = when (m) { current -> DEMO_MINT; in used -> Color(0xFF2E7D6E); else -> Color(0xFF202020) }
            drawRoundRect(c, Offset(x, 0f), Size(w * 0.6f, size.height * 0.6f), CornerRadius(3f))
        }
        drawRect(outline, Offset.Zero, size, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    }
}
