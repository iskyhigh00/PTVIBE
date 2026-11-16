package com.glidelive.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glidelive.audio.NativeEngine
import com.glidelive.princess.Character
import com.glidelive.princess.AacWriter
import com.glidelive.princess.Clip
import com.glidelive.princess.Cue
import com.glidelive.princess.CueMode
import com.glidelive.princess.CueType
import com.glidelive.princess.DramaRenderer
import com.glidelive.princess.MicRecorder
import com.glidelive.princess.Placement
import com.glidelive.princess.History
import com.glidelive.princess.PitchShifter
import com.glidelive.princess.PrincessLibrary
import com.glidelive.princess.TimelineOps
import com.glidelive.princess.Project
import com.glidelive.princess.NarratedAudio
import com.glidelive.princess.WavIo
import com.glidelive.stems.AudioDecoder
import com.glidelive.stems.StemLibrary
import kotlinx.coroutines.CoroutineExceptionHandler
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
    /** Dónde está la línea roja (segundos) y si hay algo que deshacer o rehacer. */
    val cursor: Double = 0.0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Los efectos bajados quedaron a medias. */
    val sfxPartial: Boolean = false,
    /** Aviso amable de una línea (no es un error): "Listo, elige WhatsApp", "Pon la línea roja sobre el audio"… */
    val notice: String? = null,
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
    @Volatile private var leaveGen = 0
    private var addingSound = false
    private val history = History<Project>(40)
    private var previewing = false

    /** Cualquier error inesperado se convierte en un aviso en español; nunca cierra la app. */
    private val safety = CoroutineExceptionHandler { _, t -> report(t) }

    private fun friendly(t: Throwable): String = Friendly.message(t)

    private fun report(t: Throwable) {
        if (t is java.util.concurrent.CancellationException) return
        _ui.update { it.copy(busy = null, download = null, error = friendly(t)) }
    }

    /** Lanza trabajo en segundo plano sin que un fallo pueda cerrar la app. */
    private fun launchSafe(block: suspend () -> Unit) = viewModelScope.launch(safety) {
        try { block() } catch (c: java.util.concurrent.CancellationException) { throw c } catch (t: Throwable) { report(t) }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val projectWrites = Dispatchers.IO.limitedParallelism(1)

    init {
        refreshSounds()
        viewModelScope.launch(Dispatchers.IO) {
            val list = PrincessLibrary.list(app)
            val last = prefs.getString("princess_last", null)
            _ui.update { u ->
                // Si ya se creó o abrió algo mientras cargaba la lista, eso manda.
                val merged = u.projects + list.filter { p -> u.projects.none { it.id == p.id } }
                u.copy(projects = merged, current = u.current ?: list.firstOrNull { p -> p.id == last } ?: list.firstOrNull())
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (_ui.value.loaded && !PlayerOwner.story) {   // Pistas se quedó con el reproductor
                    previewing = false
                    _clock.value = StemClock()
                    _ui.update { it.copy(loaded = false, playingCue = -1) }
                }
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
            val partial = com.glidelive.princess.SoundStore.sfxPartial(app)
            _ui.update { it.copy(hasSfx = sfx, musicCount = music, mine = mine, storageMb = mb, sfxPartial = partial) }
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
            r.onFailure { e -> if (e !is java.util.concurrent.CancellationException) _ui.update { it.copy(error = friendly(e)) } }
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
            r.onFailure { e -> if (e !is java.util.concurrent.CancellationException) _ui.update { it.copy(error = friendly(e)) } }
            _ui.update { it.copy(download = null) }
            refreshSounds()
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    /** Baja de una vez los sonidos y la música (una sola vez; queda guardado en el teléfono). */
    fun downloadAll() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _ui.update { it.copy(download = "Descargando sonidos…" to 0f, error = null) }
            val r = runCatching {
                com.glidelive.princess.SoundStore.downloadSfx(app, 10, { p -> _ui.update { u -> u.copy(download = "Descargando sonidos…" to p * 0.8f) } }, { !isActive })
                com.glidelive.princess.SoundStore.downloadMusic(app, { p -> _ui.update { u -> u.copy(download = "Descargando música…" to 0.8f + p * 0.2f) } }, { !isActive })
            }
            r.onFailure { e -> if (e !is java.util.concurrent.CancellationException) _ui.update { it.copy(error = friendly(e)) } }
            _ui.update { it.copy(download = null, notice = if (r.isSuccess) "Listo. Ya tienes sonidos y música, guardados en tu teléfono." else it.notice) }
            refreshSounds()
        }
    }

    fun renameProject(title: String) = edit { it.copy(title = title.trim().ifBlank { it.title }) }

    /** Guarda una captura (de cualquier pestaña) como sonido propio. */
    fun saveCaptured(wav: File, name: String) {
        viewModelScope.launch(Dispatchers.IO + safety) {
            val r = com.glidelive.princess.SoundStore.addMine(app, wav, name)
            if (r == null) _ui.update { it.copy(error = "No pude guardar ese sonido. Revisa que quede espacio en el teléfono.") }
            refreshSounds()
        }
    }

    fun deleteMine(id: String) { viewModelScope.launch(Dispatchers.IO + safety) { com.glidelive.princess.SoundStore.deleteMine(app, id); refreshSounds() } }

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
    /** Segundo de la obra donde cae un sonido puesto "encima": el cursor (lo que suena o quedó en pausa) o el inicio de la última pista. */
    fun overlayTime(): Double {
        val c = _clock.value
        if (_ui.value.loaded && !previewing && c.dur > 0 && c.playing) return Math.round(c.pos * 10) / 10.0
        return Math.round(_ui.value.cursor * 10) / 10.0
    }

    /** Pone la línea roja en el segundo [t] (también mueve la reproducción si está sonando la mezcla). */
    fun setCursor(t: Double) {
        val total = _ui.value.current?.let { DramaRenderer.totalSeconds(it) } ?: 0.0
        val v = t.coerceIn(0.0, maxOf(total, 0.0) + 30.0)
        _ui.update { it.copy(cursor = v) }
        if (_ui.value.loaded && !previewing && _clock.value.dur > 0) seek(v.coerceAtMost(_clock.value.dur))
    }

    fun addSoundFile(file: File, label: String, type: CueType, atSeconds: Double? = null) {
        val p = _ui.value.current ?: return
        if (addingSound) return
        addingSound = true
        launchSafe {
            try {
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
                val over = atSeconds != null && !bed
                // "Encima": posición absoluta en el timeline; el resto de las pistas no se mueve.
                addTo(p.id, Cue(PrincessLibrary.newId(), type, null, label, name, sec, if (bed) 0f else 0.5f,
                    if (bed) CueMode.BED else CueMode.SEQ, if (bed) 0.5f else 1f, 0, if (bed) 1.5f else 0f, if (bed) 2f else 0f,
                    startSeconds = if (bed) (atSeconds ?: 0.0).toFloat() else if (over) atSeconds!!.toFloat() else null))
                val where = atSeconds ?: 0.0
                _ui.update { it.copy(notice = if (bed) "Puse «$label» de fondo, suave, desde el segundo ${"%.0f".format(where)}. Se oye bajito mientras hablas." else "Agregué «$label» donde está la línea roja.") }
            }
            r.onFailure { e -> _ui.update { it.copy(error = if (noSpace(e)) friendly(e) else "No pude agregar ese sonido. Prueba con otro.") } }
            } finally { addingSound = false }
        }
    }

    /** Escucha un archivo del pack sin agregarlo. */
    fun previewFile(file: File) = launchSafe {
        stop()
        val gen = leaveGen
        val d = withContext(Dispatchers.IO) { runCatching { AudioDecoder.decode(file) }.getOrNull() } ?: return@launchSafe
        if (gen != leaveGen) return@launchSafe
        previewing = true
        beforePlay()
        PlayerOwner.story = true
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
    private var creating = false

    fun newProject(title: String) {
        if (creating) return
        creating = true
        _ui.update { it.copy(current = null) }
        viewModelScope.launch(projectWrites + safety) {
            try {
                val p = PrincessLibrary.create(app, title)
                _ui.update { it.copy(projects = listOf(p) + it.projects) }
                withContext(Dispatchers.Main) { open(p) }
            } finally { creating = false }
        }
    }

    fun open(p: Project) {
        stop(); synchronized(clips) { clips.clear() }; places = emptyList(); history.clear(); previewing = false
        viewModelScope.launch(projectWrites + safety) { PrincessLibrary.cleanup(app, p) }
        prefs.edit().putString("princess_last", p.id).apply()
        _ui.update { it.copy(current = p, loaded = false, playingCue = -1, error = null, cursor = 0.0, canUndo = false, canRedo = false) }
    }

    fun deleteProject(p: Project) {
        if (_ui.value.current?.id == p.id) { stop(); _ui.update { it.copy(current = null, loaded = false) } }
        viewModelScope.launch(projectWrites) {
            PrincessLibrary.delete(app, p)
            val rest = _ui.value.projects.filter { it.id != p.id }
            _ui.update { it.copy(projects = rest) }
            if (_ui.value.current == null) { history.clear(); _ui.update { it.copy(canUndo = false, canRedo = false) } }
        }
    }

    fun edit(f: (Project) -> Project) {
        viewModelScope.launch(safety) {
            projectEdits.withLock {
                val cur = _ui.value.current ?: return@withLock
                val p = try { f(cur) } catch (e: Exception) { report(e); return@withLock }
                if (p != cur) { history.record(cur); _ui.update { it.copy(canUndo = history.canUndo, canRedo = history.canRedo) } }
                updateProject(p)
                viewModelScope.launch(projectWrites) {
                    runCatching { PrincessLibrary.save(app, p) }.onFailure {
                        _ui.update { u -> u.copy(error = "No se pudo guardar la obra.") }
                    }
                }
            }
        }
    }

    /** Vuelve atrás el último cambio. */
    fun undo() = stepHistory { cur -> history.undo(cur) }
    fun redo() = stepHistory { cur -> history.redo(cur) }

    private fun stepHistory(next: (Project) -> Project?) {
        launchSafe {
            projectEdits.withLock {
                val cur = _ui.value.current ?: return@withLock
                val target = next(cur) ?: return@withLock
                updateProject(target)
                _ui.update { it.copy(canUndo = history.canUndo, canRedo = history.canRedo) }
                withContext(projectWrites) { PrincessLibrary.save(app, target) }
            }
        }
    }

    /** Guarda ya mismo lo que haya (al salir de la pantalla o de la app). */
    fun flush() {
        val p = _ui.value.current ?: return
        runCatching { PrincessLibrary.save(app, p) }
    }

    /** Al salir de la pestaña o pasar la app a segundo plano: se guarda la grabación en curso y se para el sonido. */
    fun onLeave() {
        leaveGen++
        if (recorder != null) finishRecording()
        stop()
        flush()
    }

    private fun updateProject(p: Project) {
        if (_ui.value.current?.id == p.id && _ui.value.current != p) {
            stop()
            places = emptyList()
            _ui.update { it.copy(loaded = false, playingCue = -1) }
        }
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
            history.record(p); _ui.update { it.copy(canUndo = history.canUndo, canRedo = history.canRedo) }
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
    fun addCharacter(name: String): String {
        val id = PrincessLibrary.newId()
        edit { p ->
            p.copy(characters = p.characters + Character(id, name.ifBlank { "Personaje" }, PrincessLibrary.PALETTE[p.characters.size % PrincessLibrary.PALETTE.size]))
        }
        return id
    }

    fun editCharacter(id: String, f: (Character) -> Character) = edit { p -> p.copy(characters = p.characters.map { if (it.id == id) f(it) else it }) }

    fun removeCharacter(id: String) = edit { p ->
        if (p.characters.size <= 1 || p.cues.any { it.characterId == id }) p
        else p.copy(characters = p.characters.filter { it.id != id })
    }

    // ---- pistas ----
    private fun add(cue: Cue) = edit { p -> p.copy(cues = p.cues + cue) }

    /** Agrega al cuento [projectId]; si mientras tanto se abrió otro, no se mezcla (el audio no queda en el cuento equivocado). */
    private fun addTo(projectId: String, cue: Cue) = edit { p -> if (p.id != projectId) p else p.copy(cues = p.cues + cue) }

    private fun noSpace(t: Throwable) = t is java.io.IOException && (t.message ?: "").contains("ENOSPC", true)

    fun updateCue(index: Int, f: (Cue) -> Cue) = edit { p -> if (index in p.cues.indices) p.copy(cues = p.cues.toMutableList().also { it[index] = f(it[index]) }) else p }

    fun editTimelineCue(id: String, change: (Cue) -> Cue) = edit { project ->
        val anchored = DramaRenderer.anchorTimeline(project)
        anchored.copy(cues = anchored.cues.map { if (it.id == id) change(it) else it })
    }

    fun muteTimelineLane(ids: Set<String>, muted: Boolean) = edit { project ->
        project.copy(cues = project.cues.map { if (it.id in ids) it.copy(muted = muted) else it })
    }

    fun playFromSeconds(seconds: Double) {
        val p = _ui.value.current ?: return
        if (p.cues.isEmpty() || _ui.value.busy != null) return
        if (DramaRenderer.frameCount(p) > DramaRenderer.RATE * 15 * 60) {
            _ui.update { it.copy(notice = "Tu cuento dura más de 15 minutos: para oírlo entero toca «📤 Enviar» y escúchalo desde ahí.") }; return
        }
        _ui.update { it.copy(busy = "Preparando tu cuento…" to 0f, error = null) }   // antes de lanzar: un segundo toque no repite el trabajo
        val gen = leaveGen
        launchSafe {
            stop()
            val pcm = renderProject(p, "Preparando tu cuento…")
            if (gen != leaveGen) { _ui.update { it.copy(busy = null) }; return@launchSafe }
            if (_ui.value.current != p) { _ui.update { it.copy(busy = null) }; playFromSeconds(seconds); return@launchSafe }   // se editó mientras se preparaba: se vuelve a preparar
            places = DramaRenderer.layout(p)
            previewing = false
            load(pcm, seconds.coerceIn(0.0, DramaRenderer.totalSeconds(p)))
            _ui.update { it.copy(busy = null) }
        }
    }

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
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = "Importando…" to 0f, error = null) }
        launchSafe {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val name = StemLibrary.displayName(app, uri)
                    val ext = name.substringAfterLast('.', "audio").lowercase().take(5)
                    val id = PrincessLibrary.newId()
                    val f = File(PrincessLibrary.dir(app, p), "$id.$ext")
                    try {
                        app.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        val d = AudioDecoder.decode(f)
                        require(d.seconds <= 30 * 60) { "audio demasiado largo" }
                        Triple(f.name, name.substringBeforeLast('.'), d.seconds.toFloat())
                    } catch (e: Throwable) { f.delete(); throw e }
                }
            }
            r.onSuccess { (file, title, sec) ->
                addTo(p.id, Cue(PrincessLibrary.newId(), type, if (type == CueType.VOICE) characterId else null, title, file, sec,
                    if (type == CueType.MUSIC) 0f else 0.5f,
                    if (type == CueType.MUSIC) CueMode.BED else CueMode.SEQ, if (type == CueType.MUSIC) 0.5f else 1f,
                    0, if (type == CueType.MUSIC) 1.5f else 0f, if (type == CueType.MUSIC) 2f else 0f))
            }
            r.onFailure { e -> _ui.update { it.copy(error = if (noSpace(e)) friendly(e) else "No pude usar ese archivo. Prueba con otro audio (mp3, m4a, wav) de menos de 30 minutos.") } }
            _ui.update { it.copy(busy = null) }
        }
    }

    // ---- grabar voces ----
    private var recordAt: Double? = null

    fun startRecording(characterId: String, atSeconds: Double? = null): Boolean {
        if (recorder != null) return false
        recordAt = atSeconds
        stop()
        val rec = MicRecorder { lvl -> _ui.update { it.copy(recLevel = lvl, recSeconds = recorder?.seconds ?: it.recSeconds) } }
        recorder = rec
        if (!rec.start()) {
            recorder = null
            _ui.update { it.copy(error = "No pude abrir el micrófono. Puede que otra app lo esté usando o que falte el permiso.") }
            return false
        }
        _ui.update { it.copy(recordingFor = characterId, recSeconds = 0f, recLevel = 0f, error = null) }
        // Si el tope de 10 minutos o un fallo la detienen, se guarda sola (sin esperar a que se toque Terminar).
        viewModelScope.launch(safety) {
            while (recorder === rec && rec.isRunning) delay(300)
            if (recorder === rec) finishRecording()
        }
        return true
    }

    fun cancelRecording() {
        recorder?.stop(); recorder = null
        _ui.update { it.copy(recordingFor = null, recLevel = 0f) }
    }

    /** Termina la grabación y agrega la voz en la línea roja (o después de lo último), sin el silencio sobrante. */
    fun finishRecording(atSeconds: Double? = null) {
        val rec = recorder ?: return
        val at = atSeconds ?: recordAt
        val who = _ui.value.recordingFor
        val p = _ui.value.current
        val pcm = rec.stop(); recorder = null
        val peak = rec.peak; val failed = rec.failed; val full = rec.full
        _ui.update { it.copy(recordingFor = null, recLevel = 0f) }
        if (p == null || who == null) return
        if (failed && pcm.size < MicRecorder.RATE) {
            _ui.update { it.copy(error = "Se cortó la grabación: otra app pudo haber tomado el micrófono. Vuelve a intentar.") }; return
        }
        if (pcm.size < MicRecorder.RATE / 5) {
            _ui.update { it.copy(notice = "La grabación fue muy corta. Toca el botón rojo y habla un poco más.") }; return
        }
        if (peak < 150) {
            _ui.update { it.copy(error = "No se oyó nada. Revisa que el micrófono no esté tapado ni silenciado y habla más cerca.") }; return
        }
        launchSafe {
            val saved = withContext(Dispatchers.IO) {
                val trimmed = WavIo.trimSilence(pcm, MicRecorder.RATE)
                val f = File(PrincessLibrary.dir(app, p), "voz_${PrincessLibrary.newId()}.wav")
                WavIo.write(f, trimmed, 1, MicRecorder.RATE)
                f.name to trimmed.size.toFloat() / MicRecorder.RATE
            }
            val cueCount = _ui.value.current?.cues?.count { it.type == CueType.VOICE } ?: 0
            addTo(p.id, Cue(PrincessLibrary.newId(), CueType.VOICE, who, "Mi voz ${cueCount + 1}", saved.first, saved.second, 0.6f,
                startSeconds = at?.toFloat()))
            if (full) _ui.update { it.copy(notice = "Llegaste al máximo de 10 minutos seguidos. Lo grabado quedó guardado.") }
        }
    }

    // ---- escuchar y exportar ----
    private fun clipOf(p: Project, c: Cue): Clip? {
        val name = c.file ?: return null
        synchronized(clips) { clips[name]?.let { return it } }
        val d = runCatching { AudioDecoder.decode(File(PrincessLibrary.dir(app, p), name)) }.getOrNull() ?: return null
        val clip = Clip(d.pcm, d.channels, d.rate)
        synchronized(clips) {
            // Caché acotada (~64 MB de audio): al pasarse se suelta lo más viejo.
            var bytes = clips.values.sumOf { it.pcm.size * 2L }
            val it = clips.entries.iterator()
            while (bytes > 64_000_000L && it.hasNext()) { val e = it.next(); bytes -= e.value.pcm.size * 2L; it.remove() }
            clips[name] = clip
        }
        return clip
    }

    private suspend fun renderProject(p: Project, label: String): ShortArray = withContext(Dispatchers.Default) {
        _ui.update { it.copy(busy = label to 0f) }
        DramaRenderer.render(p, { clipOf(p, it) }) { pr -> _ui.update { it.copy(busy = label to pr) } }
    }

    private fun load(pcm: ShortArray, from: Double) {
        beforePlay()
        PlayerOwner.story = true
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
    fun playAll(fromCue: Int = -1) {
        val p = _ui.value.current ?: return
        val from = DramaRenderer.layout(p).firstOrNull { it.index == fromCue }?.start ?: 0.0
        playFromSeconds(from)
    }

    /** Escucha solo una pista, con el tono y volumen de su personaje. */
    fun preview(index: Int) {
        val p = _ui.value.current ?: return
        val c = p.cues.getOrNull(index) ?: return
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = "Preparando…" to 0f, error = null) }
        val gen = leaveGen
        launchSafe {
            stop()
            // La pista sola, desde el segundo 0 y de una vez (la música de fondo suena entera una sola vez).
            val solo = p.copy(cues = listOf(c.copy(mode = CueMode.SEQ, gap = 0f, span = 0, startSeconds = null, loop = c.loop || c.mode == CueMode.BED, muted = false)), duckMusic = false)
            val pcm = renderProject(solo, "Preparando…")
            if (gen != leaveGen) { _ui.update { it.copy(busy = null) }; return@launchSafe }
            places = DramaRenderer.layout(solo).map { it.copy(index = index) }
            previewing = true
            load(pcm, 0.0)
            _ui.update { it.copy(busy = null) }
        }
    }

    fun previewCue(id: String) { val i = _ui.value.current?.cues?.indexOfFirst { it.id == id } ?: -1; if (i >= 0) preview(i) }

    // ---- editar sin tocar los archivos: cortar, quitar, mover, tono ----
    /** Corta el audio [id] donde está la línea roja. Si no se puede, explica por qué. */
    fun cutAtCursor(id: String) {
        val p = _ui.value.current ?: return
        val at = _ui.value.cursor
        val problem = TimelineOps.cutProblem(p, id, at)
        if (problem != null) { _ui.update { it.copy(notice = problem) }; return }
        edit { cur -> TimelineOps.split(cur, id, at, PrincessLibrary.newId()) ?: cur }
        _ui.update { it.copy(notice = "Listo, lo corté en dos. Ahora puedes mover o quitar cada pedazo.") }
    }

    fun removeCue(id: String) = edit { cur -> TimelineOps.delete(DramaRenderer.anchorTimeline(cur), id) }

    fun moveCueTo(id: String, start: Double, characterId: String? = null) = edit { cur ->
        val moved = TimelineOps.moveTo(cur, id, start.coerceIn(0.0, DramaRenderer.totalSeconds(cur) + 60.0))
        if (characterId != null && cur.characters.any { it.id == characterId })
            moved.copy(cues = moved.cues.map { if (it.id == id && it.type == CueType.VOICE) it.copy(characterId = characterId) else it })
        else moved
    }

    /** Cambia el tono de una grabación sin cambiar su velocidad. [steps]: −2 muy grave … 0 normal … +2 muy aguda. */
    fun setTone(id: String, steps: Int) {
        val p = _ui.value.current ?: return
        val cue = p.cues.firstOrNull { it.id == id } ?: return
        if (cue.file == null || cue.type == CueType.SILENCE) return
        if (_ui.value.busy != null) return
        if (cue.seconds > 120f) { _ui.update { it.copy(notice = "Esta grabación es muy larga para cambiarle el tono (máximo 2 minutos). Córtala en pedazos más chicos primero.") }; return }
        val target = steps.coerceIn(-2, 2)
        if (target == cue.toneSteps) return
        _ui.update { it.copy(busy = "Cambiando la voz…" to 0f, error = null) }
        launchSafe {
            val dir = PrincessLibrary.dir(app, p)
            val original = cue.sourceFile ?: cue.file
            val result = withContext(Dispatchers.Default) {
                if (target == 0) Triple(original, null as String?, AudioDecoder.decode(File(dir, original)).seconds.toFloat())
                else {
                    val d = AudioDecoder.decode(File(dir, original))
                    val mono = if (d.channels == 1) d.pcm else ShortArray(d.pcm.size / d.channels) { i -> ((d.pcm[i * d.channels].toInt() + d.pcm[i * d.channels + 1]) / 2).toShort() }
                    val shifted = PitchShifter.shift(mono, d.rate, PitchShifter.semitonesFor(target).toFloat())
                    val name = "tono_${PrincessLibrary.newId()}.wav"
                    WavIo.write(File(dir, name), shifted, 1, d.rate)
                    Triple(name, original, shifted.size.toFloat() / d.rate)
                }
            }
            edit { cur ->
                cur.copy(cues = cur.cues.map {
                    if (it.id == id) it.copy(file = result.first, sourceFile = result.second, toneSteps = target, seconds = result.third) else it
                })
            }
            synchronized(clips) { clips.remove(cue.file) }
            _ui.update { it.copy(busy = null) }
        }
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }
    fun setNotice(text: String) = _ui.update { it.copy(notice = text) }

    fun stop() {
        if (_ui.value.loaded) NativeEngine.stemPlay(false)
        _clock.update { it.copy(playing = false) }
    }

    fun seek(sec: Double) { NativeEngine.stemSeek(sec); _clock.update { it.copy(pos = sec) } }

    /** Mezcla el cuento por bloques y lo deja en un archivo M4A (si el teléfono no puede, en WAV). */
    suspend fun renderExport(p: Project, label: String = "Preparando tu cuento para enviar…"): File = withContext(Dispatchers.Default) {
        val safe = "Cuento " + p.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "sin nombre" }
        val dir = PrincessLibrary.dir(app, p)
        val progress = { pr: Float -> _ui.update { it.copy(busy = label to pr) } }
        val part = File(dir, "export.part")
        try {
            val m4a = runCatching {
                AacWriter(part).use { w -> DramaRenderer.renderTo(p, { clipOf(p, it) }, progress) { block, _, n -> w.write(block, n * 2) } }
                File(dir, "$safe.m4a").also { if (!part.renameTo(it)) part.copyTo(it, overwrite = true) }
            }
            m4a.getOrNull() ?: run {
                WavIo.Writer(part, 2, DramaRenderer.RATE).use { w -> DramaRenderer.renderTo(p, { clipOf(p, it) }, progress) { block, _, n -> w.write(block, n * 2) } }
                File(dir, "$safe.wav").also { if (!part.renameTo(it)) part.copyTo(it, overwrite = true) }
            }
        } finally { part.delete() }
    }

    /** Mezcla el cuento a un audio y abre el menú de compartir (WhatsApp, guardar, etc.). */
    fun export() {
        val p = _ui.value.current ?: return
        if (p.cues.isEmpty()) { _ui.update { it.copy(notice = "Todavía no hay nada que enviar. Graba tu voz o agrega un sonido primero.") }; return }
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = "Preparando tu cuento para enviar…" to 0f, error = null) }
        val gen = leaveGen
        launchSafe {
            val f = renderExport(p)
            _ui.update { it.copy(busy = null, notice = "Listo. Elige dónde enviarlo, por ejemplo WhatsApp.") }
            if (gen != leaveGen) return@launchSafe
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).setType(if (f.extension == "m4a") "audio/mp4" else "audio/wav").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.startActivity(Intent.createChooser(send, "Enviar mi cuento").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null, notice = null) }

    override fun onCleared() {
        flush()
        recorder?.stop()
        if (_ui.value.loaded) NativeEngine.stemPlay(false)
        super.onCleared()
    }
}
