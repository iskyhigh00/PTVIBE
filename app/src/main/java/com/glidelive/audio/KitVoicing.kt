package com.glidelive.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class KitVoice(val label: String) {
    BANDA("Banda"),
    ORIGINAL("Original"),
}

/** Ecualización y recorte de cola aplicados a las muestras al cargarlas. */
object KitVoicing {
    private enum class Kind { PEAK, HP, LOW_SHELF, HIGH_SHELF }
    private class Band(val kind: Kind, val f0: Double, val q: Double, val gainDb: Double)
    private class Chain(val bands: List<Band>, val holdS: Double = 0.0, val tauS: Double = 0.0)

    private val KICK = Chain(
        listOf(
            Band(Kind.HP, 32.0, 0.7, 0.0),
            Band(Kind.PEAK, 48.0, 1.0, -6.0),
            Band(Kind.PEAK, 86.0, 1.2, 3.5),
            Band(Kind.PEAK, 380.0, 1.4, -3.0),
            Band(Kind.PEAK, 4000.0, 0.9, 6.0),
        ),
        holdS = 0.045, tauS = 0.07,
    )
    private val SNARE = Chain(
        listOf(
            Band(Kind.PEAK, 1100.0, 1.1, -4.5),
            Band(Kind.PEAK, 4500.0, 1.0, 1.5),
            Band(Kind.HIGH_SHELF, 8000.0, 0.7, 3.0),
        ),
        holdS = 0.025, tauS = 0.04,
    )
    private val TOM = Chain(
        listOf(Band(Kind.PEAK, 400.0, 1.2, -2.5), Band(Kind.PEAK, 4000.0, 1.0, 2.5)),
        holdS = 0.08, tauS = 0.15,
    )
    private val CYMBAL = Chain(listOf(Band(Kind.HIGH_SHELF, 9000.0, 0.7, 1.5)))

    private fun chainFor(pad: Int): Chain = when (pad) {
        0 -> KICK
        1, 2 -> SNARE
        6, 7, 8 -> TOM
        else -> CYMBAL
    }

    /** Devuelve una copia procesada (intercalada, [channels] canales) con el mismo pico que el original. */
    fun apply(voice: KitVoice, pad: Int, data: FloatArray, channels: Int, rate: Int): FloatArray {
        if (voice == KitVoice.ORIGINAL || data.isEmpty() || pad >= 13) return data  // la percusión queda tal cual
        val chain = chainFor(pad)
        val out = data.copyOf()
        for (b in chain.bands) biquad(out, channels, rate, b)
        if (chain.tauS > 0) {
            val frames = out.size / channels
            val hold = (chain.holdS * rate).toInt()
            for (i in hold until frames) {
                val g = exp(-(i - hold) / (chain.tauS * rate)).toFloat()
                for (c in 0 until channels) out[i * channels + c] *= g
            }
        }
        var pin = 0f
        var pout = 0f
        for (v in data) pin = maxOf(pin, abs(v))
        for (v in out) pout = maxOf(pout, abs(v))
        if (pout > 1e-9f) {
            val k = pin / pout
            for (i in out.indices) out[i] *= k
        }
        return out
    }

    private fun biquad(x: FloatArray, channels: Int, rate: Int, band: Band) {
        val a = 10.0.pow(band.gainDb / 40)
        val w0 = 2 * PI * band.f0 / rate
        val al = sin(w0) / (2 * band.q)
        val c = cos(w0)
        val bq: DoubleArray
        val aq: DoubleArray
        when (band.kind) {
            Kind.PEAK -> { bq = doubleArrayOf(1 + al * a, -2 * c, 1 - al * a); aq = doubleArrayOf(1 + al / a, -2 * c, 1 - al / a) }
            Kind.HP -> { bq = doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2); aq = doubleArrayOf(1 + al, -2 * c, 1 - al) }
            Kind.LOW_SHELF -> {
                val sq = 2 * sqrt(a) * al
                bq = doubleArrayOf(a * ((a + 1) - (a - 1) * c + sq), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sq))
                aq = doubleArrayOf((a + 1) + (a - 1) * c + sq, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sq)
            }
            Kind.HIGH_SHELF -> {
                val sq = 2 * sqrt(a) * al
                bq = doubleArrayOf(a * ((a + 1) + (a - 1) * c + sq), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sq))
                aq = doubleArrayOf((a + 1) - (a - 1) * c + sq, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sq)
            }
        }
        val b0 = bq[0] / aq[0]; val b1 = bq[1] / aq[0]; val b2 = bq[2] / aq[0]
        val a1 = aq[1] / aq[0]; val a2 = aq[2] / aq[0]
        val frames = x.size / channels
        for (ch in 0 until channels) {
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in 0 until frames) {
                val idx = i * channels + ch
                val xi = x[idx].toDouble()
                val y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = xi; y2 = y1; y1 = y
                x[idx] = y.toFloat()
            }
        }
    }
}
