package com.glidelive.audio

/** Órdenes hacia el sintetizador nativo (cada voz = un slot). */
sealed interface SynthCommand {
    data class NoteOn(val slot: Int, val midi: Int) : SynthCommand
    data class GlideTo(val slot: Int, val midi: Int) : SynthCommand
    data class Spawn(val slot: Int, val fromSlot: Int, val midi: Int) : SynthCommand
    data class GlideRelease(val slot: Int, val midi: Int) : SynthCommand
    data class Release(val slot: Int) : SynthCommand
    data object ReleaseAll : SynthCommand
}
