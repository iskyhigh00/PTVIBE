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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
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

// ───────────────────────────── Hojas ─────────────────────────────

@Composable
internal fun Sheet(title: String, onClose: () -> Unit, u: PrincessUi? = null, content: @Composable () -> Unit) {
    val scrim = remember { MutableInteractionSource() }
    val card = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(interactionSource = scrim, indication = null) { onClose() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.98f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .semantics { paneTitle = title }
                .clickable(interactionSource = card, indication = null) {}.padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                BigButton("Listo", Color(0xFF1F6F5F), onClose, Modifier.height(48.dp))
            }
            if (u != null) SheetBanner(u)
            content()
        }
    }
}

/** Lo que pasa mientras la hoja está abierta (errores, avisos, importando…), para que no quede escondido detrás. */
@Composable
internal fun SheetBanner(u: PrincessUi) {
    val msg = u.error ?: u.notice
    if (u.busy != null) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(u.busy.first, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            LinearProgressIndicator(progress = { u.busy.second.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    } else if (msg != null) {
        Text(msg, Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }.background(
            if (u.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)).padding(10.dp),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            color = if (u.error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun MoreAudio(u: PrincessUi, onDownload: () -> Unit, onCancel: () -> Unit, music: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    u.download?.let { (label, progress) ->
        Text("$label ${(progress * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = onCancel) { Text("Cancelar descarga") }
        return
    }
    if (music && u.downloadedMusicCount >= SoundCatalog.music.size || !music && u.hasSfx) return
    val label = if (music) "Más música" else "Más efectos"
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar descarga" else label) }
    if (expanded) {
        Text(if (music) "Descarga la selección completa (unos 50 MB) para usarla sin internet."
            else "Descarga más animales, ambientes y otros efectos (unos 150 MB) para usarlos sin internet.")
        FilledTonalButton(onClick = onDownload) { Text(if (!music && u.sfxPartial) "Completar descarga" else "Descargar") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SoundsSheet(vm: PrincessViewModel, u: PrincessUi, onClose: () -> Unit, onPickFile: () -> Unit) {
    val ctx = LocalContext.current
    val groups = u.soundGroups.map { it.name } + "Mis sonidos"
    var chosenGroup by rememberSaveable { mutableStateOf("Animales") }
    val group = chosenGroup.takeIf { it in groups } ?: groups.first()
    Sheet("Poner un sonido en ${clock(vm.overlayTime())}", onClose, u) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { name ->
                    FilledTonalButton(onClick = { chosenGroup = name }, modifier = Modifier.height(48.dp).semantics { selected = group == name; role = androidx.compose.ui.semantics.Role.Tab },
                        colors = if (group == name) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()) {
                        Text((if (group == name) "✓ " else "") + name, fontSize = 15.sp, maxLines = 1)
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (group == "Mis sonidos") {
                    if (u.mine.isEmpty()) Text("Puedes elegir un archivo de audio de tu teléfono.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    u.mine.forEach { sound ->
                        val file = java.io.File(SoundStore.mineDir(ctx), sound.file)
                        SoundRow("🎙", sound.name, onListen = { vm.previewFile(file) }, onAdd = { vm.addSoundFile(file, sound.name, CueType.SFX, vm.overlayTime()) })
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (sound in u.soundGroups.firstOrNull { it.name == group }?.sounds.orEmpty()) {
                            SoundTile(ICON[sound.id] ?: "🔊", sound.label,
                                onListen = { vm.clipFor(sound.id)?.let(vm::previewFile) },
                                onAdd = { vm.heardOrNext(sound.id)?.let { vm.addSoundFile(it, sound.label, CueType.SFX, vm.overlayTime()) } })
                        }
                    }
                }
                TextButton(onClick = onPickFile, modifier = Modifier.height(48.dp)) { Text("Elegir un sonido de mi teléfono…", fontSize = 15.sp) }
                MoreAudio(u, { vm.downloadSfx(10) }, vm::cancelDownload, music = false)
                Text(SoundCatalog.SFX_CREDIT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SoundTile(icon: String, label: String, onListen: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier.width(120.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, fontSize = 34.sp)
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSecondaryContainer)
        FilledTonalButton(onClick = onAdd, modifier = Modifier.height(48.dp).fillMaxWidth().semantics { contentDescription = "Poner $label en la línea roja" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("＋ Poner", fontSize = 16.sp, maxLines = 1) }
        TextButton(onClick = onListen, modifier = Modifier.height(48.dp).semantics { contentDescription = "Oír $label" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("▶ Oír", fontSize = 16.sp) }
    }
}

@Composable
internal fun SoundRow(icon: String, label: String, onListen: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$icon  $label", Modifier.weight(1f), fontSize = 16.sp)
        TextButton(onClick = onListen, modifier = Modifier.height(48.dp).semantics { contentDescription = "Oír $label" }) { Text("▶ Oír", fontSize = 16.sp) }
        FilledTonalButton(onClick = onAdd, modifier = Modifier.height(48.dp).semantics { contentDescription = "Poner $label en la línea roja" }) { Text("＋ Poner", fontSize = 16.sp) }
    }
}

@Composable
internal fun MusicSheet(vm: PrincessViewModel, u: PrincessUi, onClose: () -> Unit, onPickFile: () -> Unit) {
    val ctx = LocalContext.current
    val have = remember(u.musicCount) { SoundStore.musicFiles(ctx) }
    Sheet("Poner música de fondo desde ${clock(vm.overlayTime())}", onClose, u) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("La música baja sola cuando alguien habla. Empieza en la línea roja.", style = MaterialTheme.typography.bodyMedium)
            have.forEach { (track, file) ->
                SoundRow("🎵", track.label, onListen = { vm.previewFile(file) },
                    onAdd = { vm.addSoundFile(file, track.label.substringBefore(" ·"), CueType.MUSIC, vm.overlayTime()) })
            }
            TextButton(onClick = onPickFile, modifier = Modifier.height(48.dp)) { Text("Elegir una música de mi teléfono…", fontSize = 15.sp) }
            MoreAudio(u, vm::downloadMusic, vm::cancelDownload, music = true)
            Text(SoundCatalog.MUSIC_CREDIT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InlineTone(cue: Cue, onPick: (Int) -> Unit, onListen: () -> Unit, onDone: () -> Unit, busy: Boolean) {
    val labels = listOf(-2 to "Voz de monstruo", -1 to "Gruesa", 0 to "Normal", 1 to "Finita", 2 to "Voz de ardilla")
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEach { (steps, label) ->
            Button(onClick = { onPick(steps) }, enabled = !busy, modifier = Modifier.height(48.dp).widthIn(min = 84.dp).semantics { selected = cue.toneSteps == steps; role = androidx.compose.ui.semantics.Role.RadioButton }, contentPadding = pad,
                colors = if (cue.toneSteps == steps) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()) { Text((if (cue.toneSteps == steps) "✓ " else "") + label, fontSize = 15.sp, maxLines = 1) }
        }
        FilledTonalButton(onClick = onListen, enabled = !busy, modifier = Modifier.height(48.dp).widthIn(min = 84.dp), contentPadding = pad) { Text("▶ Oír", fontSize = 15.sp, maxLines = 1) }
        Button(onClick = onDone, modifier = Modifier.height(48.dp).widthIn(min = 84.dp), contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6F5F), contentColor = Color.White)) { Text("✓ Listo", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}

@Composable
internal fun InlineVolume(cue: Cue, onSet: (Float) -> Unit, onListen: () -> Unit, onDone: () -> Unit) {
    var v by remember(cue.id) { mutableStateOf(cue.volume) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bajito", fontSize = 15.sp)
        Slider(v, { v = it }, valueRange = 0f..(if (cue.type == CueType.MUSIC) 1f else 1.5f), onValueChangeFinished = { onSet(v) }, modifier = Modifier.weight(1f).semantics { contentDescription = "Volumen"; stateDescription = "${(v * 100).roundToInt()} por ciento" })
        Text("Fuerte  ${(v * 100).roundToInt()} %", fontSize = 15.sp)
        FilledTonalButton(onClick = onListen, modifier = Modifier.height(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text("▶ Oír", fontSize = 15.sp) }
        Button(onClick = onDone, modifier = Modifier.height(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6F5F), contentColor = Color.White)) { Text("✓ Listo", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InlineMove(cue: Cue, start: Double, onMove: (Double) -> Unit, onDone: () -> Unit, onVoice: ((Int) -> Unit)? = null) {
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    @Composable fun Step(label: String, delta: Double) =
        FilledTonalButton(onClick = { onMove(delta) }, modifier = Modifier.height(48.dp).widthIn(min = 84.dp), contentPadding = pad) { Text(label, fontSize = 15.sp, maxLines = 1) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Empieza en ${clock(start)}", Modifier.height(48.dp).padding(end = 6.dp).wrapContentHeight(Alignment.CenterVertically).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Step("◀◀ 5 s antes", -5.0); Step("◀ 1 s antes", -1.0); Step("1 s después ▶", 1.0); Step("5 s después ▶▶", 5.0)
            if (onVoice != null) {
                FilledTonalButton(onClick = { onVoice(-1) }, modifier = Modifier.height(48.dp).widthIn(min = 84.dp), contentPadding = pad) { Text("▲ Voz anterior", fontSize = 15.sp, maxLines = 1) }
                FilledTonalButton(onClick = { onVoice(1) }, modifier = Modifier.height(48.dp).widthIn(min = 84.dp), contentPadding = pad) { Text("▼ Voz siguiente", fontSize = 15.sp, maxLines = 1) }
            }
            Button(onClick = onDone, modifier = Modifier.height(48.dp).widthIn(min = 84.dp), contentPadding = pad,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6F5F), contentColor = Color.White)) { Text("✓ Listo", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InlineCursor(position: Double, onNudge: (Double) -> Unit, onListen: () -> Unit, onDone: () -> Unit) {
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    @Composable fun Step(label: String, delta: Double) =
        FilledTonalButton(onClick = { onNudge(delta) }, modifier = Modifier.height(48.dp).widthIn(min = 84.dp), contentPadding = pad) { Text(label, fontSize = 15.sp, maxLines = 1) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Línea roja: ${"%d:%04.1f".format(position.toInt() / 60, position % 60)}", Modifier.height(48.dp).padding(end = 6.dp).wrapContentHeight(Alignment.CenterVertically).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Step("◀ 1 s", -1.0); Step("◀ 0,1 s", -0.1); Step("0,1 s ▶", 0.1); Step("1 s ▶", 1.0)
        FilledTonalButton(onClick = onListen, modifier = Modifier.height(48.dp).widthIn(min = 84.dp), contentPadding = pad) { Text("▶ Oír este pedacito", fontSize = 15.sp, maxLines = 1) }
        Button(onClick = onDone, modifier = Modifier.height(48.dp).widthIn(min = 84.dp), contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6F5F), contentColor = Color.White)) { Text("✓ Listo", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}
