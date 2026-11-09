package com.glidelive.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glidelive.audio.NativeEngine
import com.glidelive.princess.Character
import com.glidelive.princess.Clip
import com.glidelive.princess.Cue
import com.glidelive.princess.CueMode
import com.glidelive.princess.CueType
import com.glidelive.princess.DramaRenderer
import com.glidelive.princess.MicRecorder
import com.glidelive.princess.Placement
import com.glidelive.princess.PrincessLibrary
import com.glidelive.princess.Project
import com.glidelive.princess.NarratedAudio
import com.glidelive.princess.WavIo
import com.glidelive.stems.AudioDecoder
import com.glidelive.stems.StemLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class PrincessUi(
    val projects: List<Project> = emptyList(),
    val current: Project? = null,
    /** Personaje que está grabando (null = no se graba) y cuánto lleva. */
    val recordingFor: String? = null,
    val recSeconds: Float = 0f,
    val recLevel: Float = 0f,
    /** Texto de lo que se está haciendo (mezclando, exportando…) y su avance. */
    val busy: Pair<String, Float>? = null,
    val error: String? = null,
    /** Pista que suena ahora (-1 = ninguna) y si hay una mezcla lista en el reproductor. */
    val playingCue: Int = -1,
    val loaded: Boolean = false,
    /** Sonidos del pack y los propios. */
    val hasSfx: Boolean = false,
    val musicCount: Int = 0,
    val mine: List<com.glidelive.princess.MySound> = emptyList(),
    val download: Pair<String, Float>? = null,
    val storageMb: Long = 0,
)

