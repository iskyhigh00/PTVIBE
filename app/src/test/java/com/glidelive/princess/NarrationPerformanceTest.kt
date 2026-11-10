package com.glidelive.princess

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.*

class NarrationPerformanceTest {
    private fun speech(rate: Int) = ShortArray(rate * 2) { i ->
        if (i < rate / 5 || i > rate * 18 / 10) 0 else
            (10000 * sin(2 * PI * 180 * i / rate) + 2000 * sin(2 * PI * 360 * i / rate)).roundToInt().toShort()
    }

    @Test fun separatesSentencesWithoutRewritingChileanDialogue() {
        val text = "¿Vamos al bosque? ¡Ya, po! La tortuga esperaba."
        val parts = NarrationPerformance.phrases(text, Locale.forLanguageTag("es"))
        assertEquals(text, parts.joinToString(" "))
        assertEquals(3, parts.size)
    }

    @Test fun allPresetsRemainBoundedAndKeepSpeechAndSilence() {
        val source = speech(8000)
        for (preset in NarrationPresets.all) {
            val output = NarrationPerformance.process(source, 8000, "Hola, vecina.", preset.id)
            assertTrue(preset.id, output.size in source.size * 8 / 10..source.size * 15 / 10)
            assertTrue(preset.id, output.any { abs(it.toInt()) > 1000 })
            assertEquals(0, output.first().toInt())
            assertEquals(0, output.last().toInt())
        }
    }

    @Test fun characterAndPunctuationProduceDifferentPerformances() {
        val source = speech(24000)
        val question = NarrationPerformance.process(source, 24000, "¿Vienes?", "loro")
        val statement = NarrationPerformance.process(source, 24000, "Vienes.", "loro")
        val tortoise = NarrationPerformance.process(source, 24000, "Vienes.", "tortuga")
        assertFalse(question.contentEquals(statement))
        assertFalse(statement.contentEquals(tortoise))
        assertTrue(tortoise.size > statement.size)
        assertTrue(NarrationPerformance.pauseMs("Espera…", "tortuga") > NarrationPerformance.pauseMs("Hola.", "loro"))
    }

    @Test fun silenceEmptyAndShortClipsAreSafe() {
        assertEquals(0, NarrationPerformance.process(shortArrayOf(), 8000, "", null).size)
        assertArrayEquals(ShortArray(8000), NarrationPerformance.process(ShortArray(8000), 8000, "Silencio.", "bruja"))
        val short = shortArrayOf(1, 1000, -1000, 0)
        assertArrayEquals(short, NarrationPerformance.process(short, 8000, "¡Ah!", "loro"))
    }
}
