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
            _ui.update { it.copy(error = null, loading = 0f, loadingName = "Copiando archivos…") }
            val song = withContext(Dispatchers.IO) {
                runCatching { StemLibrary.import(app, uris.take(StemPlayerLimit), title) }
            }
            song.onFailure { e -> _ui.update { it.copy(loading = null, error = "No se pudo importar: ${e.message}") } }
            song.onSuccess { s ->
                _ui.update { it.copy(songs = listOf(s) + it.songs) }
                open(s)
            }
        }
    }

    fun open(song: StemSong) {
        stop()
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
            _ui.update { it.copy(loading = null, loadingName = "", error = failed) }
        }
    }

    fun close() {
        stop()
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

    fun dismissError() = _ui.update { it.copy(error = null) }

    override fun onCleared() {
        NativeEngine.stemPlay(false)
        super.onCleared()
    }

    private companion object { const val StemPlayerLimit = 8 }
}
