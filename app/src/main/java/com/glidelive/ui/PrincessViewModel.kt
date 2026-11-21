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
import com.glidelive.princess.SoundCatalog
import com.glidelive.princess.SoundStore
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
    /** Lo más fuerte que se ha oído en la grabación actual (0..1). */
    val recPeak: Float = 0f,
    /** Texto de lo que se está haciendo (mezclando, exportando…) y su avance. */
    val busy: Pair<String, Float>? = null,
    val error: String? = null,
    /** Pista que suena ahora (-1 = ninguna) y si hay una mezcla lista en el reproductor. */
    val playingCue: Int = -1,
    val loaded: Boolean = false,
    /** Sonidos del pack y los propios. */
    val hasSfx: Boolean = false,
    val musicCount: Int = 0,
    val downloadedMusicCount: Int = 0,
    val soundGroups: List<SoundCatalog.Group> = emptyList(),
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
    /** Suena una prueba de un solo audio (no el cuento entero). */
    val previewing: Boolean = false,
    /** Último audio agregado (para llevarlo a la vista). */
    val lastAddedId: String? = null,
    /** Forma de onda (240 puntos, 0..1) de cada archivo de audio del cuento. */
    val peaks: Map<String, FloatArray> = emptyMap(),
    /** Se está creando un cuento nuevo. */
    val creating: Boolean = false,
)

