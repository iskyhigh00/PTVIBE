package com.glidelive.theory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los bancos C1..C8 deben sonar bien en cualquier tonalidad: se comprueban las reglas de cada voicing. */
class CuratedBanksTest {
    @Test fun sixteenBanksOfEightPadsLinkedFromTheBankSelector() {
        assertEquals(16, CuratedBanks.all.size)
        for (b in CuratedBanks.all) assertEquals(b.id, 8, b.pads.size)
        val linked = Bank.entries.mapNotNull { it.curated }
        assertEquals(CuratedBanks.all.map { it.id }, linked)
        for (id in linked) assertNotNull(CuratedBanks.byId(id))
    }

    @Test fun everyVoicingIsWellBuiltInAllTwelveKeys() {
        for (b in CuratedBanks.all) for (tonic in 0 until 12) {
            for (row in CuratedBanks.rows(b, tonic)) for (pad in row) {
                val v = pad.voicing!!.sorted()
                val tag = "${b.id} en $tonic: ${pad.chord.name()} $v"
                assertTrue("$tag: 4 a 6 voces", v.size in 4..6)
                assertEquals("$tag: el bajo es la fundamental", pad.chord.rootPc, Math.floorMod(v[0], 12))
                assertEquals("$tag: contiene todas las notas del acorde", pad.chord.tones.toSet(), v.map { Math.floorMod(it, 12) }.toSet())
                assertTrue("$tag: registro", v.first() >= 28 && v.last() <= 88)
                for (k in 1 until v.size) {
                    val a = v[k - 1]; val d = v[k] - a
                    assertTrue("$tag: sin unísonos", d > 0)
                    assertTrue("$tag: sin segundas menores", d != 1)
                    if (a < 40) assertTrue("$tag: grave abierto", d >= 5)
                    if (a < 52) assertTrue("$tag: medio-grave sin racimos", d >= 3)
                }
            }
        }
    }

    @Test fun topVoiceMovesSmoothlyAlongEachBank() {
        for (b in CuratedBanks.all) {
            val tops = b.pads.map { it.voicing.max() }
            for (k in 1 until tops.size) assertTrue("${b.id}: salto ${tops[k - 1]} -> ${tops[k]}", kotlin.math.abs(tops[k] - tops[k - 1]) <= 6)
        }
    }

    @Test fun banksFollowTheChosenKeyByTheShortestShift() {
        val c2 = CuratedBanks.byId("C2")!!       // escrito en Si♭
        assertEquals(0, c2.shiftTo(10))
        assertEquals(2, c2.shiftTo(0))            // a Do: sube un tono
        assertEquals(-5, c2.shiftTo(5))           // a Fa: baja una cuarta (no sube una quinta)
        val first = CuratedBanks.rows(c2, 0).first().first()
        assertEquals("Cadd9", first.chord.name())
    }
}
