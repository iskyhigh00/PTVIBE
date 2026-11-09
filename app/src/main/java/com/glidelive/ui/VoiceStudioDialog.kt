package com.glidelive.ui

import android.content.Intent
import android.os.Build
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.glidelive.princess.AndroidNarrator
import com.glidelive.princess.NarratedAudio
import com.glidelive.princess.NarrationFamily
import com.glidelive.princess.NarrationPack
import com.glidelive.princess.NarrationPresets
import com.glidelive.princess.NarrationRequest
import com.glidelive.princess.NarrationText
import com.glidelive.princess.NarrationTexture
import com.glidelive.princess.NeuralNarrator
import com.glidelive.princess.Project
import com.glidelive.princess.PocketSetup
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

private data class DraftKey(val request: NarrationRequest, val external: Boolean, val engine: String?, val voice: String?)

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
    var engines by remember { mutableStateOf(AndroidNarrator.engines(app)) }
    var external by remember(project.id, characterId) {
        mutableStateOf(character?.narrationEngine != null || engines.any { it.packageName == AndroidNarrator.POCKET_PACKAGE })
    }
    var enginePackage by remember(project.id, characterId) { mutableStateOf(character?.narrationEngine ?: engines.firstOrNull()?.packageName) }
    var engineVoices by remember(project.id, characterId) { mutableStateOf<List<AndroidNarrator.Voice>>(emptyList()) }
    var engineVoice by remember(project.id, characterId) { mutableStateOf<String?>(null) }
    var refreshEngines by remember { mutableStateOf(0) }
    var needsEngineRefresh by remember { mutableStateOf(false) }
    var pocketInstalled by remember { mutableStateOf(PocketSetup.isInstalled(app)) }
    var pendingPocketApk by remember { mutableStateOf<File?>(null) }
    var awaitingPocketPermission by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val spanishFolder = if (Build.VERSION.SDK_INT >= 29) "Descargas/GlideLive/Pocket"
        else "Android/data/${app.packageName}/files/Download/Pocket"
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
    val key = DraftKey(request, external, if (external) enginePackage else null, if (external) engineVoice else null)
    val audio = draft?.takeIf { it.first == key }?.second
    val canGenerate = if (external) engineVoice != null && engineVoices.any { it.name == engineVoice } else installed
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

    fun refreshInstalledEngines() {
        val previouslyHadPocket = pocketInstalled
        pocketInstalled = PocketSetup.isInstalled(app)
        engines = AndroidNarrator.engines(app)
        val nowHasPocket = pocketInstalled
        if (nowHasPocket) {
            pendingPocketApk = null
            awaitingPocketPermission = false
        }
        if (nowHasPocket && !previouslyHadPocket) {
            external = true
            enginePackage = AndroidNarrator.POCKET_PACKAGE
        } else if (engines.none { it.packageName == enginePackage }) {
            enginePackage = engines.firstOrNull()?.packageName
        }
        refreshEngines++
    }

    fun launchPocketInstaller(file: File) {
        when (PocketSetup.requestInstall(context, file)) {
            PocketSetup.InstallResult.ALREADY_INSTALLED -> {
                notice = "Pocket TTS ya está instalado."
                refreshInstalledEngines()
            }
            PocketSetup.InstallResult.NEEDS_PERMISSION -> {
                awaitingPocketPermission = true
                notice = "Autoriza a GlideLive a instalar esta aplicación y vuelve. Android mostrará la confirmación de instalación."
            }
            PocketSetup.InstallResult.INSTALLER_OPENED -> {
                awaitingPocketPermission = false
                notice = "Confirma la instalación en Android. Al volver se detectará Pocket TTS."
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) needsEngineRefresh = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(needsEngineRefresh, busy) {
        if (needsEngineRefresh && !busy) {
            needsEngineRefresh = false
            refreshInstalledEngines()
            val apk = pendingPocketApk
            if (awaitingPocketPermission && apk != null && app.packageManager.canRequestPackageInstalls()) {
                awaitingPocketPermission = false
                runCatching { launchPocketInstaller(apk) }.onFailure { error = it.localizedMessage ?: "No se pudo continuar la instalación." }
            }
        }
    }

    LaunchedEffect(project.id, characterId, external, enginePackage, refreshEngines) {
        if (external) {
            engineVoices = emptyList()
            engineVoice = null
            val selectedEngine = enginePackage
            if (selectedEngine != null) action("Buscando voces españolas sin conexión") {
                engineVoices = AndroidNarrator.voices(app, selectedEngine)
                val storedVoice = character?.takeIf { it.narrationEngine == selectedEngine }?.narrationVoice
                engineVoice = if (storedVoice == null) engineVoices.firstOrNull()?.name else engineVoices.firstOrNull { it.name == storedVoice }?.name
                val voice = engineVoice
                val who = characterId
                if (voice != null && who != null) vm.editCharacter(who) { it.copy(narrationEngine = selectedEngine, narrationVoice = voice) }
                if (storedVoice != null && engineVoice == null && engineVoices.isNotEmpty()) notice = "La voz guardada para este personaje ya no está disponible. Elige otra voz."
                if (engineVoices.isEmpty()) notice = "Este motor no ofrece voces españolas sin conexión instaladas. Abre su aplicación para descargar o importar una voz."
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
        val generated = if (external) {
            AndroidNarrator.render(app, requireNotNull(enginePackage), requireNotNull(engineVoice), selected, destination)
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
                if (!pocketInstalled) {
                    Text("Preparar Pocket TTS", style = MaterialTheme.typography.titleSmall)
                    Text("GlideLive puede descargar e iniciar la instalación del motor. Android te pedirá autorizar y confirmar la instalación.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = {
                        action("Preparando instalación de Pocket TTS") {
                            val apk = pendingPocketApk ?: PocketSetup.downloadApk(app) { progress = "Descargando Pocket TTS" to it }
                            pendingPocketApk = apk
                            launchPocketInstaller(apk)
                        }
                    }) { Text(if (pendingPocketApk == null) "Instalar Pocket TTS" else "Continuar instalación de Pocket TTS") }
                } else if (!external || enginePackage != AndroidNarrator.POCKET_PACKAGE) {
                    TextButton(enabled = !busy, onClick = {
                        external = true
                        engineVoices = emptyList()
                        engineVoice = null
                        enginePackage = AndroidNarrator.POCKET_PACKAGE
                        refreshEngines++
                    }) { Text("Usar Pocket TTS") }
                }
                Text("Motor de voz", style = MaterialTheme.typography.titleSmall)
                Row {
                    TextButton(enabled = !busy, onClick = {
                        external = false
                        characterId?.let { who -> vm.editCharacter(who) { it.copy(narrationEngine = null, narrationVoice = null) } }
                    }) { Text(if (!external) "● Supertonic" else "Supertonic") }
                    TextButton(enabled = !busy, onClick = { external = true }) { Text(if (external) "● Otro motor Android" else "Otro motor Android") }
                }
                if (external && !busy) {
                    Picker("Motor: " + (engines.firstOrNull { it.packageName == enginePackage }?.label ?: "Ninguno instalado"),
                        engines, { it.label }) {
                        engineVoices = emptyList()
                        engineVoice = null
                        enginePackage = it.packageName
                    }
                    TextButton(onClick = {
                        refreshInstalledEngines()
                    }) { Text("Actualizar motores y voces") }
                    if (engineVoices.isNotEmpty()) Picker("Voz: " + (engineVoices.firstOrNull { it.name == engineVoice }?.label ?: "Elegir"),
                        engineVoices, { it.label }) { chosen ->
                        engineVoice = chosen.name
                        val engine = enginePackage
                        if (engine != null) characterId?.let { who ->
                            vm.editCharacter(who) { it.copy(narrationEngine = engine, narrationVoice = chosen.name) }
                        }
                    }
                    Text("Se usa la voz original del motor, sin cambios de tono, estiramientos ni presets de GlideLive.", style = MaterialTheme.typography.bodySmall)
                    if (character != null && character.pitch != 0f) {
                        Text("Este personaje tiene un ajuste de tono en la obra. La escucha previa será original; al mezclar se aplicará ese ajuste a todas sus pistas.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.editCharacter(character.id) { it.copy(pitch = 0f) } }) { Text("Restablecer tono del personaje") }
                    }
                    if (pocketInstalled && enginePackage == AndroidNarrator.POCKET_PACKAGE) {
                        if (engineVoices.isEmpty()) {
                            Text("Paso 2: descarga el paquete español (207 MB). Luego, en Pocket, pulsa Importar paquete de idioma y elige ${PocketSetup.SPANISH_FILE} en $spanishFolder.",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                action("Preparando voces españolas") {
                                    PocketSetup.downloadSpanishPack(app) { progress = "Descargando paquete español" to it }
                                    notice = "Paquete guardado: ${PocketSetup.SPANISH_FILE}. En Pocket pulsa Importar paquete de idioma y selecciónalo en $spanishFolder."
                                    PocketSetup.openPocket(context)
                                }
                            }) { Text("Descargar español y abrir Pocket") }
                        }
                        Text("Para añadir otras voces, abre Pocket, elige el paquete español e importa un audio WAV de referencia. Al volver, GlideLive actualizará las voces automáticamente.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            runCatching { PocketSetup.openPocket(context) }.onFailure { error = it.localizedMessage ?: "No se pudo abrir Pocket TTS." }
                        }) { Text("Abrir Pocket para importar paquete o voces") }
                    }
                    enginePackage?.takeIf { it != AndroidNarrator.POCKET_PACKAGE }?.let { pkg ->
                        TextButton(onClick = {
                            val launch = app.packageManager.getLaunchIntentForPackage(pkg)
                            if (launch == null) error = "Abre la aplicación del motor desde Android para configurar sus voces."
                            else runCatching { context.startActivity(launch) }.onFailure {
                                error = "No se pudo abrir la aplicación del motor. Ábrela desde Android."
                            }
                        }) { Text("Configurar voces en el motor") }
                    }
                }
                if (!external && !installed) {
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
                if (!busy && !external && installed) {
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
                    Text("Estos son los presets anteriores de tono y efectos. Para probar voces originales distintas, cambia a otro motor Android.",
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
                if (!external) Text(NarrationPack.ATTRIBUTION, style = MaterialTheme.typography.labelSmall)
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
