package com.glidelive.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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
private data class DraftKey(val request: NarrationRequest, val pocket: Boolean, val voice: String?)

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

@Composable
fun VoiceStudioDialog(
    project: Project,
    vm: PrincessViewModel,
    document: TextFieldValue,
    onDocument: (TextFieldValue) -> Unit,
    onClose: () -> Unit,
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
    var presetId by remember(project.id, characterId) { mutableStateOf<String?>(initialPreset.id) }
    var family by remember(project.id, characterId) { mutableStateOf(initialPreset.family) }
    var speaker by remember(project.id, characterId) { mutableStateOf(initialPreset.speaker) }
    var language by remember(project.id, characterId) { mutableStateOf(narrationLanguages.first()) }
    var pace by remember(project.id, characterId) { mutableStateOf(initialPreset.pace) }
    var pitch by remember(project.id, characterId) { mutableStateOf(initialPreset.pitch) }
    var texture by remember(project.id, characterId) { mutableStateOf(initialPreset.texture) }
    val pocketSupported = remember { EmbeddedPocketPack.supportsDevice() }
    var pocketReady by remember { mutableStateOf(EmbeddedPocketPack.isReady(app)) }
    var pocketVoices by remember { mutableStateOf(EmbeddedPocketPack.voices(app)) }
    var usePocket by remember(project.id, characterId) { mutableStateOf(character?.narrationEngine != SUPERTONIC_ENGINE) }
    var pocketVoiceId by remember(project.id, characterId) {
        mutableStateOf(character?.takeIf { it.narrationEngine == POCKET_ENGINE }?.narrationVoice ?: pocketVoices.firstOrNull()?.id)
    }
    var expressive by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<Pair<DraftKey, NarratedAudio>?>(null) }
    var progress by remember { mutableStateOf<Pair<String, Float?>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val busy = progress != null

    val selection = document.selection
    val selected = document.text.substring(selection.min, selection.max).trim()
    val request = NarrationRequest(selected, speaker, language.first, pace, pitch, texture, presetId, expressive)
    val key = DraftKey(request, usePocket, if (usePocket) pocketVoiceId else null)
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

    LaunchedEffect(project.id, characterId, usePocket, pocketReady) {
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
            notice = "Texto cargado. Mantén presionado para marcar el fragmento que quieres narrar."
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
            EmbeddedPocketNarrator.render(app, selected, voice.filePath, destination) { progress = "Generando con Pocket integrado" to it }
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

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        modifier = Modifier.fillMaxWidth(0.9f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Narrar texto") },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                progress?.let { (label, fraction) ->
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Motor de voz", style = MaterialTheme.typography.titleSmall)
                Row {
                    TextButton(enabled = !busy, onClick = { usePocket = true }) { Text(if (usePocket) "● Pocket integrado" else "Pocket integrado") }
                    TextButton(enabled = !busy, onClick = {
                        usePocket = false
                        characterId?.let { who -> vm.editCharacter(who) { it.copy(narrationEngine = SUPERTONIC_ENGINE, narrationVoice = null) } }
                    }) { Text(if (!usePocket) "● Supertonic" else "Supertonic") }
                }
                if (usePocket) {
                    if (!pocketSupported) {
                        Text("Pocket integrado requiere un dispositivo Android de 64 bits ARM. Este dispositivo no es compatible; puedes usar Supertonic.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else if (!pocketReady) {
                        Text("Descarga una vez las voces Pocket en español (aprox. 207 MB). El modelo se guarda dentro de GlideLive y después genera sin conexión.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = {
                            action("Preparando voces Pocket") {
                                EmbeddedPocketPack.install(app) { progress = "Descargando y preparando voces Pocket" to it }
                                pocketVoices = EmbeddedPocketPack.voices(app)
                                pocketReady = EmbeddedPocketPack.isReady(app)
                                check(pocketReady && pocketVoices.isNotEmpty()) { "El paquete Pocket no se pudo preparar. Intenta descargarlo de nuevo." }
                                notice = "Voces Pocket preparadas dentro de GlideLive. Elige una voz y genera una toma nueva."
                            }
                        }) { Text("Descargar voces Pocket") }
                    } else {
                        Text("Voces Pocket listas · Generación dentro de GlideLive y sin conexión", style = MaterialTheme.typography.bodySmall)
                        if (!busy) Picker("Voz: " + (pocketVoices.firstOrNull { it.id == pocketVoiceId }?.name ?: "Elegir"),
                            pocketVoices, { it.name }) { chosen ->
                            pocketVoiceId = chosen.id
                            characterId?.let { who -> vm.editCharacter(who) { it.copy(narrationEngine = POCKET_ENGINE, narrationVoice = chosen.id) } }
                        }
                    }
                    Text("Esta ruta conserva la voz generada por el modelo, sin cambios de tono ni alargamientos de GlideLive. Se procesa en fragmentos cortos para reducir omisiones; escucha la toma y comprueba el libreto antes de guardarla.",
                        style = MaterialTheme.typography.bodySmall)
                    if (character != null && character.pitch != 0f) {
                        Text("Este personaje tiene un ajuste de tono en la obra. La escucha previa será original; al mezclar se aplicará ese ajuste a todas sus pistas.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = { vm.editCharacter(character.id) { it.copy(pitch = 0f) } }) { Text("Restablecer tono del personaje") }
                    }
                }
                if (!usePocket && !installed) {
                    Text("Descarga el paquete de 10 voces neuronales (129 MB). Una vez instalado, puedes narrar sin conexión.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = {
                        action("Descargando voces") {
                            NarrationPack.install(app) { progress = (if (it < 0.9f) "Descargando voces" else "Instalando voces") to it }
                            installed = true
                        }
                    }) { Text("Descargar voces") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = { openText.launch(arrayOf("text/*")) }) { Text("Abrir TXT") }
                    TextButton(enabled = !busy && document.text.isNotEmpty(), onClick = {
                        onDocument(document.copy(selection = TextRange(0, document.text.length)))
                    }) { Text("Seleccionar todo") }
                }
                OutlinedTextField(
                    value = document,
                    onValueChange = {
                        if (!busy) {
                            if (it.text.length <= NarrationText.MAX_DOCUMENT) onDocument(it)
                            else error = "El texto supera ${NarrationText.MAX_DOCUMENT} caracteres."
                        }
                    },
                    label = { Text("Libreto o texto") },
                    placeholder = { Text("Abre un TXT o pega el texto. Mantén presionado para seleccionar un fragmento.") },
                    readOnly = busy,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(when {
                    selected.isEmpty() -> "Marca el texto que quieres narrar."
                    selected.length > NarrationText.MAX_SELECTION -> "Selecciona hasta ${NarrationText.MAX_SELECTION} caracteres por audio."
                    else -> "${selected.length} caracteres seleccionados."
                }, style = MaterialTheme.typography.bodySmall)
                if (!busy) Picker("Personaje: " + (project.characters.firstOrNull { it.id == characterId }?.name ?: "Elegir"),
                        project.characters, { it.name }) { characterId = it.id }
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
                        val id = characterId
                        if (id != null) vm.editCharacter(id) { character -> character.copy(narrationPresetId = chosen.id) }
                    }
                    preset?.let { Text(it.description, style = MaterialTheme.typography.bodySmall) }
                    Text("Estos son los presets anteriores de tono y efectos. Para probar el otro modelo, elige Pocket integrado.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Español: puedes escribir con modismos chilenos. El paquete no tiene un acento chileno específico.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { expressive = !expressive }) {
                        Text(if (expressive) "Procesamiento experimental: activado" else "Procesamiento experimental: desactivado")
                    }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Ocultar ajustes" else "Ajustes opcionales") }
                    if (advanced) {
                        Text("Los ajustes manuales son temporales; el personaje conserva su preset.", style = MaterialTheme.typography.bodySmall)
                        Picker("Voz ${speaker + 1}", (0 until NeuralNarrator.VOICES).toList(), { "Voz ${it + 1}" }) { speaker = it; presetId = null }
                        Picker(language.second, narrationLanguages, { it.second }) { language = it; presetId = null }
                        Picker(delivery, deliveries, { it.name }) { pace = it.pace; pitch = it.pitch; presetId = null }
                        Picker("Textura: ${texture.label}", NarrationTexture.entries.toList(), { it.label }) { texture = it; presetId = null }
                        LabeledSlider("Velocidad ${(pace * 100).roundToInt()} %", pace, 0.75f..1.4f) { pace = it; presetId = null }
                        LabeledSlider("Tono %+.0f semitonos".format(Locale.ROOT, pitch.toFloat()), pitch.toFloat(), -6f..6f) { pitch = it.roundToInt(); presetId = null }
                    }
                }
                if (audio != null) Row {
                    TextButton(enabled = !busy, onClick = { listen(audio) }) { Text("Escuchar de nuevo") }
                    TextButton(enabled = !busy, onClick = vm::stop) { Text("Parar") }
                    TextButton(enabled = !busy, onClick = { generate() }) { Text("Otra toma") }
                }
                if (!usePocket) Text(NarrationPack.ATTRIBUTION, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            if (audio == null) {
                TextButton(enabled = !busy && canGenerate && selected.isNotEmpty() && selected.length <= NarrationText.MAX_SELECTION,
                    onClick = { generate() }) { Text("Generar y escuchar") }
            } else {
                TextButton(enabled = !busy && characterId != null, onClick = {
                    saving = true
                    action("Guardando en la obra") {
                        vm.addNarration(project.id, requireNotNull(characterId), audio)
                        previewJob?.cancel()
                        audio.file.delete()
                        draft = null
                        notice = "Audio agregado. Marca otro fragmento para seguir."
                    }
                }) { Text("Agregar a la obra") }
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = { if (busy) job?.cancel() else onClose() }) {
                Text(if (busy) "Cancelar" else "Cerrar")
            }
        },
    )
}
