package com.glidelive.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glidelive.audio.NativeEngine
import com.glidelive.data.PatternRepository
import com.glidelive.drums.DrumKeys
import com.glidelive.drums.DrumPattern
import com.glidelive.drums.Step
import com.glidelive.drums.Variation
import com.glidelive.viz.EventHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class Meter(val beats: Int, val unit: Int) {
    val label get() = "$beats/$unit"
    val barTicks get() = beats * 384 / unit

    companion object {
        val all = listOf(2 to 4, 3 to 4, 4 to 4, 5 to 4, 6 to 8, 7 to 8, 9 to 8, 11 to 8, 12 to 8).map { Meter(it.first, it.second) }
    }
}

enum class RecQuant(val ticks: Int, val label: String) {
    FREE(0, "Libre"), EIGHTH(48, "1/8"), SIXTEENTH(24, "1/16"), TRIPLET(32, "Tresillos"), THIRTY_SECOND(12, "1/32")
}

enum class ChordQuant(val code: Int, val label: String) {
    OFF(0, "Apagada"), BEAT(1, "Al tiempo"), BAR(2, "Al compás")
}

/** Parte de una base que se edita en el secuenciador. */
enum class EditPart(val label: String) { A("A"), B("B"), C("C"), FILL("Relleno"), END("Final") }

data class EditorState(val pattern: DrumPattern, val part: Int = 0)

data class TransportUi(
    val bpm: Int = 100,
    val meter: Meter = Meter(4, 4),
    val swing: Float = 0f,
    val metro: Boolean = false,
    val metroVol: Float = 0.6f,
    val playing: Boolean = false,
    val bar: Int = 1,
    val beat: Int = 1,
    val patterns: List<DrumPattern> = emptyList(),
    val patternId: String? = null,
    val variation: Int = 0,
    val patternOn: Boolean = false,
    val fillOn: Boolean = false,
    val endPending: Boolean = false,
    val muted: Set<Int> = emptySet(),
    val solo: Int? = null,
    val adoptPattern: Boolean = true,
    val recDrum: Boolean = false,
    val recChord: Boolean = false,
    val drumEvents: Int = 0,
    val chordEvents: Int = 0,
    val drumBars: Int = 1,
    val drumMeter: Meter = Meter(4, 4),
    val chordBars: Int = 4,
    val chordMeter: Meter = Meter(4, 4),
    val recQuant: RecQuant = RecQuant.FREE,
    val chordQuant: ChordQuant = ChordQuant.OFF,
    val drumLoopOn: Boolean = true,
    val chordLoopOn: Boolean = true,
    val editor: EditorState? = null,
) {
    val pattern: DrumPattern? get() = patterns.firstOrNull { it.id == patternId }
}

class TransportViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(TransportUi())
    val ui: StateFlow<TransportUi> = _ui

    private val taps = ArrayList<Long>()
    private var lastPass = 0

    init {
        PatternRepository.load(app)
        val list = PatternRepository.all.value
        _ui.update { it.copy(patterns = list, patternId = list.firstOrNull()?.id) }
        push()
        list.firstOrNull()?.let { sendPattern(it, it.variations[0].tracks, restart = true) }
        viewModelScope.launch(Dispatchers.Default) {
            PatternRepository.all.collect { l -> _ui.update { it.copy(patterns = l) } }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) { poll(); delay(40) }
        }
    }

    // ---- reloj ----
    private fun push() {
        val u = _ui.value
        NativeEngine.setParam(NativeEngine.P_BPM, u.bpm.toFloat())
        NativeEngine.setParam(NativeEngine.P_BEATS, u.meter.beats.toFloat())
        NativeEngine.setParam(NativeEngine.P_BEAT_UNIT, u.meter.unit.toFloat())
        NativeEngine.setParam(NativeEngine.P_SWING, u.swing)
        NativeEngine.setParam(NativeEngine.P_METRO, if (u.metro) 1f else 0f)
        NativeEngine.setParam(NativeEngine.P_METRO_VOL, u.metroVol)
        NativeEngine.setParam(NativeEngine.P_REC_QUANT, u.recQuant.ticks.toFloat())
        NativeEngine.setParam(NativeEngine.P_CHORD_QUANT, u.chordQuant.code.toFloat())
        NativeEngine.setParam(NativeEngine.P_MUTE_MASK, muteMask(u).toFloat())
    }

    private fun muteMask(u: TransportUi): Int {
        val solo = u.solo
        if (solo != null) return ((1 shl DrumKeys.keys.size) - 1) and (1 shl solo).inv()
        return u.muted.fold(0) { m, p -> m or (1 shl p) }
    }

    fun edit(f: (TransportUi) -> TransportUi) { _ui.update(f); push() }

    fun play() { NativeEngine.seq(NativeEngine.CMD_PLAY) }
    fun stop() { NativeEngine.seq(NativeEngine.CMD_STOP); _ui.update { it.copy(recDrum = false, recChord = false) } }
    fun togglePlay() = if (_ui.value.playing) stop() else play()

    fun setBpm(v: Int) = edit { it.copy(bpm = v.coerceIn(30, 300)) }

    fun tap() {
        val now = SystemClock.uptimeMillis()
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        taps.add(now)
        while (taps.size > 5) taps.removeAt(0)
        if (taps.size >= 2) {
            val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
            setBpm((60000.0 / avg).toInt())
        }
    }

    // ---- bases de batería ----
    private fun sendPattern(p: DrumPattern, tracks: Map<String, String>, restart: Boolean) {
        val c = p.compile(tracks)
        NativeEngine.setPattern(c.ticks, c.pads, c.vels, p.lengthTicks, restart, false)
        val e = p.compileEnd()
        NativeEngine.setEndPattern(e.ticks, e.pads, e.vels, p.barTicks)
    }

    fun selectPattern(p: DrumPattern, adopt: Boolean = _ui.value.adoptPattern) {
        _ui.update { it.copy(patternId = p.id, variation = 0, fillOn = false, endPending = false) }
        if (adopt) {
            edit { it.copy(bpm = p.bpmDefault, meter = Meter(p.beats, p.beatUnit)) }
        }
        sendPattern(p, p.variations[0].tracks, restart = true)
        lastPass = 0
    }

    /** Carga de escena: no corta el sonido; el patrón se cambia sin tocar el transporte. */
    fun applyScene(
        bpm: Int?, meter: Meter?, swing: Float?, metro: Boolean?, patternId: String?, variation: Int?,
        recQuant: RecQuant?, chordQuant: ChordQuant?,
    ) {
        edit {
            it.copy(
                bpm = bpm ?: it.bpm, meter = meter ?: it.meter, swing = swing ?: it.swing, metro = metro ?: it.metro,
                recQuant = recQuant ?: it.recQuant, chordQuant = chordQuant ?: it.chordQuant,
            )
        }
        val p = _ui.value.patterns.firstOrNull { it.id == patternId }
        if (p != null) {
            if (_ui.value.patternId != p.id) selectPattern(p, adopt = false)
            variation?.let { selectVariation(it.coerceIn(0, p.variations.size - 1)) }
        }
    }

    fun selectVariation(i: Int) {
        val p = _ui.value.pattern ?: return
        _ui.update { it.copy(variation = i, fillOn = false) }
        sendPattern(p, p.variations[i.coerceIn(0, p.variations.size - 1)].tracks, restart = false)
    }

    fun togglePattern() {
        val on = !_ui.value.patternOn
        _ui.update { it.copy(patternOn = on, endPending = false) }
        NativeEngine.seq(NativeEngine.CMD_PATTERN_PLAY, if (on) 1 else 0)
        if (on && !_ui.value.playing) play()
    }

    /** Relleno en el último compás: se vuelve a la variación al dar la vuelta. */
    fun fill() {
        val u = _ui.value
        val p = u.pattern ?: return
        if (!u.patternOn) return
        sendPattern(p, p.withFill(u.variation), restart = false)
        lastPass = NativeEngine.seqStatus()[2].toInt()
        _ui.update { it.copy(fillOn = true) }
    }

    /** Final: entra justo al terminar el patrón y la base se detiene. */
    fun finish() {
        if (!_ui.value.patternOn) return
        NativeEngine.seq(NativeEngine.CMD_PATTERN_END)
        _ui.update { it.copy(endPending = true) }
    }

    fun toggleMute(pad: Int) = edit {
        it.copy(solo = null, muted = if (pad in it.muted) it.muted - pad else it.muted + pad)
    }

    fun toggleSolo(pad: Int) = edit { it.copy(solo = if (it.solo == pad) null else pad) }

    // ---- loopers ----
    fun toggleRecDrum() {
        val u = _ui.value
        if (u.recDrum) {
            NativeEngine.seq(NativeEngine.CMD_REC_DRUM, 0)
        } else {
            NativeEngine.seq(NativeEngine.CMD_DRUM_LEN, u.drumBars * u.drumMeter.barTicks)
            if (!u.playing) play()
            NativeEngine.seq(NativeEngine.CMD_REC_DRUM, 1)
        }
        _ui.update { it.copy(recDrum = !it.recDrum) }
    }

    fun toggleRecChord() {
        val u = _ui.value
        if (u.recChord) {
            NativeEngine.seq(NativeEngine.CMD_REC_CHORD, 0)
        } else {
            NativeEngine.seq(NativeEngine.CMD_CHORD_LEN, u.chordBars * u.chordMeter.barTicks)
            if (!u.playing) play()
            NativeEngine.seq(NativeEngine.CMD_REC_CHORD, 1)
        }
        _ui.update { it.copy(recChord = !it.recChord) }
    }

    fun setDrumLoop(bars: Int, meter: Meter) {
        _ui.update { it.copy(drumBars = bars, drumMeter = meter) }
        NativeEngine.seq(NativeEngine.CMD_DRUM_LEN, bars * meter.barTicks)
    }

    fun setChordLoop(bars: Int, meter: Meter) {
        _ui.update { it.copy(chordBars = bars, chordMeter = meter) }
        NativeEngine.seq(NativeEngine.CMD_CHORD_LEN, bars * meter.barTicks)
    }

    fun undoDrum() = NativeEngine.seq(NativeEngine.CMD_DRUM_UNDO)
    fun undoChord() = NativeEngine.seq(NativeEngine.CMD_CHORD_UNDO)
    fun clearDrum() = NativeEngine.seq(NativeEngine.CMD_DRUM_CLEAR)
    fun clearChord() = NativeEngine.seq(NativeEngine.CMD_CHORD_CLEAR)

    fun toggleDrumLoop() {
        val on = !_ui.value.drumLoopOn
        _ui.update { it.copy(drumLoopOn = on) }
        NativeEngine.seq(NativeEngine.CMD_DRUM_PLAY, if (on) 1 else 0)
    }

    fun toggleChordLoop() {
        val on = !_ui.value.chordLoopOn
        _ui.update { it.copy(chordLoopOn = on) }
        NativeEngine.seq(NativeEngine.CMD_CHORD_PLAY, if (on) 1 else 0)
    }

    // ---- sondeo del estado nativo ----
    private fun poll() {
        val s = NativeEngine.seqStatus()
        val u = _ui.value
        val pos = s[0].toLong()
        val bt = u.meter.barTicks
        val beatTicks = 384 / u.meter.unit
        val bar = (pos / bt).toInt() + 1
        val beat = ((pos % bt) / beatTicks).toInt() + 1
        val pass = s[2].toInt()
        var fillOn = u.fillOn
        if (fillOn && pass > lastPass) {
            u.pattern?.let { p -> sendPattern(p, p.variations[u.variation.coerceIn(0, p.variations.size - 1)].tracks, false) }
            fillOn = false
        }
        var patternOn = u.patternOn
        var endPending = u.endPending
        if (endPending && s[8] < 0.5) { patternOn = false; endPending = false }
        EventHub.ghostLoopMs = if (u.chordLoopOn && s[6] > 0 && s[1] > 0.5)
            u.chordBars * u.chordMeter.barTicks * 60000.0 / (u.bpm * 96.0) else 0.0
        _ui.update {
            it.copy(
                playing = s[1] > 0.5, bar = bar, beat = beat,
                recDrum = s[3] > 0.5, recChord = s[4] > 0.5,
                drumEvents = s[5].toInt(), chordEvents = s[6].toInt(),
                fillOn = fillOn, patternOn = patternOn, endPending = endPending,
            )
        }
    }

    // ---- secuenciador por pasos ----
    fun openEditor(p: DrumPattern) {
        val target = if (p.factory) PatternRepository.duplicate(p) else p
        _ui.update { it.copy(editor = EditorState(target, 0)) }
    }

    fun closeEditor() = _ui.update { it.copy(editor = null) }

    fun editorPart(i: Int) = _ui.update { u -> u.editor?.let { u.copy(editor = it.copy(part = i)) } ?: u }

    private fun updatePattern(f: (DrumPattern) -> DrumPattern) =
        _ui.update { u -> u.editor?.let { u.copy(editor = it.copy(pattern = f(it.pattern))) } ?: u }

    private fun tracksOf(p: DrumPattern, part: Int): Map<String, String> = when (part) {
        0, 1, 2 -> p.variations[part.coerceAtMost(p.variations.size - 1)].tracks
        3 -> p.fill
        else -> p.end
    }

    fun editorToggle(key: String, step: Int) = updatePattern { p ->
        val part = _ui.value.editor?.part ?: 0
        val len = if (part >= 3) p.barSteps else p.steps
        val cur = (tracksOf(p, part)[key] ?: ".".repeat(len)).padEnd(len, '.')
        val next = cur.toCharArray().also { it[step] = Step.next(it[step]) }.concatToString()
        replaceTrack(p, part, key, next)
    }

    private fun replaceTrack(p: DrumPattern, part: Int, key: String, s: String): DrumPattern {
        fun upd(m: Map<String, String>) = (m + (key to s)).filterValues { str -> str.any { it != '.' } }
        return when (part) {
            3 -> p.copy(fill = upd(p.fill))
            4 -> p.copy(end = upd(p.end))
            else -> p.copy(variations = p.variations.mapIndexed { i, v ->
                if (i == part) v.copy(tracks = upd(v.tracks)) else v
            })
        }
    }

    fun editorName(name: String) = updatePattern { it.copy(name = name) }

    fun editorUnit(unit: Int) = updatePattern { it.copy(unit = unit) }

    /** Cambia pasos por compás y número de compases (longitud libre, compases irregulares). */
    fun editorResize(barSteps: Int, bars: Int) = updatePattern { p ->
        val bs = barSteps.coerceIn(1, 32)
        val b = bars.coerceIn(1, 8)
        val n = bs * b
        fun fit(s: String, len: Int) = s.take(len).padEnd(len, '.')
        p.copy(
            barSteps = bs, bars = b,
            variations = p.variations.map { v -> v.copy(tracks = v.tracks.mapValues { fit(it.value, n) }) },
            fill = p.fill.mapValues { fit(it.value, bs) },
            end = p.end.mapValues { fit(it.value, bs) },
        )
    }

    fun editorMeter(beats: Int, unit: Int) = updatePattern { it.copy(beats = beats, beatUnit = unit, meter = "$beats/$unit") }

    fun editorAddVariation() = updatePattern { p ->
        if (p.variations.size >= 3) p else p.copy(variations = p.variations + Variation(listOf("A", "B", "C")[p.variations.size], p.variations[0].tracks))
    }

    /** Aplica la base editada al motor para probarla. */
    fun editorTry() {
        val p = _ui.value.editor?.pattern ?: return
        sendPattern(p, p.variations[0].tracks, restart = true)
        _ui.update { it.copy(patternId = p.id, variation = 0) }
        if (!_ui.value.patternOn) togglePattern()
    }

    fun editorSave() {
        val e = _ui.value.editor ?: return
        PatternRepository.saveUser(app, e.pattern)
        _ui.update { it.copy(patternId = e.pattern.id, editor = e.copy(pattern = e.pattern.copy(factory = false))) }
        sendPattern(e.pattern, e.pattern.variations[0].tracks, restart = true)
    }

    fun deleteUserPattern(id: String) {
        PatternRepository.deleteUser(app, id)
        val first = PatternRepository.all.value.firstOrNull()
        if (_ui.value.patternId == id && first != null) selectPattern(first)
    }
}
