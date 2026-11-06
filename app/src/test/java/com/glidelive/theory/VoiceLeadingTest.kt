package com.glidelive.theory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VoiceLeadingTest {
    private val lo = 45  // A2
    private val hi = 72  // C5

    @Test fun commonToneStaysStill() {
        // C mayor (C3 E3 G3) -> F mayor: el Do se mantiene, mi->fa y sol->la.
        val r = VoiceLeading.resolve(listOf(48, 52, 55), Chord(5, ChordType.MAJ).tones, lo, hi)
        assertEquals(listOf(48, 53, 57), r.notes)
        assertEquals(listOf(0 to 0, 1 to 1, 2 to 2), r.links)
    }

    @Test fun firstChordIsRootPositionInRange() {
        val r = VoiceLeading.resolve(emptyList(), Chord(0, ChordType.MAJ7).tones, lo, hi)
        assertEquals(0, r.notes.first() % 12)
        assertTrue(r.notes.all { it in lo..hi })
    }

    @Test fun voicesNeverCrossAndStayInRangeAlongChain() {
        var prev = emptyList<Int>()
        for (pad in ChordBanks.iiVI(0).flatten()) {
            val r = VoiceLeading.resolve(prev, pad.chord.tones, lo, hi)
            assertTrue(r.notes.zipWithNext().all { (a, b) -> a < b })
            assertTrue("fuera de rango: ${r.notes}", r.notes.all { it in lo..hi })
            assertEquals(pad.chord.tones.toSet(), r.notes.map { it % 12 }.toSet())
            prev = r.notes
        }
    }

    @Test fun minimalMovementOnEqualVoiceCounts() {
        var prev = VoiceLeading.resolve(emptyList(), Chord(5, ChordType.MIN7).tones, lo, hi).notes
        for (c in listOf(Chord(10, ChordType.DOM7), Chord(3, ChordType.MAJ7), Chord(8, ChordType.MAJ7))) {
            val r = VoiceLeading.resolve(prev, c.tones, lo, hi)
            val moved = prev.indices.sumOf { abs(prev[it] - r.notes[it]) }
            assertTrue("movimiento $moved en ${c.name()}", moved <= 6)
            prev = r.notes
        }
    }

    @Test fun voiceCountChangeSplitsOrMerges() {
        val three = VoiceLeading.resolve(emptyList(), Chord(0, ChordType.MAJ).tones, lo, hi).notes
        val up = VoiceLeading.resolve(three, Chord(0, ChordType.MAJ9).tones, lo, hi)
        assertEquals(5, up.notes.size)
        assertTrue((0 until 3).all { i -> up.links.any { it.first == i } })
        assertTrue(up.notes.indices.all { j -> up.links.any { it.second == j } })
        assertTrue(up.links.zipWithNext().all { (a, b) -> b.first >= a.first && b.second >= a.second })

        val down = VoiceLeading.resolve(up.notes, Chord(0, ChordType.MAJ).tones, lo, hi)
        assertEquals(3, down.notes.size)
        assertTrue(up.notes.indices.all { i -> down.links.any { it.first == i } })
        assertTrue(down.notes.indices.all { j -> down.links.any { it.second == j } })
    }

    @Test fun inversionPutsChosenToneInBass() {
        val tones = Chord(0, ChordType.MAJ).tones
        val r = VoiceLeading.resolve(emptyList(), tones, lo, hi, bassTone = 1)
        assertEquals(4, r.notes.first() % 12)  // mi en el bajo
    }

    @Test fun fixedNoteIsRespected() {
        val tones = Chord(0, ChordType.MAJ).tones
        val r = VoiceLeading.resolve(emptyList(), tones, lo, hi, fixed = mapOf(7 to 67))
        assertTrue(r.notes.contains(67))
    }

    @Test fun narrowRangeStillResolves() {
        val r = VoiceLeading.resolve(emptyList(), Chord(0, ChordType.MAJ).tones, 60, 64)
        assertEquals(3, r.notes.size)
    }
}
