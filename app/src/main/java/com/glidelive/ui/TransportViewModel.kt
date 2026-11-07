package com.glidelive.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glidelive.audio.NativeEngine
import com.glidelive.bass.Bass
import com.glidelive.bass.BassLine
import com.glidelive.demo.BAR
import com.glidelive.demo.TocataSong
import com.glidelive.drums.Compiled
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
import kotlinx.coroutines.withContext

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
enum class EditPart(val label: String) { A("A"), B("B"), C("C"), D("D"), FILL("Relleno"), END("Final") }

/** Cuándo entra un cambio de variación: en el siguiente compás de la base o al instante. */
enum class VariationSwap(val label: String) { BAR("Al compás"), NOW("Inmediato") }

/** Demo de "Última tocata": sección que suena, posición dentro de ella y si está activa. */
data class DemoUi(val active: Boolean = false, val section: Int = 0, val tick: Int = 0, val starting: Boolean = false)

data class EditorState(val pattern: DrumPattern, val part: Int = 0)

data class TransportUi(
    val bpm: Int = 100,
    val meter: Meter = Meter(4, 4),
    val swing: Float = 0f,
    val metro: Boolean = false,
    val metroVol: Float = 0.6f,
    val playing: Boolean = false,
    val patterns: List<DrumPattern> = emptyList(),
    val patternId: String? = null,
    val variation: Int = 0,
    /** Variación en espera del próximo compás (null = ninguna). */
    val pendingVariation: Int? = null,
    val variationSwap: VariationSwap = VariationSwap.BAR,
    val patternOn: Boolean = false,
    val fillOn: Boolean = false,
    /** Break en curso (índice) o null si el último compás alternativo es el relleno. */
    val activeBreak: Int? = null,
    val endPending: Boolean = false,
    val muted: Set<Int> = emptySet(),
    val solo: Int? = null,
    val adoptPattern: Boolean = true,
    val recDrum: Boolean = false,
    val recChord: Boolean = false,
    val drumBars: Int = 1,
    val drumMeter: Meter = Meter(4, 4),
    val chordBars: Int = 4,
    val chordMeter: Meter = Meter(4, 4),
    val recQuant: RecQuant = RecQuant.FREE,
    val chordQuant: ChordQuant = ChordQuant.OFF,
    val drumLoopOn: Boolean = true,
    val chordLoopOn: Boolean = true,
    val editor: EditorState? = null,
    val bassLines: List<BassLine> = emptyList(),
    val bassId: String? = null,
    val bassOn: Boolean = true,
    /** Tono del bajo (0 = Do … 11 = Si). */
    val bassKey: Int = 6,
    val bassVol: Float = 0.8f,
    val bassTone: Float = 0.5f,
) {
    val pattern: DrumPattern? get() = patterns.firstOrNull { it.id == patternId }
    val bassLine: BassLine? get() = bassLines.firstOrNull { it.id == bassId }
}

/** Posición y contadores que cambian a cada tiempo: viven aparte para no recomponer toda la barra. */
data class TransportPos(val bar: Int = 1, val beat: Int = 1, val drumEvents: Int = 0, val chordEvents: Int = 0)

class TransportViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(TransportUi())
    val ui: StateFlow<TransportUi> = _ui

    private val _pos = MutableStateFlow(TransportPos())
    val pos: StateFlow<TransportPos> = _pos

    private val _demo = MutableStateFlow(DemoUi())
    val demo: StateFlow<DemoUi> = _demo
    @Volatile private var demoQueued = -1
    @Volatile private var demoSwapBase = 0
    @Volatile private var demoEndSent = false

    private val taps = ArrayList<Long>()
    @Volatile private var lastPass = 0
    /** Valor del contador nativo de cambios cuando se encoló la variación pendiente. */
    @Volatile private var swapBase = 0

    init {
        PatternRepository.load(app)
        Bass.load(app)
        val list = PatternRepository.all.value
        val first = list.firstOrNull()
        val firstBass = Bass.lines.firstOrNull { it.id == first?.bass }
        _ui.update {
            it.copy(
                patterns = list, patternId = first?.id, bassLines = Bass.lines,
                bassId = firstBass?.id ?: Bass.lines.firstOrNull()?.id, bassKey = firstBass?.root ?: it.bassKey,
            )
        }
        push()
        first?.let { sendPattern(it, 0, restart = true) }
        viewModelScope.launch(Dispatchers.Default) {
            PatternRepository.all.collect { l -> _ui.update { it.copy(patterns = l) } }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) { poll(); delay(if (_ui.value.playing) 40L else 100L) }
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
        NativeEngine.setParam(NativeEngine.P_BASS_VOL, u.bassVol)
        NativeEngine.setParam(NativeEngine.P_BASS_TONE, u.bassTone)
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
    private fun activeBass(): BassLine? = _ui.value.let { if (it.bassOn) it.bassLine else null }

    /** Batería de la variación [v] (con [last] en la última vuelta: relleno o break) más el bajo de esa variación. */
    private fun build(p: DrumPattern, v: Int, last: Map<String, String>? = null): Pair<Compiled, Int> {
        val vi = v.coerceIn(0, p.variations.size - 1)
        val normal = p.compile(p.variations[vi].tracks)
        val lastC = last?.let { p.compile(it) } ?: normal
        val line = activeBass() ?: return Bass.merge(normal, lastC, p.lengthTicks, null, 0)
        val bv = line.variation(vi)
        val bass = Bass.compile(bv.notes, line.unit, Bass.shift(line.root, _ui.value.bassKey))
        return Bass.merge(normal, lastC, p.lengthTicks, bass, line.lengthTicks(bv))
    }

    private fun sendPattern(p: DrumPattern, v: Int, restart: Boolean, last: Map<String, String>? = null) {
        val (c, len) = build(p, v, last)
        NativeEngine.setPattern(c.ticks, c.pads, c.vels, len, restart, false)
        val e = p.compileEnd()
        val line = activeBass()
        val (ec, elen) = if (line != null && line.end.isNotEmpty()) {
            Bass.merge(e, e, p.barTicks, Bass.compile(line.end, line.unit, Bass.shift(line.root, _ui.value.bassKey)), p.barTicks)
        } else e to p.barTicks
        NativeEngine.setEndPattern(ec.ticks, ec.pads, ec.vels, elen)
    }

    /** Reenvía la base actual con el bajo nuevo (sin reiniciar el compás). */
    private fun resendBass() {
        if (_demo.value.active) return
        val u = _ui.value
        val p = u.pattern ?: return
        if (u.pendingVariation != null) NativeEngine.cancelQueuedPattern()
        _ui.update { it.copy(pendingVariation = null, fillOn = false, activeBreak = null) }
        sendPattern(p, u.pendingVariation ?: u.variation, restart = false)
        u.pendingVariation?.let { v -> _ui.update { it.copy(variation = v) } }
    }

    fun selectBass(id: String?) {
        val line = _ui.value.bassLines.firstOrNull { it.id == id }
        _ui.update { it.copy(bassId = line?.id ?: it.bassId, bassOn = line != null) }
        resendBass()
    }

    /** Carga de escena / autoguardado del bajo (los null no cambian). */
    fun applyBass(id: String?, on: Boolean?, key: Int?, vol: Float?, tone: Float?) {
        _ui.update { u ->
            u.copy(
                bassId = u.bassLines.firstOrNull { it.id == id }?.id ?: u.bassId, bassOn = on ?: u.bassOn,
                bassKey = key?.let { Math.floorMod(it, 12) } ?: u.bassKey,
                bassVol = vol ?: u.bassVol, bassTone = tone ?: u.bassTone,
            )
        }
        push()
        resendBass()
    }

    fun toggleBass() { _ui.update { it.copy(bassOn = !it.bassOn) }; resendBass() }

    fun setBassKey(k: Int) { _ui.update { it.copy(bassKey = Math.floorMod(k, 12)) }; resendBass() }

    // ---- demo de la canción ----
    private fun tocata(): DrumPattern? = _ui.value.patterns.firstOrNull { it.id == "tocata" }

    private fun buildDemo(i: Int) = TocataSong.build(TocataSong.sections[i], tocata()!!, Bass.lines.firstOrNull { it.id == "tocata" })

    /** Toca la canción desde la sección [from] (batería, bajo, acordes y solos) encadenando las secciones al compás. */
    fun startDemo(from: Int = 0) {
        val p = tocata() ?: return
        val idx = from.coerceIn(0, TocataSong.sections.size - 1)
        NativeEngine.cancelQueuedPattern()
        demoQueued = -1
        demoEndSent = false
        edit { it.copy(bpm = TocataSong.BPM, meter = Meter(4, 4), swing = 0f) }
        val sec = TocataSong.sections[idx]
        _ui.update {
            it.copy(
                patternId = p.id, variation = sec.variation, pendingVariation = null, fillOn = false, activeBreak = null,
                endPending = false, bassId = "tocata", bassOn = true, bassKey = TocataSong.KEY,
            )
        }
        val b = buildDemo(idx)
        NativeEngine.setPattern(b.events.ticks, b.events.pads, b.events.vels, b.length, true, false)
        val e = TocataSong.end()
        NativeEngine.setEndPattern(e.ticks, e.pads, e.vels, BAR)
        _demo.value = DemoUi(active = true, section = idx, tick = 0, starting = true)
        if (!_ui.value.patternOn) {
            _ui.update { it.copy(patternOn = true) }
            NativeEngine.seq(NativeEngine.CMD_PATTERN_PLAY, 1)
        }
        if (!_ui.value.playing) play()
    }

    fun stopDemo() {
        if (!_demo.value.active) return
        endDemo()
        stop()
    }

    /** Deja la demo y vuelve a la base normal (sin cortar el transporte). */
    private fun endDemo() {
        if (!_demo.value.active) return
        _demo.value = DemoUi()
        if (demoQueued >= 0) NativeEngine.cancelQueuedPattern()
        demoQueued = -1
        tocata()?.let { sendPattern(it, _ui.value.variation, restart = false) }
    }

    private fun demoTick(s: DoubleArray) {
        val d = _demo.value
        if (!d.active) return
        val playing = s[1] > 0.5
        val patOn = s[8] > 0.5
        if (d.starting) {
            if (playing && patOn) _demo.value = d.copy(starting = false)
            return
        }
        if (!playing || !patOn) {  // se paró o terminó el final
            _demo.value = DemoUi()
            demoQueued = -1
            tocata()?.let { sendPattern(it, 0, restart = true) }
            if (demoEndSent && playing) stop()
            demoEndSent = false
            return
        }
        var section = d.section
        if (demoQueued >= 0 && s[10].toInt() != demoSwapBase) {
            section = demoQueued
            demoQueued = -1
            _ui.update { it.copy(variation = TocataSong.sections[section].variation) }
        }
        val local = s[7].toInt()
        val len = TocataSong.sections[section].lengthTicks
        if (demoQueued < 0 && local > 24 && section + 1 < TocataSong.sections.size) {
            val b = buildDemo(section + 1)
            demoSwapBase = s[10].toInt()
            demoQueued = section + 1
            NativeEngine.queuePattern(b.events.ticks, b.events.pads, b.events.vels, b.length, len)
        } else if (section == TocataSong.sections.size - 1 && !demoEndSent && local > 24) {
            demoEndSent = true
            NativeEngine.seq(NativeEngine.CMD_PATTERN_END)
            _ui.update { it.copy(endPending = true) }
        }
        _demo.value = d.copy(section = section, tick = Math.floorMod(local, len))
    }

    fun selectPattern(p: DrumPattern, adopt: Boolean = _ui.value.adoptPattern) {
        endDemo()
        _ui.update { it.copy(patternId = p.id, variation = 0, pendingVariation = null, fillOn = false, activeBreak = null, endPending = false) }
        NativeEngine.cancelQueuedPattern()
        if (adopt) {
            edit { it.copy(bpm = p.bpmDefault, meter = Meter(p.beats, p.beatUnit)) }
        }
        p.bass?.let { id ->
            Bass.lines.firstOrNull { it.id == id }?.let { line -> _ui.update { it.copy(bassId = line.id, bassOn = true, bassKey = line.root) } }
        }
        sendPattern(p, 0, restart = true)
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

    /**
     * Con "Al compás" (y la base sonando) la variación se encola y entra exactamente en el siguiente compás de la
     * base, igual que el relleno y el final; con "Inmediato" (o sin base sonando) se aplica al instante.
     */
    fun selectVariation(i: Int) {
        endDemo()
        val u = _ui.value
        val p = u.pattern ?: return
        val idx = i.coerceIn(0, p.variations.size - 1)
        val queue = u.variationSwap == VariationSwap.BAR && u.patternOn && u.playing && !u.fillOn
        if (!queue) {
            if (u.pendingVariation != null) NativeEngine.cancelQueuedPattern()
            _ui.update { it.copy(variation = idx, pendingVariation = null, fillOn = false) }
            sendPattern(p, idx, restart = false)
            return
        }
        if (idx == u.variation) {  // volver a la activa cancela la espera
            if (u.pendingVariation != null) {
                NativeEngine.cancelQueuedPattern()
                _ui.update { it.copy(pendingVariation = null) }
            }
            return
        }
        if (u.pendingVariation == idx) return
        val (c, len) = build(p, idx)
        swapBase = NativeEngine.seqStatus()[10].toInt()
        _ui.update { it.copy(pendingVariation = idx) }  // reemplaza a la que hubiera en espera
        NativeEngine.queuePattern(c.ticks, c.pads, c.vels, len, p.barTicks)
    }

    fun togglePattern() {
        val on = !_ui.value.patternOn
        _ui.update { it.copy(patternOn = on, endPending = false) }
        NativeEngine.seq(NativeEngine.CMD_PATTERN_PLAY, if (on) 1 else 0)
        if (on && !_ui.value.playing) play()
    }

    /**
     * Relleno en el último compás: se vuelve a la variación al dar la vuelta. Si hay una variación en espera, el
     * relleno se hace sobre ella y pasa a ser la activa ahora mismo (se cancela la cola nativa para que el cambio
     * pendiente no pise el relleno ni la reversión).
     */
    fun fill() = lastBar(null)

    /** Break [i]: último compás alternativo (parada, golpes desplazados o toms); vuelve a la variación al dar la vuelta. */
    fun breakNow(i: Int) = lastBar(i)

    private fun lastBar(breakIndex: Int?) {
        if (_demo.value.active) return
        val u = _ui.value
        val p = u.pattern ?: return
        if (!u.patternOn) return
        if (breakIndex != null && breakIndex !in p.breaks.indices) return
        val v = u.pendingVariation ?: u.variation
        if (u.pendingVariation != null) NativeEngine.cancelQueuedPattern()
        sendPattern(p, v, restart = false, last = if (breakIndex == null) p.withFill(v) else p.withBreak(v, breakIndex))
        lastPass = NativeEngine.seqStatus()[2].toInt()
        _ui.update { it.copy(variation = v, pendingVariation = null, fillOn = true, activeBreak = breakIndex) }
    }

    /** Final: entra justo al terminar el patrón y la base se detiene. */
    fun finish() {
        if (_demo.value.active) { endDemo(); }
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
        val snap = _ui.value
        val pos = s[0].toLong()
        val bt = snap.meter.barTicks
        val beatTicks = 384 / snap.meter.unit
        _pos.value = TransportPos(
            bar = (pos / bt).toInt() + 1,
            beat = ((pos % bt) / beatTicks).toInt() + 1,
            drumEvents = s[5].toInt(), chordEvents = s[6].toInt(),
        )
        demoTick(s)
        val pass = s[2].toInt()
        EventHub.ghostLoopMs = if (snap.chordLoopOn && s[6] > 0 && s[1] > 0.5)
            snap.chordBars * snap.chordMeter.barTicks * 60000.0 / (snap.bpm * 96.0) else 0.0
        // El cálculo parte del estado más reciente (sin copias obsoletas); el efecto lateral se hace después.
        var revert: DrumPattern? = null
        var revertVariation = 0
        _ui.update { u ->
            revert = null
            var fillOn = u.fillOn
            if (fillOn && pass > lastPass) {
                u.pattern?.let { revert = it; revertVariation = u.variation }
                fillOn = false
            }
            var variation = u.variation
            var pendingV = u.pendingVariation
            if (pendingV != null && (s[10].toInt() != swapBase || s[1] < 0.5 || s[8] < 0.5)) {
                // El motor aplicó el cambio (o la base se paró y entró de inmediato): la espera termina.
                variation = pendingV
                pendingV = null
            }
            var patternOn = u.patternOn
            var endPending = u.endPending
            if (endPending && s[8] < 0.5) { patternOn = false; endPending = false }
            u.copy(
                playing = s[1] > 0.5,
                recDrum = s[3] > 0.5, recChord = s[4] > 0.5,
                fillOn = fillOn, activeBreak = if (fillOn) u.activeBreak else null, patternOn = patternOn, endPending = endPending,
                variation = variation, pendingVariation = pendingV,
            )
        }
        revert?.let { p -> sendPattern(p, revertVariation, false) }
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
        0, 1, 2, 3 -> p.variations[part.coerceAtMost(p.variations.size - 1)].tracks
        4 -> p.fill
        else -> p.end
    }

    fun editorToggle(key: String, step: Int) = updatePattern { p ->
        val part = _ui.value.editor?.part ?: 0
        val len = if (part >= 4) p.barSteps else p.steps
        val cur = (tracksOf(p, part)[key] ?: ".".repeat(len)).padEnd(len, '.')
        val next = cur.toCharArray().also { it[step] = Step.next(it[step]) }.concatToString()
        replaceTrack(p, part, key, next)
    }

    private fun replaceTrack(p: DrumPattern, part: Int, key: String, s: String): DrumPattern {
        fun upd(m: Map<String, String>) = (m + (key to s)).filterValues { str -> str.any { it != '.' } }
        return when (part) {
            4 -> p.copy(fill = upd(p.fill))
            5 -> p.copy(end = upd(p.end))
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
        if (p.variations.size >= 4) p else p.copy(variations = p.variations + Variation(listOf("A", "B", "C", "D")[p.variations.size], p.variations[0].tracks))
    }

    /** Aplica la base editada al motor para probarla. */
    fun editorTry() {
        val p = _ui.value.editor?.pattern ?: return
        sendPattern(p, 0, restart = true)
        _ui.update { it.copy(patternId = p.id, variation = 0) }
        if (!_ui.value.patternOn) togglePattern()
    }

    fun editorSave() {
        val e = _ui.value.editor ?: return
        viewModelScope.launch(Dispatchers.IO) { PatternRepository.saveUser(app, e.pattern) }
        _ui.update { it.copy(patternId = e.pattern.id, editor = e.copy(pattern = e.pattern.copy(factory = false))) }
        sendPattern(e.pattern, 0, restart = true)
    }

    fun deleteUserPattern(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { PatternRepository.deleteUser(app, id) }
            val first = PatternRepository.all.value.firstOrNull()
            if (_ui.value.patternId == id && first != null) selectPattern(first)
        }
    }
}
