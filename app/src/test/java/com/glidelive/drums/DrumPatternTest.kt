package com.glidelive.drums

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DrumPatternTest {
    private val patterns: List<DrumPattern> by lazy {
        DrumPattern.parseAll(File("src/main/assets/patterns.json").readText(), true)
    }

    @Test fun hasAtLeastTwelveOriginalPatterns() {
        assertTrue(patterns.size >= 12)
        assertEquals(patterns.size, patterns.map { it.id }.toSet().size)
    }

    @Test fun everyPatternIsWellFormed() {
        for (p in patterns) {
            assertTrue("${p.id}: 2 o 3 variaciones", p.variations.size in 2..3)
            assertTrue("${p.id}: rango de tempo", p.bpmMin in 30..p.bpmMax && p.bpmMax <= 260)
            for (v in p.variations) for ((k, s) in v.tracks) {
                assertTrue("${p.id}/${v.name}: pista desconocida $k", DrumKeys.index(k) >= 0)
                assertEquals("${p.id}/${v.name}/$k", p.steps, s.length)
                assertTrue(s.all { it in ".XxogR" })
            }
            for ((k, s) in p.fill + p.end) {
                assertTrue(DrumKeys.index(k) >= 0)
                assertEquals("${p.id} relleno/final $k", p.barSteps, s.length)
            }
            assertTrue("${p.id}: relleno y final", p.fill.isNotEmpty() && p.end.isNotEmpty())
        }
    }

    @Test fun irregularMetersHaveTheRightLength() {
        fun len(id: String) = patterns.first { it.id == id }.lengthTicks
        assertEquals(7 * 48, len("o78"))        // 7/8
        assertEquals(5 * 96, len("cinco4"))     // 5/4
        assertEquals(11 * 48, len("o118"))      // 11/8
        assertEquals(9 * 48, len("nueve8"))     // 9/8
        assertEquals(12 * 48, len("doce8"))     // 12/8
        assertEquals(5 * 16 * 24, len("cinco_16"))  // se repite cada 5 compases
        assertEquals(24 * 32, len("modulacion"))    // dos compases
    }

    @Test fun grouping2plus2plus3AccentsGroupStarts() {
        val p = patterns.first { it.id == "o78" }
        val hc = p.variations[0].tracks["hc"]!!
        assertEquals('X', hc[0]); assertEquals('X', hc[2]); assertEquals('X', hc[4])
    }

    @Test fun compileMapsStepsToTicksAndVelocities() {
        val c = DrumPattern.compileTracks(mapOf("k" to "X.o.", "s" to "..g."), 24)
        assertEquals(listOf(0, 48, 48), c.ticks.toList())
        assertEquals(listOf(0, 0, 1), c.pads.toList())
        assertEquals(1.0f, c.vels[0], 0f)
        assertEquals(0.2f, c.vels[2], 0f)
    }

    @Test fun fillReplacesOnlyTheLastBar() {
        val p = patterns.first { it.id == "cinco_16" }
        val f = p.withFill(0)
        val hc = p.variations[0].tracks["hc"]!!
        assertEquals(hc.take(64), f["hc"]!!.take(64))          // compases 1-4 intactos
        assertTrue(f["hc"]!!.takeLast(16).all { it == '.' })  // el último compás cambia
        assertTrue(f["t1"]!!.takeLast(16).any { it != '.' })
        assertEquals(p.steps, f["s"]!!.length)
    }

    @Test fun jsonRoundTrip() {
        val p = patterns[3]
        val back = DrumPattern.parseAll(DrumPattern.toJsonAll(listOf(p)), true).first()
        assertEquals(p, back)
    }

    @Test fun stepCycle() {
        assertEquals('x', Step.next('.'))
        assertEquals('.', Step.next('g'))
    }
}