/** Pista de audio de una obra dramática: personajes, voces grabadas, silencios, música de fondo y efectos. */
class PrincessViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(PrincessUi())
    val ui: StateFlow<PrincessUi> = _ui
    private val _clock = MutableStateFlow(StemClock())
    val clock: StateFlow<StemClock> = _clock

    /** Lo llama la pantalla antes de usar el reproductor, para soltar la canción abierta en Pistas. */
    var beforePlay: () -> Unit = {}

    private val clips = LinkedHashMap<String, Clip>(16, 0.75f, true)   // orden de uso: al pasarse de memoria se suelta lo menos usado
    @Volatile private var places: List<Placement> = emptyList()
    @Volatile private var recorder: MicRecorder? = null
    private val prefs = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    private val projectEdits = Mutex()
    @Volatile private var leaveGen = 0
    private var addingSound = false
    private val history = History<Project>(40)
    @Volatile private var previewingFlag = false
    private var previewing: Boolean
        get() = previewingFlag
        set(v) { previewingFlag = v; _ui.update { it.copy(previewing = v) } }

    /** Cualquier error inesperado se convierte en un aviso en español; nunca cierra la app. */
    private val safety = CoroutineExceptionHandler { _, t -> report(t) }

    private fun friendly(t: Throwable): String = Friendly.message(t)

    private fun report(t: Throwable) {
        if (t is java.util.concurrent.CancellationException) return
        _ui.update { it.copy(busy = null, download = null, error = friendly(t)) }
    }

    /** Mezcla en curso (escuchar o enviar), para poder cancelarla. */
    private var workJob: kotlinx.coroutines.Job? = null

    /** Cancela lo que se esté preparando (escuchar o enviar). */
    fun cancelWork() {
        workJob?.cancel(); workJob = null
        _ui.update { it.copy(busy = null, notice = "Cancelado.") }
    }

    /** Lanza trabajo en segundo plano sin que un fallo pueda cerrar la app. */
    private fun launchSafe(block: suspend () -> Unit) = viewModelScope.launch(safety) {
        try { block() } catch (c: java.util.concurrent.CancellationException) {
            // Solo el trabajo vigente limpia «ocupado»: uno viejo que termina tarde no debe borrar el del trabajo nuevo.
            val me = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            if (workJob == null || workJob === me) _ui.update { it.copy(busy = null) }
            throw c
        } catch (t: Throwable) { report(t) }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val projectWrites = Dispatchers.IO.limitedParallelism(1)

    init {
        refreshSounds()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PrincessLibrary.sweep(app) }
            val list = PrincessLibrary.list(app)
            val last = prefs.getString("princess_last", null)
            _ui.update { u ->
                // Si ya se creó o abrió algo mientras cargaba la lista, eso manda.
                val merged = u.projects + list.filter { p -> u.projects.none { it.id == p.id } }
                u.copy(projects = merged, current = u.current ?: list.firstOrNull { p -> p.id == last } ?: list.firstOrNull())
            }
            _ui.value.current?.let { c -> withContext(projectWrites) { runCatching { recoverTakes(c) } } }   // una toma que quedó a medias al cerrarse la app
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
                    if (serviceOn && s[2] <= 0.5 && System.currentTimeMillis() - playStartedAt > 800) {   // terminó de sonar (se espera un momento tras empezar)
                        PlaybackService.stop(app); serviceOn = false; abandonAudioFocus()
                        if (inBackground) NativeEngine.stop()   // con la app atrás no se deja el audio abierto gastando batería
                    }
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
            runCatching { SoundStore.prepareStarter(app) }.onFailure { report(it) }
            val sfx = com.glidelive.princess.SoundStore.hasSfx(app)
            val music = com.glidelive.princess.SoundStore.musicFiles(app).size
            val groups = SoundCatalog.groups.map { group ->
                group.copy(sounds = group.sounds.filter { SoundStore.clips(app, it.id).isNotEmpty() })
            }.filter { it.sounds.isNotEmpty() }
            val mine = com.glidelive.princess.SoundStore.mine(app)
            val mb = com.glidelive.princess.SoundStore.bytesUsed(app) / 1_000_000
            val partial = com.glidelive.princess.SoundStore.sfxPartial(app)
            _ui.update { it.copy(hasSfx = sfx, musicCount = music, downloadedMusicCount = SoundStore.downloadedMusicCount(app),
                soundGroups = groups, mine = mine, storageMb = mb, sfxPartial = partial) }
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

    /** Un clip cualquiera de [category], alternando para que no suene siempre el mismo. */
    fun clipFor(category: String): File? {
        val list = com.glidelive.princess.SoundStore.clips(app, category)
        if (list.isEmpty()) return null
        val n = (sfxCounter[category] ?: -1) + 1
        sfxCounter[category] = n
        return list[n % list.size].also { lastHeard[category] = it }
    }

    private val lastHeard = HashMap<String, File>()

    /** El clip que se acaba de escuchar de [category] (para agregar exactamente ese); si no hay, uno nuevo. */
    fun heardOrNext(category: String): File? = lastHeard[category]?.takeIf { it.exists() } ?: clipFor(category)
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

    fun addSoundFile(file: File, label: String, type: CueType, atSeconds: Double? = null, ambient: Boolean = false) {
        val p = _ui.value.current ?: return
        if (addingSound) return
        addingSound = true
        launchSafe {
            try {
            var sfxVolume = 1f
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val id = PrincessLibrary.newId()
                    if (type == CueType.MUSIC) {
                        val f = File(PrincessLibrary.dir(app, p), "$id.${file.extension}")
                        file.copyTo(f, overwrite = true)
                        Pair(f.name, AudioDecoder.decode(f).seconds.toFloat())
                    } else {
                        val d = AudioDecoder.decode(file)
                        // Un ambiente ya tiene sus propias transiciones y textura suave.
                        val pcm = if (ambient) d.pcm else WavIo.trimSilence(d.pcm, d.rate,
                            threshold = 350, marginSec = 0.06f, channels = d.channels)
                        val f = File(PrincessLibrary.dir(app, p), "$id.wav")
                        WavIo.write(f, pcm, d.channels, d.rate)
                        // Los efectos se igualan: su pico queda cerca de la mitad, para que un ladrido no tape la voz.
                        val peak = pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                        sfxVolume = if (peak > 200) (0.5f * 32768f / peak).coerceIn(0.25f, 1.2f) else 1f
                        Pair(f.name, pcm.size.toFloat() / d.channels / d.rate)
                    }
                }
            }
            r.onSuccess { (name, sec) ->
                val bed = type == CueType.MUSIC
                val over = atSeconds != null && !bed
                // "Encima": posición absoluta en el timeline; el resto de las pistas no se mueve.
                addTo(p.id, Cue(PrincessLibrary.newId(), type, null, label, name, sec, if (bed) 0f else 0.5f,
                    if (bed) CueMode.BED else CueMode.SEQ, if (bed) 0.5f else sfxVolume, 0,
                    fadeIn = if (bed) 1.5f else if (ambient) minOf(0.8f, sec / 3) else 0f,
                    fadeOut = if (bed) 2f else if (ambient) minOf(1.2f, sec / 3) else 0f,
                    startSeconds = if (bed) (atSeconds ?: 0.0).toFloat() else if (over) atSeconds!!.toFloat() else null,
                    duckUnderVoice = type == CueType.SFX && ambient))
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
    @Volatile private var creating = false

    fun newProject(title: String) {
        if (creating) return
        creating = true
        _ui.update { it.copy(current = null, creating = true) }
        viewModelScope.launch(projectWrites + safety) {
            try {
                val p = PrincessLibrary.create(app, title)
                _ui.update { it.copy(projects = listOf(p) + it.projects) }
                withContext(Dispatchers.Main) { open(p) }
            } finally { creating = false; _ui.update { it.copy(creating = false) } }
        }
    }

    fun open(p: Project) {
        openEpoch++
        lastMix = null
        if (recorder != null) finishRecording()   // una grabación en curso se guarda en el cuento donde empezó
        stop(); synchronized(clips) { clips.clear() }; places = emptyList(); history.clear(); previewing = false
        prefs.edit().putString("princess_last", p.id).apply()
        _ui.update { it.copy(current = p, loaded = false, playingCue = -1, error = null, cursor = 0.0, canUndo = false, canRedo = false) }
        viewModelScope.launch(projectWrites + safety) { runCatching { recoverTakes(p) }; PrincessLibrary.cleanup(app, _ui.value.current?.takeIf { it.id == p.id } ?: p) }
    }

    /** Hace una copia del cuento (con sus audios) para reutilizarlo. */
    fun duplicateProject(p: Project) {
        viewModelScope.launch(projectWrites + safety) {
            val copy = PrincessLibrary.duplicate(app, p)
            _ui.update { it.copy(projects = listOf(copy) + it.projects, notice = "Hice una copia: «${copy.title}».") }
        }
    }

    fun deleteProject(p: Project) {
        if (_ui.value.current?.id == p.id) { if (recorder != null) cancelRecording(); workJob?.cancel(); lastExport = null; stop(); _ui.update { it.copy(current = null, loaded = false) } }
        viewModelScope.launch(projectWrites + safety) {
            projectEdits.withLock { PrincessLibrary.delete(app, p) }
            _ui.update { u -> u.copy(projects = u.projects.filter { it.id != p.id }) }
            if (_ui.value.current == null) projectEdits.withLock { history.clear(); _ui.update { it.copy(canUndo = false, canRedo = false) } }
        }
    }

    @Volatile private var openEpoch = 0

    fun edit(f: (Project) -> Project) {
        val epoch = openEpoch
        viewModelScope.launch(safety) {
            projectEdits.withLock {
                if (epoch != openEpoch) return@withLock   // se abrió otro cuento mientras esperaba: este cambio ya no corresponde
                applyLocked(f)
            }
        }
    }

    /** Aplica un cambio al cuento abierto. Solo se llama con [projectEdits] tomado. */
    private fun applyLocked(f: (Project) -> Project) {
        val cur = _ui.value.current ?: return
        val p = try { f(cur) } catch (e: Exception) { report(e); return }
        if (p != cur) { history.record(cur); _ui.update { it.copy(canUndo = history.canUndo, canRedo = history.canRedo) } }
        updateProject(p)
        viewModelScope.launch(projectWrites) {
            runCatching { PrincessLibrary.save(app, p) }.onFailure {
                _ui.update { u -> u.copy(error = "No se pudo guardar el cuento. Revisa que quede espacio en el teléfono.") }
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
        runCatching { PrincessLibrary.save(app, p) }.onFailure { _ui.update { u -> u.copy(error = "No se pudo guardar el cuento. Revisa que quede espacio en el teléfono.") } }
    }

    /** Al salir de la pestaña o pasar la app a segundo plano: se guarda la grabación en curso y se para el sonido. */
    /** Al pasar la app a segundo plano: se termina de guardar lo grabado y lo editado, y el cuento sigue sonando si estaba sonando. */
    fun onBackground() {
        inBackground = true
        if (recorder != null) { interrupted = true; finishRecording() }
        flush()
    }

    /** La app volvió a primer plano. */
    fun onForeground() { inBackground = false }

    @Volatile private var inBackground = false
    @Volatile private var interrupted = false

    fun isStoryPlaying(): Boolean = _ui.value.loaded && PlayerOwner.story && _clock.value.playing

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
            if (_ui.value.current?.id == projectId) {
                history.record(p)
                _ui.update { it.copy(canUndo = history.canUndo, canRedo = history.canRedo, lastAddedId = cue.id) }
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
    /** Agrega el audio al cuento [projectId]: si está abierto, como una edición normal; si ya no lo está, directo en su carpeta. La decisión se toma con el candado tomado, así no se pierde nada si se vuelve a abrir. */
    private fun addTo(projectId: String, cue: Cue) {
        viewModelScope.launch(safety) {
            projectEdits.withLock {
                if (_ui.value.current?.id == projectId) {
                    _ui.update { it.copy(lastAddedId = cue.id) }
                    applyLocked { p -> p.copy(cues = p.cues + cue) }
                } else {
                    withContext(projectWrites) {
                        val p = PrincessLibrary.load(app, projectId) ?: return@withContext   // el cuento se borró
                        val next = p.copy(cues = p.cues + cue)
                        PrincessLibrary.saveInto(PrincessLibrary.dirOf(app, projectId), next, createIfMissing = false)
                        _ui.update { u -> u.copy(projects = u.projects.map { if (it.id == projectId) next else it }) }
                    }
                    // Si mientras tanto se reabrió ese cuento, el abierto tiene que quedar con la toma también.
                    if (_ui.value.current?.id == projectId) applyLocked { c -> if (c.cues.any { it.id == cue.id }) c else c.copy(cues = c.cues + cue) }
                }
            }
        }
    }

    private fun noSpace(t: Throwable) = t is java.io.IOException && (t.message ?: "").contains("ENOSPC", true)

    fun editTimelineCue(id: String, change: (Cue) -> Cue) = edit { project ->
        val anchored = DramaRenderer.anchorTimeline(project)
        anchored.copy(cues = anchored.cues.map { if (it.id == id) change(it) else it })
    }

    fun playFromSeconds(seconds: Double, stopAfterMs: Long = 0) {
        val p = _ui.value.current ?: return
        if (p.cues.isEmpty() || _ui.value.busy != null) return
        if (DramaRenderer.frameCount(p) > DramaRenderer.RATE * 15 * 60) {
            _ui.update { it.copy(notice = "Tu cuento dura más de 15 minutos: para oírlo entero toca «📤 Enviar» y escúchalo desde ahí.") }; return
        }
        _ui.update { it.copy(busy = "Preparando tu cuento…" to 0f, error = null) }   // antes de lanzar: un segundo toque no repite el trabajo
        val gen = leaveGen
        workJob = launchSafe {
            stop()
            val cached = lastMix?.takeIf { it.first == p }?.second
            val pcm = cached ?: renderProject(p, "Preparando tu cuento…").also { if (it.size <= 2 * DramaRenderer.RATE * 240) lastMix = p to it }   // solo cuentos de hasta 4 min
            if (gen != leaveGen) { _ui.update { it.copy(busy = null) }; return@launchSafe }
            if (_ui.value.current != p) { _ui.update { it.copy(busy = null) }; playFromSeconds(seconds, stopAfterMs); return@launchSafe }   // se editó mientras se preparaba: se vuelve a preparar
            places = DramaRenderer.layout(p)
            previewing = false
            val total = DramaRenderer.totalSeconds(p)
            load(pcm, if (seconds >= total - 0.1) 0.0 else seconds.coerceIn(0.0, total))
            _ui.update { it.copy(busy = null) }
            if (stopAfterMs > 0) { val token = playToken; kotlinx.coroutines.delay(stopAfterMs); if (gen == leaveGen && token == playToken) stop() }
        }
    }

    /** Trae un archivo de audio: [type] voz (del personaje [characterId]), música de fondo o efecto. */
    fun importAudio(uri: Uri, type: CueType, characterId: String?, replaceCueId: String? = null) {
        val p = _ui.value.current ?: return
        if (_ui.value.busy != null) return
        if (replaceCueId != null && p.cues.none { it.id == replaceCueId && it.type == CueType.VOICE }) return
        val at = overlayTime()
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
                        require(d.seconds <= 20 * 60) { "audio demasiado largo" }
                        Triple(f.name, name.substringBeforeLast('.'), d.seconds.toFloat())
                    } catch (e: Throwable) { f.delete(); throw e }
                }
            }
            r.onSuccess { (file, title, sec) ->
                val cue = Cue(PrincessLibrary.newId(), type, if (type == CueType.VOICE) characterId else null, title, file, sec,
                    if (type == CueType.MUSIC) 0f else 0.5f,
                    if (type == CueType.MUSIC) CueMode.BED else CueMode.SEQ, if (type == CueType.MUSIC) 0.5f else 1f,
                    0, if (type == CueType.MUSIC) 1.5f else 0f, if (type == CueType.MUSIC) 2f else 0f,
                    startSeconds = if (type == CueType.VOICE) null else at.toFloat())
                if (replaceCueId != null) {
                    try {
                        check(replaceVoice(p.id, replaceCueId, cue)) { "La voz que ibas a cambiar ya no está en este cuento." }
                    } catch (error: Throwable) {
                        withContext(NonCancellable + Dispatchers.IO) { File(PrincessLibrary.dirOf(app, p.id), file).delete() }
                        throw error
                    }
                } else {
                    addTo(p.id, cue)
                }
            }
            r.onFailure { e -> _ui.update { it.copy(error = if (noSpace(e)) friendly(e) else "No pude usar ese archivo. Prueba con otro audio (mp3, m4a, wav) de menos de 20 minutos.") } }
            _ui.update { it.copy(busy = null) }
        }
    }

    // ---- grabar voces ----
    private var recordAt: Double? = null
    private var replaceOnFinish: String? = null

    private var recordingFile: File? = null
    private val finishing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()   // tomas que se están guardando ahora (la recuperación no las toca)

    fun startRecording(characterId: String, atSeconds: Double? = null, replaceCueId: String? = null): Boolean {
        if (recorder != null || _ui.value.busy != null) return false
        val p = _ui.value.current ?: return false
        if (p.characters.none { it.id == characterId }) return false
        if (replaceCueId != null && p.cues.none { it.id == replaceCueId && it.type == CueType.VOICE && it.characterId == characterId }) return false
        recordAt = atSeconds
        replaceOnFinish = replaceCueId
        if (!PlayerOwner.story) beforePlay()   // una canción de Pistas no debe entrar al micrófono
        stop()
        // La toma se va escribiendo en el disco mientras se graba: si la app se cierra, se puede recuperar.
        val file = File(PrincessLibrary.dir(app, p), "grabando_${characterId}_${PrincessLibrary.newId()}.wav")
        recordingFile = file
        val rec = try { MicRecorder(file) { lvl -> _ui.update { it.copy(recLevel = lvl, recPeak = maxOf(it.recPeak, lvl), recSeconds = recorder?.seconds ?: it.recSeconds) } } } catch (t: Throwable) { report(t); return false }
        recorder = rec
        if (!rec.start()) {
            recorder = null; file.delete()
            _ui.update { it.copy(error = "No pude abrir el micrófono. Puede que otra app lo esté usando o que falte el permiso.") }
            return false
        }
        _ui.update { it.copy(recordingFor = characterId, recSeconds = 0f, recLevel = 0f, recPeak = 0f, error = null) }
        // Si el tope de 10 minutos o un fallo la detienen, se guarda sola (sin esperar a que se toque Terminar).
        viewModelScope.launch(safety) {
            while (recorder === rec && rec.isRunning) delay(300)
            if (recorder === rec) finishRecording()
        }
        return true
    }

    fun cancelRecording() {
        recorder?.stop(); recorder = null
        replaceOnFinish = null
        recordingFile?.delete(); recordingFile = null
        _ui.update { it.copy(recordingFor = null, recLevel = 0f) }
    }

    /** Termina la grabación y agrega la voz en la línea roja (o a continuación), conservando los extremos de la toma. */
    fun finishRecording(atSeconds: Double? = null) {
        val rec = recorder ?: return
        val at = atSeconds ?: recordAt
        val replacement = replaceOnFinish
        replaceOnFinish = null
        val wasInterrupted = interrupted
        interrupted = false
        val who = _ui.value.recordingFor
        val p = _ui.value.current
        val frames = rec.stop(); recorder = null
        val file = recordingFile; recordingFile = null
        val peak = rec.peak; val failed = rec.failed; val full = rec.full
        _ui.update { it.copy(recordingFor = null, recLevel = 0f) }
        if (p == null || who == null || file == null) { file?.delete(); return }
        if (failed && frames < MicRecorder.RATE) {
            file.delete(); _ui.update { it.copy(error = "Se cortó la grabación: otra app pudo haber tomado el micrófono. Vuelve a intentar.") }; return
        }
        if (frames < MicRecorder.RATE / 5) {
            file.delete(); _ui.update { it.copy(notice = "La grabación fue muy corta. Toca el botón rojo y habla un poco más.") }; return
        }
        if (peak < 150) {
            file.delete(); _ui.update { it.copy(error = "No se oyó nada. Revisa que el micrófono no esté tapado ni silenciado y habla más cerca.") }; return
        }
        finishing += file.name
        launchSafe {
            val take = try { withContext(Dispatchers.IO) { takeFrom(file) } } finally { finishing -= file.name }
            if (take == null) { _ui.update { it.copy(error = "No pude guardar la grabación ahora, pero no se perdió: se recuperará al abrir el cuento otra vez.") }; return@launchSafe }
            val number = nextTakeNumber(_ui.value.current)
            val cue = takeCue(who, "Mi voz $number", take, at)
            if (replacement != null) {
                if (!wasInterrupted && !failed && replaceVoice(p.id, replacement, cue)) return@launchSafe
                // No cambiar el original si faltó terminar la toma o si se quitó la pista.
                addTo(p.id, cue)
                _ui.update { it.copy(notice = "Guardé la nueva toma aparte. No cambié la voz anterior.") }
                return@launchSafe
            }
            val startAt = at ?: ((_ui.value.current?.let { DramaRenderer.totalSeconds(it) } ?: 0.0) + 0.6)   // 0,6 s es el espacio con que entra una voz «a continuación»
            val notice = when {
                failed || wasInterrupted -> "La grabación se interrumpió. Se guardó lo grabado."
                full -> "Límite de 10 minutos. Se guardó la toma."
                peak < 3000 -> "Nivel bajo. Acércate al micrófono para repetir la toma."
                else -> null
            }
            _ui.update { it.copy(cursor = startAt + take.length, notice = notice) }
            addTo(p.id, cue)
        }
    }

    /** Reemplaza la voz conservando su inicio y la posición de todos los demás audios. */
    private suspend fun replaceVoice(projectId: String, cueId: String, take: Cue): Boolean = withContext(NonCancellable) {
        projectEdits.withLock {
            val open = _ui.value.current?.takeIf { it.id == projectId }
            val original = open ?: withContext(projectWrites) { PrincessLibrary.load(app, projectId) } ?: return@withLock false
            if (original.cues.none { it.id == cueId && it.type == CueType.VOICE }) return@withLock false
            val anchored = DramaRenderer.anchorTimeline(original)
            val updated = anchored.copy(cues = anchored.cues.map { clip ->
                if (clip.id != cueId) clip else clip.copy(file = take.file, seconds = take.seconds,
                    durationSeconds = take.durationSeconds, trimStart = take.trimStart, toneSteps = 0, sourceFile = null)
            })
            withContext(projectWrites) {
                PrincessLibrary.saveInto(PrincessLibrary.dirOf(app, projectId), updated, createIfMissing = false)
            }
            if (_ui.value.current?.id == projectId) {
                history.record(original)
                _ui.update { it.copy(canUndo = history.canUndo, canRedo = history.canRedo, lastAddedId = cueId) }
            }
            updateProject(updated)
            true
        }
    }

    private class Take(val file: String, val length: Float)

    /** Conserva la toma completa. Un umbral de volumen cortaría consonantes suaves y susurros. */
    private fun takeFrom(grabando: File): Take? {
        val region = WavIo.dataRegion(grabando) ?: return null
        val name = "voz_${PrincessLibrary.newId()}.wav"
        if (!grabando.renameTo(File(grabando.parentFile, name))) return null
        val definitivo = File(grabando.parentFile, name)
        definitivo.setLastModified(System.currentTimeMillis())   // el renombre conserva la fecha vieja: así la limpieza no lo confunde con un sobrante
        runCatching { WavIo.repairHeader(definitivo) }
        return Take(name, region.frames.toFloat() / region.rate)
    }

    private fun takeCue(who: String, title: String, t: Take, at: Double?) =
        Cue(PrincessLibrary.newId(), CueType.VOICE, who, title, t.file, t.length, 0.6f, startSeconds = at?.toFloat(), durationSeconds = t.length)

    private fun nextTakeNumber(p: Project?): Int =
        (p?.cues.orEmpty().mapNotNull { Regex("^Mi voz (\\d+)$").find(it.text)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1

    /** Tomas que quedaron a medias (la app se cerró grabando): se recuperan como audios al final del cuento. */
    private suspend fun recoverTakes(p: Project) {
        val dir = PrincessLibrary.dir(app, p)
        val first = p.characters.firstOrNull()?.id ?: return
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("grabando_") && f.name.endsWith(".wav") }.orEmpty()
        for (f in files) {
            if (f.name in finishing || f.name == recordingFile?.name) continue   // la que se está grabando o guardando ahora no se toca
            if (f.length() < 44 + MicRecorder.RATE / 5 * 2) { f.delete(); continue }   // menos de 0,2 s: no hay nada que recuperar
            val who = f.name.removePrefix("grabando_").substringBeforeLast("_").takeIf { id -> p.characters.any { it.id == id } } ?: first
            val take = withContext(Dispatchers.IO) { runCatching { takeFrom(f) }.getOrNull() }
            if (take == null) continue   // no se borra: se intentará de nuevo la próxima vez
            addTo(p.id, takeCue(who, "Toma recuperada", take, null))
            _ui.update { it.copy(notice = "Recuperé una grabación que quedó a medias. Está al final de tu cuento.") }
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
        val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        _ui.update { it.copy(busy = label to 0f) }
        DramaRenderer.render(p, { clipOf(p, it) }, { pr -> if (job?.isActive != false) _ui.update { it.copy(busy = label to pr) } }, { job?.isActive == false })
    }

    private fun load(pcm: ShortArray, from: Double) {
        if (!requestAudioFocus()) { _ui.update { it.copy(notice = "Ahora no se puede reproducir: otra app está usando el sonido (¿una llamada?). Cierra esa app o termina la llamada y vuelve a tocar ▶ Escuchar.") }; return }
        beforePlay()
        PlayerOwner.story = true
        NativeEngine.replicaMask(0)
        NativeEngine.stemClear()
        NativeEngine.stemLoad(0, pcm, 2, DramaRenderer.RATE)
        NativeEngine.stemTrack(0, 1f, 0f, false, false)
        NativeEngine.stemMaster(1f, 1f)
        NativeEngine.stemLoop(-1.0, -1.0)
        NativeEngine.stemSeek(from)
        playStartedAt = System.currentTimeMillis()   // antes de empezar: el vigilante del final no debe ver la marca anterior
        NativeEngine.stemPlay(true)
        playToken++
        if (!serviceOn) serviceOn = PlaybackService.start(app)   // sigue sonando con la pantalla apagada
        _ui.update { it.copy(loaded = true) }
    }

    private var lastMix: Pair<Project, ShortArray>? = null
    @Volatile private var serviceOn = false
    @Volatile private var playStartedAt = 0L
    @Volatile private var playToken = 0

    /** Escucha solo una pista, con el tono y volumen de su personaje. */
    fun preview(index: Int) {
        val p = _ui.value.current ?: return
        val c = p.cues.getOrNull(index) ?: return
        if (_ui.value.busy != null) return
        if (c.seconds > 15 * 60) { _ui.update { it.copy(notice = "Este audio dura más de 15 minutos: es demasiado largo para escucharlo aquí.") }; return }
        _ui.update { it.copy(busy = "Preparando…" to 0f, error = null) }
        val gen = leaveGen
        workJob = launchSafe {
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
        dismissNotice()
    }

    fun removeCue(id: String) = edit { cur -> TimelineOps.delete(DramaRenderer.anchorTimeline(cur), id) }

    /** Quita el audio y junta lo que venía después, sin dejar un hueco. */
    fun removeAndClose(id: String) = edit { cur -> TimelineOps.deleteAndClose(cur, id) }

    /** Recorta el principio del audio hasta la línea roja (el archivo no se toca). */
    fun trimStartAtCursor(id: String) {
        val at = _ui.value.cursor
        val p = _ui.value.current ?: return
        if (TimelineOps.trimStartTo(p, id, at) == null) { _ui.update { it.copy(notice = "Mueve la línea roja al nuevo inicio del clip.") }; return }
        edit { cur -> TimelineOps.trimStartTo(cur, id, at) ?: cur }
        dismissNotice()
    }

    /** Recorta el final del audio desde la línea roja. */
    fun trimEndAtCursor(id: String) {
        val at = _ui.value.cursor
        val p = _ui.value.current ?: return
        if (TimelineOps.trimEndAt(p, id, at) == null) { _ui.update { it.copy(notice = "Mueve la línea roja al nuevo final del clip.") }; return }
        edit { cur -> TimelineOps.trimEndAt(cur, id, at) ?: cur }
        dismissNotice()
    }

    fun duplicateCue(id: String) {
        edit { cur -> TimelineOps.duplicate(cur, id, PrincessLibrary.newId()) ?: cur }
    }

    fun toggleMute(id: String) {
        val on = _ui.value.current?.cues?.firstOrNull { it.id == id }?.muted == true
        editTimelineCue(id) { it.copy(muted = !on) }
    }

    fun toggleFade(id: String, fadeIn: Boolean) {
        val c = _ui.value.current?.cues?.firstOrNull { it.id == id } ?: return
        val on = (if (fadeIn) c.fadeIn else c.fadeOut) > 0f
        editTimelineCue(id) { if (fadeIn) it.copy(fadeIn = if (on) 0f else 0.6f) else it.copy(fadeOut = if (on) 0f else 0.6f) }
    }

    fun renameCue(id: String, name: String) {
        val n = name.trim()
        if (n.isNotEmpty()) edit { cur -> cur.copy(cues = cur.cues.map { if (it.id == id) it.copy(text = n) else it }) }
    }

    fun nudgeCursor(delta: Double) = setCursor((_ui.value.cursor + delta).coerceAtLeast(0.0))

    /** Escucha un tramo breve de la mezcla alrededor de la posición indicada. */
    fun listenAround(at: Double = _ui.value.cursor) {
        playFromSeconds((at - 1.0).coerceAtLeast(0.0), stopAfterMs = 2500)
    }

    // ---- ondas de sonido (para ver dónde hay voz al cortar) ----
    private val peakCache = HashMap<String, FloatArray>()

    fun loadPeaks(p: Project) {
        val missing = p.cues.filter { it.file != null && it.type != CueType.MUSIC && it.type != CueType.SILENCE && (it.seconds <= 300f || it.file.endsWith(".wav")) && !peakCache.containsKey(it.file) }
        if (missing.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO + safety) {
            val dir = PrincessLibrary.dir(app, p)
            for (c in missing) {
                val name = c.file ?: continue
                val peaks = runCatching { peaksOf(File(dir, name)) }.getOrNull() ?: FloatArray(0)
                synchronized(peakCache) { peakCache[name] = peaks }
            }
            _ui.update { it.copy(peaks = synchronized(peakCache) { HashMap(peakCache) }) }
        }
    }

    private fun peaksOf(f: File, buckets: Int = 240): FloatArray {
        WavIo.peaksOfFile(f)?.let { return it }   // WAV: por partes y con muchos puntos, sirve para tomas largas
        val d = AudioDecoder.decode(f)
        val frames = d.pcm.size / d.channels
        if (frames < buckets) return FloatArray(0)
        val out = FloatArray(buckets)
        for (b in 0 until buckets) {
            val from = (b.toLong() * frames / buckets).toInt(); val to = ((b + 1L) * frames / buckets).toInt()
            var m = 0
            var i = from
            while (i < to) { m = maxOf(m, kotlin.math.abs(d.pcm[i * d.channels].toInt())); i += maxOf(1, (to - from) / 64) }
            out[b] = m / 32768f
        }
        val top = out.max().coerceAtLeast(0.02f)
        for (i in out.indices) out[i] = (out[i] / top).coerceIn(0f, 1f)
        return out
    }

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
        if (cue.seconds < 0.3f) { _ui.update { it.copy(notice = "Este audio es muy cortito para cambiarle el tono.") }; return }
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
                    withContext(Dispatchers.IO) { WavIo.write(File(dir, name), shifted, 1, d.rate) }
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

    private var focusRequest: android.media.AudioFocusRequest? = null

    private fun requestAudioFocus(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 26) return true
        val am = app.getSystemService(android.media.AudioManager::class.java) ?: return true
        val req = focusRequest ?: android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                // Una llamada u otra app con sonido: el cuento se pausa (no sigue sonando encima).
                if (change == android.media.AudioManager.AUDIOFOCUS_LOSS || change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    viewModelScope.launch(Dispatchers.Main) {
                        if (isStoryPlaying()) _ui.update { it.copy(notice = "El cuento se detuvo porque entró una llamada o otro sonido. Toca ▶ Escuchar para seguir.") }
                        stop()
                        if (inBackground) NativeEngine.stop()   // con la app atrás no se deja el audio abierto
                    }
            }.build().also { focusRequest = it }
        return runCatching { am.requestAudioFocus(req) }.getOrDefault(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) != android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val req = focusRequest ?: return
        runCatching { app.getSystemService(android.media.AudioManager::class.java)?.abandonAudioFocusRequest(req) }
    }

    fun stop() {
        playToken++
        if (serviceOn) { PlaybackService.stop(app); serviceOn = false }
        abandonAudioFocus()
        if (_ui.value.loaded) NativeEngine.stemPlay(false)
        _clock.update { it.copy(playing = false) }
    }

    fun seek(sec: Double) { NativeEngine.stemSeek(sec); _clock.update { it.copy(pos = sec) } }

    /** Por qué el último envío cayó a WAV (para diagnóstico y pruebas). */
    @Volatile var exportFallback: Throwable? = null

    /** Mezcla el cuento por bloques y lo deja en un archivo M4A (si el teléfono no puede, en WAV). */
    suspend fun renderExport(p: Project, label: String = "Preparando tu cuento para enviar…"): File = withContext(Dispatchers.Default) {
        exportFallback = null
        val safe = p.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifBlank { "Mi cuento" }
        val dir = File(PrincessLibrary.dir(app, p), "enviar").also { it.mkdirs() }
        val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        val progress = { pr: Float -> if (job?.isActive != false) _ui.update { it.copy(busy = label to pr) } }
        val part = File(dir, "export_${PrincessLibrary.newId()}.part")
        val cancelled = { job?.isActive == false }
        try {
            val m4a = runCatching {
                AacWriter(part).use { w -> DramaRenderer.renderTo(p, { clipOf(p, it) }, progress, cancelled) { block, _, n -> w.write(block, n * 2) } }
                File(dir, "$safe.m4a").also { if (!part.renameTo(it)) part.copyTo(it, overwrite = true) }
            }
            if (job?.isActive == false) throw java.util.concurrent.CancellationException("envío cancelado")
            m4a.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException || it is OutOfMemoryError) throw it }
            m4a.exceptionOrNull()?.let { exportFallback = it }
            val out = m4a.getOrNull() ?: run {
                _ui.update { it.copy(notice = "Este teléfono no pudo hacer el audio liviano: sale en un archivo más pesado (WAV).") }
                WavIo.Writer(part, 2, DramaRenderer.RATE).use { w -> DramaRenderer.renderTo(p, { clipOf(p, it) }, progress, cancelled) { block, _, n -> w.write(block, n * 2) } }
                File(dir, "$safe.wav").also { if (!part.renameTo(it)) part.copyTo(it, overwrite = true) }
            }
            dir.listFiles()?.filter { it.isFile && (it.extension == "m4a" || it.extension == "wav") && it != out }?.forEach { it.delete() }   // envíos anteriores
            out
        } finally { part.delete() }
    }

    /** Mezcla el cuento a un audio y abre el menú de compartir (WhatsApp, guardar, etc.). */
    fun export() = exportThen("Listo. Elige WhatsApp u otra app para enviarlo.", ::share)

    /** Guarda el cuento en el teléfono (Música › Cuentos) para usarlo sin internet, por ejemplo en el aula. */
    fun saveToPhone() = exportThen(null) { f -> saveToMediaStore(f) }

    private fun saveToMediaStore(f: File) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            _ui.update { it.copy(notice = "Este teléfono es antiguo: toca «📤 Enviar» y elige «Guardar en archivos» (o tu app de archivos).") }; return
        }
        val resolver = app.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, f.name)
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, if (f.extension == "m4a") "audio/mp4" else "audio/x-wav")
            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/Cuentos")
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("No se pudo crear el archivo en el teléfono")
        try {
            resolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            values.clear(); values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) { runCatching { resolver.delete(uri, null, null) }; throw t }
        val real = runCatching {
            resolver.query(uri, arrayOf(android.provider.MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: f.name
        _ui.update { it.copy(notice = "Guardé tu cuento «${real.substringBeforeLast('.')}». Ábrelo con la app Música o Archivos (carpeta Música › Cuentos).") }
    }

    private fun exportThen(doneNotice: String?, action: (File) -> Unit) {
        val p = _ui.value.current ?: return
        if (p.cues.isEmpty()) { _ui.update { it.copy(notice = "Todavía no hay nada que enviar. Graba tu voz o agrega un sonido primero.") }; return }
        if (_ui.value.busy != null) return
        lastExport?.let { (q, file) -> if (q == p && file.exists()) { viewModelScope.launch(Dispatchers.IO + safety) { runCatching { action(file) }.onFailure { report(it) } }; return } }
        _ui.update { it.copy(busy = "Preparando tu cuento para enviar…" to 0f, error = null) }
        val gen = leaveGen
        workJob = launchSafe {
            val f = renderExport(p)
            lastExport = p to f
            if (gen != leaveGen) { _ui.update { it.copy(busy = null, notice = "Tu cuento está listo: vuelve a tocar el botón para usarlo.") }; return@launchSafe }
            _ui.update { it.copy(busy = null, notice = doneNotice ?: it.notice) }
            withContext(Dispatchers.IO) { if (doneNotice == null) action(f) }
            if (doneNotice != null) action(f)
        }
    }

    /** Último cuento mezclado para enviar: si no cambió nada, se vuelve a mandar sin mezclar otra vez. */
    private var lastExport: Pair<Project, File>? = null

    private fun share(f: File) {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).setType(if (f.extension == "m4a") "audio/mp4" else "audio/wav").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(send, "Enviar mi cuento").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun dismissError() = _ui.update { it.copy(error = null, notice = null) }

    override fun onCleared() {
        flush()
        recorder?.stop()
        if (_ui.value.loaded) NativeEngine.stemPlay(false)
        super.onCleared()
    }
}
