package com.glidelive.princess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.util.Locale

class NarrationTextTest {
    @Test fun pastedTextCanBeNarratedWithoutSelectingItFirst() {
        val text = "  ¿Dónde está la niña? ¡Aquí!  "
        assertEquals(text.trim(), NarrationText.forNarration(text, text.length, text.length))
        assertEquals(text.trim(), NarrationText.forNarration(text, 0, 0))
    }

    @Test fun selectionNarratesOnlyTheChosenPassageInEitherDirection() {
        val text = "El zorro. La niña. El dragón."
        assertEquals("La niña.", NarrationText.forNarration(text, 10, 18))
        assertEquals("La niña.", NarrationText.forNarration(text, 18, 10))
        assertEquals("", NarrationText.forNarration(text, 9, 10))
    }

    @Test fun preservesAccentsAndPunctuationInCommonTextEncodings() {
        val text = "¿Dónde está la niña?\r\n¡Aquí!"
        assertEquals(text, NarrationText.decode(("\uFEFF" + text).toByteArray(Charsets.UTF_8)))
        assertEquals(text, NarrationText.decode(text.toByteArray(Charsets.UTF_16)))
        assertEquals(text, NarrationText.decode(text.toByteArray(Charset.forName("windows-1252"))))
    }

    @Test fun rejectsBinaryAndOversizedFiles() {
        assertThrows(IllegalArgumentException::class.java) { NarrationText.decode(byteArrayOf(0, 9, 5)) }
        assertThrows(IllegalArgumentException::class.java) {
            NarrationText.decode(ByteArray(NarrationText.MAX_FILE_BYTES + 1))
        }
    }

    @Test fun splitsAtPunctuationOrWordsWithoutOmittingText() {
        val text = "¿Cuándo llegamos? Al amanecer. Era una mañana helada en el sur."
        val parts = NarrationText.chunks(text, Locale.forLanguageTag("es"), limit = 22)
        assertEquals(text, parts.joinToString(" "))
        assertEquals("¿Cuándo llegamos?", parts.first())
        assertTrue(parts.all { it.length <= 22 })
    }

    @Test fun doesNotSplitASurrogatePair() {
        val text = "123🐱abcdef"
        val parts = NarrationText.chunks(text, Locale.ROOT, limit = 4)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }
}