/** Pista de audio de una obra dramática: personajes, voces grabadas, silencios, música de fondo y efectos. */
class PrincessViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(PrincessUi())
    val ui: StateFlow<PrincessUi> = _ui
    private val _clock = MutableStateFlow(StemClock())
    val clock: StateFlow<StemClock> = _clock

    /** Lo llama la pantalla antes de usar el reproductor, para soltar la canción abierta en Pistas. */
    var beforePlay: () -> Unit = {}

    private val clips = HashMap<String, Clip>()
    private var places: List<Placement> = emptyList()
    private var recorder: MicRecorder? = null
    private val prefs = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    private val projectEdits = Mutex()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val projectWrites = Dispatchers.IO.limitedParallelism(1)

    init {
        refreshSounds()
        viewModelScope.launch(Dispatchers.IO) {
            val list = PrincessLibrary.list(app)
            val last = prefs.getString("princess_last", null)
            _ui.update { it.copy(projects = list, current = list.firstOrNull { p -> p.id == last } ?: list.firstOrNull()) }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (_ui.value.loaded) {
                    val s = NativeEngine.stemStatus()
                    val c = StemClock(s[0], s[1], s[2] > 0.5)
                    if (c != _clock.value) _clock.value = c
                    val cue = if (c.playing) places.lastOrNull { c.pos >= it.start && c.pos < it.end && it.cue.mode != CueMode.BED }?.index ?: -1 else -1
                    if (cue != _ui.value.playingCue) _ui.update { it.copy(playingCue = cue) }
                }
                delay(if (_clock.value.playing) 100L else 300L)
            }
        }
    }

    // ---- sonidos del pack ----
    private var downloadJob: kotlinx.coroutines.Job? = null

    fun refreshSounds() {
        viewModelScope.launch(Dispatchers.IO) {
            val sfx = com.glidelive.princess.SoundStore.hasSfx(app)
            val music = com.glidelive.princess.SoundStore.musicFiles(app).size
            val mine = com.glidelive.princess.SoundStore.mine(app)
            val mb = com.glidelive.princess.SoundStore.bytesUsed(app) / 1_000_000
            _ui.update { it.copy(hasSfx = sfx, musicCount = music, mine = mine, storageMb = mb) }
        }
    }

    /** Baja los efectos (perSound clips de cada sonido). Queda guardado en la app hasta que se borre. */
    fun downloadSfx(perSound: Int) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _ui.update { it.copy(download = "Bajando sonidos…" to 0f, error = null) }
            val r = runCatching {
                com.glidelive.princess.SoundStore.downloadSfx(app, perSound, { p -> _ui.update { u -> u.copy(download = "Bajando sonidos…" to p) } }, { !isActive })
            }
            r.onFailure { e -> if (e !is java.util.concurrent.CancellationException) _ui.update { it.copy(error = "No se pudieron bajar los sonidos: ${e.message}") } }
            _ui.update { it.copy(download = null) }
            refreshSounds()
        }
    }

    fun downloadMusic() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _ui.update { it.copy(download = "Bajando música…" to 0f, error = null) }
            val r = runCatching {
                com.glidelive.princess.SoundStore.downloadMusic(app, { p -> _ui.update { u -> u.copy(download = "Bajando música…" to p) } }, { !isActive })
            }
            r.onFailure { e -> if (e !is java.util.concurrent.CancellationException) _ui.update { it.copy(error = "No se pudo bajar la música: ${e.message}") } }
            _ui.update { it.copy(download = null) }
            refreshSounds()
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    /** Guarda una captura (de cualquier pestaña) como sonido propio. */
    fun saveCaptured(wav: File, name: String) {
        viewModelScope.launch(Dispatchers.IO) { com.glidelive.princess.SoundStore.addMine(app, wav, name); refreshSounds() }
    }

    fun deleteMine(id: String) { viewModelScope.launch(Dispatchers.IO) { com.glidelive.princess.SoundStore.deleteMine(app, id); refreshSounds() } }

    /** Un clip cualquiera de [category], alternando para que no suene siempre el mismo. */
    fun clipFor(category: String): File? {
        val list = com.glidelive.princess.SoundStore.clips(app, category)
        if (list.isEmpty()) return null
        val n = (sfxCounter[category] ?: -1) + 1
        sfxCounter[category] = n
        return list[n % list.size]
    }
    private val sfxCounter = HashMap<String, Int>()

    /** Agrega a la obra un sonido del pack o propio: efectos sin el silencio sobrante, música de fondo entera. */
    fun addSoundFile(file: File, label: String, type: CueType) {
        val p = _ui.value.current ?: return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val id = PrincessLibrary.newId()
                    if (type == CueType.MUSIC) {
                        val f = File(PrincessLibrary.dir(app, p), "$id.${file.extension}")
                        file.copyTo(f, overwrite = true)
                        Pair(f.name, AudioDecoder.decode(f).seconds.toFloat())
                    } else {
                        val d = AudioDecoder.decode(file)
                        val pcm = WavIo.trimSilence(d.pcm, d.rate, threshold = 350, marginSec = 0.06f)
                        val f = File(PrincessLibrary.dir(app, p), "$id.wav")
                        WavIo.write(f, pcm, d.channels, d.rate)
                        Pair(f.name, pcm.size.toFloat() / d.channels / d.rate)
                    }
                }
            }
            r.onSuccess { (name, sec) ->
                val bed = type == CueType.MUSIC
                add(Cue(PrincessLibrary.newId(), type, null, label, name, sec, if (bed) 0f else 0.5f,
                    if (bed) CueMode.BED else CueMode.SEQ, if (bed) 0.5f else 1f, 0, if (bed) 1.5f else 0f, if (bed) 2f else 0f))
            }
            r.onFailure { e -> _ui.update { it.copy(error = "No se pudo agregar: ${e.message}") } }
        }
    }

    /** Escucha un archivo del pack sin agregarlo. */
    fun previewFile(file: File) = viewModelScope.launch {
        stop()
        val d = withContext(Dispatchers.IO) { runCatching { AudioDecoder.decode(file) }.getOrNull() } ?: return@launch
        beforePlay()
        NativeEngine.replicaMask(0)
        NativeEngine.stemClear()
        NativeEngine.stemLoad(0, d.pcm, d.channels, d.rate)
        NativeEngine.stemTrack(0, 1f, 0f, false, false)
        NativeEngine.stemMaster(1f, 1f)
        NativeEngine.stemLoop(-1.0, -1.0)
        NativeEngine.stemSeek(0.0)
        NativeEngine.stemPlay(true)
        places = emptyList()
        _ui.update { it.copy(loaded = true) }
    }

    // ---- obras ----
    fun newProject(title: String) {
        viewModelScope.launch(projectWrites) {
            val p = PrincessLibrary.create(app, title)
            _ui.update { it.copy(projects = listOf(p) + it.projects) }
            open(p)
        }
    }

    fun open(p: Project) {
        stop(); clips.clear(); places = emptyList()
        prefs.edit().putString("princess_last", p.id).apply()
        _ui.update { it.copy(current = p, loaded = false, playingCue = -1, error = null) }
    }

    fun deleteProject(p: Project) {
        if (_ui.value.current?.id == p.id) { stop(); _ui.update { it.copy(current = null, loaded = false) } }
        viewModelScope.launch(projectWrites) {
            PrincessLibrary.delete(app, p)
            val rest = _ui.value.projects.filter { it.id != p.id }
            _ui.update { it.copy(projects = rest, current = it.current ?: rest.firstOrNull()) }
        }
    }

    fun edit(f: (Project) -> Project) {
        viewModelScope.launch {
            projectEdits.withLock {
                val cur = _ui.value.current ?: return@withLock
                val p = f(cur)
                updateProject(p)
                viewModelScope.launch(projectWrites) {
                    runCatching { PrincessLibrary.save(app, p) }.onFailure {
                        _ui.update { u -> u.copy(error = "No se pudo guardar la obra.") }
                    }
                }
            }
        }
    }

    private fun updateProject(p: Project) {
        _ui.update { u -> u.copy(current = if (u.current?.id == p.id) p else u.current,
            projects = u.projects.map { if (it.id == p.id) p else it }) }
    }

    /** Agrega el audio de la selección como una pista de voz editable de la obra. */
    suspend fun addNarration(projectId: String, characterId: String, audio: NarratedAudio) = withContext(NonCancellable) {
        projectEdits.withLock {
            val p = _ui.value.current?.takeIf { it.id == projectId } ?: error("Abre la obra en la que preparaste la narración.")
            require(p.characters.any { it.id == characterId }) { "Elige un personaje de la obra." }
            val name = "narracion_${UUID.randomUUID()}.wav"
            val cue = Cue(PrincessLibrary.newId(), CueType.VOICE, characterId, audio.text, name, audio.seconds, 0.6f)
            val updated = p.copy(cues = p.cues + cue)
            withContext(projectWrites) {
                val target = File(PrincessLibrary.dir(app, p), name)
                try {
                    audio.file.copyTo(target)
                    PrincessLibrary.save(app, updated)
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
            }
            updateProject(updated)
        }
    }

    // ---- personajes ----
    fun addCharacter(name: String) = edit { p ->
        p.copy(characters = p.characters + Character(PrincessLibrary.newId(), name.ifBlank { "Personaje" }, PrincessLibrary.PALETTE[p.characters.size % PrincessLibrary.PALETTE.size]))
    }

    fun editCharacter(id: String, f: (Character) -> Character) = edit { p -> p.copy(characters = p.characters.map { if (it.id == id) f(it) else it }) }

    fun removeCharacter(id: String) = edit { p ->
        if (p.characters.size <= 1 || p.cues.any { it.characterId == id }) p
        else p.copy(characters = p.characters.filter { it.id != id })
    }

    // ---- pistas ----
    private fun add(cue: Cue) = edit { p -> p.copy(cues = p.cues + cue) }

    fun updateCue(index: Int, f: (Cue) -> Cue) = edit { p -> if (index in p.cues.indices) p.copy(cues = p.cues.toMutableList().also { it[index] = f(it[index]) }) else p }

    fun moveCue(index: Int, delta: Int) = edit { p ->
        val to = index + delta
        if (index !in p.cues.indices || to !in p.cues.indices) p
        else p.copy(cues = p.cues.toMutableList().also { val c = it.removeAt(index); it.add(to, c) })
    }

    fun deleteCue(index: Int) = edit { p -> if (index in p.cues.indices) p.copy(cues = p.cues.filterIndexed { i, _ -> i != index }) else p }

    fun addSilence(seconds: Float) = add(Cue(PrincessLibrary.newId(), CueType.SILENCE, null, "Silencio", null, seconds, 0f))

    /** Trae un archivo de audio: [type] voz (del personaje [characterId]), música de fondo o efecto. */
    fun importAudio(uri: Uri, type: CueType, characterId: String?) {
        val p = _ui.value.current ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Importando…" to 0f, error = null) }
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val name = StemLibrary.displayName(app, uri)
                    val ext = name.substringAfterLast('.', "audio").lowercase().take(5)
                    val id = PrincessLibrary.newId()
                    val f = File(PrincessLibrary.dir(app, p), "$id.$ext")
                    app.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    val d = AudioDecoder.decode(f)
                    Triple(f.name, name.substringBeforeLast('.'), d.seconds.toFloat())
                }
            }
            r.onSuccess { (file, title, sec) ->
                add(Cue(PrincessLibrary.newId(), type, if (type == CueType.VOICE) characterId else null, title, file, sec,
                    if (type == CueType.MUSIC) 0f else 0.5f,
                    if (type == CueType.MUSIC) CueMode.BED else CueMode.SEQ, if (type == CueType.MUSIC) 0.5f else 1f,
                    0, if (type == CueType.MUSIC) 1.5f else 0f, if (type == CueType.MUSIC) 2f else 0f))
            }
            r.onFailure { e -> _ui.update { it.copy(error = "No se pudo importar: ${e.message}") } }
            _ui.update { it.copy(busy = null) }
        }
    }

    // ---- grabar voces ----
    fun startRecording(characterId: String): Boolean {
        if (recorder != null) return false
        stop()
        val rec = MicRecorder { lvl -> _ui.update { it.copy(recLevel = lvl, recSeconds = recorder?.seconds ?: it.recSeconds) } }
        recorder = rec
        if (!rec.start()) { recorder = null; _ui.update { it.copy(error = "No se pudo abrir el micrófono") }; return false }
        _ui.update { it.copy(recordingFor = characterId, recSeconds = 0f, recLevel = 0f, error = null) }
        return true
    }

    fun cancelRecording() {
        recorder?.stop(); recorder = null
        _ui.update { it.copy(recordingFor = null, recLevel = 0f) }
    }

    /** Termina la grabación y agrega la voz al final de la obra (sin silencio sobrante). */
    fun finishRecording() {
        val rec = recorder ?: return
        val who = _ui.value.recordingFor
        val p = _ui.value.current
        val pcm = rec.stop(); recorder = null
        _ui.update { it.copy(recordingFor = null, recLevel = 0f) }
        if (p == null || who == null || pcm.size < MicRecorder.RATE / 5) return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val trimmed = WavIo.trimSilence(pcm, MicRecorder.RATE)
                val f = File(PrincessLibrary.dir(app, p), "voz_${PrincessLibrary.newId()}.wav")
                WavIo.write(f, trimmed, 1, MicRecorder.RATE)
                f.name to trimmed.size.toFloat() / MicRecorder.RATE
            }
            val cueCount = _ui.value.current?.cues?.count { it.type == CueType.VOICE } ?: 0
            add(Cue(PrincessLibrary.newId(), CueType.VOICE, who, "Línea ${cueCount + 1}", saved.first, saved.second, 0.6f))
        }
    }

    // ---- escuchar y exportar ----
    private fun clipOf(p: Project, c: Cue): Clip? {
        val name = c.file ?: return null
        synchronized(clips) { clips[name]?.let { return it } }
        val d = runCatching { AudioDecoder.decode(File(PrincessLibrary.dir(app, p), name)) }.getOrNull() ?: return null
        val clip = Clip(d.pcm, d.channels, d.rate)
        synchronized(clips) { clips[name] = clip }
        return clip
    }

    private suspend fun renderProject(p: Project, label: String): ShortArray = withContext(Dispatchers.Default) {
        DramaRenderer.render(p, { clipOf(p, it) }) { pr -> _ui.update { it.copy(busy = label to pr) } }
    }

    private fun load(pcm: ShortArray, from: Double) {
        beforePlay()
        NativeEngine.replicaMask(0)
        NativeEngine.stemClear()
        NativeEngine.stemLoad(0, pcm, 2, DramaRenderer.RATE)
        NativeEngine.stemTrack(0, 1f, 0f, false, false)
        NativeEngine.stemMaster(1f, 1f)
        NativeEngine.stemLoop(-1.0, -1.0)
        NativeEngine.stemSeek(from)
        NativeEngine.stemPlay(true)
        _ui.update { it.copy(loaded = true) }
    }

    /** Escucha la obra completa (o desde la pista [fromCue]). */
    fun playAll(fromCue: Int = 0) {
        val p = _ui.value.current ?: return
        if (p.cues.isEmpty()) return
        viewModelScope.launch {
            stop()
            val pcm = renderProject(p, "Mezclando la obra…")
            places = DramaRenderer.layout(p)
            val from = places.firstOrNull { it.index == fromCue }?.start ?: 0.0
            load(pcm, from)
            _ui.update { it.copy(busy = null) }
        }
    }

    /** Escucha solo una pista, con el tono y volumen de su personaje. */
    fun preview(index: Int) {
        val p = _ui.value.current ?: return
        val c = p.cues.getOrNull(index) ?: return
        viewModelScope.launch {
            stop()
            // La pista sola, desde el segundo 0 y de una vez (la música de fondo suena entera una sola vez).
            val solo = p.copy(cues = listOf(c.copy(mode = CueMode.SEQ, gap = 0f, span = 0)), duckMusic = false)
            val pcm = renderProject(solo, "Preparando…")
            places = DramaRenderer.layout(solo).map { it.copy(index = index) }
            load(pcm, 0.0)
            _ui.update { it.copy(busy = null) }
        }
    }

    fun stop() {
        if (_ui.value.loaded) NativeEngine.stemPlay(false)
        _clock.update { it.copy(playing = false) }
    }

    fun seek(sec: Double) { NativeEngine.stemSeek(sec); _clock.update { it.copy(pos = sec) } }

    /** Mezcla la obra a un WAV y abre el menú de compartir. */
    fun export() {
        val p = _ui.value.current ?: return
        if (p.cues.isEmpty()) return
        viewModelScope.launch {
            val pcm = renderProject(p, "Exportando…")
            val f = withContext(Dispatchers.IO) {
                File(PrincessLibrary.dir(app, p), p.title.replace(Regex("[^A-Za-z0-9áéíóúñÁÉÍÓÚÑ _-]"), "").ifBlank { "obra" } + ".wav")
                    .also { WavIo.write(it, pcm, 2, DramaRenderer.RATE) }
            }
            _ui.update { it.copy(busy = null) }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.startActivity(Intent.createChooser(send, "Exportar la obra").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    override fun onCleared() {
        recorder?.stop()
        if (_ui.value.loaded) NativeEngine.stemPlay(false)
        super.onCleared()
    }
}
