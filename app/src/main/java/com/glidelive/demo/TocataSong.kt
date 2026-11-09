package com.glidelive.demo

import com.glidelive.bass.Bass
import com.glidelive.bass.BassLine
import com.glidelive.drums.Compiled
import com.glidelive.drums.DrumPattern
import com.glidelive.theory.CuratedBanks

/** Estilo del bajo en una sección. */
enum class BassStyle { RIFF, PULSE, HOLD }

data class SoloNote(val step: Int, val midi: Int, val len: Int)

data class Section(
    val name: String,
    val bars: Int,
    /** Variación de la base "Última tocata" (0 = A … 3 = D). */
    val variation: Int,
    /** Un acorde por compás (símbolos del banco C17). */
    val chords: List<String>,
    val bass: BassStyle,
    val fill: Boolean = false,
    val solo: List<SoloNote> = emptyList(),
    /** Instrumento del solista durante la sección (5 guitarra limpia, 6 wah + distorsión) o null. */
    val soloInstrument: Int? = null,
    /** Guitarra rítmica de la sección: 5 limpia (verso, puentes) o 6 con distorsión (riff, coros, solos). */
    val guitar: Int = 5,
) {
    val lengthTicks: Int get() = bars * BAR
}

/** Eventos ya compilados de una sección + datos para mostrar qué suena. */
class BuiltSection(val events: Compiled, val length: Int, val bassRoots: List<Int>)

const val BAR = 384
private const val STEP = 24

/**
 * "Última tocata": forma, acordes, bajo y los dos solos sacados de la grabación (Fa# menor, 98 BPM).
 * Cada sección se convierte en un patrón con batería + bajo + acordes + solista que el motor toca con precisión de muestra.
 */
object TocataSong {
    const val BPM = 98
    const val KEY = 6  // Fa#
    const val BANK = "C17"

    /** Símbolo -> índice del pad en el banco C17 (mismo orden que en la canción). */
    val PAD_OF = mapOf("F#m" to 0, "D" to 1, "C#m" to 2, "G#m" to 3, "B" to 4, "G" to 5, "A" to 6, "F#" to 7)
    private val ROOT = mapOf("F#m" to 6, "D" to 2, "C#m" to 1, "G#m" to 8, "B" to 11, "G" to 7, "A" to 9, "F#" to 6)

    private fun n(vararg t: Int) = t.toList().chunked(3).map { SoloNote(it[0], it[1], it[2]) }

    val SOLO1 = n(
        0, 66, 7, 8, 66, 2, 12, 68, 3, 15, 69, 4, 20, 68, 3, 23, 68, 2, 25, 68, 2, 28, 69, 4,
        36, 66, 4, 44, 64, 6, 63, 64, 2, 69, 66, 2, 72, 66, 3, 77, 69, 6, 84, 68, 3, 88, 68, 4,
        94, 71, 4, 100, 73, 2, 102, 73, 2, 105, 73, 2, 108, 73, 4, 125, 76, 6, 132, 78, 5,
        137, 66, 2, 139, 68, 2, 141, 69, 6, 149, 68, 2, 153, 68, 2, 156, 69, 4,
    )
    val SOLO2 = n(
        1, 66, 23, 34, 66, 2, 42, 68, 22, 64, 68, 2, 70, 69, 2, 73, 68, 2, 76, 68, 3, 80, 68, 2,
        82, 68, 10, 92, 69, 4, 97, 71, 9, 107, 73, 6, 121, 68, 7, 133, 66, 5, 139, 64, 3,
        143, 66, 4, 147, 68, 3, 151, 69, 2, 153, 68, 7,
    )

    private fun ch(s: String) = s.trim().split(Regex("\\s+"))
    private val CHORUS = ch("D D F#m F#m D D F#m F#m D D F#m F#m D D")

    val sections = listOf(
        Section("Intro", 4, 2, ch("F#m F#m F#m F#m"), BassStyle.HOLD),
        Section("Verso", 14, 2, CHORUS, BassStyle.PULSE),
        Section("Pre-coro", 4, 1, ch("C#m G#m B D"), BassStyle.PULSE, fill = true),
        Section("Riff", 12, 0, List(12) { "F#m" }, BassStyle.RIFF, fill = true, guitar = 6),
        Section("Puente", 8, 2, ch("C#m G#m B G G G G G"), BassStyle.HOLD, fill = true),
        Section("Solo 1 · limpio", 10, 0, List(10) { "F#m" }, BassStyle.RIFF, fill = true, solo = SOLO1, soloInstrument = 5, guitar = 6),
        Section("Coro", 14, 1, CHORUS, BassStyle.RIFF, fill = true, guitar = 6),
        Section("Riff", 8, 0, List(8) { "F#m" }, BassStyle.RIFF, guitar = 6),
        Section("Puente", 8, 2, ch("C#m G#m B G C#m G#m B G"), BassStyle.HOLD, fill = true),
        Section("Solo 2 · wah", 10, 3, List(10) { "F#m" }, BassStyle.RIFF, fill = true, solo = SOLO2, soloInstrument = 6, guitar = 6),
        Section("Coro final", 14, 3, CHORUS, BassStyle.RIFF, fill = true, guitar = 6),
        Section("Final", 3, 2, ch("F# F# F#"), BassStyle.HOLD, guitar = 6),
    )

    /** Voicing (sin la nota más grave: de eso se encarga el bajo) del acorde en el banco C17. */
    fun voicing(symbol: String): List<Int> {
        val bank = CuratedBanks.byId(BANK) ?: return emptyList()
        val pad = bank.pads[PAD_OF[symbol] ?: 0]
        return pad.voicing.sorted().drop(1)
    }

