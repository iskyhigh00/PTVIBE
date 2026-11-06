package com.glidelive.theory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleChordTest {
    private fun names(scale: Scale, tonic: Int, seventh: Boolean) =
        (0 until 7).map { Chord.diatonic(scale, tonic, it, seventh).name() }

    @Test fun majorScalePitchClasses() {
        assertEquals(listOf(0, 2, 4, 5, 7, 9, 11), Scale.MAJOR.pitchClasses(0))
        assertEquals(listOf(7, 9, 11, 0, 2, 4, 6), Scale.MAJOR.pitchClasses(7))
    }

    @Test fun harmonicMinorHasLeadingTone() {
        assertTrue(Scale.HARMONIC_MINOR.contains(9, 8))  // La menor armónica contiene Sol♯
        assertFalse(Scale.NATURAL_MINOR.contains(9, 8))
    }

    @Test fun diatonicTriadsInCMajor() {
        assertEquals(listOf("C", "Dm", "Em", "F", "G", "Am", "Bdim"), names(Scale.MAJOR, 0, false))
    }

    @Test fun diatonicSeventhsInCMajor() {
        assertEquals(
            listOf("Cmaj7", "Dm7", "Em7", "Fmaj7", "G7", "Am7", "Bm7(♭5)"),
            names(Scale.MAJOR, 0, true),
        )
    }

    @Test fun diatonicTriadsInANaturalMinor() {
        assertEquals(listOf("Am", "Bdim", "C", "Dm", "Em", "F", "G"), names(Scale.NATURAL_MINOR, 9, false))
    }

    @Test fun harmonicMinorTriads() {
        val n = names(Scale.HARMONIC_MINOR, 9, false)
        assertEquals("Caug", n[2])
        assertEquals("E", n[4])
        assertEquals("A♭dim", n[6])
    }

    @Test fun chordTones() {
        assertEquals(listOf(5, 8, 0, 3), Chord(5, ChordType.MIN7).tones)
        assertEquals(listOf(0, 3, 7, 10, 2), Chord(0, ChordType.MIN9).tones)
        assertEquals(listOf(0, 4, 7, 10, 1), Chord(0, ChordType.DOM7B9).tones)
    }

    @Test fun iiVIPresetNames() {
        val names = ChordBanks.iiVI(0).flatten().map { it.chord.name() }
        assertEquals(
            listOf("Fm7", "B♭7", "E♭maj7", "A♭maj7", "Dm7(♭5)", "G7(♭9)", "Cm9", "C7(♭9)", "Fm9"),
            names,
        )
    }

    @Test fun iiVITransposes() {
        assertEquals("Gm7", ChordBanks.iiVI(2).flatten().first().chord.name())
    }

    @Test fun noteNames() {
        assertEquals("A♭3", NoteNames.midiName(56))
        assertEquals("C5", NoteNames.midiName(72))
        assertEquals("E♭4", NoteNames.midiName(63))
    }

    @Test fun diatonicBankShape() {
        val rows = ChordBanks.diatonic(0, Scale.MAJOR, null)
        assertEquals(3, rows.size)
        assertEquals(7, rows[0].size)
        assertEquals("vii°", rows[0][6].roman)
        val ext = ChordBanks.diatonic(0, Scale.MAJOR, ChordType.MAJ9)[0]
        assertEquals("Fmaj9", ext[3].chord.name())
    }
}
