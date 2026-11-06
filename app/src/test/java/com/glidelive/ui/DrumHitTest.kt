package com.glidelive.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrumHitTest {
    @Test fun everyCellBelongsToExactlyOnePadAndEveryPadHasCells() {
        val counts = IntArray(Pads.COUNT)
        for (r in 0 until DrumHit.GRID_ROWS) for (c in 0 until DrumHit.GRID_COLS) {
            val p = DrumHit.cellOwner(c, r)
            assertTrue("celda ($c,$r) sin pad", p in 0 until Pads.COUNT)
            counts[p]++
        }
        for (p in 0 until Pads.COUNT) {
            val l = Pads.layout[p]
            assertEquals("pad $p", l[2] * l[3], counts[p])  // el rectángulo del layout coincide con sus celdas
        }
    }

    @Test fun wholeSurfaceFiresAPad() {
        for ((w, h) in listOf(1600 to 800, 1800 to 700, 700 to 300, 2200 to 1000)) {
            var y = 0f
            while (y <= h) {
                var x = 0f
                while (x <= w) {
                    assertTrue("toque ($x,$y) en ${w}x$h", DrumHit.hitTest(x, y, w, h) >= 0)
                    x += 7f
                }
                y += 7f
            }
        }
    }

    @Test fun centerOfEveryPadHitsThatPad() {
        val w = 1600; val h = 800
        val l = FloatArray(Pads.COUNT); val t = FloatArray(Pads.COUNT)
        val r = FloatArray(Pads.COUNT); val b = FloatArray(Pads.COUNT)
        DrumHit.geometry(w, h, 12f, l, t, r, b)
        for (i in 0 until Pads.COUNT) assertEquals("pad $i", i, DrumHit.hitTest((l[i] + r[i]) / 2, (t[i] + b[i]) / 2, w, h))
    }

    @Test fun padsAreBigEnoughForFingers() {
        // En una vista de 1000 x 420 dp, ningún pad baja de ~80 x 120 dp.
        val w = 1000; val h = 420
        val l = FloatArray(Pads.COUNT); val t = FloatArray(Pads.COUNT)
        val r = FloatArray(Pads.COUNT); val b = FloatArray(Pads.COUNT)
        DrumHit.geometry(w, h, 8f, l, t, r, b)
        for (i in 0 until Pads.COUNT) {
            assertTrue("pad $i ancho", r[i] - l[i] >= 80f)
            assertTrue("pad $i alto", b[i] - t[i] >= 120f)
        }
    }

    @Test fun edgesStayInTheirPad() {
        assertEquals(DrumHit.hitTest(0f, 0f, 1200, 600), DrumHit.hitTest(-5f, -5f, 1200, 600))
        assertEquals(DrumHit.hitTest(1199f, 599f, 1200, 600), DrumHit.hitTest(1300f, 700f, 1200, 600))
    }

    @Test fun velocityStrongInCenterSoftAtEdge() {
        assertEquals(1f, DrumHit.velocity(Pads.KICK, 0f, 0f), 0.001f)
        assertTrue(DrumHit.velocity(Pads.KICK, 0.9f, 0f) < DrumHit.velocity(Pads.KICK, 0.2f, 0f))
        assertTrue(DrumHit.velocity(Pads.TOM1, 1f, 1f) in 0.3f..0.6f)
    }

    @Test fun ghostZoneOnlyOnTheSnare() {
        assertEquals(DrumHit.GHOST_VELOCITY, DrumHit.velocity(Pads.SNARE, 0f, 0.8f), 0.001f)
        assertTrue(DrumHit.velocity(Pads.TOM1, 0f, 0.8f) > 0.5f)
        assertTrue(DrumHit.velocity(Pads.SNARE, 0f, 0.3f) > 0.9f)
    }
}
