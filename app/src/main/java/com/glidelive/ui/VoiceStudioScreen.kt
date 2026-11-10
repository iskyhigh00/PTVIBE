package com.glidelive.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.glidelive.princess.EmbeddedPocketNarrator
import com.glidelive.princess.EmbeddedPocketPack
import com.glidelive.princess.NarratedAudio
import com.glidelive.princess.NarrationFamily
import com.glidelive.princess.NarrationPack
import com.glidelive.princess.NarrationPresets
import com.glidelive.princess.NarrationRequest
import com.glidelive.princess.NarrationText
import com.glidelive.princess.NarrationTexture
import com.glidelive.princess.NeuralNarrator
import com.glidelive.princess.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private const val POCKET_ENGINE = "pocket-embedded"
private const val SUPERTONIC_ENGINE = "supertonic"
private data class DraftKey(val characterId: String?, val request: NarrationRequest, val pocket: Boolean, val voice: String?)

private data class Delivery(val name: String, val pace: Float, val pitch: Int)
private val deliveries = listOf(
    Delivery("Natural", 1f, 0),
    Delivery("Serena", 0.87f, -1),
    Delivery("Grave y pausada", 0.8f, -5),
    Delivery("Aguda y vivaz", 1.17f, 4),
    Delivery("Cálida", 0.92f, 1),
    Delivery("Enérgica", 1.28f, 2),
    Delivery("Misteriosa", 0.81f, -3),
    Delivery("Caricatura", 1.15f, 6),
)

