package com.glidelive.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glidelive.audio.Cancelable
import com.glidelive.audio.ChordPlayer
import com.glidelive.audio.ExitMode
import com.glidelive.audio.NativeEngine
import com.glidelive.theory.Bank
import com.glidelive.theory.Chord
import com.glidelive.theory.ChordBanks
import com.glidelive.theory.CuratedBanks
import com.glidelive.theory.ChordType
import com.glidelive.theory.PadDef
import com.glidelive.theory.Scale
import com.glidelive.theory.VoiceLeading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class TriggerMode(val label: String) { HOLD("Sostener"), LATCH("Alternar"), GATE("Mientras toco") }
enum class GlideCurve(val label: String) { LINEAR("Recta"), SMOOTH("Suave"), SLOW("Arranque lento") }

val INSTRUMENTS = listOf("Piano eléctrico", "Órgano", "Pad", "Cuerdas", "Lead", "Guitarra rítmica limpia", "Guitarra rítmica con distorsión")

/** Instrumentos del solista: los de acordes más las guitarras (cuerda pulsada). */
val SOLO_INSTRUMENTS = INSTRUMENTS.take(5) + listOf("Guitarra limpia", "Guitarra wah + distorsión")

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
    /** Voces: los botones de la fila suben en escalera para intuir lo que viene. */
    val stairs: Boolean = true,
    val key: Int = 0,
    val scale: Scale = Scale.MAJOR,
    val transpose: Int = 0,
    val bank: Bank = Bank.DIATONIC,
    val quality: ChordType? = null,
    val instrument: Int = 0,
    val glideMs: Int = 250,
    val curve: GlideCurve = GlideCurve.SMOOTH,
    val trigger: TriggerMode = TriggerMode.HOLD,
    val exit: ExitMode = ExitMode.BLEND,
    val tailMs: Int = 1500,
    /** Desvanecimiento final de la salida fundida (ms). */
    val fadeMs: Int = 3000,
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
    val editMode: Boolean = false,
) {
    val tonic: Int get() = Math.floorMod(key + transpose, 12)

    /** Filas de pads: se calculan una sola vez por combinación de tono/escala/banco/calidad/transposición. */
    val rows: List<List<PadDef>> get() = PadRows.get(tonic, scale, bank, quality, transpose).rows

    fun padById(id: String): PadDef? = PadRows.get(tonic, scale, bank, quality, transpose).byId[id]

    fun chordOf(def: PadDef): Chord = Chord(def.chord.rootPc, edits[def.id]?.type ?: def.chord.type)
}

/** Memoiza las filas de pads (y el mapa id -> pad) de la última combinación pedida. */
internal object PadRows {
    class Entry(val rows: List<List<PadDef>>, val byId: Map<String, PadDef>)
    private data class Key(val tonic: Int, val scale: Scale, val bank: Bank, val quality: ChordType?, val transpose: Int)

    private var key: Key? = null
    private var entry: Entry? = null

    @Synchronized
    fun get(tonic: Int, scale: Scale, bank: Bank, quality: ChordType?, transpose: Int): Entry {
        val k = Key(tonic, scale, bank, quality, transpose)
        entry?.let { if (k == key) return it }
        val curated = bank.curated?.let { CuratedBanks.byId(it) }
        val rows = when {
            curated != null -> CuratedBanks.rows(curated, tonic)
            bank == Bank.II_V_I -> ChordBanks.iiVI(transpose)
            else -> ChordBanks.diatonic(tonic, scale, quality)
        }
        val e = Entry(rows, rows.flatten().associateBy { it.id })
        key = k
        entry = e
        return e
    }
}

class ChordsViewModel : ViewModel() {
    private val _ui = MutableStateFlow(ChordsUi())
    val ui: StateFlow<ChordsUi> = _ui

    // La cola de salida se programa en el hilo principal (corrutina del ViewModel).
    private val player = ChordPlayer({ NativeEngine.synth(it) }, { delayMs, action ->
        val job = viewModelScope.launch { delay(delayMs); action() }
        Cancelable { job.cancel() }
    })

    /** Hay un acorde diferido esperando el próximo tiempo/compás (cuantización de acordes). */
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending

    init {
        push(_ui.value); publishKey(_ui.value)
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                _pending.value = NativeEngine.seqStatus()[9] > 0.5
                delay(100)
            }
        }
    }

    /** Tonalidad y escala para el solista; los bancos C1..C8 traen su propia escala (mayor, menor, dórica). */
    private fun publishKey(u: ChordsUi) {
        val bankScale = u.bank.curated?.let { CuratedBanks.byId(it)?.scale }
        SharedHarmony.update { it.copy(tonic = u.tonic, scale = bankScale ?: u.scale) }
    }

    fun edit(f: (ChordsUi) -> ChordsUi) {
        _ui.update(f)
        push(_ui.value)
        publishKey(_ui.value)
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

    private fun findPad(u: ChordsUi, id: String): PadDef? = u.padById(id)

    fun padDown(id: String) {
        val u = _ui.value
        val def = findPad(u, id) ?: return
        if (u.trigger == TriggerMode.LATCH && u.activeId == id && player.sounding) {
            stopAll()
            return
        }
        val chord = u.chordOf(def)
        val e = u.edits[id]
        val tones = chord.tones
        val fixedVoicing = def.voicing
        if (fixedVoicing != null && (e == null || e.isDefault)) {
            // Bancos C1..C8: voicing diseñado y verificado, siempre igual (suena bien sea cual sea el anterior).
            player.playVoicing(fixedVoicing)
        } else {
            val ed = e ?: PadEdit()
            val inv = ed.inversion.coerceIn(0, tones.size - 1)
            player.play(tones, if (inv > 0) inv else null, ed.fixed, ed.octave, u.rangeLo, u.rangeHi)
        }
        SharedHarmony.update { it.copy(chordPcs = tones) }
        _ui.update { it.copy(activeId = id) }
    }

    fun padUp(id: String) {
        val u = _ui.value
        if (u.trigger == TriggerMode.GATE && u.activeId == id) stopAll()
    }

    /** [immediate]: corta ya (p. ej. al pausar la app); si no, usa la salida configurada (Fundir / Cortar). */
    fun stopAll(immediate: Boolean = false) {
        val u = _ui.value
        if (immediate || u.exit == ExitMode.CUT) player.stopAll(ExitMode.CUT)
        else player.stopAll(ExitMode.BLEND, u.glideMs, u.tailMs, u.fadeMs)
        SharedHarmony.update { it.copy(chordPcs = emptyList()) }
        _ui.update { it.copy(activeId = null) }
    }

    fun openEditor(id: String) = _ui.update { it.copy(editing = id) }
    fun closeEditor() = _ui.update { it.copy(editing = null, editMode = false) }

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

    override fun onCleared() = player.stopAll(ExitMode.CUT)
}
