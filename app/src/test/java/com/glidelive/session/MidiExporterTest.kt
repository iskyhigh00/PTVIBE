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

    /** Evento decodificado de una pista: tick absoluto, estado (con canal), datos. */
    private class Msg(val tick: Long, val status: Int, val d1: Int, val d2: Int) {
        val cmd get() = status and 0xF0
        val ch get() = status and 0x0F
    }

    private fun parseTracks(m: ByteArray): List<List<Msg>> {
        fun u32(i: Int) = ((m[i].toInt() and 0xFF) shl 24) or ((m[i + 1].toInt() and 0xFF) shl 16) or
            ((m[i + 2].toInt() and 0xFF) shl 8) or (m[i + 3].toInt() and 0xFF)
        val out = ArrayList<List<Msg>>()
        var pos = 14
        while (pos + 8 <= m.size) {
            assertEquals("MTrk", String(m, pos, 4))
            val len = u32(pos + 4)
            var i = pos + 8
            val end = i + len
            var tick = 0L
            val list = ArrayList<Msg>()
            while (i < end) {
                var delta = 0L
                while (true) {
                    val b = m[i++].toInt() and 0xFF
                    delta = (delta shl 7) or (b and 0x7F).toLong()
                    if (b and 0x80 == 0) break
                }
                tick += delta
                val st = m[i++].toInt() and 0xFF
                if (st == 0xFF) {
                    i++ // tipo
                    val l = m[i++].toInt() and 0xFF
                    i += l
                } else {
                    val two = (st and 0xF0) != 0xC0 && (st and 0xF0) != 0xD0
                    val d1 = m[i++].toInt() and 0xFF
                    val d2 = if (two) m[i++].toInt() and 0xFF else 0
                    list.add(Msg(tick, st, d1, d2))
                }
            }
            out.add(list)
            pos = end
        }
        return out
    }

    @Test fun everyNoteOnHasANoteOff() {
        val tracks = parseTracks(sample())
        var pairs = 0
        for (t in tracks) {
            val open = HashMap<Int, Int>()  // (canal*128+nota) -> notas abiertas
            for (e in t) {
                val key = e.ch * 128 + e.d1
                if (e.cmd == 0x90 && e.d2 > 0) open[key] = (open[key] ?: 0) + 1
                else if (e.cmd == 0x80 || (e.cmd == 0x90 && e.d2 == 0)) {
                    val n = open[key] ?: 0
                    assertTrue("note off sin note on: canal ${e.ch} nota ${e.d1}", n > 0)
                    open[key] = n - 1
                    pairs++
                }
            }
            assertTrue("notas sin cerrar: ${open.filterValues { it != 0 }}", open.values.all { it == 0 })
        }
        assertTrue(pairs >= 5)
    }

    @Test fun longGlideReconstructsFinalPitchWithoutSaturating() {
        val m = MidiExporter.build(
            listOf(
                LogEv.Chord(1000.0, 10, 0, 0, 60f, 0f, 0),
                LogEv.Chord(2000.0, 11, 0, 0, 76f, 600f, 1),
                LogEv.Chord(4000.0, 15, 0, 0, 0f, 0f, 0),
            ),
            bpm = 120, startMs = 1000.0,
        )
        val ev = parseTracks(m).flatten().filter { it.ch == 0 && it.cmd >= 0x80 && it.cmd <= 0xE0 && it.cmd != 0xB0 }
        val releaseTick = Math.round(3000.0 * 120 * MidiExporter.PPQ / 60000.0)
        var note = -1
        var bend = 8192
        var maxBendDev = 0
        for (e in ev) {
            if (e.tick >= releaseTick) break
            when {
                e.cmd == 0x90 && e.d2 > 0 -> note = e.d1
                e.cmd == 0x80 -> if (note == e.d1) note = -1
                e.cmd == 0xE0 -> { bend = (e.d2 shl 7) or e.d1; maxBendDev = maxOf(maxBendDev, kotlin.math.abs(bend - 8192)) }
            }
        }
        val pitch = note + (bend - 8192) / 8192.0 * 12.0
        assertEquals(76.0, pitch, 0.05)
        assertTrue("el bend nunca debe pasar del rango", maxBendDev <= 8192)
        // La nota vieja se cierra y la nueva se abre en el mismo tick (legato).
        val off60 = ev.first { it.cmd == 0x80 && it.d1 == 60 }
        val on76 = ev.first { it.cmd == 0x90 && it.d1 == 76 }
        assertEquals(off60.tick, on76.tick)
    }

    @Test fun soloFingersUseTheirOwnChannels() {
        val m = MidiExporter.build(
            listOf(
                LogEv.Solo(1000.0, 5, 0, 60f), LogEv.Solo(1000.0, 6, 0, 62f), LogEv.Solo(1000.0, 7, 0, 64f),
                LogEv.Solo(1500.0, 5, 2, 0f), LogEv.Solo(1500.0, 6, 2, 0f), LogEv.Solo(1500.0, 7, 2, 0f),
            ),
            bpm = 120, startMs = 1000.0,
        )
        val ons = parseTracks(m).flatten().filter { it.cmd == 0x90 }
        assertEquals(setOf(15, 8), ons.map { it.ch }.toSet())
        assertTrue(ons.none { it.d1 == 64 })
    }
}
