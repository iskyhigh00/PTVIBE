package com.glidelive.princess

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cambia el tono de una voz SIN cambiar su duración: se remuestrea (sube o baja el tono y la velocidad) y luego se estira
 * o comprime el tiempo con superposición de granos alineados por correlación (WSOLA) para volver al largo original.
 * Es para grabaciones propias; no sustituye a las voces neuronales.
 */
object PitchShifter {
    /** Pasos de la escala: 0 es "Normal"; cada paso son 2 semitonos. */
    const val SEMITONES_PER_STEP = 2

    fun semitonesFor(steps: Int) = steps.coerceIn(-2, 2) * SEMITONES_PER_STEP

    fun shift(input: ShortArray, rate: Int, semitones: Float): ShortArray {
        if (semitones == 0f || input.size < rate / 4) return input
        val f = 2.0.pow(semitones / 12.0)
        // 1) remuestreo: el audio dura n / f y su tono sube f veces
        val n2 = (input.size / f).toInt()
        val resampled = ShortArray(n2) { i ->
            val pos = i * f
            val i0 = pos.toInt().coerceAtMost(input.size - 1)
            val i1 = min(i0 + 1, input.size - 1)
            val fr = pos - i0
            (input[i0] + (input[i1] - input[i0]) * fr).roundToInt().toShort()
        }
        // 2) estirar el tiempo f veces para recuperar la duración
        return stretch(resampled, rate, f, input.size)
    }

    private fun stretch(input: ShortArray, rate: Int, factor: Double, targetLength: Int): ShortArray {
        val window = rate * 40 / 1000
        val hop = window / 2
        if (input.size < window * 2) return input.copyOf(targetLength)
        val length = targetLength
        val sums = DoubleArray(length + window)
        val weights = DoubleArray(sums.size)
        val search = rate / 200
        var destination = 0
        while (destination < length) {
            val expected = (destination / factor).roundToInt().coerceIn(0, input.size - window)
            var best = expected
            if (destination > 0) {
                var bestScore = -Double.MAX_VALUE
                for (candidate in max(0, expected - search)..min(input.size - window, expected + search) step 2) {
                    var dot = 0.0; var ea = 1.0; var eb = 1.0
                    for (i in 0 until hop step 4) {
                        val w = weights[destination + i]
                        val a = if (w > 0) sums[destination + i] / w else 0.0
                        val b = input[candidate + i].toDouble()
                        dot += a * b; ea += a * a; eb += b * b
                    }
                    val score = dot / sqrt(ea * eb)
                    if (score > bestScore) { bestScore = score; best = candidate }
                }
            }
            for (i in 0 until window) {
                val w = 0.5 - 0.5 * cos(2 * PI * (i + 0.5) / window)
                sums[destination + i] += input[best + i] * w
                weights[destination + i] += w
            }
            destination += hop
        }
        return ShortArray(length) { i -> (sums[i] / max(weights[i], 1e-9)).roundToInt().coerceIn(-32768, 32767).toShort() }
    }
}
