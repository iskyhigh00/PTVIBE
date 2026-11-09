package com.glidelive.princess

import org.junit.Assert.*
import org.junit.Test

class PocketCompletionTest {
    private val text = "Al abrir la puerta del castillo, apareció un enorme dragón."

    @Test fun rejectsTheReproducedTenWordEarlyEos() {
        assertTrue(PocketCompletion.isObviouslyIncomplete(text, (24000 * .42).toInt(), 24000, true))
    }

    @Test fun plausibleDurationIsNotRejectedButDoesNotVerifyWords() {
        assertFalse(PocketCompletion.isObviouslyIncomplete(text, 24000 * 4, 24000, true))
    }

    @Test fun silenceAndEmptyAudioAreRejectedEvenForShortPhrases() {
        assertTrue(PocketCompletion.isObviouslyIncomplete("¡Sí!", 24000, 24000, false))
        assertTrue(PocketCompletion.isObviouslyIncomplete("¡Sí!", 0, 24000, true))
        assertFalse(PocketCompletion.isObviouslyIncomplete("¡Sí!", 4800, 24000, true))
    }

    @Test fun durationGuardScalesWithSampleRateAndCountsSpanishWords() {
        for (rate in listOf(8000, 24000, 44100, 48000)) {
            assertTrue(PocketCompletion.isObviouslyIncomplete("¿Dónde está la niña? ¡Aquí, po!", rate / 2, rate, true))
            assertFalse(PocketCompletion.isObviouslyIncomplete("¿Dónde está la niña? ¡Aquí, po!", rate * 3, rate, true))
        }
    }
}
