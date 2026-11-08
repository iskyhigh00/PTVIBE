package com.glidelive.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrumHitTest {
    @Test fun everyLayoutTilesTheWholeSurfaceWithoutOverlaps() {
        for (layout in PadLayout.entries) {
            val counts = IntArray(Pads.TOTAL)
            for (cell in layout.owner) {
                assertTrue("${layout.name}: celda sin pad", cell in 0 until Pads.TOTAL)
                counts[cell]++
            }
            for (p in 0 until Pads.TOTAL) {
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
            val l = FloatArray(Pads.TOTAL); val t = FloatArray(Pads.TOTAL)
            val r = FloatArray(Pads.TOTAL); val b = FloatArray(Pads.TOTAL)
            DrumHit.geometry(layout, w, h, 12f, l, t, r, b)
            for (i in 0 until Pads.TOTAL) {
                if (!layout.visible(i)) continue
                assertEquals("${layout.name} pad $i", i, DrumHit.hitTest(layout, (l[i] + r[i]) / 2, (t[i] + b[i]) / 2, w, h))
            }
        }
    }

    @Test fun padsAreBigEnoughForFingers() {
        // Vista de 1000 x 420 dp: ningún pad visible baja de ~80 dp de ancho ni ~90 dp de alto.
        val w = 1000; val h = 420
        for (layout in PadLayout.entries) {
            val l = FloatArray(Pads.TOTAL); val t = FloatArray(Pads.TOTAL)
            val r = FloatArray(Pads.TOTAL); val b = FloatArray(Pads.TOTAL)
            DrumHit.geometry(layout, w, h, 8f, l, t, r, b)
            for (i in 0 until Pads.TOTAL) {
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

    @Test fun combosPlayTwoSounds() {
        assertTrue(PadLayout.COMBOS.visible(Pads.COMBO_KICK_RIDE))
        assertEquals(listOf(Pads.KICK, Pads.RIDE), Pads.members(Pads.COMBO_KICK_RIDE).toList())
        assertEquals(listOf(Pads.SNARE), Pads.members(Pads.SNARE).toList())
        assertEquals(Pads.TOTAL, Pads.names.size)
    }

    @Test fun edgesStayInTheirPad() {
        val k = PadLayout.KIT
        assertEquals(DrumHit.hitTest(k, 0f, 0f, 1200, 600), DrumHit.hitTest(k, -5f, -5f, 1200, 600))
        assertEquals(DrumHit.hitTest(k, 1199f, 599f, 1200, 600), DrumHit.hitTest(k, 1300f, 700f, 1200, 600))
    }

    @Test fun everyPointOfAPadSoundsTheSame() {
        for (pad in 0 until Pads.TOTAL) for (nx in listOf(-1f, -0.6f, 0f, 0.6f, 1f)) for (ny in listOf(-1f, -0.6f, 0f, 0.6f, 1f)) {
            assertEquals(DrumHit.VELOCITY, DrumHit.velocity(pad, nx, ny), 0f)
        }
    }

    @Test fun gapsBetweenPadsAreDeadAndPadCentersStillHit() {
        val w = 1600; val h = 800; val gap = 24f
        for (layout in PadLayout.entries) {
            val l = FloatArray(Pads.TOTAL); val t = FloatArray(Pads.TOTAL)
            val r = FloatArray(Pads.TOTAL); val b = FloatArray(Pads.TOTAL)
            DrumHit.geometry(layout, w, h, gap, l, t, r, b)
            for (i in 0 until Pads.TOTAL) {
                if (!layout.visible(i)) continue
                val cx = (l[i] + r[i]) / 2; val cy = (t[i] + b[i]) / 2
                assertEquals("${layout.name} centro de $i", i, DrumHit.hitTestStrict(layout, cx, cy, w, h, gap))
                // Justo dentro del borde del pad sigue sonando; justo fuera (en el hueco) no.
                assertEquals("${layout.name} borde interior de $i", i, DrumHit.hitTestStrict(layout, l[i] + 2f, cy, w, h, gap))
                if (l[i] > gap) assertEquals("${layout.name} hueco a la izquierda de $i", -1, DrumHit.hitTestStrict(layout, l[i] - gap / 2, cy, w, h, gap))
            }
        }
    }
}
