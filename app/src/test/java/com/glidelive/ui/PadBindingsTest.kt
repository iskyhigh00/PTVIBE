package com.glidelive.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadBindingsTest {
    @Test fun everyPadHasAMidiNoteByDefault() {
        val m = PadBindings.defaultMidi()
        assertEquals(Pads.TOTAL, m.size)
        assertEquals(Pads.KICK, m[36])
        assertEquals(Pads.SNARE, m[37])
        assertEquals((0 until Pads.TOTAL).toSet(), m.values.toSet())
        assertTrue(m.keys.all { it in 36..127 })
    }

    @Test fun defaultKeysCoverTheMainPadsAndCombos() {
        val k = PadBindings.defaultKeys()
        assertEquals(Pads.KICK, k[54])      // Z
        assertEquals(Pads.KICK, k[62])      // espacio
        assertEquals(Pads.SNARE, k[52])     // X
        assertEquals(Pads.HH_CLOSED, k[31]) // C
        assertEquals(Pads.COMBO_KICK_CRASH, k[8])   // 1
        assertEquals(Pads.COMBO_KICK_COWBELL, k[15]) // 8
        assertTrue(k.values.all { it in 0 until Pads.TOTAL })
        for (pad in 0..Pads.COWBELL) assertTrue("pad $pad con tecla", pad in k.values)
    }
}