    /** Voicing de guitarra: cuerdas al aire graves (fundamental, quinta, octava, tercera, quinta) como un acorde de guitarra. */
    fun guitarVoicing(symbol: String): List<Int> {
        val pc = ROOT[symbol] ?: 6
        val r = 38 + Math.floorMod(pc - 2, 12)
        val third = if (symbol.endsWith("m")) 15 else 16
        return listOf(r, r + 7, r + 12, r + third, r + 19)
    }

    /** Rasgueos de un compás: (paso de semicorchea, largo en pasos, fuerza). */
    private val STRUM = listOf(0 to 0.95f, 2 to 0.7f, 4 to 0.85f, 6 to 0.7f, 8 to 0.95f, 10 to 0.7f, 12 to 0.85f, 14 to 0.7f)

    /** Fundamental del bajo para el acorde: entre Fa#1 y Fa2. */
    fun bassRoot(symbol: String): Int = 30 + Math.floorMod((ROOT[symbol] ?: 6) - 6, 12)

    private fun velPack(lenTicks: Int, vel: Float) = lenTicks.toFloat() + vel.coerceIn(0.01f, 0.99f)

    fun build(s: Section, pattern: DrumPattern, line: BassLine?): BuiltSection {
        val t = ArrayList<Int>(); val p = ArrayList<Int>(); val v = ArrayList<Float>()
        fun add(tick: Int, pad: Int, vel: Float) { t += tick; p += pad; v += vel }

        // Batería: compás a compás desde la variación; el último puede ser el relleno.
        val tracks = pattern.variations[s.variation.coerceIn(0, pattern.variations.size - 1)].tracks
        for (b in 0 until s.bars) {
            val last = s.fill && b == s.bars - 1
            val barTracks = if (last) pattern.fill else tracks.mapValues { (_, str) ->
                val from = (b % pattern.bars) * pattern.barSteps
                str.substring(from, minOf(str.length, from + pattern.barSteps))
            }
            val c = DrumPattern.compileTracks(barTracks, pattern.unit)
            for (i in c.ticks.indices) add(c.ticks[i] + b * BAR, c.pads[i], c.vels[i])
        }
        if (s.variation != 2 && tracks["cr"]?.firstOrNull() != 'X') add(0, 9, 0.9f)  // crash al entrar la sección

        // Bajo.
        val roots = s.chords.map { bassRoot(it) }
        var run = 0
        for (b in 0 until s.bars) {
            val sym = s.chords[b]
            run = if (b > 0 && s.chords[b - 1] == sym) run + 1 else 0
            val base = b * BAR
            val riffVar = when (sym) { "F#m" -> 0; "D" -> 1; else -> -1 }
            if (s.bass == BassStyle.RIFF && line != null && riffVar >= 0) {
                val lv = line.variation(riffVar)
                val from = (run % lv.bars) * line.barSteps
                for (nt in lv.notes) if (nt.step in from until from + line.barSteps) {
                    add(base + (nt.step - from) * line.unit, Bass.PAD_BASE + nt.midi, velPack(nt.len * line.unit, nt.vel))
                }
            } else if (s.bass == BassStyle.HOLD) {
                add(base, Bass.PAD_BASE + roots[b], velPack(BAR - 12, 0.85f))
            } else {
                for (e in 0 until 8) add(base + e * 48, Bass.PAD_BASE + roots[b], velPack(44, if (e % 4 == 0) 0.95f else if (e % 2 == 0) 0.8f else 0.55f))
            }
        }

        // Guitarra rítmica: rasgueos de corchea en cada compás (cada rasgueo vuelve a tocar las cuerdas del acorde).
        for (bar in 0 until s.bars) {
            val notes = guitarVoicing(s.chords[bar])
            for ((step, vel) in STRUM) {
                notes.forEachIndexed { slot, midi ->
                    add(bar * BAR + step * STEP, com.glidelive.audio.NativeEngine.CHORD_PAD_BASE + slot * 128 + midi, velPack(2 * STEP - 4, vel))
                }
            }
        }

        // Solista.
        for (nt in s.solo) add(nt.step * STEP, com.glidelive.audio.NativeEngine.SOLO_PAD_BASE + nt.midi, velPack(nt.len * STEP, 0.9f))

        return BuiltSection(Compiled(t.toIntArray(), p.toIntArray(), v.toFloatArray()), s.lengthTicks, roots)
    }

    /** Cierre: golpe con crash y Fa# mayor sostenido un compás. */
    fun end(): Compiled {
        val t = ArrayList<Int>(); val p = ArrayList<Int>(); val v = ArrayList<Float>()
        t += 0; p += 0; v += 1f
        t += 0; p += 9; v += 1f
        t += 0; p += Bass.PAD_BASE + bassRoot("F#"); v += velPack(BAR - 12, 0.95f)
        guitarVoicing("F#").forEachIndexed { slot, midi ->
            t += 0; p += com.glidelive.audio.NativeEngine.CHORD_PAD_BASE + slot * 128 + midi; v += velPack(BAR, 0.8f)
        }
        return Compiled(t.toIntArray(), p.toIntArray(), v.toFloatArray())
    }

    /** Acorde que suena en el tick [tick] de la sección. */
    fun chordAt(s: Section, tick: Int): String = s.chords[(tick / BAR).coerceIn(0, s.bars - 1)]

    /** Nota del solista que suena en [tick] (o null). */
    fun soloAt(s: Section, tick: Int): Int? = s.solo.firstOrNull { tick >= it.step * STEP && tick < (it.step + it.len) * STEP }?.midi
}
