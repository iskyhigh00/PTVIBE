package com.glidelive.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class FriendlyTest {
    @Test fun noSpaceAndNetworkAndMemoryHaveClearMessages() {
        assertTrue(Friendly.message(java.io.IOException("write failed: ENOSPC (No space left on device)")).contains("espacio"))
        assertTrue(Friendly.message(java.net.UnknownHostException("x")).contains("internet"))
        assertTrue(Friendly.message(OutOfMemoryError()).contains("memoria"))
        assertTrue(Friendly.message(SecurityException()).contains("permiso"))
    }

    @Test fun anythingElseAsksToTryAgain() {
        assertTrue(Friendly.message(IllegalStateException("x")).contains("intentarlo"))
    }

    @Test fun messagesNeverMentionRemovedButtons() {
        for (t in listOf(OutOfMemoryError(), RuntimeException())) assertTrue(!Friendly.message(t).contains("Desde aquí"))
    }
}
