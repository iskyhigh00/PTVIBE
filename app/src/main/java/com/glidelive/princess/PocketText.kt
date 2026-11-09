package com.glidelive.princess

/** Short, lossless text ranges: the generator never receives an entire long scene at once. */
object PocketText {
    data class Segment(val text: String, val start: Int, val end: Int)

    fun segments(text: String): List<Segment> {
        require(text.isNotBlank() && text.length <= NarrationText.MAX_SELECTION) { "Selecciona hasta ${NarrationText.MAX_SELECTION} caracteres." }
        val result = mutableListOf<Segment>()
        var start = 0
        var count = 0
        var sentenceEnded = false
        for (word in Regex("\\S+").findAll(text)) {
            require(word.value.length <= 160) { "Hay una palabra de más de 160 caracteres. Revísala antes de narrar." }
            if (count > 0 && (sentenceEnded || count >= 14 || word.range.last + 1 - start > 120)) {
                result += Segment(text.substring(start, word.range.first), start, word.range.first)
                start = word.range.first
                count = 0
            }
            count++
            sentenceEnded = word.value.trimEnd('"', '»', '”', '\'', ')').lastOrNull() in listOf('.', '?', '!', '…')
        }
        if (start < text.length) result += Segment(text.substring(start), start, text.length)
        check(result.joinToString("") { it.text } == text) { "No se pudo conservar el texto completo." }
        return result
    }
}
