package com.glidelive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.glidelive.princess.*
import java.util.Locale
import kotlin.math.*

private data class TimelineLane(val label: String, val color: Color, val clips: List<Placement>)
private fun timeLabel(seconds: Double) = String.format(Locale.ROOT, "%d:%04.1f", seconds.toInt() / 60, seconds % 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrincessTimeline(project: Project, vm: PrincessViewModel, position: Double, playing: Boolean, busy: Boolean) {
    val placements = remember(project) { DramaRenderer.layout(project) }
    var selectedId by remember(project.id) { mutableStateOf<String?>(null) }
    var cursor by remember(project.id) { mutableStateOf(0.0) }
    var zoom by remember(project.id) { mutableStateOf(36f) }
    val visibleCursor = if (playing) position else cursor
    val selected = placements.firstOrNull { it.cue.id == selectedId }
    val lanes = buildList {
        project.characters.forEach { character ->
            add(TimelineLane(character.name, Color(character.color), placements.filter { it.cue.type == CueType.VOICE && it.cue.characterId == character.id }))
        }
        val orphans = placements.filter { it.cue.type == CueType.VOICE && project.characters.none { c -> c.id == it.cue.characterId } }
        if (orphans.isNotEmpty()) add(TimelineLane("Otras voces", Color(0xFF60A5FA), orphans))
        add(TimelineLane("Música", Color(0xFFA78BFA), placements.filter { it.cue.type == CueType.MUSIC }))
        add(TimelineLane("Ambientes / efectos", Color(0xFF34D399), placements.filter { it.cue.type == CueType.SFX }))
        val silence = placements.filter { it.cue.type == CueType.SILENCE }
        if (silence.isNotEmpty()) add(TimelineLane("Silencios", Color.Gray, silence))
    }
    val duration = max(20.0, DramaRenderer.totalSeconds(project) + 5.0)
    val widthDp = (duration * zoom).coerceAtMost(28000.0).toFloat()
    val scale = widthDp / duration
    val density = LocalDensity.current.density
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .25f), RoundedCornerShape(12.dp)).padding(8.dp)) {
        Text("Timeline multipista", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(enabled = !busy && project.cues.isNotEmpty(), onClick = { if (playing) vm.stop() else vm.playFromSeconds(cursor) }) { Text(if (playing) "■ Parar" else "▶ Desde cursor") }
            Text(timeLabel(visibleCursor), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(8f) }) { Text("Zoom −") }
            TextButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(120f) }) { Text("Zoom +") }
        }
        Text("Toca un clip para editarlo. Mantén pulsado y arrastra para moverlo. Toca la regla para situar el cursor.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.width(132.dp)) {
                Text("Carril", Modifier.height(30.dp), style = MaterialTheme.typography.labelMedium)
                lanes.forEach { lane ->
                    val rows = timelineRows(lane.clips)
                    Column(Modifier.height((rows.size.coerceAtLeast(1) * 52).dp).padding(end = 6.dp)) {
                        Text(lane.label, maxLines = 1, color = lane.color, style = MaterialTheme.typography.labelLarge)
                        val allMuted = lane.clips.isNotEmpty() && lane.clips.all { it.cue.muted }
                        TextButton(enabled = !busy && lane.clips.isNotEmpty(), onClick = { vm.muteTimelineLane(lane.clips.map { it.cue.id }.toSet(), !allMuted) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(26.dp)) {
                            Text(if (allMuted) "Activar carril" else "Silenciar", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).horizontalScroll(scroll)) {
                Box(Modifier.width(widthDp.dp).height(30.dp).pointerInput(duration, scale, busy) {
                    detectTapGestures { at ->
                        if (!busy) { cursor = (at.x / density / scale).coerceIn(0.0, duration); if (playing) vm.seek(cursor) }
                    }
                }) {
                    val step = when { scale >= 60 -> 1; scale >= 20 -> 5; else -> 10 }
                    for (second in 0..duration.toInt() step step) {
                        Text(timeLabel(second.toDouble()), Modifier.offset(x = (second * scale).toFloat().dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                lanes.forEach { lane ->
                    val rows = timelineRows(lane.clips)
                    Column {
                        rows.ifEmpty { listOf(emptyList()) }.forEach { row ->
                            Box(Modifier.width(widthDp.dp).height(52.dp).border(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val x = (visibleCursor * scale * density).toFloat()
                                    drawLine(Color(0xFFF472B6), Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                                }
                                row.forEach { place ->
                                    var drag by remember(place.cue.id) { mutableStateOf(0f) }
                                    val start = (place.start + drag).coerceAtLeast(0.0)
                                    Box(Modifier.offset(x = (start * scale).toFloat().dp, y = 4.dp)
                                        .width(max(24f, ((place.end - place.start) * scale).toFloat()).dp).height(44.dp)
                                        .background(lane.color.copy(alpha = if (place.cue.muted) .12f else .45f), RoundedCornerShape(6.dp))
                                        .border(if (place.cue.id == selectedId) 2.dp else 1.dp, if (place.cue.id == selectedId) Color.White else lane.color, RoundedCornerShape(6.dp))
                                        .clickable(enabled = !busy) { selectedId = place.cue.id }
                                        .pointerInput(place.cue.id, place.start, scale, busy) {
                                            if (!busy) detectDragGesturesAfterLongPress(
                                                onDragStart = { selectedId = place.cue.id },
                                                onDragCancel = { drag = 0f },
                                                onDragEnd = {
                                                    val target = (place.start + drag).coerceIn(0.0, max(600.0, duration)).toFloat()
                                                    drag = 0f
                                                    vm.editTimelineCue(place.cue.id) { it.copy(startSeconds = target) }
                                                },
                                                onDrag = { change, amount -> change.consume(); drag += amount.x / density / scale.toFloat() },
                                            )
                                        }.padding(5.dp)) {
                                        Text((if (place.cue.muted) "🔇 " else "") + place.cue.text.ifBlank { place.cue.type.label }, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (project.cues.isEmpty()) Text("Los audios que generes o importes aparecerán aquí.", style = MaterialTheme.typography.bodySmall)
        selected?.let { place -> TimelineClipEditor(place, project, vm, enabled = !busy) }
    }
}

private fun timelineRows(clips: List<Placement>): List<List<Placement>> {
    val rows = mutableListOf<MutableList<Placement>>()
    for (clip in clips.sortedBy { it.start }) {
        val row = rows.firstOrNull { it.last().end <= clip.start } ?: mutableListOf<Placement>().also { rows += it }
        row += clip
    }
    return rows
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineClipEditor(place: Placement, project: Project, vm: PrincessViewModel, enabled: Boolean) {
    val cue = place.cue
    val naturalDuration = (cue.seconds - cue.trimStart).coerceAtLeast(.05f) / DramaRenderer.pitchFactor(DramaRenderer.pitchOf(project, cue)).toFloat()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(cue.text.ifBlank { cue.type.label }, maxLines = 2, fontWeight = FontWeight.Bold)
        TimelineNumber("Inicio (segundos)", place.start.toFloat(), 0f, max(600f, DramaRenderer.totalSeconds(project).toFloat() + 60f), enabled) { value -> vm.editTimelineCue(cue.id) { it.copy(startSeconds = value) } }
        TimelineNumber("Duración en timeline (segundos)", (place.end - place.start).toFloat(), .05f,
            if (cue.loop || cue.mode == CueMode.BED || cue.type == CueType.SILENCE) 600f else naturalDuration.coerceAtLeast(.05f), enabled) { value ->
            vm.editTimelineCue(cue.id) { it.copy(durationSeconds = value) }
        }
        if (cue.type != CueType.SILENCE) {
            TimelineNumber("Recortar inicio del archivo (segundos)", cue.trimStart, 0f, (cue.seconds - .05f).coerceAtLeast(0f), enabled) { value ->
                vm.editTimelineCue(cue.id) { it.copy(trimStart = value, durationSeconds = if (it.loop || it.mode == CueMode.BED) it.durationSeconds else null) }
            }
            Slider(cue.volume, { value -> vm.editTimelineCue(cue.id) { it.copy(volume = value) } }, enabled = enabled, valueRange = 0f..1.5f)
            Text("Volumen: ${(cue.volume * 100).roundToInt()} %", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = cue.muted, enabled = enabled, onClick = { vm.editTimelineCue(cue.id) { it.copy(muted = !it.muted) } }, label = { Text("Silenciar clip") })
                FilterChip(selected = cue.loop || cue.mode == CueMode.BED, enabled = enabled, onClick = {
                    vm.editTimelineCue(cue.id) {
                        val repeat = !(it.loop || it.mode == CueMode.BED)
                        it.copy(loop = repeat, mode = CueMode.SEQ, durationSeconds = if (repeat) it.durationSeconds else min(it.durationSeconds ?: naturalDuration, naturalDuration))
                    }
                }, label = { Text("Repetir audio") })
                TextButton(enabled = enabled, onClick = { vm.preview(place.index) }) { Text("Escuchar clip") }
            }
            TimelineNumber("Entrada suave (segundos)", cue.fadeIn, 0f, 10f, enabled) { v -> vm.editTimelineCue(cue.id) { it.copy(fadeIn = v) } }
            TimelineNumber("Salida suave (segundos)", cue.fadeOut, 0f, 10f, enabled) { v -> vm.editTimelineCue(cue.id) { it.copy(fadeOut = v) } }
        }
    }
}

@Composable
private fun TimelineNumber(label: String, value: Float, minimum: Float, maximum: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    var text by remember(value, label) { mutableStateOf(String.format(Locale.ROOT, "%.2f", value)) }
    val number = text.replace(',', '.').toFloatOrNull()
    val valid = number != null && number.isFinite() && number >= minimum && number <= maximum
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !valid, modifier = Modifier.weight(1f))
        TextButton(enabled = enabled && valid && number != value, onClick = { number?.let(onChange) }) { Text("Aplicar") }
    }
}
