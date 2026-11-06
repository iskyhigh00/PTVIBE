package com.glidelive.audio

import com.glidelive.theory.VoiceLeading

/**
 * Convierte un cambio de acorde en órdenes de glide por voz: las notas comunes quedan quietas,
 * las voces no se cruzan y, si cambia el número de voces, una se divide o dos se fusionan.
 */
class ChordPlayer(
    private val sink: (SynthCommand) -> Unit,
    private val maxSlots: Int = 8,
) {
    var lastVoicing: List<Int> = emptyList()
        private set
    var sounding: Boolean = false
        private set
    private var slots: IntArray = IntArray(0)

    fun play(
        tones: List<Int>,
        bassTone: Int?,
        fixed: Map<Int, Int>,
        octave: Int,
        lo: Int,
        hi: Int,
    ): VoiceLeading.Result {
        val res = VoiceLeading.resolve(
            prev = lastVoicing,
            tones = tones,
            lo = lo + 12 * octave,
            hi = hi + 12 * octave,
            bassTone = bassTone,
            fixed = fixed,
        )
        val notes = res.notes
        if (!sounding || slots.isEmpty()) {
            slots = IntArray(notes.size) { it }
            notes.forEachIndexed { j, n -> sink(SynthCommand.NoteOn(j, n)) }
        } else {
            val prevSlots = slots
            val used = HashSet<Int>()
            val newSlots = IntArray(notes.size) { -1 }
            val inUse = prevSlots.toHashSet()
            val spawns = ArrayList<Triple<Int, Int, Int>>()
            for (j in notes.indices) {
                val cand = res.links.filter { it.second == j }.map { it.first }
                val keeper = cand.firstOrNull { it !in used }
                if (keeper != null) {
                    used.add(keeper)
                    newSlots[j] = prevSlots[keeper]
                    sink(SynthCommand.GlideTo(prevSlots[keeper], notes[j]))
                } else if (cand.isNotEmpty()) {
                    val free = (0 until maxSlots).firstOrNull { it !in inUse }
                    if (free != null) {
                        inUse.add(free)
                        newSlots[j] = free
                        spawns.add(Triple(free, prevSlots[cand.first()], notes[j]))
                    }
                }
            }
            for ((s, from, midi) in spawns) sink(SynthCommand.Spawn(s, from, midi))
            for (i in prevSlots.indices) {
                if (i in used) continue
                val target = res.links.firstOrNull { it.first == i }?.second
                if (target != null) sink(SynthCommand.GlideRelease(prevSlots[i], notes[target]))
                else sink(SynthCommand.Release(prevSlots[i]))
            }
            slots = newSlots.filter { it >= 0 }.toIntArray()
        }
        lastVoicing = notes
        sounding = true
        return res
    }

    fun stopAll() {
        if (sounding) sink(SynthCommand.ReleaseAll)
        sounding = false
        slots = IntArray(0)
    }
}
