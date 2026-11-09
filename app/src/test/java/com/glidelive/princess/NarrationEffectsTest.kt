package com.glidelive.princess

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class NarrationEffectsTest {
    @Test fun naturalPreservesSamplesExactly() {
        val original = shortArrayOf(Short.MIN_VALUE, -12000, 0, 12000, Short.MAX_VALUE)
        val samples = original.copyOf()
        NarrationEffects.apply(samples, 24000, NarrationTexture.NATURAL)
        assertArrayEquals(original, samples)
    }

    @Test fun effectsDoNotAddSoundToSilence() {
        for (texture in NarrationTexture.entries) {
            val samples = ShortArray(2400)
            NarrationEffects.apply(samples, 24000, texture)
            assertArrayEquals(texture.label, ShortArray(2400), samples)
        }
    }

    @Test fun texturesProduceDistinctSignalsAtSupportedSampleRates() {
        for (rate in listOf(8000, 24000, 44100, 48000)) {
            val original = ShortArray(rate / 5) { i ->
                (9000 * sin(2 * PI * 440 * i / rate) + 6000 * sin(2 * PI * 3100 * i / rate)).toInt().toShort()
            }
            val outputs = NarrationTexture.entries.map { texture ->
                original.copyOf().also { samples ->
                    NarrationEffects.apply(samples, rate, texture)
                    assertTrue(texture.label, samples.any { it != 0.toShort() })
                    if (texture != NarrationTexture.NATURAL) assertFalse(texture.label, original.contentEquals(samples))
                }.toList()
            }
            assertEquals(NarrationTexture.entries.size, outputs.toSet().size)
        }
    }

    @Test fun brightTransientsSaturateWithoutWrappingPcm() {
        val samples = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)
        NarrationEffects.apply(samples, 48000, NarrationTexture.BRIGHT)
        assertEquals(Short.MAX_VALUE, samples[0])
        assertEquals(Short.MIN_VALUE, samples[1])
    }
}
