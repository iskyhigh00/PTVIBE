package com.glidelive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glidelive.audio.NativeEngine
import com.glidelive.stems.AudioDecoder
import com.glidelive.stems.StemKind
import com.glidelive.stems.StemLibrary
import com.glidelive.stems.StemSong
import com.glidelive.stems.StemTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StemPreset(val label: String) {
    ALL("Todo"), KARAOKE("Karaoke (sin voz)"), ACAPELLA("Solo voz"), NO_DRUMS("Sin batería"), NO_BASS("Sin bajo"),
    NO_GUITAR("Sin guitarra"), DRUMS_BASS("Batería + bajo"),
}

data class StemsUi(
    val songs: List<StemSong> = emptyList(),
    val current: StemSong? = null,
    /** Progreso de carga 0..1 (null = nada cargando) y qué pista se está decodificando. */
    val loading: Float? = null,
    val loadingName: String = "",
    val error: String? = null,
    val speed: Float = 1f,
    val master: Float = 0.9f,
    val loopA: Double = -1.0,
    val loopB: Double = -1.0,
    /** Separación en curso: texto de la etapa y avance 0..1 (null = ninguna). */
    val separating: Pair<String, Float>? = null,
    /** Aviso de una línea (p. ej. "esa canción ya estaba guardada"). */
    val notice: String? = null,
    /** Réplica con los instrumentos de la app. */
    val replicaNotes: Int = 0,
    val replicaMelody: StemKind = StemKind.VOCALS,
    val replicaAnalyzing: Pair<String, Float>? = null,
    /** Bits: 1 batería, 2 bajo, 4 melodía. */
    val replicaMask: Int = 0,
    val replicaGain: List<Float> = listOf(0.9f, 0.9f, 0.9f),
)

/** Posición del reproductor: cambia ~10 veces por segundo, por eso vive aparte del resto del estado. */
data class StemClock(val pos: Double = 0.0, val dur: Double = 0.0, val playing: Boolean = false)

class StemsViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(StemsUi())
    val ui: StateFlow<StemsUi> = _ui
    private val _clock = MutableStateFlow(StemClock())
    val clock: StateFlow<StemClock> = _clock

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val songs = StemLibrary.load(app)
            _ui.update { it.copy(songs = songs) }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (_ui.value.current != null) {
                    val s = NativeEngine.stemStatus()
                    val c = StemClock(s[0], s[1], s[2] > 0.5)
                    if (c != _clock.value) _clock.value = c
                    NativeEngine.stemFree()
                }
                delay(if (_clock.value.playing) 100L else 300L)
            }
        }
    }

    // ---- biblioteca ----
    fun import(uris: List<Uri>, title: String) {
        viewModelScope.launch {
            _ui.update { it.copy(error = null, notice = null, loading = 0f, loadingName = "Copiando archivos…") }
            // Si esos mismos archivos ya se importaron, se abre la canción guardada (con sus pistas separadas).
            val key = withContext(Dispatchers.IO) { runCatching { StemLibrary.keyOf(app, uris.take(StemPlayerLimit)) }.getOrDefault("") }
            val existing = _ui.value.songs.firstOrNull { key.isNotEmpty() && it.key == key }
            if (existing != null) {
                _ui.update { it.copy(loading = null, notice = "«${existing.title}» ya estaba guardada: se abrió sin volver a subirla.") }
                open(existing)
                return@launch
            }
            val song = withContext(Dispatchers.IO) {
                runCatching { StemLibrary.import(app, uris.take(StemPlayerLimit), title, key) }
            }
            song.onFailure { e -> _ui.update { it.copy(loading = null, error = "No se pudo importar: ${e.message}") } }
            song.onSuccess { s ->
                _ui.update { it.copy(songs = listOf(s) + it.songs) }
                open(s)
            }
        }
    }

    private val prefs = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)

    /** Abre la última canción que se estaba usando (si sigue guardada). */
    fun openLast() {
        if (_ui.value.current != null || _ui.value.loading != null) return
        val id = prefs.getString("stems_last", null) ?: return
        _ui.value.songs.firstOrNull { it.id == id }?.let { open(it) }
    }

    fun open(song: StemSong) {
        prefs.edit().putString("stems_last", song.id).apply()
        stop()
        NativeEngine.replicaMask(0)
        viewModelScope.launch {
            NativeEngine.stemClear()
            _ui.update { it.copy(current = song, error = null, loading = 0f, loopA = -1.0, loopB = -1.0, speed = 1f) }
            val dir = StemLibrary.dir(app, song)
            var failed: String? = null
            song.tracks.take(StemPlayerLimit).forEachIndexed { i, t ->
                if (failed != null) return@forEachIndexed
                _ui.update { it.copy(loadingName = "Cargando ${t.name}…") }
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        AudioDecoder.decode(java.io.File(dir, t.file)) { p ->
                            _ui.update { u -> u.copy(loading = (i + p) / song.tracks.size.coerceAtLeast(1)) }
                        }
                    }
                }
                r.onSuccess { d ->
                    NativeEngine.stemLoad(i, d.pcm, d.channels, d.rate)
                    NativeEngine.stemTrack(i, t.volume, t.pan, t.mute, t.solo)
                }
                r.onFailure { e -> failed = "No se pudo leer ${t.name}: ${e.message}" }
            }
            NativeEngine.stemMaster(_ui.value.master, 1f)
            NativeEngine.stemLoop(-1.0, -1.0)
            NativeEngine.stemSeek(0.0)
            loadReplica(song)
            _ui.update { it.copy(loading = null, loadingName = "", error = failed) }
        }
    }

    // ---- réplica con los instrumentos de la app ----
    private var replicaJob: kotlinx.coroutines.Job? = null

    private fun pushReplica(notes: List<com.glidelive.stems.sep.Note>) {
        NativeEngine.replicaSet(
            DoubleArray(notes.size) { notes[it].t }, FloatArray(notes.size) { notes[it].dur },
            FloatArray(notes.size) { notes[it].vel }, IntArray(notes.size) { notes[it].note }, IntArray(notes.size) { notes[it].kind },
        )
        val u = _ui.value
        NativeEngine.replicaMask(u.replicaMask)
        NativeEngine.replicaGain(u.replicaGain[0], u.replicaGain[1], u.replicaGain[2])
    }

    private suspend fun loadReplica(song: StemSong) {
        NativeEngine.replicaMask(0)
        val notes = withContext(Dispatchers.IO) {
            if (com.glidelive.stems.sep.ReplicaBuilder.has(app, song)) com.glidelive.stems.sep.ReplicaBuilder.load(app, song) else emptyList()
        }
        val melody = withContext(Dispatchers.IO) { com.glidelive.stems.sep.ReplicaBuilder.melodySource(app, song) } ?: StemKind.VOCALS
        _ui.update { it.copy(replicaNotes = notes.size, replicaMask = 0, replicaMelody = melody) }
        pushReplica(notes)
        NativeEngine.replicaMask(0)
    }

    fun setReplicaMelody(k: StemKind) { _ui.update { it.copy(replicaMelody = k) } }

    /** Analiza las pistas y genera la réplica (tarda según el largo de la canción). */
    fun analyzeReplica() {
        val song = _ui.value.current ?: return
        if (replicaJob?.isActive == true) return
        stop()
        val melody = _ui.value.replicaMelody
        replicaJob = viewModelScope.launch {
            _ui.update { it.copy(error = null, replicaAnalyzing = "Preparando…" to 0f) }
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    com.glidelive.stems.sep.ReplicaBuilder.build(app, song, melody,
                        { s, p -> _ui.update { u -> u.copy(replicaAnalyzing = s to p) } }, { !isActive })
                }
            }
            r.onSuccess { notes ->
                _ui.update { it.copy(replicaNotes = notes.size, replicaAnalyzing = null, replicaMask = 0) }
                pushReplica(notes)
                NativeEngine.replicaMask(0)
            }
            r.onFailure { e ->
                val msg = if (e is java.util.concurrent.CancellationException) null else "No se pudo analizar: ${e.message ?: e.javaClass.simpleName}"
                _ui.update { it.copy(replicaAnalyzing = null, error = msg) }
            }
        }
    }

    fun cancelReplica() { replicaJob?.cancel() }

    /** Enciende o apaga una parte de la réplica; al encenderla, silencia la pista original (y al apagarla la devuelve). */
    fun setReplica(bit: Int, on: Boolean) {
        val song = _ui.value.current ?: return
        val mask = if (on) _ui.value.replicaMask or bit else _ui.value.replicaMask and bit.inv()
        _ui.update { it.copy(replicaMask = mask) }
        NativeEngine.replicaMask(mask)
        val kind = when (bit) { 1 -> StemKind.DRUMS; 2 -> StemKind.BASS; else -> _ui.value.replicaMelody }
        song.tracks.indexOfFirst { it.kind == kind }.takeIf { it >= 0 }?.let { i -> editTrack(i) { it.copy(mute = on) } }
    }

    fun setReplicaGain(index: Int, v: Float) {
        val g = _ui.value.replicaGain.toMutableList().also { it[index] = v }
        _ui.update { it.copy(replicaGain = g) }
        NativeEngine.replicaGain(g[0], g[1], g[2])
    }

    fun close() {
        stop()
        NativeEngine.replicaMask(0)
        NativeEngine.stemClear()
        _ui.update { it.copy(current = null, loading = null) }
        _clock.value = StemClock()
    }

    fun delete(song: StemSong) {
        if (_ui.value.current?.id == song.id) close()
        viewModelScope.launch(Dispatchers.IO) {
            StemLibrary.delete(app, song)
            _ui.update { it.copy(songs = it.songs.filter { s -> s.id != song.id }) }
        }
    }

    // ---- reproducción ----
    fun togglePlay() = if (_clock.value.playing) stop() else play()
    fun play() {
        if (_ui.value.current == null || _ui.value.loading != null) return
        NativeEngine.stemPlay(true)
        _clock.update { it.copy(playing = true) }
    }
    fun stop() {
        NativeEngine.stemPlay(false)
        _clock.update { it.copy(playing = false) }
    }
    fun seek(sec: Double) { NativeEngine.stemSeek(sec); _clock.update { it.copy(pos = sec) } }

    fun setSpeed(v: Float) { _ui.update { it.copy(speed = v) }; NativeEngine.stemMaster(_ui.value.master, v) }
    fun setMaster(v: Float) { _ui.update { it.copy(master = v) }; NativeEngine.stemMaster(v, _ui.value.speed) }

    fun markA() {
        val pos = _clock.value.pos
        _ui.update { it.copy(loopA = pos, loopB = if (it.loopB > pos) it.loopB else -1.0) }
        pushLoop()
    }
    fun markB() {
        val pos = _clock.value.pos
        val a = _ui.value.loopA
        _ui.update { it.copy(loopA = if (a >= 0) a else maxOf(0.0, pos - 8.0), loopB = pos) }
        pushLoop()
    }
    fun clearLoop() { _ui.update { it.copy(loopA = -1.0, loopB = -1.0) }; pushLoop() }
    private fun pushLoop() { val u = _ui.value; NativeEngine.stemLoop(u.loopA, u.loopB) }

    // ---- mezcla ----
    fun editTrack(index: Int, f: (StemTrack) -> StemTrack) {
        val song = _ui.value.current ?: return
        if (index !in song.tracks.indices) return
        val t = f(song.tracks[index])
        val updated = song.copy(tracks = song.tracks.toMutableList().also { it[index] = t })
        applyUpdated(updated)
        NativeEngine.stemTrack(index, t.volume, t.pan, t.mute, t.solo)
    }

    fun preset(p: StemPreset) {
        val song = _ui.value.current ?: return
        val kinds = song.tracks.map { it.kind }.toSet()
        val tracks = song.tracks.map { t ->
            val mute = when (p) {
                StemPreset.ALL -> false
                StemPreset.KARAOKE -> t.kind == StemKind.VOCALS
                StemPreset.ACAPELLA -> t.kind != StemKind.VOCALS
                StemPreset.NO_DRUMS -> t.kind == StemKind.DRUMS
                StemPreset.NO_BASS -> t.kind == StemKind.BASS
                // Con 4 pistas la guitarra viene dentro de "Otros".
                StemPreset.NO_GUITAR -> t.kind == StemKind.GUITAR || (StemKind.GUITAR !in kinds && t.kind == StemKind.OTHER)
                StemPreset.DRUMS_BASS -> t.kind != StemKind.DRUMS && t.kind != StemKind.BASS
            }
            t.copy(mute = mute, solo = false)
        }
        applyUpdated(song.copy(tracks = tracks))
        tracks.forEachIndexed { i, t -> NativeEngine.stemTrack(i, t.volume, t.pan, t.mute, t.solo) }
    }

    private fun applyUpdated(updated: StemSong) {
        _ui.update { u -> u.copy(current = updated, songs = u.songs.map { if (it.id == updated.id) updated else it }) }
        val all = _ui.value.songs
        viewModelScope.launch(Dispatchers.IO) { StemLibrary.save(app, all) }
    }

    private var sepJob: kotlinx.coroutines.Job? = null

    /** Separa la mezcla de la canción abierta en voz, batería, bajo y otros (en el teléfono, sin internet salvo la 1.ª vez). */
    fun separate(high: Boolean = true) {
        val song = _ui.value.current ?: return
        if (sepJob?.isActive == true) return
        stop()
        sepJob = viewModelScope.launch {
            _ui.update { it.copy(error = null, separating = "Preparando…" to 0f) }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    com.glidelive.stems.sep.Separator.run(
                        app, song, high,
                        { s, p -> _ui.update { u -> u.copy(separating = s to p) } },
                        { !isActive },
                    )
                }
            }
            result.onSuccess { tracks ->
                val updated = song.copy(tracks = tracks)
                val all = _ui.value.songs.map { if (it.id == song.id) updated else it }
                withContext(Dispatchers.IO) { StemLibrary.save(app, all) }
                _ui.update { it.copy(songs = all, separating = null) }
                open(updated)
            }
            result.onFailure { e ->
                val msg = if (e is kotlinx.coroutines.CancellationException || e is java.util.concurrent.CancellationException) null
                else "No se pudo separar: ${e.message ?: e.javaClass.simpleName}"
                _ui.update { it.copy(separating = null, error = msg) }
            }
        }
    }

    fun cancelSeparation() { sepJob?.cancel() }

    fun dismissError() = _ui.update { it.copy(error = null, notice = null) }

    override fun onCleared() {
        NativeEngine.stemPlay(false)
        super.onCleared()
    }

    private companion object { const val StemPlayerLimit = 8 }
}
