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
import com.glidelive.princess.NarratedAudio
import com.glidelive.princess.NarrationPack
import com.glidelive.princess.NarrationRequest
import com.glidelive.princess.NarrationText
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
    var characterId by remember { mutableStateOf(project.characters.firstOrNull()?.id) }
    var speaker by remember { mutableStateOf(0) }
    var language by remember { mutableStateOf(narrationLanguages.first()) }
    var pace by remember { mutableStateOf(1f) }
    var pitch by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf<Pair<NarrationRequest, NarratedAudio>?>(null) }
    var progress by remember { mutableStateOf<Pair<String, Float?>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val busy = progress != null

    val selection = document.selection
    val selected = document.text.substring(selection.min, selection.max).trim()
    val request = NarrationRequest(selected, speaker, language.first, pace, pitch)
    val audio = draft?.takeIf { it.first == request }?.second
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
        val generated = NeuralNarrator.render(app, request, destination) { progress = "Generando narración" to it }
        val previous = draft?.second
        draft = request to generated
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
                if (!installed) {
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
                if (!busy && installed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Picker("Voz ${speaker + 1}", (0 until NeuralNarrator.VOICES).toList(), { "Voz ${it + 1}" }) { speaker = it }
                        Picker(language.second, narrationLanguages, { it.second }) { language = it }
                        Picker(delivery, deliveries, { it.name }) { pace = it.pace; pitch = it.pitch }
                        Picker(project.characters.firstOrNull { it.id == characterId }?.name ?: "Personaje",
                            project.characters, { it.name }) { characterId = it.id }
                    }
                    LabeledSlider("Velocidad ${(pace * 100).roundToInt()} %", pace, 0.75f..1.4f) { pace = it }
                    LabeledSlider("Tono %+.0f semitonos".format(Locale.ROOT, pitch.toFloat()), pitch.toFloat(), -6f..6f) { pitch = it.roundToInt() }
                }
                if (audio != null) Row {
                    TextButton(enabled = !busy, onClick = { listen(audio) }) { Text("Escuchar de nuevo") }
                    TextButton(enabled = !busy, onClick = vm::stop) { Text("Parar") }
                    TextButton(enabled = !busy, onClick = { generate() }) { Text("Otra toma") }
                }
                progress?.let { (label, fraction) ->
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(NarrationPack.ATTRIBUTION, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            if (audio == null) {
                TextButton(enabled = !busy && installed && selected.isNotEmpty() && selected.length <= NarrationText.MAX_SELECTION,
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
