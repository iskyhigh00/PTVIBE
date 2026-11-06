package com.glidelive.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrumHitTest {
    private class Geo(w: Int, h: Int, gap: Float) {
        val cx = FloatArray(Pads.COUNT)
        val cy = FloatArray(Pads.COUNT)
        val rx = FloatArray(Pads.COUNT)
        val ry = FloatArray(Pads.COUNT)
        val order = IntArray(Pads.COUNT)
        init {
            DrumHit.geometry(w, h, gap, cx, cy, rx, ry)
            DrumHit.orderByArea(rx, ry, order)
        }
        fun hit(x: Float, y: Float) = DrumHit.hitTest(x, y, cx, cy, rx, ry, order)
    }

    private fun coverage(w: Int, h: Int, gap: Float): Double {
        val g = Geo(w, h, gap)
        var total = 0
        var fired = 0
        var y = 2f
        while (y < h) {
            var x = 2f
            while (x < w) {
                val inLayout = Pads.layout.any { l ->
                    x >= l[0] * w && x <= (l[0] + l[2]) * w && y >= l[1] * h && y <= (l[1] + l[3]) * h
                }
                if (inLayout) {
                    total++
                    if (g.hit(x, y) >= 0) fired++
                }
                x += 4f
            }
            y += 4f
        }
        return fired.toDouble() / total
    }

    @Test fun almostEveryPointOfTheLayoutFiresAPad() {
        for ((w, h) in listOf(1600 to 800, 1800 to 700, 1200 to 900, 2200 to 1000)) {
            val c = coverage(w, h, 16.5f)
            assertTrue("cobertura $c en ${w}x$h", c > 0.95)
        }
    }

    @Test fun centerOfEveryPadHitsThatPad() {
        val g = Geo(1600, 800, 16.5f)
        for (i in 0 until Pads.COUNT) assertEquals("pad $i", i, g.hit(g.cx[i], g.cy[i]))
    }

    @Test fun farOutsideDoesNotFire() {
        val g = Geo(1600, 800, 16.5f)
        assertEquals(-1, g.hit(-2000f, -2000f))
    }

    @Test fun orderIsBySizeAscending() {
        val g = Geo(1600, 800, 16.5f)
        for (k in 1 until Pads.COUNT) {
            val a = g.order[k - 1]
            val b = g.order[k]
            assertTrue(g.rx[a] * g.ry[a] <= g.rx[b] * g.ry[b])
        }
    }
}
