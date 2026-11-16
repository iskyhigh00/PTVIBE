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
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
        Text(msg, Modifier.fillMaxWidth().padding(vertical = 4.dp).background(
            if (u.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)).padding(10.dp),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            color = if (u.error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
internal fun DownloadCard(vm: PrincessViewModel, u: PrincessUi, what: String) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val d = u.download
        val onCard = MaterialTheme.colorScheme.onSecondaryContainer
        if (d != null) {
            Text("${d.first}  ${(d.second * 100).toInt()} %", fontWeight = FontWeight.Bold, color = onCard)
            LinearProgressIndicator(progress = { d.second.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = vm::cancelDownload, modifier = Modifier.height(48.dp), colors = ButtonDefaults.textButtonColors(contentColor = onCard)) { Text("Cancelar", fontWeight = FontWeight.Bold) }
        } else {
            Text(if (u.sfxPartial) "La descarga quedó a medias." else "Para tener $what hay que bajar los sonidos y la música una sola vez.", fontWeight = FontWeight.Bold, color = onCard)
            Text("Conviene usar WiFi: son varias decenas de MB. Después quedan guardados en tu teléfono y funcionan sin internet.", style = MaterialTheme.typography.bodyMedium, color = onCard)
            BigButton(if (u.sfxPartial) "Completar la descarga" else "⬇ Descargar", RED, { vm.downloadAll() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SoundsSheet(vm: PrincessViewModel, u: PrincessUi, onClose: () -> Unit, onPickFile: () -> Unit) {
    val ctx = LocalContext.current
    var group by rememberSaveable { mutableStateOf("Animales") }
    val groups = SoundCatalog.groups.map { it.name } + "Mis sonidos"
    Sheet("Poner un sonido en ${clock(vm.overlayTime())}", onClose, u) {
        if (!u.hasSfx) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DownloadCard(vm, u, "los sonidos")
            TextButton(onClick = onPickFile, modifier = Modifier.height(48.dp)) { Text("O elegir un sonido de mi teléfono…", fontSize = 15.sp) }
        }
        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { g -> FilledTonalButton(onClick = { group = g }, modifier = Modifier.height(48.dp).semantics { selected = group == g; role = androidx.compose.ui.semantics.Role.Tab }, colors = if (group == g) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()) { Text((if (group == g) "✓ " else "") + g, fontSize = 15.sp, maxLines = 1) } }
            }
            Text("Toca un sonido y se pone donde está la línea roja. «▶ Oír» es para escucharlo antes.", style = MaterialTheme.typography.bodyMedium)
            if (group == "Mis sonidos") {
                if (u.mine.isEmpty()) Text("Aquí aparecen los sonidos que guardes tú.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                u.mine.forEach { m ->
                    val f = java.io.File(SoundStore.mineDir(ctx), m.file)
                    SoundRow("🎙", m.name, onListen = { vm.previewFile(f) }, onAdd = { vm.addSoundFile(f, m.name, CueType.SFX, vm.overlayTime()) })
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val available = remember(group, u.hasSfx) { SoundCatalog.groups.firstOrNull { it.name == group }?.sounds.orEmpty().filter { SoundStore.clips(ctx, it.id).isNotEmpty() } }
                    available.forEach { s ->
                        SoundTile(ICON[s.id] ?: "🔊", s.label,
                            onListen = { vm.clipFor(s.id)?.let(vm::previewFile) }, onAdd = { vm.clipFor(s.id)?.let { vm.addSoundFile(it, s.label, CueType.SFX, vm.overlayTime()) } })
                    }
                }
            }
            TextButton(onClick = onPickFile, modifier = Modifier.height(48.dp)) { Text("Elegir un sonido de mi teléfono…", fontSize = 15.sp) }
            Text(SoundCatalog.SFX_CREDIT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SoundTile(icon: String, label: String, onListen: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier.width(120.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)).clickable(onClick = onAdd).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, fontSize = 34.sp)
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSecondaryContainer)
        TextButton(onClick = onListen, modifier = Modifier.height(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("▶ Oír", fontSize = 16.sp) }
    }
}

@Composable
internal fun SoundRow(icon: String, label: String, onListen: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$icon  $label", Modifier.weight(1f), fontSize = 16.sp)
        TextButton(onClick = onListen, modifier = Modifier.height(48.dp)) { Text("▶ Oír", fontSize = 16.sp) }
        FilledTonalButton(onClick = onAdd, modifier = Modifier.height(48.dp)) { Text("＋ Poner", fontSize = 16.sp) }
    }
}

@Composable
internal fun MusicSheet(vm: PrincessViewModel, u: PrincessUi, onClose: () -> Unit, onPickFile: () -> Unit) {
    val ctx = LocalContext.current
    val have = remember(u.musicCount) { SoundStore.musicFiles(ctx) }
    Sheet("Poner música de fondo desde ${clock(vm.overlayTime())}", onClose, u) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (have.isEmpty()) DownloadCard(vm, u, "la música")
            else {
                Text("La música suena suave por debajo y baja sola cuando alguien habla. Empieza en la línea roja.", style = MaterialTheme.typography.bodyMedium)
                have.forEach { (t, f) -> SoundRow("🎵", t.label, onListen = { vm.previewFile(f) }, onAdd = { vm.addSoundFile(f, t.label.substringBefore(" ·"), CueType.MUSIC, vm.overlayTime()) }) }
            }
            TextButton(onClick = onPickFile, modifier = Modifier.height(48.dp)) { Text("Elegir una música de mi teléfono…", fontSize = 15.sp) }
            Text(SoundCatalog.MUSIC_CREDIT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InlineTone(cue: Cue, onPick: (Int) -> Unit, onListen: () -> Unit, onDone: () -> Unit, busy: Boolean) {
    val labels = listOf(-2 to "Muy grave", -1 to "Grave", 0 to "Normal", 1 to "Aguda", 2 to "Muy aguda")
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
        Slider(v, { v = it }, valueRange = 0f..1.5f, onValueChangeFinished = { onSet(v) }, modifier = Modifier.weight(1f))
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
