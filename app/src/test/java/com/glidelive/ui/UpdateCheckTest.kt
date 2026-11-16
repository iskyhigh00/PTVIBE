package com.glidelive.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpdateCheckTest {
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun sha256OfKnownContent() {
        val f = File.createTempFile("upd", ".apk"); f.writeText("abc")
        assertEquals(abc, UpdateCheck.sha256(f)); f.delete()
    }

    @Test fun parsesTheSha256FileFormat() {
        assertEquals(abc, UpdateCheck.parseHash("${abc.uppercase()}  GlideLive.apk\n"))
        assertEquals("", UpdateCheck.parseHash("   "))
    }

    @Test fun onlyAFullMatchingHashPasses() {
        assertTrue(UpdateCheck.matches(abc, abc))
        assertFalse(UpdateCheck.matches(abc, abc.reversed()))
        assertFalse(UpdateCheck.matches("", ""))
        assertFalse(UpdateCheck.matches(abc.take(10), abc.take(10)))
    }
}
