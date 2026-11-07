package com.glidelive.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class KitVoicingTest {
    private val rate = 44100

    private fun snare(): FloatArray {
        val n = rate / 2
        val r = java.util.Random(1)
        return FloatArray(n * 2) { i ->
            val t = (i / 2).toDouble() / rate
            ((0.5 * sin(2 * Math.PI * 215 * t) + 0.5 * (r.nextDouble() * 2 - 1)) * exp(-t / 0.08)).toFloat()
        }
    }

    private fun energy(x: FloatArray, fromS: Double, toS: Double): Double {
        var e = 0.0
        for (i in (fromS * rate).toInt() until (toS * rate).toInt()) e += x[i * 2] * x[i * 2].toDouble()
        return e
    }

    @Test fun originalIsUntouched() {
        val x = snare()
        assertSame(x, KitVoicing.apply(KitVoice.ORIGINAL, 1, x, 2, rate))
    }

    @Test fun bandSnareKeepsPeakAndShortensTail() {
        val x = snare()
        val y = KitVoicing.apply(KitVoice.BANDA, 1, x, 2, rate)
        assertEquals(x.maxOf { abs(it) }, y.maxOf { abs(it) }, 1e-4f)
        val tailX = energy(x, 0.15, 0.3) / energy(x, 0.0, 0.03)
        val tailY = energy(y, 0.15, 0.3) / energy(y, 0.0, 0.03)
        assertTrue("cola $tailY vs $tailX", tailY < tailX * 0.2)
    }

    @Test fun cymbalsKeepTheirLength() {
        val x = snare()
        val y = KitVoicing.apply(KitVoice.BANDA, 9, x, 2, rate)
        val tailX = energy(x, 0.15, 0.3) / energy(x, 0.0, 0.03)
        val tailY = energy(y, 0.15, 0.3) / energy(y, 0.0, 0.03)
        assertTrue(tailY > tailX * 0.5)
    }
}
