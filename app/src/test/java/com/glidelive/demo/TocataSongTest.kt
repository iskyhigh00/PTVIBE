package com.glidelive.demo

import com.glidelive.audio.NativeEngine
import com.glidelive.bass.Bass
import com.glidelive.drums.DrumPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TocataSongTest {
    private val pattern = DrumPattern.parseAll(File("src/main/assets/patterns.json").readText(), true).first { it.id == "tocata" }
    private val line = Bass.parseAll(File("src/main/assets/basslines.json").readText()).first { it.id == "tocata" }

    @Test fun formMatchesTheRecording() {
        val bars = TocataSong.sections.sumOf { it.bars }
        assertEquals(109, bars)  // ~4:27 a 98 BPM
        for (s in TocataSong.sections) {
            assertEquals(s.name, s.bars, s.chords.size)
            for (c in s.chords) assertTrue("${s.name}: $c en el banco", c in TocataSong.PAD_OF)
            for (n in s.solo) assertTrue("${s.name}: solo dentro de la sección", (n.step + n.len) * 24 <= s.lengthTicks)
            val sorted = s.solo.sortedBy { it.step }
            for (i in 1 until sorted.size) assertTrue(sorted[i - 1].step + sorted[i - 1].len <= sorted[i].step)
        }
        // Las notas de los solos son de Fa# menor.
        val fsMinor = setOf(6, 8, 9, 11, 1, 2, 4)
        for (n in TocataSong.SOLO1 + TocataSong.SOLO2) assertTrue("nota ${n.midi}", Math.floorMod(n.midi, 12) in fsMinor)
    }

    @Test fun everySectionFitsInOnePattern() {
        for (s in TocataSong.sections) {
            val b = TocataSong.build(s, pattern, line)
            val e = b.events
            assertTrue("${s.name}: ${e.ticks.size} eventos", e.ticks.size in 1..2000)
            assertTrue(e.ticks.all { it in 0 until b.length })
            val chordNotes = e.pads.count { it in NativeEngine.CHORD_PAD_BASE until NativeEngine.SOLO_PAD_BASE }
            assertTrue("${s.name}: acordes", chordNotes >= 4 * s.bars)
            assertEquals("${s.name}: solo", s.solo.size, e.pads.count { it >= NativeEngine.SOLO_PAD_BASE })
            assertTrue("${s.name}: bajo", e.pads.any { it in Bass.PAD_BASE until NativeEngine.CHORD_PAD_BASE })
            assertTrue("${s.name}: batería", e.pads.any { it < Bass.PAD_BASE })
        }
    }

    @Test fun chordsComeFromBankC17() {
        assertEquals(listOf(57, 61, 66, 69), TocataSong.voicing("F#m"))
        assertEquals(30, TocataSong.bassRoot("F#m"))
        assertEquals(38, TocataSong.bassRoot("D"))
        val solo = TocataSong.sections.first { it.solo.isNotEmpty() }
        assertEquals(66, TocataSong.soloAt(solo, 0))
        assertEquals(null, TocataSong.soloAt(solo, 7 * 24))
    }
}
