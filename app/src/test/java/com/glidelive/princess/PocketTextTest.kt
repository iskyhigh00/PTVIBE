package com.glidelive.princess

import org.junit.Assert.*
import org.junit.Test

class PocketTextTest {
    @Test fun keepsEveryCharacterAndWordInOrder() {
        val text = "  El zorro se escondió detrás del árbol.\r\n¿Quién anda ahí?, preguntó la lechuza. ¡Soy yo, no tengas miedo!, respondió el ratón.  "
        val pieces = PocketText.segments(text)
        assertEquals(text, pieces.joinToString("") { it.text })
        assertTrue(pieces.size >= 3)
        var end = 0
        pieces.forEach {
            assertEquals(end, it.start)
            assertEquals(it.text, text.substring(it.start, it.end))
            end = it.end
        }
        assertEquals(text.length, end)
    }

    @Test fun longDialogueHasBoundedSegmentsWithoutDroppingWords() {
        val text = (1..180).joinToString(" ") { "palabra$it" }
        val pieces = PocketText.segments(text)
        assertEquals(text, pieces.joinToString("") { it.text })
        assertTrue(pieces.all { Regex("\\S+").findAll(it.text).count() <= 14 })
        assertTrue(pieces.all { it.text.trim().length <= 120 })
    }

    @Test fun rejectsUnsupportedInputInsteadOfSilentlyTruncatingIt() {
        assertThrows(IllegalArgumentException::class.java) { PocketText.segments(" ") }
        assertThrows(IllegalArgumentException::class.java) { PocketText.segments("x".repeat(161)) }
        assertThrows(IllegalArgumentException::class.java) { PocketText.segments("hola ".repeat(1000)) }
    }
}
