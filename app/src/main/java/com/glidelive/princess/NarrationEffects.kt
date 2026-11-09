package com.glidelive.princess

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** Gentle offline coloration of speech; keeps silence and the clip's duration intact. */
object NarrationEffects {
    fun apply(samples: ShortArray, sampleRate: Int, texture: NarrationTexture) {
        require(sampleRate > 0)
        if (texture == NarrationTexture.NATURAL) return
        val alpha = 1.0 - exp(-2.0 * PI * 1600.0 / sampleRate)
        var low = 0.0
        for (i in samples.indices) {
            val dry = samples[i] / 32768.0
            low += alpha * (dry - low)
            val time = i.toDouble() / sampleRate
            val wet = when (texture) {
                NarrationTexture.BRIGHT -> dry + 0.55 * (dry - low)
                NarrationTexture.WARM -> 0.35 * dry + 0.65 * low
                NarrationTexture.ROUGH -> tanh(1.8 * dry) / tanh(1.8) * (0.88 + 0.12 * sin(2 * PI * 43 * time))
                NarrationTexture.TREMBLING -> dry * (0.90 + 0.10 * sin(2 * PI * 5 * time))
                NarrationTexture.VIBRANT -> dry * (0.80 + 0.20 * sin(2 * PI * 24 * time))
                NarrationTexture.NATURAL -> dry
            }
            samples[i] = (wet * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
