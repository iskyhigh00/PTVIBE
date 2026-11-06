package com.glidelive.theory

import kotlin.math.abs
import kotlin.math.floor

data class HarmonyState(
    val tonic: Int = 0,
    val scale: Scale = Scale.MAJOR,
    /** Clases de altura del acorde que suena (vacío si no suena ninguno). */
    val chordPcs: List<Int> = emptyList(),
)

data class ScaleChoice(val label: String, val steps: List<Int>) {
    companion object {
        val all: List<ScaleChoice> = Scale.entries.map { ScaleChoice(it.label, it.steps.toList()) } + listOf(
            ScaleChoice("Pentatónica menor", listOf(0, 3, 5, 7, 10)),
            ScaleChoice("Pentatónica mayor", listOf(0, 2, 4, 7, 9)),
            ScaleChoice("Blues", listOf(0, 3, 5, 6, 7, 10)),
            ScaleChoice("Cromática (todas)", (0..11).toList()),
        )
    }
}

object SoloPitch {
    /** Notas permitidas: las del acorde que suena (si se sigue) o las de la escala elegida. */
    fun allowed(h: HarmonyState, followChord: Boolean, override: ScaleChoice?): Set<Int> {
        if (followChord && h.chordPcs.isNotEmpty()) return h.chordPcs.map { Math.floorMod(it, 12) }.toSet()
        val steps = override?.steps ?: h.scale.steps.toList()
        return steps.map { Math.floorMod(h.tonic + it, 12) }.toSet()
    }

    /** Nota entera permitida más cercana a [midi] (a igual distancia, la más grave). */
    fun nearestAllowed(midi: Float, allowed: Set<Int>): Float {
        if (allowed.isEmpty()) return midi
        val base = floor(midi).toInt()
        var best = Float.NaN
        var bestD = Float.MAX_VALUE
        for (n in base - 7..base + 8) {
            if (Math.floorMod(n, 12) !in allowed) continue
            val d = abs(n - midi)
            if (d < bestD) { bestD = d; best = n.toFloat() }
        }
        return if (best.isNaN()) midi else best
    }

    /** Imán de escala: [lock] 0 = libre, 1 = salta a la nota de la escala más cercana. */
    fun applyLock(free: Float, allowed: Set<Int>, lock: Float): Float {
        val l = lock.coerceIn(0f, 1f)
        if (l <= 0f) return free
        val snapped = nearestAllowed(free, allowed)
        return snapped + (free - snapped) * (1f - l)
    }

    fun xToMidi(xNorm: Float, lo: Int, octaves: Int): Float = lo + xNorm.coerceIn(0f, 1f) * 12f * octaves
}

/**
 * Detecta un temblor lateral leve del dedo (vibrato): componente rápida de la posición x.
 * Un deslizamiento largo (desfase grande) no cuenta como vibrato.
 */
class WiggleVibrato(private val density: Float) {
    private var lp = Float.NaN
    private var vib = 0f

    fun reset(x: Float) { lp = x; vib = 0f }

    /** @return profundidad de vibrato 0..1. */
    fun update(x: Float): Float {
        if (lp.isNaN()) lp = x
        lp += (x - lp) * 0.15f
        val hf = abs(x - lp) / density
        val target = when {
            hf < 2f -> 0f
            hf < 16f -> ((hf - 2f) / 8f).coerceAtMost(1f)
            else -> 0f
        }
        vib += (target - vib) * 0.2f
        return vib
    }
}
