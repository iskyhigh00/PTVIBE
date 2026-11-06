package com.glidelive.theory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoloPitchTest {
    private val cMajor = Scale.MAJOR.pitchClasses(0).toSet()

    @Test fun nearestSnapsToScale() {
        assertEquals(60f, SoloPitch.nearestAllowed(60.4f, cMajor), 0f)
        assertEquals(62f, SoloPitch.nearestAllowed(61.2f, cMajor), 0f)   // Do♯ -> Re
        assertEquals(60f, SoloPitch.nearestAllowed(60.9f, cMajor), 0f)   // más cerca de Do (si Do♯ no está)
    }

    @Test fun lockZeroIsFreeAndOneIsSnapped() {
        assertEquals(61.3f, SoloPitch.applyLock(61.3f, cMajor, 0f), 0.0001f)
        assertEquals(62f, SoloPitch.applyLock(61.3f, cMajor, 1f), 0.0001f)
    }

    @Test fun lockHalfPullsHalfway() {
        // 61.3 está a -0.7 de Re (62): con 50 % queda a mitad de camino.
        assertEquals(61.65f, SoloPitch.applyLock(61.3f, cMajor, 0.5f), 0.0001f)
    }

    @Test fun chromaticLockRoundsToSemitone() {
        val all = (0..11).toSet()
        assertEquals(61f, SoloPitch.applyLock(61.4f, all, 1f), 0f)
    }

    @Test fun followChordUsesChordTonesElseScale() {
        val h = HarmonyState(tonic = 0, scale = Scale.MAJOR, chordPcs = listOf(5, 9, 0))
        assertEquals(setOf(5, 9, 0), SoloPitch.allowed(h, true, null))
        assertEquals(cMajor, SoloPitch.allowed(h, false, null))
        assertEquals(cMajor, SoloPitch.allowed(h.copy(chordPcs = emptyList()), true, null))
    }

    @Test fun scaleOverrideAndTonic() {
        val h = HarmonyState(tonic = 9)  // La
        val pent = ScaleChoice.all.first { it.label == "Pentatónica menor" }
        assertEquals(setOf(9, 0, 2, 4, 7), SoloPitch.allowed(h, false, pent))
    }

    @Test fun xMapsAcrossOctaves() {
        assertEquals(48f, SoloPitch.xToMidi(0f, 48, 3), 0f)
        assertEquals(84f, SoloPitch.xToMidi(1f, 48, 3), 0f)
        assertEquals(66f, SoloPitch.xToMidi(0.5f, 48, 3), 0f)
    }

    @Test fun wiggleDetectsSmallOscillationButNotSlidesOrRest() {
        val still = WiggleVibrato(1f)
        var v = 0f
        repeat(40) { v = still.update(100f) }
        assertEquals(0f, v, 0.001f)

        val wig = WiggleVibrato(1f)
        for (i in 0 until 60) v = wig.update(100f + if (i % 2 == 0) 6f else -6f)
        assertTrue("vibrato $v", v > 0.2f)

        val slide = WiggleVibrato(1f)
        for (i in 0 until 60) v = slide.update(100f + i * 20f)
        assertEquals(0f, v, 0.05f)
    }
}
