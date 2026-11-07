package com.glidelive.bass

import com.glidelive.drums.Compiled
import com.glidelive.drums.DrumPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BassLineTest {
    private val lines = Bass.parseAll(File("src/main/assets/basslines.json").readText())

    @Test fun parsesNotes() {
        assertEquals(42, Bass.parseNote("F#2"))
        assertEquals(28, Bass.parseNote("E1"))
        assertEquals(27, Bass.parseNote("Eb1"))
        assertEquals(30, Bass.parseNote("F#1"))
    }

    @Test fun everyLineIsWellFormed() {
        assertTrue(lines.size >= 5)
        assertEquals(lines.size, lines.map { it.id }.toSet().size)
        for (l in lines) {
            assertEquals("${l.id}: A-D", listOf("A", "B", "C", "D"), l.variations.map { it.name })
            for (v in l.variations) {
                val steps = v.bars * l.barSteps
                assertTrue("${l.id}/${v.name}: con notas", v.notes.isNotEmpty())
                for (n in v.notes) {
                    assertTrue("${l.id}/${v.name}: paso ${n.step}", n.step in 0 until steps)
                    assertTrue("${l.id}/${v.name}: largo", n.len in 1..steps)
                    assertTrue("${l.id}/${v.name}: registro de bajo ${n.midi}", n.midi in 23..55)
                }
                val sorted = v.notes.sortedBy { it.step }
                for (i in 1 until sorted.size) {
                    assertTrue("${l.id}/${v.name}: notas sin superponerse en ${sorted[i].step}",
                        sorted[i - 1].step + sorted[i - 1].len <= sorted[i].step)
                }
            }
            assertTrue(l.end.isNotEmpty())
        }
    }

    @Test fun songBaseIsLinked() {
        val pats = DrumPattern.parseAll(File("src/main/assets/patterns.json").readText(), true)
        val p = pats.first()
        assertEquals("tocata", p.id)
        val line = lines.first { it.id == p.bass }
        assertEquals(6, line.root)  // Fa#
        assertEquals(listOf(30, 32, 32, 30, 33, 30), line.variations[0].notes.take(6).map { it.midi })
    }

    @Test fun shiftStaysClose() {
        assertEquals(2, Bass.shift(4, 6))
        assertEquals(-5, Bass.shift(4, 11))
        assertEquals(6, Bass.shift(4, 10))
        assertEquals(0, Bass.shift(6, 6))
    }

    @Test fun compilePacksDurationAndVelocity() {
        val c = Bass.compile(listOf(BassNote(4, 30, 3, 0.8f)), 24, 2)
        assertEquals(96, c.ticks[0])
        assertEquals(Bass.PAD_BASE + 32, c.pads[0])
        assertEquals(72, c.vels[0].toInt())
        assertEquals(0.8f, c.vels[0] - 72, 1e-3f)
    }

    @Test fun mergeUsesCommonLengthAndFillOnlyAtTheEnd() {
        val drums = Compiled(intArrayOf(0), intArrayOf(0), floatArrayOf(1f))
        val fill = Compiled(intArrayOf(0, 300), intArrayOf(0, 6), floatArrayOf(1f, 1f))
        val bass = Compiled(intArrayOf(0, 384), intArrayOf(94, 96), floatArrayOf(48.8f, 48.8f))
        val (m, len) = Bass.merge(drums, fill, 384, bass, 768)
        assertEquals(768, len)
        val toms = m.ticks.indices.filter { m.pads[it] == 6 }.map { m.ticks[it] }
        assertEquals(listOf(684), toms)  // relleno solo en la última vuelta
        assertEquals(2, m.pads.count { it == 0 })
        assertEquals(2, m.pads.count { it >= Bass.PAD_BASE })
        val (m2, len2) = Bass.merge(drums, drums, 384, null, 0)
        assertEquals(384, len2)
        assertEquals(1, m2.ticks.size)
    }
}
