package com.glidelive.princess

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NarrationTailTest {
    @Test fun generatedMonoWavRetainsEverySampleIncludingItsEnding() {
        val file = Files.createTempFile("narration-tail", ".wav").toFile()
        try {
            val source = ShortArray(24000 * 3 + 731) { i -> if (i >= 24000 * 3) 16000 else (i % 200 - 100).toShort() }
            WavIo.write(file, source, 1, 24000)
            val decoded = requireNotNull(WavIo.read(file))
            assertArrayEquals(source, decoded.pcm)
            assertEquals(source.size / 24000.0, decoded.seconds, 1e-9)
            assertEquals(source.size * 2L + 44, file.length())
        } finally { file.delete() }
    }

    @Test fun timelineMixRetainsTheFinalSpeechRegionAtPocketSampleRate() {
        val source = ShortArray(24000 * 3 + 731) { i -> if (i >= 24000 * 3) 16000 else 1000 }
        val character = Character("n", "Narrador", 1L)
        val cue = Cue("c", CueType.VOICE, "n", "Final", "voice.wav", source.size / 24000f, gap = 0f)
        val p = Project("p", "Cola", listOf(character), listOf(cue), normalizeVoices = false, duckMusic = false)
        val mix = DramaRenderer.render(p, { Clip(source, 1, 24000) })
        val finalFrame = (3.02 * DramaRenderer.RATE).toInt()
        assertTrue(mix[finalFrame * 2] > 15000)
        assertTrue(mix[finalFrame * 2 + 1] > 15000)
        assertEquals(source.size / 24000.0, DramaRenderer.totalSeconds(p), 1e-6)
    }

    @Test fun aVoiceWithoutAFadeRetainsItsLastFrame() {
        val cue = Cue("voice", CueType.VOICE, seconds = 1f, gap = 0f)
        val p = Project("p", "Final", emptyList(), listOf(cue), normalizeVoices = false)
        val source = ShortArray(DramaRenderer.RATE).also { it[it.lastIndex] = 3072 }
        val mix = DramaRenderer.render(p, { Clip(source, 1, DramaRenderer.RATE) })
        assertEquals(3072, mix[2 * (source.size - 1)].toInt())
    }

    @Test fun anAmbientClipReachesSilenceAtTheTwoMixBoundaries() {
        val rate = DramaRenderer.RATE
        val cue = Cue("rain", CueType.SFX, seconds = 5f, gap = 0f, fadeIn = .8f, fadeOut = 1.2f)
        val p = Project("p", "Lluvia", emptyList(), listOf(cue))
        val source = ShortArray(rate * 5) { 8000 }
        val mix = DramaRenderer.render(p, { Clip(source, 1, rate) })
        assertEquals(0, mix[0].toInt())
        assertEquals(4000.0, mix[(.4 * rate).toInt() * 2].toDouble(), 2.0)
        assertEquals(8000, mix[rate * 2].toInt())
        assertEquals(4000.0, mix[(4.4 * rate).toInt() * 2].toDouble(), 2.0)
        assertEquals(0, mix[(source.size - 1) * 2].toInt())
    }
}
