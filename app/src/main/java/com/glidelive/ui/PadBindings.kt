package com.glidelive.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Asignación de teclas a pads: notas de un teclado MIDI y teclas de un teclado físico (USB / Bluetooth).
 * Se puede asignar cualquier tecla a cualquier pad (incluidos los combos) y queda guardado.
 */
object PadBindings {
    data class State(
        val midi: Map<Int, Int>,
        val keys: Map<Int, Int>,
        /** true: el teclado MIDI toca los pads asignados; false: toca el solista como antes. */
        val midiToPads: Boolean = true,
        /** Pad esperando que se toque una tecla para asignarla (null = no se está asignando). */
        val learnPad: Int? = null,
    )

    // Códigos de KeyEvent (constantes de Android, fijas).
    private const val K_A = 29
    private const val K_B = 30
    private const val K_C = 31
    private const val K_D = 32
    private const val K_E = 33
    private const val K_F = 34
    private const val K_G = 35
    private const val K_I = 37
    private const val K_M = 41
    private const val K_N = 42
    private const val K_Q = 45
    private const val K_R = 46
    private const val K_S = 47
    private const val K_T = 48
    private const val K_U = 49
    private const val K_V = 50
    private const val K_W = 51
    private const val K_X = 52
    private const val K_Y = 53
    private const val K_Z = 54
    private const val K_COMMA = 55
    private const val K_SPACE = 62
    private const val K_1 = 8

    fun defaultMidi(): Map<Int, Int> = (0 until Pads.TOTAL).associate { 36 + it to it }  // Do2 en adelante, en el orden de los pads

    fun defaultKeys(): Map<Int, Int> = mapOf(
        K_Z to Pads.KICK, K_SPACE to Pads.KICK, K_X to Pads.SNARE, K_A to Pads.RIM, K_C to Pads.HH_CLOSED, K_V to Pads.HH_OPEN,
        K_COMMA to Pads.HH_PEDAL, K_B to Pads.TOM1, K_N to Pads.TOM2, K_M to Pads.FLOOR,
        K_S to Pads.CRASH, K_D to Pads.RIDE, K_F to Pads.BELL, K_G to Pads.SPLASH,
        K_Q to Pads.GUIRO_SHORT, K_W to Pads.GUIRO_LONG, K_E to Pads.MARACAS, K_R to Pads.CONGA,
        K_T to Pads.QUINTO, K_Y to Pads.TUMBA, K_U to Pads.BONGO, K_I to Pads.COWBELL,
    ) + (0 until Pads.combos.size).associate { (K_1 + it) to (Pads.COUNT + it) }

    private val _state = MutableStateFlow(State(defaultMidi(), defaultKeys()))
    val state: StateFlow<State> = _state

    /** El teclado del teléfono toca pads solo en las pantallas con pads (evita robar teclas al escribir un nombre). */
    @Volatile var keysEnabled = false

    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString("pad_bindings", null)
        if (raw != null) runCatching {
            val o = JSONObject(raw)
            fun map(k: String): Map<Int, Int> = o.getJSONObject(k).let { j -> j.keys().asSequence().associate { it.toInt() to j.getInt(it) } }
            val m = map("midi").filterValues { it in 0 until Pads.TOTAL }
            val k = map("keys").filterValues { it in 0 until Pads.TOTAL }
            _state.value = State(m, k, o.optBoolean("midiToPads", true))
        }
    }

    private fun save() {
        val s = _state.value
        prefs?.edit()?.putString(
            "pad_bindings",
            JSONObject().put("midi", JSONObject(s.midi.mapKeys { it.key.toString() })).put("keys", JSONObject(s.keys.mapKeys { it.key.toString() }))
                .put("midiToPads", s.midiToPads).toString(),
        )?.apply()
    }

    fun padForMidi(note: Int): Int? = _state.value.let { if (it.midiToPads) it.midi[note] else null }
    fun padForKey(code: Int): Int? = _state.value.keys[code]

    fun startLearn(pad: Int?) = _state.update { it.copy(learnPad = pad) }

    /** Si se está asignando, liga la nota MIDI al pad en espera y devuelve true (la nota no debe sonar). */
    fun learnMidi(note: Int): Boolean {
        val pad = _state.value.learnPad ?: return false
        _state.update { it.copy(midi = it.midi + (note to pad), learnPad = null) }
        save()
        return true
    }

    fun learnKey(code: Int): Boolean {
        val pad = _state.value.learnPad ?: return false
        _state.update { it.copy(keys = it.keys + (code to pad), learnPad = null) }
        save()
        return true
    }

    fun clear(pad: Int) {
        _state.update { it.copy(midi = it.midi.filterValues { p -> p != pad }, keys = it.keys.filterValues { p -> p != pad }, learnPad = null) }
        save()
    }

    fun setMidiToPads(on: Boolean) { _state.update { it.copy(midiToPads = on) }; save() }

    fun reset() { _state.update { State(defaultMidi(), defaultKeys(), it.midiToPads) }; save() }

    fun notesOf(pad: Int): List<Int> = _state.value.midi.filterValues { it == pad }.keys.sorted()
    fun keysOf(pad: Int): List<Int> = _state.value.keys.filterValues { it == pad }.keys.sorted()
}
