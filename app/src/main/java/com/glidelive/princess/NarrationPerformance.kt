package com.glidelive.princess

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.*

/** Rule-based acting over synthesized speech. No transcript is rewritten or spoken as instructions. */
object NarrationPerformance {
    data class Style(val melody: Double, val ending: Double, val pauseMs: Int, val pulse: Double)

    fun style(presetId: String?): Style = when (NarrationPresets.find(presetId)?.family) {
        NarrationFamily.BIRDS -> Style(2.3, 1.12, 150, 0.10)
        NarrationFamily.CHILDREN -> Style(1.8, 1.15, 190, 0.07)
        NarrationFamily.ELDERS -> Style(0.8, 1.32, 340, 0.02)
        NarrationFamily.ANIMALS -> when (presetId) {
            "tortuga", "oso", "vaca" -> Style(0.9, 1.38, 310, 0.02)
            "raton", "perrito", "conejo" -> Style(2.0, 1.12, 160, 0.09)
            else -> Style(1.5, 1.24, 240, 0.05)
        }
        NarrationFamily.STORY -> when (presetId) {
            "bandido-grave", "bandida-astuta", "bruja" -> Style(1.4, 1.35, 300, 0.04)
            "duende", "hada" -> Style(2.1, 1.18, 190, 0.08)
            else -> Style(1.2, 1.28, 270, 0.03)
        }
        else -> Style(1.1, 1.18, 240, 0.03)
    }

    fun phrases(text: String, locale: Locale): List<String> {
        val iterator = BreakIterator.getSentenceInstance(locale).apply { setText(text) }
        val result = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            result += NarrationText.chunks(text.substring(start, end), locale)
            start = end
            end = iterator.next()
        }
        return result
    }

    fun pauseMs(text: String, presetId: String?): Int {
        val base = style(presetId).pauseMs
        return when (text.trimEnd().lastOrNull()) {
            '…' -> base + 250
            '?', '!' -> base + 90
            '.', ';', ':' -> base
            else -> base / 3 // Continuation of a long sentence, not a dramatic full stop.
        }
    }

    /** Energy locates the speech ending; it does not claim phoneme or word alignment. */
    fun process(input: ShortArray, rate: Int, text: String, presetId: String?): ShortArray {
        require(rate in 8000..48000)
        if (input.isEmpty()) return input
        val peak = input.maxOf { abs(it.toInt()) }
        if (peak < 32) return input.copyOf()
        val first = input.indexOfFirst { abs(it.toInt()) > peak * 0.05 }
        val last = input.indexOfLast { abs(it.toInt()) > peak * 0.05 }
        if (last - first < rate / 10) return input.copyOf()
        val style = style(presetId)
        // Stretch only the last ~280 ms of speech, preserving pitch with waveform matching.
        val tailStart = max(first, last - rate * 28 / 100)
        val tail = stretch(input.copyOfRange(tailStart, last + 1), rate, style.ending)
        // Match the original ending before rejoining its trailing silence.
        val join = min(rate / 100, min(tail.size, last - tailStart + 1))
        for (i in 0 until join) {
            val mix = (i + 1.0) / join
            val at = tail.size - join + i
            tail[at] = (tail[at] * (1 - mix) + input[last - join + 1 + i] * mix).roundToInt().toShort()
        }
        val extended = input.copyOfRange(0, tailStart) + tail + input.copyOfRange(last + 1, input.size)
        val speechEnd = tailStart + tail.size
        // Smooth pitch/micro-rhythm contour. Small resampling changes intentionally also affect tempo.
        val output = ShortArray(ceil(extended.size * 1.3).toInt())
        var position = 0.0
        var count = 0
        val question = text.trimEnd().trimEnd('"', '»', '”').endsWith('?')
        val exclamation = text.contains('!')
        val fade = max(1, rate / 200)
        while (position < extended.size - 1 && count < output.size) {
            val at = position.toInt()
            val t = ((position - first) / (speechEnd - first)).coerceIn(0.0, 1.0)
            val arch = sin(PI * t) * style.melody * if (exclamation) 1.25 else 0.65
            val ending = ((t - 0.65) / 0.35).coerceIn(0.0, 1.0)
            val semitones = arch + ending * if (question) style.melody else -style.melody * 0.7
            val fraction = position - at
            val sample = extended[at] + (extended[at + 1] - extended[at]) * fraction
            val gain = (0.94 + 0.06 * sin(PI * t)) * min(1.0, count.toDouble() / fade) *
                min(1.0, (extended.size - 1 - position) / fade)
            output[count++] = (sample * gain).roundToInt().coerceIn(-32768, 32767).toShort()
            position += 2.0.pow(semitones / 12) * (1 + style.pulse * sin(4 * PI * t))
        }
        return output.copyOf(count)
    }

    /** WSOLA-style overlap/add: choose nearby grains by normalized waveform correlation. */
    private fun stretch(input: ShortArray, rate: Int, factor: Double): ShortArray {
        val window = rate * 40 / 1000
        val hop = window / 2
        if (input.size < window * 2) return input
        val length = (input.size * factor).roundToInt()
        val sums = DoubleArray(length + window)
        val weights = DoubleArray(sums.size)
        var destination = 0
        while (destination < length) {
            val expected = (destination / factor).roundToInt().coerceAtMost(input.size - window)
            var best = expected
            var bestScore = -Double.MAX_VALUE
            if (destination > 0) {
                val search = rate / 200
                for (candidate in max(0, expected - search)..min(input.size - window, expected + search) step 2) {
                    var dot = 0.0; var energyA = 1.0; var energyB = 1.0
                    for (i in 0 until hop step 4) {
                        val a = if (weights[destination + i] > 0) sums[destination + i] / weights[destination + i] else 0.0
                        val b = input[candidate + i].toDouble()
                        dot += a * b; energyA += a * a; energyB += b * b
                    }
                    val score = dot / sqrt(energyA * energyB)
                    if (score > bestScore) { bestScore = score; best = candidate }
                }
            }
            for (i in 0 until window) {
                val weight = 0.5 - 0.5 * cos(2 * PI * (i + 0.5) / window)
                sums[destination + i] += input[best + i] * weight
                weights[destination + i] += weight
            }
            destination += hop
        }
        return ShortArray(length) { i -> (sums[i] / max(weights[i], 1e-9)).roundToInt().coerceIn(-32768, 32767).toShort() }
    }
}
