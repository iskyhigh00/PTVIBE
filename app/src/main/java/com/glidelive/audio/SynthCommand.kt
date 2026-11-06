package com.glidelive.audio

/** Órdenes hacia el sintetizador nativo (cada voz = un slot). */
sealed interface SynthCommand {
    data class NoteOn(val slot: Int, val midi: Int) : SynthCommand
    data class GlideTo(val slot: Int, val midi: Int) : SynthCommand
    data class Spawn(val slot: Int, val fromSlot: Int, val midi: Int) : SynthCommand
    /** glideMs = 0: usa el glide global; > 0: duración propia (p. ej. al fundir las voces en la salida). */
    data class GlideRelease(val slot: Int, val midi: Int, val glideMs: Int = 0) : SynthCommand
    data class Release(val slot: Int) : SynthCommand
    data object ReleaseAll : SynthCommand
}
