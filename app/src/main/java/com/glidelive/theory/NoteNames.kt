package com.glidelive.theory

object NoteNames {
    private val flats = arrayOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")
    private val sharps = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    fun pc(midi: Int): Int = Math.floorMod(midi, 12)

    fun name(pc: Int, useSharps: Boolean = false): String =
        (if (useSharps) sharps else flats)[Math.floorMod(pc, 12)]

    /** Do central = C4 = 60. */
    fun midiName(midi: Int, useSharps: Boolean = false): String =
        name(midi, useSharps) + (Math.floorDiv(midi, 12) - 1)
}
