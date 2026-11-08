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
import com.glidelive.princess.WavIo
import com.glidelive.stems.AudioDecoder
import com.glidelive.stems.StemLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    init {
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

    // ---- obras ----
    fun newProject(title: String) {
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch(Dispatchers.IO) {
            PrincessLibrary.delete(app, p)
            val rest = _ui.value.projects.filter { it.id != p.id }
            _ui.update { it.copy(projects = rest, current = it.current ?: rest.firstOrNull()) }
        }
    }

    fun edit(f: (Project) -> Project) {
        val cur = _ui.value.current ?: return
        val p = f(cur)
        _ui.update { u -> u.copy(current = p, projects = u.projects.map { if (it.id == p.id) p else it }) }
        viewModelScope.launch(Dispatchers.IO) { PrincessLibrary.save(app, p) }
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
