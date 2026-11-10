package com.glidelive.princess

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.text.BreakIterator
import java.util.Locale

object NarrationText {
    const val MAX_DOCUMENT = 48_000
    const val MAX_SELECTION = 4_000
    const val MAX_FILE_BYTES = 256 * 1024

    /** A cursor alone means the whole document; a selection means only that passage. */
    fun forNarration(text: String, selectionStart: Int, selectionEnd: Int): String {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        return (if (start == end) text else text.substring(start, end)).trim()
    }

    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_FILE_BYTES) { "El archivo de texto es demasiado grande." }
        val text = try {
            val first = bytes.getOrNull(0)?.toUByte()?.toInt()
            val second = bytes.getOrNull(1)?.toUByte()?.toInt()
            val utf16 = (first == 0xFE && second == 0xFF) || (first == 0xFF && second == 0xFE)
            val charset = if (utf16) Charsets.UTF_16 else Charsets.UTF_8
            charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }.removePrefix("\uFEFF")
        require(text.isNotBlank() && text.length <= MAX_DOCUMENT && text.none {
            it.isISOControl() && it !in "\n\r\t"
        }) { "Abre un archivo TXT de hasta $MAX_DOCUMENT caracteres." }
        return text
    }

    /** Divide en frases y conserva las palabras y signos del fragmento. */
    fun chunks(text: String, locale: Locale, limit: Int = 220): List<String> {
        require(limit >= 2)
        val boundaries = BreakIterator.getSentenceInstance(locale).apply { setText(text) }
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length) {
                val sentence = if (boundaries.isBoundary(end)) end else boundaries.preceding(end)
                if (sentence > start) end = sentence
                else {
                    val space = (end - 1 downTo start + 1).firstOrNull { text[it].isWhitespace() }
                    if (space != null) end = space + 1
                    else if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                }
            }
            val part = text.substring(start, end).trim()
            if (part.isNotEmpty()) parts += part
            start = end
        }
        return parts
    }
}
