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

    @Test fun softLimiterKeepsQuietPartsUntouchedAndNeverClips() {
        val loud = Cue("a", CueType.SFX, seconds = 1f, startSeconds = 15f, durationSeconds = 1f, volume = 1.5f)
        val quiet = Cue("b", CueType.SFX, seconds = 1f, startSeconds = 1f, durationSeconds = 1f)
        val out = DramaRenderer.render(project(loud, quiet), { c -> Clip(ShortArray(44100) { if (c.id == "a") 30000 else 10000 }, 1, 44100) })
        val peak = out.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("sin recortar: $peak", peak < 32767)
        assertEquals("lo bajito no se toca aunque haya un golpe fuerte en otro momento", 10000.0, at(out, 1.5).toDouble(), 40.0)
        assertTrue("el golpe fuerte queda comprimido pero sigue siendo el más fuerte", at(out, 15.5) in 26000..32766)
    }

    @Test fun softLimitIsContinuousMonotonicAndBounded() {
        var last = -1f
        for (i in 0..400) {
            val x = i / 100f
            val y = DramaRenderer.softLimit(x)
            assertTrue(y >= last - 1e-6f); assertTrue(y <= 1f); last = y
        }
        assertEquals(0.5f, DramaRenderer.softLimit(0.5f), 1e-6f)
        assertEquals(-DramaRenderer.softLimit(0.9f), DramaRenderer.softLimit(-0.9f), 1e-6f)
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

    @Test fun trimSilenceNeverSplitsAStereoFrame() {
        val pcm = shortArrayOf(0, 0, 0, 0, 5000, -5000, 6000, -6000, 0, 0)
        val out = WavIo.trimSilence(pcm, 44100, threshold = 500, marginSec = 0f, channels = 2)
        assertArrayEquals(shortArrayOf(5000, -5000, 6000, -6000), out)
    }

    @Test fun cutLeavesNoGapOrOverlapEvenLateInALongStory() {
        val c = Cue("v", CueType.VOICE, "n", "v", "v.wav", 40f, startSeconds = 1700.123f, durationSeconds = 40f)
        val cut = TimelineOps.split(project(c), "v", 1712.337, "w")!!
        val places = DramaRenderer.layout(cut).associateBy { it.cue.id }
        assertEquals(places.getValue("v").end, places.getValue("w").start, 1e-9)
        assertEquals(1700.123f.toDouble(), places.getValue("v").start, 1e-9)
        assertEquals(1700.123f.toDouble() + 40.0, places.getValue("w").end, 1e-3)
    }

    @Test fun duckingEntersAndLeavesWithoutJumps() {
        val music = Cue("m", CueType.MUSIC, seconds = 6f, mode = CueMode.BED, startSeconds = 0f, durationSeconds = 6f)
        val voice = Cue("v", CueType.VOICE, "n", "v", "v.wav", 2f, startSeconds = 2f, durationSeconds = 2f)
        val p = project(music, voice).copy(duckMusic = true)
        val out = DramaRenderer.render(p, { c -> if (c.id == "m") Clip(ShortArray(44100 * 6) { 8000 }, 1, 44100) else Clip(ShortArray(44100 * 2), 1, 44100) })
        var worst = 0
        for (i in 4410 until 44100 * 59 / 10) worst = maxOf(worst, kotlin.math.abs(out[2 * i] - out[2 * (i - 1)]))
        assertTrue("salto brusco de $worst entre muestras", worst <= 12)
        assertTrue("la música debe bajar durante la voz", at(out, 3.0) < 8000 * 0.5)
        assertTrue("antes de la voz suena entera", at(out, 0.5) > 7000)
    }

    @Test fun streamingWavWithZeroDataSizeStillReads() {
        val f = File.createTempFile("wav-stream", ".wav")
        val pcm = ShortArray(500) { (it - 250).toShort() }
        WavIo.write(f, pcm, 1, 22050)
        val bytes = f.readBytes()
        for (k in 40..43) bytes[k] = 0   // data size = 0, como dejan algunas apps al grabar en streaming
        f.writeBytes(bytes)
        assertArrayEquals(pcm, WavIo.read(f)!!.pcm)
        f.delete()
    }

    @Test fun cancelledRenderStopsInsteadOfFinishing() {
        val p = project(Cue("v", CueType.VOICE, "n", "v", "v.wav", 25f, startSeconds = 0f, durationSeconds = 25f))
        var blocks = 0
        try {
            DramaRenderer.render(p, { Clip(ShortArray(44100 * 25) { 100 }, 1, 44100) }, { blocks++ }, { blocks >= 2 })
            fail("debía cancelarse")
        } catch (e: java.util.concurrent.CancellationException) { /* esperado */ }
        assertTrue(blocks in 2..4)
    }

    @Test fun loopedClipWithFractionalTrimNeverReadsPastTheEnd() {
        // 48 kHz → 44,1 kHz y recorte fraccionario: antes podía salirse del arreglo al dar la vuelta
        val clip = Clip(ShortArray(48000) { (it % 1000).toShort() }, 1, 48000)
        val c = Cue("m", CueType.MUSIC, seconds = 1f, mode = CueMode.BED, startSeconds = 0f, durationSeconds = 6f, trimStart = 0.0123f)
        val out = DramaRenderer.render(project(c), { clip })
        assertTrue(out.size > 44100 * 6 * 2 - 10)
    }

    @Test fun dataChunkOfSizeZeroFollowedByMetadataIsNotReadAsAudio() {
        val f = File.createTempFile("wav-meta", ".wav")
        WavIo.write(f, ShortArray(0), 1, 22050)
        f.appendBytes("LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + "abcd".toByteArray())
        assertEquals(0, WavIo.read(f)!!.pcm.size)
        f.delete()
    }

    @Test fun loopedTwoSampleClipAndPcmThatLooksLikeLettersAreSafe() {
        val tiny = Clip(shortArrayOf(1000, -1000), 1, 44100)
        val c = Cue("m", CueType.MUSIC, seconds = 1f, mode = CueMode.BED, startSeconds = 0f, durationSeconds = 1f, trimStart = 0.5f / 44100f)
        assertTrue(DramaRenderer.render(project(c), { tiny }).isNotEmpty())
        // PCM negativo cuyos bytes parecen letras: no debe confundirse con un chunk ni perder el audio
        val f = File.createTempFile("wav-pcm", ".wav")
        val pcm = ShortArray(400) { 0xC2C2.toShort() }
        WavIo.write(f, pcm, 1, 22050)
        val bytes = f.readBytes(); for (k in 40..43) bytes[k] = 0; f.writeBytes(bytes)
        assertEquals(400, WavIo.read(f)!!.pcm.size)
        f.delete()
    }
}
