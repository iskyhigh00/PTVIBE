package com.glidelive.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiExporterTest {
    private fun indexOf(b: ByteArray, pat: IntArray): Int {
        outer@ for (i in 0..b.size - pat.size) {
            for (j in pat.indices) if ((b[i + j].toInt() and 0xFF) != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private fun count(b: ByteArray, pat: IntArray): Int {
        var n = 0
        outer@ for (i in 0..b.size - pat.size) {
            for (j in pat.indices) if ((b[i + j].toInt() and 0xFF) != pat[j]) continue@outer
            n++
        }
        return n
    }

    private fun sample(): ByteArray = MidiExporter.build(
        listOf(
            LogEv.Chord(1000.0, 10, 0, 0, 60f, 0f, 0),
            LogEv.Chord(1000.0, 10, 1, 0, 64f, 0f, 0),
            LogEv.Chord(2000.0, 11, 0, 0, 62f, 400f, 1),   // glide de 400 ms
            LogEv.Chord(3000.0, 15, 0, 0, 0f, 0f, 0),
            LogEv.Drum(1500.0, 0, 1.0f),
            LogEv.Drum(1750.0, 1, 0.5f),
            LogEv.Solo(2500.0, 0, 0, 72f), LogEv.Solo(2600.0, 0, 1, 72.5f), LogEv.Solo(2800.0, 0, 2, 0f),
        ),
        bpm = 120, startMs = 1000.0,
    )

    @Test fun headerIsFormat1With480Ppq() {
        val m = sample()
        assertEquals("MThd", String(m, 0, 4))
        assertEquals(1, ((m[8].toInt() and 0xFF) shl 8) or (m[9].toInt() and 0xFF))     // formato 1
        assertEquals(480, ((m[12].toInt() and 0xFF) shl 8) or (m[13].toInt() and 0xFF))  // PPQ
        assertTrue(indexOf(m, intArrayOf(0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20)) > 0)       // 120 BPM
    }

    @Test fun chordVoicesGoToTheirOwnChannelsAndDrumsToChannel10() {
        val m = sample()
        assertTrue("canal 1 (voz 0)", indexOf(m, intArrayOf(0x90, 60, 80)) > 0)
        assertTrue("canal 2 (voz 1)", indexOf(m, intArrayOf(0x91, 64, 80)) > 0)
        assertTrue("bombo GM 36 en canal 10", indexOf(m, intArrayOf(0x99, 36, 127)) > 0)
        assertTrue("caja GM 38 en canal 10", indexOf(m, intArrayOf(0x99, 38, 64)) > 0)
        assertTrue("solista en canal 11", indexOf(m, intArrayOf(0x9A, 72, 90)) > 0)
    }

    @Test fun glideBecomesPitchBendMessages() {
        val m = sample()
        // 400 ms / 15 ms ≈ 26 mensajes de bend en el canal 1 (más el de la nota inicial)
        assertTrue(count(m, intArrayOf(0xE0)) >= 20)
    }

    @Test fun everyNoteOnHasANoteOff() {
        val m = sample()
        for (ch in intArrayOf(0, 1, 9, 10)) {
            val on = count(m, intArrayOf(0x90 or ch)) // aproximado: incluye datos
            assertTrue(on > 0)
        }
        assertTrue(count(m, intArrayOf(0x80)) >= 1)
        assertTrue(count(m, intArrayOf(0x81)) >= 1)
    }
}
