package com.glidelive.audio

import com.glidelive.theory.VoiceLeading

/** Cómo se apaga el acorde: las voces se funden en las últimas notas y suenan una cola, o se cortan. */
enum class ExitMode(val label: String) { BLEND("Fundir"), CUT("Cortar") }

fun interface Cancelable { fun cancel() }

/**
 * Convierte un cambio de acorde en órdenes de glide por voz: las notas comunes quedan quietas,
 * las voces no se cruzan y, si cambia el número de voces, una se divide o dos se fusionan.
 */
class ChordPlayer(
    private val sink: (SynthCommand) -> Unit,
    /** Planificador inyectable (retardo en ms, acción); en la app lo da el ViewModel con una corrutina. */
    private val schedule: (Long, () -> Unit) -> Cancelable,
    private val maxSlots: Int = 8,
) {
    var lastVoicing: List<Int> = emptyList()
        private set
    var sounding: Boolean = false
        private set
    private var slots: IntArray = IntArray(0)
    private var tailSlots: IntArray = IntArray(0)   // voces que siguen sonando tras fundir (bajo y mediana)
    private var tailJob: Cancelable? = null

    /** Cancela la cola de salida; si quedaban voces en ella, las suelta ya para que no queden colgadas. */
    private fun flushTail() {
        tailJob?.cancel()
        tailJob = null
        for (s in tailSlots) sink(SynthCommand.Release(s))
        tailSlots = IntArray(0)
    }

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
        return apply(res)
    }

    /**
     * Toca un voicing fijo (notas MIDI, puede repetir clases de altura). Las voces glisan desde el acorde anterior
     * con la misma alineación sin cruces que el voice leading normal.
     */
    fun playVoicing(voicing: List<Int>): VoiceLeading.Result {
        val notes = voicing.sorted()
        val (links, cost) = VoiceLeading.align(lastVoicing, notes)
        return apply(VoiceLeading.Result(notes, notes.indices.toList(), links, cost))
    }

    private fun apply(res: VoiceLeading.Result): VoiceLeading.Result {
        flushTail()
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

    /**
     * CUT: ReleaseAll inmediato. BLEND: con las notas del último voicing de grave a agudo, si hay 3 o más, las voces que
     * no son el bajo ni la mediana (índice n/2) glisan hasta la mediana en max(glideMs, 600) ms y se apagan por su
     * release; el bajo y la mediana siguen sonando y se sueltan pasados [tailMs]. Con 1 o 2 voces siguen todas y se
     * sueltan tras la cola. Un play() o stopAll() posterior cancela la cola y suelta esas voces al instante.
     */
    fun stopAll(mode: ExitMode = ExitMode.CUT, glideMs: Int = 0, tailMs: Int = 1500) {
        flushTail()
        if (!sounding) return
        val notes = lastVoicing
        if (mode == ExitMode.CUT || slots.isEmpty() || slots.size != notes.size) {
            sink(SynthCommand.ReleaseAll)
        } else {
            val order = notes.indices.sortedBy { notes[it] }
            val n = order.size
            val keep: List<Int>
            if (n >= 3) {
                val mid = n / 2
                val exitMs = maxOf(glideMs, 600)
                for (k in 1 until n) {
                    if (k == mid) continue
                    sink(SynthCommand.GlideRelease(slots[order[k]], notes[order[mid]], exitMs))
                }
                keep = listOf(slots[order[0]], slots[order[mid]])
            } else {
                keep = order.map { slots[it] }
            }
            tailSlots = keep.toIntArray()
            tailJob = schedule(tailMs.toLong()) {
                for (s in tailSlots) sink(SynthCommand.Release(s))
                tailSlots = IntArray(0)
                tailJob = null
            }
        }
        sounding = false
        slots = IntArray(0)
    }
}
