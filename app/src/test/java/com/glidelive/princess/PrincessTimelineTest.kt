package com.glidelive.princess

import org.junit.Assert.*
import org.junit.Test

class PrincessTimelineTest {
    private val narrator = Character("n", "Narrador", 1L)
    private fun project(vararg cues: Cue) = Project("p", "Bosque", listOf(narrator), cues.toList(), normalizeVoices = false, duckMusic = false)
    private fun voice(id: String, start: Float, duration: Float = 1f) = Cue(id, CueType.VOICE, "n", id, "$id.wav", duration, startSeconds = start)
    private fun at(pcm: ShortArray, seconds: Double) = pcm[(seconds * DramaRenderer.RATE).toInt() * 2].toInt()

    @Test fun absolutePositionsAllowOverlapsRegardlessOfListOrder() {
        val p = project(voice("later", 2f), voice("first", 0f), voice("overlap", .5f))
        val places = DramaRenderer.layout(p).associateBy { it.cue.id }
        assertEquals(0.0, places.getValue("first").start, 1e-6)
        assertEquals(.5, places.getValue("overlap").start, 1e-6)
        assertEquals(3.0, DramaRenderer.totalSeconds(p), 1e-6)
        val mix = DramaRenderer.render(p, { Clip(ShortArray(44100) { 3000 }, 1, 44100) })
        assertEquals(6000.0, at(mix, .75).toDouble(), 10.0)
        assertEquals(3000.0, at(mix, 2.5).toDouble(), 10.0)
    }

    @Test fun anchoringLetsOneClipMoveWithoutMovingTheOthers() {
        val p = project(
            Cue("music", CueType.MUSIC, seconds = 2f, gap = 0f, mode = CueMode.BED),
            Cue("a", CueType.VOICE, "n", seconds = 1f, gap = .5f),
            Cue("b", CueType.VOICE, "n", seconds = 2f, gap = .5f),
        )
        val before = DramaRenderer.layout(p).associateBy { it.cue.id }
        val anchored = DramaRenderer.anchorTimeline(p)
        for (after in DramaRenderer.layout(anchored)) {
            assertEquals(before.getValue(after.cue.id).start, after.start, 1e-6)
            assertEquals(before.getValue(after.cue.id).end, after.end, 1e-6)
        }
        val moved = anchored.copy(cues = anchored.cues.map { if (it.id == "a") it.copy(startSeconds = 7f) else it })
        assertEquals(before.getValue("b").start, DramaRenderer.layout(moved).first { it.cue.id == "b" }.start, 1e-6)
        assertEquals(before.getValue("music").end, DramaRenderer.layout(moved).first { it.cue.id == "music" }.end, 1e-6)
    }

    @Test fun trimsSourceAndStopsAtEditedDuration() {
        val p = project(voice("trim", .2f).copy(trimStart = .5f, durationSeconds = .25f))
        val clip = Clip(ShortArray(44100) { if (it < 22050) 1000 else 10000 }, 1, 44100)
        val mix = DramaRenderer.render(p, { clip })
        assertEquals(0, at(mix, .1))
        assertEquals(10000.0, at(mix, .3).toDouble(), 10.0)
        assertEquals(0, at(mix, .5))
        assertEquals(.45, DramaRenderer.totalSeconds(p), 1e-5)
    }

    @Test fun explicitLoopRepeatsTrimmedSourceAndMutedVoicesDoNotDuck() {
        val c = Cue("amb", CueType.SFX, seconds = .1f, startSeconds = 0f, trimStart = .05f, durationSeconds = .5f, loop = true)
        val p = project(c)
        val mix = DramaRenderer.render(p, { Clip(ShortArray(4410) { if (it < 2205) 1000 else 4000 }, 1, 44100) })
        assertEquals(4000.0, at(mix, .44).toDouble(), 10.0)
        val muted = project(voice("v", 0f).copy(muted = true))
        assertTrue(DramaRenderer.render(muted, { Clip(ShortArray(44100) { 15000 }, 1, 44100) }).all { it == 0.toShort() })
        assertTrue(DramaRenderer.duckEnvelope(DramaRenderer.layout(muted), 1.0).all { it == 1f })
    }

    @Test fun backgroundWithoutVoicesHasItsOwnAudibleDuration() {
        val p = project(Cue("music", CueType.MUSIC, seconds = 4f, gap = 0f, mode = CueMode.BED))
        assertEquals(4.0, DramaRenderer.totalSeconds(p), 1e-6)
    }

    @Test fun timelineAndVoiceSettingsSurviveProjectJson() {
        val p = project(voice("a", 3.25f).copy(trimStart = .2f, durationSeconds = .4f, muted = true, loop = true))
            .copy(characters = listOf(narrator.copy(narrationEngine = "pocket", narrationVoice = "voice-a")))
        assertEquals(p, PrincessLibrary.parse(PrincessLibrary.toJson(p).toString()))
        val json = PrincessLibrary.toJson(project(voice("old", 0f)))
        val cue = json.getJSONArray("cues").getJSONObject(0)
        listOf("startSeconds", "durationSeconds", "trimStart", "muted", "loop").forEach { cue.remove(it) }
        val legacy = PrincessLibrary.parse(json.toString()).cues.single()
        assertNull(legacy.startSeconds)
        assertNull(legacy.durationSeconds)
        assertFalse(legacy.loop)
        assertFalse(legacy.muted)
    }
}
