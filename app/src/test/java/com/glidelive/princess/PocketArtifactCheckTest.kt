package com.glidelive.princess

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException

class PocketArtifactCheckTest {
    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private fun matches(text: String, size: Long = 3) = ByteArrayInputStream(text.toByteArray()).use {
        PocketArtifactCheck.matches(it, size, sha)
    }

    @Test fun acceptsExactlyThePinnedArtifact() {
        assertTrue(matches("abc"))
    }

    @Test fun rejectsIncompleteOversizedAndTamperedDownloads() {
        assertFalse(matches("ab"))
        assertFalse(matches("abcd"))
        assertFalse(matches("abd"))
        assertFalse(matches(""))
    }

    @Test fun preservesCancellationInsteadOfTreatingItAsSuccessfulValidation() {
        assertThrows(CancellationException::class.java) {
            PocketArtifactCheck.matches(ByteArrayInputStream("abc".toByteArray()), 3, sha) {
                throw CancellationException("cancelled")
            }
        }
    }
}
