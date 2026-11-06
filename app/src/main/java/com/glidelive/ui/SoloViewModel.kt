package com.glidelive.ui

import androidx.lifecycle.ViewModel
import com.glidelive.audio.NativeEngine
import com.glidelive.theory.ScaleChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class SoloLayout(val label: String) { SURFACE("Superficie"), FINGERBOARD("Diapasón") }
enum class Expression(val label: String) { FILTER("Filtro"), VIBRATO("Vibrato"), VOLUME("Volumen") }

enum class Tuning(val label: String, val strings: List<Int>) {
    GUITAR("Guitarra (6)", listOf(40, 45, 50, 55, 59, 64)),
    VIOLIN("Violín (4)", listOf(55, 62, 69, 76)),
    FIFTHS("Quintas (4)", listOf(48, 55, 62, 69)),
    BASS("Bajo (4)", listOf(28, 33, 38, 43)),
}

data class SoloUi(
    val layout: SoloLayout = SoloLayout.SURFACE,
    val lock: Float = 0.8f,
    val followChord: Boolean = false,
    val scaleOverride: ScaleChoice? = null,
    val expression: Expression = Expression.FILTER,
    val instrument: Int = 4,
    val volume: Float = 0.8f,
    val smoothMs: Int = 20,
    val loMidi: Int = 48,   // C3
    val octaves: Int = 3,
    val bendRange: Float = 2f,
    val tuning: Tuning = Tuning.GUITAR,
)

class SoloViewModel : ViewModel() {
    private val _ui = MutableStateFlow(SoloUi())
    val ui: StateFlow<SoloUi> = _ui

    init { push(_ui.value) }

    fun edit(f: (SoloUi) -> SoloUi) {
        _ui.update(f)
        push(_ui.value)
    }

    private fun push(u: SoloUi) {
        NativeEngine.setParam(NativeEngine.P_SOLO_INSTRUMENT, u.instrument.toFloat())
        NativeEngine.setParam(NativeEngine.P_SOLO_VOL, u.volume)
        NativeEngine.setParam(NativeEngine.P_SOLO_GLIDE, u.smoothMs.toFloat())
    }
}
