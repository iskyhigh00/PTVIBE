package com.glidelive.princess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DramaRendererTest {
    private val narrator = Character("n", "Narrador", 0xFFFF6B9D)
    private val squeak = Character("s", "Ratón", 0xFF60A5FA, pitch = 12f)

    private fun cue(id: String, type: CueType, sec: Float, gap: Float = 0f, mode: CueMode = CueMode.SEQ, ch: String? = "n", span: Int = 0, vol: Float = 1f) =
        Cue(id, type, ch, id, "$id.wav", sec, gap, mode, vol, span)

    private fun project(vararg cues: Cue, norm: Boolean = false, duck: Boolean = false) =
        Project("p", "obra", listOf(narrator, squeak), cues.toList(), norm, duck)

    private fun constant(value: Int, seconds: Float, rate: Int = 44100) = Clip(ShortArray((seconds * rate).toInt()) { value.toShort() }, 1, rate)

    @Test fun cuesFollowEachOtherWithTheirGaps() {
        val p = project(
            cue("v1", CueType.VOICE, 2f), cue("v2", CueType.VOICE, 1f, gap = 1f),
            cue("sfx", CueType.SFX, 1f, gap = 0.5f, mode = CueMode.WITH_PREV, ch = null), cue("v3", CueType.VOICE, 2f, gap = 2f),
        )
        val l = DramaRenderer.layout(p).sortedBy { it.index }
        assertEquals(0.0, l[0].start, 1e-6); assertEquals(2.0, l[0].end, 1e-6)
        assertEquals(3.0, l[1].start, 1e-6); assertEquals(4.0, l[1].end, 1e-6)
        assertEquals(3.5, l[2].start, 1e-6)   // un efecto a medio segundo de entrar la 2.ª voz
        assertEquals(6.5, l[3].start, 1e-6)   // el espacio cuenta desde lo último que sonó (4,5 s)
        assertEquals(8.5, DramaRenderer.totalSeconds(p), 1e-6)
    }

    @Test fun backgroundMusicCoversTheCuesItIsMeantFor() {
        val p = project(
            cue("m", CueType.MUSIC, 4f, mode = CueMode.BED, ch = null, span = 2),
            cue("v1", CueType.VOICE, 2f, gap = 1f), cue("v2", CueType.VOICE, 2f, gap = 1f), cue("v3", CueType.VOICE, 2f, gap = 1f),
        )
        val m = DramaRenderer.layout(p).first { it.index == 0 }
        assertEquals(0.0, m.start, 1e-6)
        assertEquals(6.0, m.end, 1e-6)        // hasta el final de la 2.ª voz que le sigue (1-3, 4-6)
        val all = project(cue("m", CueType.MUSIC, 4f, mode = CueMode.BED, ch = null, span = 0), cue("v1", CueType.VOICE, 2f, gap = 1f), cue("v2", CueType.VOICE, 2f, gap = 1f))
        assertEquals(6.0, DramaRenderer.layout(all).first { it.index == 0 }.end, 1e-6)
    }

    @Test fun aHigherVoiceIsShorter() {
        val p = project(cue("r", CueType.VOICE, 2f, ch = "s"))
        assertEquals(1.0, DramaRenderer.totalSeconds(p), 1e-6)   // +12 semitonos = el doble de agudo y la mitad de largo
    }

    @Test fun renderPlacesEachClipAtItsTime() {
        val p = project(cue("v1", CueType.VOICE, 1f, gap = 1f))
        val out = DramaRenderer.render(p) { constant(16384, 1f) }
        fun at(sec: Double) = out[((sec * DramaRenderer.RATE).toInt()) * 2].toInt()
        assertTrue("antes de entrar hay silencio", at(0.5) == 0)
        assertEquals(16383.0, at(1.5).toDouble(), 80.0)
        assertTrue("después de terminar hay silencio", at(2.3) == 0)
        assertEquals(out[at(1.5).let { (1.5 * DramaRenderer.RATE).toInt() * 2 + 1 }].toInt(), at(1.5))  // mismo nivel en ambos canales
    }

    @Test fun backgroundMusicLoopsAndDucksUnderVoices() {
        val p = project(
            cue("m", CueType.MUSIC, 1f, mode = CueMode.BED, ch = null, vol = 0.5f),
            cue("v1", CueType.VOICE, 1f, gap = 2f),
            duck = true,
        )
        val out = DramaRenderer.render(p) { c -> if (c.type == CueType.MUSIC) constant(16384, 1f) else constant(0, 1f) }
        fun at(sec: Double) = out[((sec * DramaRenderer.RATE).toInt()) * 2].toInt()
        val loud = at(1.0)             // la música se repite sola (el archivo dura 1 s y la obra 3 s)
        assertEquals(8191.0, loud.toDouble(), 100.0)
        val ducked = at(2.7)           // mientras habla la voz (2-3 s) la música baja
        assertTrue("música más baja bajo la voz: $ducked < $loud", ducked < loud * 0.6)
    }

    @Test fun voicesAreLevelledWhenAsked() {
        val quiet = constant(4000, 1f)
        val g = DramaRenderer.voiceGain(quiet)
        assertEquals(0.7f * 32768f / 4000f, g, 0.01f)
        val p = project(cue("v", CueType.VOICE, 1f), norm = true)
        val out = DramaRenderer.render(p) { quiet }
        assertTrue(out[22050 * 2] > 15000)
    }

    @Test fun silenceCueOnlyAddsTime() {
        val p = project(cue("v", CueType.VOICE, 1f), Cue("s", CueType.SILENCE, null, "", null, 2f, 0f), cue("w", CueType.VOICE, 1f))
        val l = DramaRenderer.layout(p).sortedBy { it.index }
        assertEquals(3.0, l[2].start, 1e-6)
    }

    @Test fun projectSurvivesJson() {
        val p = project(cue("v1", CueType.VOICE, 2f, gap = 1.5f), cue("m", CueType.MUSIC, 4f, mode = CueMode.BED, ch = null, span = 3))
        val back = PrincessLibrary.parse(PrincessLibrary.toJson(p).toString())
        assertEquals(p, back)
    }
}
