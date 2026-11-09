package com.glidelive.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StemLibraryTest {
    @Test fun guessesTheInstrumentFromTheFileName() {
        assertEquals(StemKind.VOCALS, StemKind.guess("Ultima tocata - vocals.wav"))
        assertEquals(StemKind.DRUMS, StemKind.guess("bateria.mp3"))
        assertEquals(StemKind.DRUMS, StemKind.guess("song_(Drums).wav"))
        assertEquals(StemKind.BASS, StemKind.guess("BAJO.flac"))
        assertEquals(StemKind.GUITAR, StemKind.guess("guitarra solo.m4a"))
        assertEquals(StemKind.OTHER, StemKind.guess("other.wav"))
        assertEquals(StemKind.MIX, StemKind.guess("Ultima tocata.mp3"))
    }

    @Test fun suggestsATitleWithoutTheStemWord() {
        assertEquals("Ultima tocata", StemLibrary.suggestTitle(listOf("Ultima tocata - vocals.wav", "Ultima tocata - drums.wav")))
        assertEquals("Mi cancion", StemLibrary.suggestTitle(listOf("Mi cancion.mp3")))
        assertEquals("Canción", StemLibrary.suggestTitle(emptyList()))
    }

    @Test fun sameFilesGiveTheSameKeyInAnyOrder() {
        val a = StemLibrary.keyOf(listOf("Voz.wav", "Bajo.wav"), listOf(100L, 200L))
        val b = StemLibrary.keyOf(listOf("bajo.wav", "voz.wav"), listOf(200L, 100L))
        assertEquals(a, b)
        assertTrue(a != StemLibrary.keyOf(listOf("Voz.wav", "Bajo.wav"), listOf(100L, 201L)))
    }

    @Test fun separatedMeansMoreThanOneTrack() {
        val t = StemTrack("a", "x", StemKind.MIX, "f")
        assertTrue(!StemSong("1", "c", listOf(t)).separated)
        assertTrue(StemSong("1", "c", listOf(t, t.copy(id = "b"))).separated)
    }
}
