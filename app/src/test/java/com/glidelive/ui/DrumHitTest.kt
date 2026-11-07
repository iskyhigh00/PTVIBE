package com.glidelive.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrumHitTest {
    @Test fun everyLayoutTilesTheWholeSurfaceWithoutOverlaps() {
        for (layout in PadLayout.entries) {
            val counts = IntArray(Pads.COUNT)
            for (cell in layout.owner) {
                assertTrue("${layout.name}: celda sin pad", cell in 0 until Pads.COUNT)
                counts[cell]++
            }
            for (p in 0 until Pads.COUNT) {
                val c = layout.cells[p]
                if (c == null) assertEquals("${layout.name}: pad oculto $p", 0, counts[p])
                else assertEquals("${layout.name}: pad $p", c[2] * c[3], counts[p])
            }
        }
    }

    @Test fun mirroredLayoutIsTheMirrorOfTheKit() {
        for (p in 0 until 13) {
            val a = PadLayout.KIT.cells[p]!!
            val b = PadLayout.KIT_LEFT.cells[p]!!
            assertEquals(12 - a[0] - a[2], b[0]); assertEquals(a[1], b[1]); assertEquals(a[2], b[2]); assertEquals(a[3], b[3])
        }
    }

    @Test fun wholeSurfaceFiresAPadInEveryLayout() {
        for (layout in PadLayout.entries) for ((w, h) in listOf(1600 to 800, 700 to 300, 2200 to 1000)) {
            var y = 0f
            while (y <= h) {
                var x = 0f
                while (x <= w) {
                    assertTrue("${layout.name} ($x,$y) en ${w}x$h", DrumHit.hitTest(layout, x, y, w, h) >= 0)
                    x += 9f
                }
                y += 9f
            }
        }
    }

    @Test fun centerOfEveryVisiblePadHitsThatPad() {
        val w = 1600; val h = 800
        for (layout in PadLayout.entries) {
            val l = FloatArray(Pads.COUNT); val t = FloatArray(Pads.COUNT)
            val r = FloatArray(Pads.COUNT); val b = FloatArray(Pads.COUNT)
            DrumHit.geometry(layout, w, h, 12f, l, t, r, b)
            for (i in 0 until Pads.COUNT) {
                if (!layout.visible(i)) continue
                assertEquals("${layout.name} pad $i", i, DrumHit.hitTest(layout, (l[i] + r[i]) / 2, (t[i] + b[i]) / 2, w, h))
            }
        }
    }

    @Test fun padsAreBigEnoughForFingers() {
        // Vista de 1000 x 420 dp: ningún pad visible baja de ~80 dp de ancho ni ~90 dp de alto.
        val w = 1000; val h = 420
        for (layout in PadLayout.entries) {
            val l = FloatArray(Pads.COUNT); val t = FloatArray(Pads.COUNT)
            val r = FloatArray(Pads.COUNT); val b = FloatArray(Pads.COUNT)
            DrumHit.geometry(layout, w, h, 8f, l, t, r, b)
            for (i in 0 until Pads.COUNT) {
                if (!layout.visible(i)) continue
                assertTrue("${layout.name} pad $i ancho ${r[i] - l[i]}", r[i] - l[i] >= 80f)
                assertTrue("${layout.name} pad $i alto ${b[i] - t[i]}", b[i] - t[i] >= 90f)
            }
        }
    }

    @Test fun essentialLayoutHidesFourPads() {
        assertEquals(4, (0 until 13).count { !PadLayout.ESSENTIAL.visible(it) })
        assertTrue(PadLayout.ESSENTIAL.visible(Pads.KICK) && PadLayout.ESSENTIAL.visible(Pads.SNARE))
    }

    @Test fun cumbiaLayoutShowsAllPercussion() {
        for (p in Pads.GUIRO_SHORT..Pads.COWBELL) assertTrue("pad $p", PadLayout.CUMBIA.visible(p))
        for (p in Pads.GUIRO_SHORT..Pads.COWBELL) assertTrue("pad $p solo en Cumbia", !PadLayout.KIT.visible(p))
        assertEquals(Pads.COUNT, com.glidelive.drums.DrumKeys.keys.size)
    }

    @Test fun edgesStayInTheirPad() {
        val k = PadLayout.KIT
        assertEquals(DrumHit.hitTest(k, 0f, 0f, 1200, 600), DrumHit.hitTest(k, -5f, -5f, 1200, 600))
        assertEquals(DrumHit.hitTest(k, 1199f, 599f, 1200, 600), DrumHit.hitTest(k, 1300f, 700f, 1200, 600))
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