private val narrationLanguages = listOf("es" to "Español", "en" to "Inglés", "pt" to "Portugués", "fr" to "Francés")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceStudioScreen(
    project: Project,
    vm: PrincessViewModel,
    document: TextFieldValue,
    onDocument: (TextFieldValue) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = context.applicationContext
    val directory = remember { File(app.cacheDir, "princess-narration-${UUID.randomUUID()}") }
    var installed by remember { mutableStateOf(NarrationPack.isReady(app)) }
    var characterId by remember(project.id) { mutableStateOf(project.characters.firstOrNull()?.id) }
    val character = project.characters.firstOrNull { it.id == characterId }
    val initialPreset = NarrationPresets.find(character?.narrationPresetId)
        ?: NarrationPresets.default
    val savedSpeaker = character?.takeIf { it.narrationEngine == SUPERTONIC_ENGINE }
        ?.narrationVoice?.toIntOrNull()?.takeIf { it in 0 until NeuralNarrator.VOICES }
    var presetId by remember(project.id, characterId) { mutableStateOf<String?>(if (savedSpeaker == null) initialPreset.id else null) }
    var family by remember(project.id, characterId) { mutableStateOf(initialPreset.family) }
    var speaker by remember(project.id, characterId) { mutableStateOf(savedSpeaker ?: initialPreset.speaker) }
    var language by remember(project.id, characterId) { mutableStateOf(narrationLanguages.first()) }
    var pace by remember(project.id, characterId) { mutableStateOf(if (savedSpeaker == null) initialPreset.pace else 1f) }
    var pitch by remember(project.id, characterId) { mutableStateOf(if (savedSpeaker == null) initialPreset.pitch else 0) }
    var texture by remember(project.id, characterId) { mutableStateOf(if (savedSpeaker == null) initialPreset.texture else NarrationTexture.NATURAL) }
    val pocketSupported = remember { EmbeddedPocketPack.supportsDevice() }
    var pocketReady by remember { mutableStateOf(EmbeddedPocketPack.isReady(app)) }
    var pocketVoices by remember { mutableStateOf(EmbeddedPocketPack.voices(app)) }
    var usePocket by remember(project.id, characterId) { mutableStateOf(pocketSupported && character?.narrationEngine != SUPERTONIC_ENGINE) }
    var pocketVoiceId by remember(project.id, characterId) {
        mutableStateOf(character?.takeIf { it.narrationEngine == POCKET_ENGINE }?.narrationVoice ?: pocketVoices.firstOrNull()?.id)
    }
    var expressive by remember(project.id, characterId) { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var voiceSettings by remember { mutableStateOf(false) }
    var newCharacter by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<Pair<DraftKey, NarratedAudio>?>(null) }
    var progress by remember { mutableStateOf<Pair<String, Float?>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val busy = progress != null
    val keyboardVisible = WindowInsets.isImeVisible

    val selection = document.selection
    val selected = NarrationText.forNarration(document.text, selection.start, selection.end)
    val request = NarrationRequest(selected, speaker, language.first, pace, pitch, texture, presetId, expressive)
    val key = DraftKey(characterId, request, usePocket, if (usePocket) pocketVoiceId else null)
    val audio = draft?.takeIf { it.first == key }?.second
    val canGenerate = if (usePocket) pocketSupported && pocketReady && pocketVoices.any { it.id == pocketVoiceId } else installed
    val delivery = deliveries.firstOrNull { it.pace == pace && it.pitch == pitch }?.name ?: "Personalizada"

    fun action(label: String, block: suspend () -> Unit) {
        if (progress != null) return
        error = null
        notice = null
        progress = label to null
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                error = "El motor de voces no está disponible en este dispositivo."
            } catch (e: Exception) {
                error = e.localizedMessage ?: "No se pudo completar la operación."
            } finally {
                progress = null
                saving = false
            }
        }
    }

    LaunchedEffect(project.id, characterId, character?.id, usePocket, pocketReady) {
        if (usePocket && pocketReady) {
            val stored = character?.takeIf { it.narrationEngine == POCKET_ENGINE }?.narrationVoice
            val chosen = pocketVoices.firstOrNull { it.id == stored } ?: pocketVoices.firstOrNull()
            pocketVoiceId = chosen?.id
            if (stored != null && chosen?.id != stored) {
                notice = "La voz guardada no está en este paquete. Se seleccionó ${chosen?.name ?: "ninguna voz"}; puedes elegir otra."
            } else if (character?.narrationEngine != null && character.narrationEngine != POCKET_ENGINE && character.narrationEngine != SUPERTONIC_ENGINE) {
                notice = "Se usa ahora Pocket dentro de GlideLive. Elige una de las voces del paquete integrado."
            }
            val who = characterId
            if (who != null && chosen != null) vm.editCharacter(who) {
                it.copy(narrationEngine = POCKET_ENGINE, narrationVoice = chosen.id)
            }
        }
    }

    val openText = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action("Leyendo el texto") {
            val text = withContext(Dispatchers.IO) {
                val input = checkNotNull(app.contentResolver.openInputStream(uri)) { "No se pudo abrir el archivo." }
                input.use {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = it.read(buffer)
                        if (size < 0) break
                        require(output.size() + size <= NarrationText.MAX_FILE_BYTES) { "El archivo de texto es demasiado grande." }
                        output.write(buffer, 0, size)
                    }
                    NarrationText.decode(output.toByteArray())
                }
            }
            onDocument(TextFieldValue(text))
            notice = "Texto cargado. Puedes narrarlo completo o seleccionar un fragmento."
        }
    }

    fun listen(clip: NarratedAudio) {
        previewJob?.cancel()
        previewJob = vm.previewFile(clip.file)
    }

    fun generate() = action("Preparando voz") {
        check(directory.isDirectory || directory.mkdirs()) { "No se pudo preparar el almacenamiento de audio." }
        val destination = File(directory, "${UUID.randomUUID()}.wav")
        val generated = if (usePocket) {
            val voice = pocketVoices.firstOrNull { it.id == pocketVoiceId } ?: error("Elige una voz del paquete integrado.")
            EmbeddedPocketNarrator.render(app, selected, voice.filePath, destination) { progress = "Creando la voz" to it }
        } else NeuralNarrator.render(app, request, destination) { progress = "Generando narración" to it }
        val previous = draft?.second
        draft = key to generated
        listen(generated)
        previous?.file?.delete()
    }

    DisposableEffect(directory) {
        onDispose {
            previewJob?.let { it.cancel(); vm.stop() }
            val pending = job
            if (pending == null || pending.isCompleted) directory.deleteRecursively()
            else pending.invokeOnCompletion { directory.deleteRecursively() }
        }
    }

    LaunchedEffect(key) {
        previewJob?.let { it.cancel(); vm.stop() }
    }

    if (newCharacter) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newCharacter = false },
            title = { Text("Nuevo personaje") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre del personaje") }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                characterId = vm.addCharacter(name.trim())
                newCharacter = false
            }) { Text("Crear") } },
            dismissButton = { TextButton(onClick = { newCharacter = false }) { Text("Cancelar") } },
        )
    }

    BackHandler { if (!busy) onClose() }
    Surface(modifier.fillMaxSize().testTag("voice-studio-viewport"), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()
            .windowInsetsPadding(playInsets.union(WindowInsets.ime))
            .padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Crear una voz", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = onClose) { Text("Volver") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1 · Texto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = { openText.launch(arrayOf("text/*")) }) { Text("Abrir TXT") }
                    TextButton(enabled = !busy && !selection.collapsed, onClick = {
                        onDocument(document.copy(selection = TextRange(document.text.length)))
                    }) { Text("Usar todo el texto") }
                }
                OutlinedTextField(
                    value = document,
                    onValueChange = {
                        if (!busy) {
                            if (it.text.length <= NarrationText.MAX_DOCUMENT) onDocument(it)
                            else error = "El texto supera ${NarrationText.MAX_DOCUMENT} caracteres."
                        }
                    },
                    label = { Text("Escribe o pega tu libreto") },
                    placeholder = { Text("Había una vez…") },
                    readOnly = busy, minLines = if (keyboardVisible) 1 else 3, maxLines = if (keyboardVisible) 1 else 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(when {
                    selected.isEmpty() -> "Pega un texto o abre un TXT para comenzar."
                    selected.length > NarrationText.MAX_SELECTION -> "Mantén presionado y selecciona un fragmento de hasta ${NarrationText.MAX_SELECTION} caracteres."
                    selection.collapsed -> "Se narrará todo el texto · ${selected.length} caracteres. Puedes seleccionar solo un fragmento."
                    else -> "Se narrará solo la selección · ${selected.length} caracteres."
                }, style = MaterialTheme.typography.bodySmall)

                Text("2 · Personaje y voz", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (!busy) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Picker("Personaje: " + (character?.name ?: "Elegir"), project.characters, { it.name }) { characterId = it.id }
                    TextButton(onClick = { newCharacter = true }) { Text("+ Personaje") }
                } else Text("Personaje: ${character?.name.orEmpty()}")
                if (usePocket) {
                    if (!pocketSupported) {
                        Text("Estas voces requieren un teléfono ARM de 64 bits. En Opciones de voz puedes elegir Supertonic.", color = MaterialTheme.colorScheme.error)
                    } else if (!pocketReady) {
                        Text("Descarga las voces en español una vez (207 MB). Después podrás usarlas sin conexión.", style = MaterialTheme.typography.bodyMedium)
                        Button(enabled = !busy, onClick = {
                            action("Preparando voces") {
                                EmbeddedPocketPack.install(app) { progress = "Descargando voces" to it }
                                pocketVoices = EmbeddedPocketPack.voices(app)
                                pocketReady = EmbeddedPocketPack.isReady(app)
                                check(pocketReady && pocketVoices.isNotEmpty()) { "No se pudo preparar el paquete. Intenta descargarlo de nuevo." }
                                notice = "Voces listas. Elige una y genera el audio."
                            }
                        }) { Text("Descargar voces") }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pocketVoices.forEach { voice ->
                                FilterChip(selected = pocketVoiceId == voice.id, enabled = !busy,
                                    onClick = {
                                        pocketVoiceId = voice.id
                                        characterId?.let { who -> vm.editCharacter(who) { it.copy(narrationEngine = POCKET_ENGINE, narrationVoice = voice.id) } }
                                    }, label = { Text(voice.name) })
                            }
                        }
                        Text("La voz se recuerda para este personaje.", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    if (!installed) {
                        Text("Descarga estas voces una vez (129 MB) para usarlas sin conexión.", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !busy, onClick = {
                            action("Descargando voces") {
                                NarrationPack.install(app) { progress = "Descargando voces" to it }
                                installed = true
                            }
                        }) { Text("Descargar voces") }
                    } else if (!busy) {
                        Picker("Voz ${speaker + 1} · Supertonic", (0 until NeuralNarrator.VOICES).toList(), { "Voz ${it + 1}" }) { chosen ->
                            speaker = chosen
                            presetId = null
                            pace = 1f
                            pitch = 0
                            texture = NarrationTexture.NATURAL
                            expressive = false
                            characterId?.let { who -> vm.editCharacter(who) {
                                it.copy(narrationEngine = SUPERTONIC_ENGINE, narrationVoice = chosen.toString(), narrationPresetId = null)
                            } }
                        }
                        NarrationPresets.find(presetId)?.let { Text("Preset activo: ${it.name}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (character != null && character.pitch != 0f) {
                    Text("Este personaje tiene un cambio de tono en la mezcla.", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { vm.editCharacter(character.id) { it.copy(pitch = 0f) } }) { Text("Usar su tono original") }
                }
                TextButton(enabled = !busy, onClick = { voiceSettings = !voiceSettings }) {
                    Text(if (voiceSettings) "Ocultar opciones de voz" else "Opciones de voz")
                }
                if (voiceSettings) {
                    Text("Motor de voz", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = usePocket, enabled = !busy, onClick = { usePocket = true }, label = { Text("Pocket · Español") })
                        FilterChip(selected = !usePocket, enabled = !busy, onClick = {
                            usePocket = false
                            characterId?.let { who -> vm.editCharacter(who) { it.copy(narrationEngine = SUPERTONIC_ENGINE, narrationVoice = if (presetId == null) speaker.toString() else null, narrationPresetId = presetId) } }
                        }, label = { Text("Supertonic") })
                    }
                    Text("Ambos funcionan dentro de GlideLive, sin conexión después de la descarga.", style = MaterialTheme.typography.bodySmall)
                    if (!busy && !usePocket && installed) {
                        Picker("Familia: ${family.label}", NarrationFamily.entries.toList(), { it.label }) { family = it }
                        val preset = NarrationPresets.find(presetId)
                        Picker("Preset: ${preset?.name ?: "Personalizado"}", NarrationPresets.all.filter { it.family == family }, { it.name }) { chosen ->
                            presetId = chosen.id
                            speaker = chosen.speaker
                            pace = chosen.pace
                            pitch = chosen.pitch
                            texture = chosen.texture
                            language = narrationLanguages.first()
                            characterId?.let { id -> vm.editCharacter(id) { it.copy(narrationPresetId = chosen.id, narrationVoice = null) } }
                        }
                        preset?.let { Text(it.description, style = MaterialTheme.typography.bodySmall) }
                        Text("Los presets modifican tono y efectos. No son voces nuevas ni un acento chileno específico.", style = MaterialTheme.typography.bodySmall)
                        FilterChip(selected = expressive, onClick = { expressive = !expressive }, label = { Text("Efectos experimentales") })
                        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Ocultar ajustes manuales" else "Ajustes manuales") }
                        if (advanced) {
                            Text("Los ajustes manuales son temporales; el personaje conserva su preset.", style = MaterialTheme.typography.bodySmall)
                            Picker(language.second, narrationLanguages, { it.second }) { language = it; presetId = null }
                            Picker(delivery, deliveries, { it.name }) { pace = it.pace; pitch = it.pitch; presetId = null }
                            Picker("Textura: ${texture.label}", NarrationTexture.entries.toList(), { it.label }) { texture = it; presetId = null }
                            LabeledSlider("Velocidad ${(pace * 100).roundToInt()} %", pace, 0.75f..1.4f) { pace = it; presetId = null }
                            LabeledSlider("Tono %+.0f semitonos".format(Locale.ROOT, pitch.toFloat()), pitch.toFloat(), -6f..6f) { pitch = it.roundToInt(); presetId = null }
                        }
                        Text(NarrationPack.ATTRIBUTION, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text("3 · Escuchar y agregar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (audio != null) {
                    Text("Audio listo para ${character?.name.orEmpty()}. Escúchalo antes de agregarlo.")
                } else Text("Al generar, la voz se reproduce automáticamente. Después podrás agregarla al timeline.", style = MaterialTheme.typography.bodySmall)
                // Messages belong in the scrolling content: a long error must not
                // push the fixed action buttons outside a short landscape window.
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                progress?.let { (label, fraction) ->
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
            // Keep playback and the main action within the inset-safe viewport.
            if (audio != null && !busy) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { listen(audio) }) { Text("▶ Escuchar") }
                TextButton(onClick = vm::stop) { Text("■ Detener") }
                TextButton(onClick = { generate() }) { Text("Otra toma") }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    TextButton(enabled = !saving, onClick = { job?.cancel() }) { Text("Cancelar") }
                }
                Button(modifier = Modifier.weight(1f),
                    enabled = !busy && characterId != null && (audio != null || (canGenerate && selected.isNotEmpty() && selected.length <= NarrationText.MAX_SELECTION)),
                    onClick = {
                        if (audio == null) generate()
                        else {
                            saving = true
                            action("Agregando al cuento") {
                                vm.addNarration(project.id, requireNotNull(characterId), audio)
                                previewJob?.cancel()
                                vm.stop()
                                audio.file.delete()
                                draft = null
                                notice = "Audio agregado al timeline. Puedes narrar otro fragmento o volver al cuento."
                            }
                        }
                    }) { Text(if (busy) "Procesando…" else if (audio == null) "Generar y escuchar" else "Agregar al timeline") }
            }
        }
    }
}
