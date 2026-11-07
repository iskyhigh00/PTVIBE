package com.glidelive.viz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordTimelineTest {
    @Test fun namesCommonChords() {
        assertEquals("Fm7", ChordNamer.name(listOf(53, 56, 60, 63)))      // F A♭ C E♭
        assertEquals("Cm9", ChordNamer.name(listOf(48, 51, 55, 58, 62)))  // C E♭ G B♭ D
        assertEquals("A♭maj7", ChordNamer.name(listOf(56, 60, 63, 67)))
        assertEquals("Dm7(♭5)", ChordNamer.name(listOf(50, 53, 56, 60)))
        assertEquals("G7(♭9)", ChordNamer.name(listOf(55, 59, 62, 65, 68)))
        assertEquals("C/E", ChordNamer.name(listOf(52, 55, 60)))
    }

    @Test fun partsSplitRootAccidentalAndQuality() {
        assertEquals(Triple("F", "", "m7"), ChordNamer.parts(listOf(53, 56, 60, 63)))
        assertEquals(Triple("A", "♭", "maj7"), ChordNamer.parts(listOf(56, 60, 63, 67)))
        assertEquals(Triple("G", "", "7(♭9)"), ChordNamer.parts(listOf(55, 59, 62, 65, 68)))
        assertEquals(Triple("D", "", "m7(♭5)"), ChordNamer.parts(listOf(50, 53, 56, 60)))
    }

    @Test fun glideInterpolatesAlongSCurve() {
        val tl = ChordTimeline()
        tl.onEvent(10, 0, 0, 60f, 0f, 0, 1000.0)
        tl.onEvent(11, 0, 0, 62f, 400f, 1, 2000.0)
        val sp = tl.spans[0]
        assertEquals(60f, sp.pitchAt(1500.0), 0.001f)
        assertEquals(61f, sp.pitchAt(2200.0), 0.001f)   // mitad de la curva S
        assertEquals(62f, sp.pitchAt(2400.0), 0.001f)
        assertEquals(62f, sp.pitchAt(9000.0), 0.001f)
    }

    @Test fun splitSpawnsFromSourcePitch() {
        val tl = ChordTimeline()
        tl.onEvent(10, 0, 0, 60f, 0f, 0, 0.0)
        tl.onEvent(12, 1, 0, 67f, 300f, 0, 1000.0)  // la voz 1 nace en la altura de la voz 0
        val sp = tl.spans.last()
        assertEquals(60f, sp.pitchAt(1000.0), 0.001f)
        assertEquals(67f, sp.pitchAt(1300.0), 0.001f)
    }

    @Test fun marksGroupEventsOfOneChordChange() {
        val tl = ChordTimeline()
        for ((i, m) in listOf(48f, 52f, 55f).withIndex()) tl.onEvent(10, i, 0, m, 0f, 0, 100.0 + i)
        assertEquals(1, tl.marks.size)
        assertEquals("C", tl.marks[0].name)
        tl.onEvent(15, 0, 0, 0f, 0f, 0, 500.0)
        assertTrue(tl.spans.all { it.endMs < Double.POSITIVE_INFINITY })
    }

    @Test fun releasedVoiceFadesOutOverItsReleaseTime() {
        val tl = ChordTimeline()
        tl.onEvent(10, 0, 0, 60f, 0f, 0, 0.0)
        tl.onEvent(14, 0, 0, 0f, 3000f, 0, 1000.0)     // se apaga en 3 s
        val sp = tl.spans[0]
        assertEquals(1f, sp.fadeAt(900.0), 0.001f)
        assertEquals(0.5f, sp.fadeAt(2500.0), 0.001f)
        assertTrue(sp.activeAt(3900.0))
        assertTrue(!sp.activeAt(4100.0))
    }
}
