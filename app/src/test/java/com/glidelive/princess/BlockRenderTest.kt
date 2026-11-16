package com.glidelive.princess

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BlockRenderTest {
    private val narrator = Character("n", "Narrador", 1L)
    private fun project(vararg cues: Cue) = Project("p", "T", listOf(narrator), cues.toList(), normalizeVoices = false, duckMusic = false)
    private fun at(pcm: ShortArray, sec: Double) = pcm[(sec * DramaRenderer.RATE).toInt() * 2].toInt()

    @Test fun aClipCrossingBlockBoundariesHasNoGapsOrJumps() {
        val p = project(Cue("v", CueType.VOICE, "n", "v", "v.wav", 25f, startSeconds = 1f, durationSeconds = 25f))
        val out = DramaRenderer.render(p, { Clip(ShortArray(44100 * 25) { 3000 }, 1, 44100) })
        for (t in listOf(1.5, 9.999, 10.0, 10.001, 11.0, 19.999, 20.0, 20.001, 25.9)) assertEquals("en $t s", 3000.0, at(out, t).toDouble(), 5.0)
        assertEquals(0.0, at(out, 0.5).toDouble(), 1.0)
    }

    @Test fun loopedMusicContinuesSeamlesslyAcrossBlocks() {
        val tone = ShortArray(44100) { i -> (8000 * Math.sin(2 * Math.PI * 441 * i / 44100.0)).toInt().toShort() }
        val p = project(Cue("m", CueType.MUSIC, seconds = 1f, mode = CueMode.BED, startSeconds = 0f, durationSeconds = 23f))
        val out = DramaRenderer.render(p, { Clip(tone, 1, 44100) })
        // 441 Hz en 44100 Hz repite cada 100 muestras: el valor en el cuadro n y n+100 debe coincidir, también al cruzar los 10 s y 20 s.
        for (frame in listOf(441_000 - 50, 441_000 + 40, 882_000 - 10, 882_000 + 30)) {
            assertEquals(out[2 * frame].toDouble(), out[2 * (frame + 100)].toDouble(), 3.0)
        }
    }

    @Test fun peakLimiterAppliesToTheWholeStoryNotJustABlock() {
        val loud = Cue("a", CueType.SFX, seconds = 1f, startSeconds = 15f, durationSeconds = 1f, volume = 1.5f)
        val quiet = Cue("b", CueType.SFX, seconds = 1f, startSeconds = 1f, durationSeconds = 1f)
        val out = DramaRenderer.render(project(loud, quiet), { c -> Clip(ShortArray(44100) { if (c.id == "a") 30000 else 10000 }, 1, 44100) })
        val peak = out.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("el pico debe quedar bajo el techo: $peak", peak <= 32767 * 0.981)
        assertTrue("lo bajito se baja parejo", at(out, 1.5) < 10000)
    }

    @Test fun streamedOutputEqualsTheFullRender() {
        val p = project(Cue("v", CueType.VOICE, "n", "v", "v.wav", 12f, startSeconds = 4f, durationSeconds = 12f))
        val clip = Clip(ShortArray(44100 * 12) { i -> (i % 2000).toShort() }, 1, 44100)
        val full = DramaRenderer.render(p, { clip })
        val acc = ArrayList<Short>()
        DramaRenderer.renderTo(p, { clip }, {}) { block, _, n -> for (i in 0 until n * 2) acc.add(block[i]) }
        assertEquals(full.size, acc.size)
        for (i in full.indices step 997) assertEquals(full[i], acc[i])
    }

    @Test fun wavWriterRoundTrips() {
        val f = File.createTempFile("wav-test", ".wav")
        val pcm = ShortArray(1000) { (it * 7 - 3000).toShort() }
        WavIo.Writer(f, 1, 22050).use { w -> w.write(pcm, 600); w.write(pcm.copyOfRange(600, 1000)) }
        val d = WavIo.read(f)!!
        assertEquals(22050, d.rate); assertEquals(1, d.channels)
        assertArrayEquals(pcm, d.pcm)
        f.delete()
    }

    @Test fun orphansAreFilesNoClipUses() {
        val p = project(Cue("a", CueType.VOICE, "n", "a", "voz_1.wav", 1f), Cue("t", CueType.VOICE, "n", "t", "tono_2.wav", 1f, sourceFile = "voz_3.wav"))
        val files = listOf("voz_1.wav", "tono_2.wav", "voz_3.wav", "voz_old.wav", "Mi cuento.wav", "project.json", "project-123.tmp", "export.part")
        assertEquals(listOf("voz_old.wav", "Mi cuento.wav", "export.part"), PrincessLibrary.orphans(files, p))
    }

    @Test fun anchoringKeepsBackgroundMusicOpenEndedSoLaterVoicesStayCovered() {
        val p = project(
            Cue("m", CueType.MUSIC, seconds = 30f, gap = 0f, mode = CueMode.BED),
            Cue("a", CueType.VOICE, "n", "a", "a.wav", 5f, gap = 0f),
        )
        val anchored = DramaRenderer.anchorTimeline(p)
        val grown = anchored.copy(cues = anchored.cues + Cue("b", CueType.VOICE, "n", "b", "b.wav", 8f, startSeconds = 5f, durationSeconds = 8f))
        val music = DramaRenderer.layout(grown).first { it.cue.id == "m" }
        assertEquals(13.0, music.end, 1e-6)
    }

    @Test fun fadesAndTrimAcrossABlockBoundaryMatchTheFormula() {
        // voz de 6 s que empieza en 8 s (cruza los 10 s), con entrada de 2 s, salida de 2 s y 1 s recortado al inicio
        val ramp = ShortArray(44100 * 8) { i -> (i / 44100 * 1000).toShort() }   // 0, 1000, 2000… por segundo
        val cue = Cue("v", CueType.VOICE, "n", "v", "v.wav", 8f, startSeconds = 8f, durationSeconds = 6f, trimStart = 1f, fadeIn = 2f, fadeOut = 2f)
        val out = DramaRenderer.render(project(cue), { Clip(ramp, 1, 44100) })
        fun expected(t: Double): Double {           // t en segundos de la mezcla
            val local = t - 8.0
            val source = 1.0 + local                // trimStart
            val value = (source.toInt()) * 1000.0
            val fade = minOf(1.0, local / 2.0, (6.0 - local) / 2.0)
            return value * fade
        }
        for (t in listOf(9.0, 9.9, 10.1, 11.5, 12.5, 13.5)) assertEquals("en $t s", expected(t), at(out, t).toDouble(), 80.0)
    }
}
