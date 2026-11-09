package com.glidelive.princess

import kotlin.math.ceil

/** Rejects physically implausible early endings, not a speech-to-text verifier. */
object PocketCompletion {
    const val MAX_ATTEMPTS = 3

    fun isObviouslyIncomplete(text: String, frames: Int, rate: Int, hasSignal: Boolean): Boolean {
        require(rate > 0)
        if (!hasSignal || frames <= 0) return true
        val words = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?").findAll(text).count()
        if (words < 5) return false
        // Eight words/second is already very fast for a story. Include the
        // engine's 100ms sentence pause: silence must not satisfy speech time.
        val minimum = ceil(words * rate / 8.0).toInt() + rate / 10
        return frames < minimum
    }
}
