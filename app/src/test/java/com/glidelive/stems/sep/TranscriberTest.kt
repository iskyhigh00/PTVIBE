package com.glidelive.stems.sep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class TranscriberTest {
    private fun tone(sr: Int, notes: List<Triple<Double, Double, Double>>, total: Double): FloatArray {
        val x = FloatArray((total * sr).toInt())
        for ((start, dur, hz) in notes) {
            val a = (start * sr).toInt(); val b = ((start + dur) * sr).toInt()
            for (i in a until minOf(b, x.size)) {
                val t = (i - a).toDouble() / sr
                val env = minOf(1.0, t * 80) * exp(-t * 1.5)
                x[i] += (0.5 * env * (sin(2 * PI * hz * t) + 0.4 * sin(2 * PI * 2 * hz * t))).toFloat()
            }
        }
        return x
    }

    @Test fun bassLineIsTranscribedWithPitchAndTiming() {
        val sr = 11025
        // F#1 (46,25 Hz), A1 (55 Hz), E1 (41,2 Hz)
        val x = tone(sr, listOf(Triple(0.2, 0.5, 46.25), Triple(0.9, 0.5, 55.0), Triple(1.6, 0.6, 41.2)), 2.6)
        val notes = Transcriber.monophonic(x, sr, 35f, 400f, 512, 256, Transcriber.BASS)
        val midis = notes.map { it.note }
        assertEquals("notas: $midis", listOf(30, 33, 28), midis)
        assertEquals(0.2, notes[0].t, 0.06)
        assertEquals(0.9, notes[1].t, 0.06)
        assertEquals(1.6, notes[2].t, 0.06)
        assertTrue(notes.all { it.dur > 0.2f })
    }

    @Test fun melodyFollowsAGlissandoOfSteps() {
        val sr = 22050
        val x = tone(sr, listOf(Triple(0.1, 0.4, 440.0), Triple(0.6, 0.4, 493.88), Triple(1.1, 0.4, 554.37)), 1.8)
        val notes = Transcriber.monophonic(x, sr, 100f, 1100f, 768, 512, Transcriber.MELODY)
        assertEquals(listOf(69, 71, 73), notes.map { it.note })
    }

    @Test fun silenceGivesNoNotes() {
        val notes = Transcriber.monophonic(FloatArray(30000), 11025, 35f, 400f, 512, 256, Transcriber.BASS)
        assertTrue(notes.isEmpty())
    }

    @Test fun drumHitsAreClassified() {
        val sr = 44100
        val x = FloatArray(sr * 4)
        val rnd = java.util.Random(5)
        fun kick(at: Double) { val a = (at * sr).toInt(); for (j in 0 until 6000) x[a + j] += (0.9 * exp(-j / 1500.0) * sin(2 * PI * (55 + 80 * exp(-j / 300.0)) * j / sr)).toFloat() }
        fun snare(at: Double) { val a = (at * sr).toInt(); for (j in 0 until 5000) x[a + j] += (0.7 * exp(-j / 1200.0) * (rnd.nextGaussian() * 0.5 + 0.5 * sin(2 * PI * 220 * j / sr))).toFloat() }
        fun hat(at: Double) { val a = (at * sr).toInt(); var prev = 0.0; for (j in 0 until 2000) { val n = rnd.nextGaussian(); val hp = n - prev; prev = n; x[a + j] += (0.4 * exp(-j / 400.0) * hp).toFloat() } }
        // Patrón: bombo en 0.5 y 1.5; caja en 1.0 y 2.0; hi-hat cada 0.25 s.
        for (b in listOf(0.5, 1.5, 2.5)) kick(b)
        for (b in listOf(1.0, 2.0, 3.0)) snare(b)
        var t = 0.25; while (t < 3.6) { hat(t); t += 0.25 }
        val notes = Transcriber.drums(x, sr)
        val kicks = notes.filter { it.note == 0 }.map { it.t }
        val snares = notes.filter { it.note == 1 }.map { it.t }
        val hats = notes.filter { it.note == 3 || it.note == 4 }.map { it.t }
        fun near(list: List<Double>, at: Double) = list.any { abs(it - at) < 0.05 }
        for (b in listOf(0.5, 1.5, 2.5)) assertTrue("bombo en $b: $kicks", near(kicks, b))
        for (b in listOf(1.0, 2.0, 3.0)) assertTrue("caja en $b: $snares", near(snares, b))
        assertTrue("hi-hats detectados: ${hats.size}", hats.size >= 8)
    }

    private fun abs(v: Double) = kotlin.math.abs(v)
}
