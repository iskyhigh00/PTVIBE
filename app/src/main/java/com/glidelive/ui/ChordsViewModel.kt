package com.glidelive.ui

import androidx.lifecycle.ViewModel
import com.glidelive.audio.ChordPlayer
import com.glidelive.audio.NativeEngine
import com.glidelive.theory.Bank
import com.glidelive.theory.Chord
import com.glidelive.theory.ChordBanks
import com.glidelive.theory.ChordType
import com.glidelive.theory.PadDef
import com.glidelive.theory.Scale
import com.glidelive.theory.VoiceLeading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class TriggerMode(val label: String) { HOLD("Hold"), LATCH("Latch"), GATE("Gate") }
enum class GlideCurve(val label: String) { LINEAR("Lineal"), SMOOTH("S suave"), SLOW("Lenta al inicio") }

val INSTRUMENTS = listOf("Piano eléctrico", "Órgano", "Pad", "Cuerdas", "Lead")

/** Edición de un pad: tipo, inversión, octava y notas fijadas (tonePc -> MIDI). */
data class PadEdit(
    val type: ChordType? = null,
    val inversion: Int = 0,
    val octave: Int = 0,
    val fixed: Map<Int, Int> = emptyMap(),
) {
    val isDefault get() = type == null && inversion == 0 && octave == 0 && fixed.isEmpty()
}

data class ChordsUi(
    val key: Int = 0,
    val scale: Scale = Scale.MAJOR,
    val transpose: Int = 0,
    val bank: Bank = Bank.DIATONIC,
    val quality: ChordType? = null,
    val instrument: Int = 0,
    val glideMs: Int = 250,
    val curve: GlideCurve = GlideCurve.SMOOTH,
    val trigger: TriggerMode = TriggerMode.HOLD,
    val rangeLo: Int = 45,  // A2
    val rangeHi: Int = 72,  // C5
    val cutoff: Float = 0.8f,
    val resonance: Float = 0.15f,
    val chorus: Float = 0.35f,
    val room: Float = 0.3f,
    val volume: Float = 0.8f,
    val activeId: String? = null,
    val edits: Map<String, PadEdit> = emptyMap(),
    val editing: String? = null,
) {
    val tonic: Int get() = Math.floorMod(key + transpose, 12)

    val rows: List<List<PadDef>>
        get() = when (bank) {
            Bank.DIATONIC -> ChordBanks.diatonic(tonic, scale, quality)
            Bank.II_V_I -> ChordBanks.iiVI(transpose)
        }

    fun chordOf(def: PadDef): Chord = Chord(def.chord.rootPc, edits[def.id]?.type ?: def.chord.type)
}

class ChordsViewModel : ViewModel() {
    private val _ui = MutableStateFlow(ChordsUi())
    val ui: StateFlow<ChordsUi> = _ui

    private val player = ChordPlayer({ NativeEngine.synth(it) })

    init { push(_ui.value) }

    fun edit(f: (ChordsUi) -> ChordsUi) {
        _ui.update(f)
        push(_ui.value)
    }

    private fun push(u: ChordsUi) {
        NativeEngine.setParam(NativeEngine.P_INSTRUMENT, u.instrument.toFloat())
        NativeEngine.setParam(NativeEngine.P_GLIDE_MS, u.glideMs.toFloat())
        NativeEngine.setParam(NativeEngine.P_GLIDE_CURVE, u.curve.ordinal.toFloat())
        NativeEngine.setParam(NativeEngine.P_CHORD_VOL, u.volume)
        NativeEngine.setParam(NativeEngine.P_CUTOFF, u.cutoff)
        NativeEngine.setParam(NativeEngine.P_RESONANCE, u.resonance)
        NativeEngine.setParam(NativeEngine.P_CHORUS, u.chorus)
        NativeEngine.setParam(NativeEngine.P_SYNTH_ROOM, u.room)
    }

    private fun findPad(u: ChordsUi, id: String): PadDef? = u.rows.flatten().firstOrNull { it.id == id }

    fun padDown(id: String) {
        val u = _ui.value
        val def = findPad(u, id) ?: return
        if (u.trigger == TriggerMode.LATCH && u.activeId == id && player.sounding) {
            stopAll()
            return
        }
        val chord = u.chordOf(def)
        val e = u.edits[id] ?: PadEdit()
        val tones = chord.tones
        val inv = e.inversion.coerceIn(0, tones.size - 1)
        player.play(tones, if (inv > 0) inv else null, e.fixed, e.octave, u.rangeLo, u.rangeHi)
        _ui.update { it.copy(activeId = id) }
    }

    fun padUp(id: String) {
        val u = _ui.value
        if (u.trigger == TriggerMode.GATE && u.activeId == id) stopAll()
    }

    fun stopAll() {
        player.stopAll()
        _ui.update { it.copy(activeId = null) }
    }

    fun openEditor(id: String) = _ui.update { it.copy(editing = id) }
    fun closeEditor() = _ui.update { it.copy(editing = null) }

    fun updateEdit(id: String, f: (PadEdit) -> PadEdit) {
        _ui.update { u ->
            val e = f(u.edits[id] ?: PadEdit())
            u.copy(edits = if (e.isDefault) u.edits - id else u.edits + (id to e))
        }
    }

    /** Voicing con el que sonaría el pad ahora (para el editor de notas). */
    fun preview(id: String): Pair<List<Int>, List<Int>>? {
        val u = _ui.value
        val def = findPad(u, id) ?: return null
        val e = u.edits[id] ?: PadEdit()
        val tones = u.chordOf(def).tones
        val inv = e.inversion.coerceIn(0, tones.size - 1)
        val r = VoiceLeading.resolve(
            player.lastVoicing, tones,
            u.rangeLo + 12 * e.octave, u.rangeHi + 12 * e.octave,
            if (inv > 0) inv else null, e.fixed,
        )
        return r.notes to r.toneIndex.map { tones[it] }
    }

    override fun onCleared() = player.stopAll()
}
