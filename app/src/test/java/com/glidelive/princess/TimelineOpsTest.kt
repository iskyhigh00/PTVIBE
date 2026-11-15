package com.glidelive.princess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TimelineOpsTest {
    private val rate = 44100
    private val who = Character("n", "Narrador", 0xFFFF6B9D)

    /** Una rampa de 4 s: cada instante tiene un valor distinto, así se nota si un trozo quedó mal colocado. */
    private fun ramp(seconds: Float) = Clip(ShortArray((seconds * rate).toInt()) { i -> (i * 20000L / (seconds * rate)).toInt().toShort() }, 1, rate)

    private fun project(vararg cues: Cue) = Project("p", "cuento", listOf(who), cues.toList(), normalizeVoices = false, duckMusic = false)
    private fun voice(id: String, sec: Float, start: Float? = 0f) = Cue(id, CueType.VOICE, "n", id, "$id.wav", sec, 0f, startSeconds = start)

    @Test fun cuttingAndPlayingBothPiecesSoundsLikeTheOriginal() {
        val p = project(voice("a", 4f))
        val whole = DramaRenderer.render(p, { ramp(4f) })
        val cut = TimelineOps.split(p, "a", 1.7, "b")
        assertNotNull(cut)
        assertEquals(2, cut!!.cues.size)
        val parts = DramaRenderer.render(cut, { ramp(4f) })
        assertEquals(whole.size.toDouble(), parts.size.toDouble(), 8.0)
        var worst = 0
        for (i in 0 until minOf(whole.size, parts.size) step 7) worst = maxOf(worst, abs(whole[i] - parts[i]))
        assertTrue("las dos mitades suenan igual que el original (diferencia máx $worst)", worst <= 3)
    }

    @Test fun aRightPieceCanBeMovedWithoutChangingTheLeftOne() {
        val p = project(voice("a", 4f))
        val cut = TimelineOps.split(p, "a", 1.0, "b")!!
        val moved = TimelineOps.moveTo(cut, "b", 6.0)
        val places = DramaRenderer.layout(moved).sortedBy { it.start }
        assertEquals(0.0, places[0].start, 1e-6); assertEquals(1.0, places[0].end, 1e-6)
        assertEquals(6.0, places[1].start, 1e-6); assertEquals(9.0, places[1].end, 1e-6)   // el trozo derecho dura 3 s
        val out = DramaRenderer.render(moved, { ramp(4f) })
        fun at(sec: Double) = out[(sec * rate).toInt() * 2].toInt()
        assertTrue("entre medio hay silencio", at(3.0) == 0)
        assertTrue("el trozo derecho empieza donde estaba en el original (1 s)", abs(at(6.05) - (1.05 * rate * 20000 / (4 * rate))) < 600)
    }

    @Test fun cuttingTwiceKeepsAllTheAudio() {
        val p = project(voice("a", 6f))
        val one = TimelineOps.split(p, "a", 2.0, "b")!!
        val two = TimelineOps.split(one, "b", 4.0, "c")!!
        assertEquals(3, two.cues.size)
        val total = DramaRenderer.layout(two).sumOf { it.end - it.start }
        assertEquals(6.0, total, 1e-3)
    }

    @Test fun badCutsAreRefusedWithAHumanMessage() {
        val p = project(voice("a", 4f), voice("m", 3f).copy(type = CueType.MUSIC, mode = CueMode.BED, loop = true, startSeconds = 0f))
        assertNotNull(TimelineOps.cutProblem(p, "a", 0.05))      // casi en el borde
        assertNotNull(TimelineOps.cutProblem(p, "a", 3.99))
        assertNotNull(TimelineOps.cutProblem(p, "m", 1.0))       // música de fondo
        assertNotNull(TimelineOps.cutProblem(p, "zzz", 1.0))
        assertNull(TimelineOps.cutProblem(p, "a", 2.0))
        assertNull(TimelineOps.split(p, "a", 0.05, "x"))
    }

    @Test fun historyUndoesAndRedoes() {
        val h = History<Int>(3)
        assertFalse(h.canUndo)
        h.record(1); h.record(2); h.record(3)
        assertEquals(3, h.undo(4)); assertEquals(2, h.undo(3)); assertTrue(h.canRedo)
        assertEquals(3, h.redo(2))
        h.record(9)                                  // una edición nueva borra lo que se podía rehacer
        assertFalse(h.canRedo)
        h.record(10); h.record(11); h.record(12)     // el tope descarta lo más antiguo
        assertEquals(12, h.undo(13)); assertEquals(11, h.undo(12)); assertEquals(10, h.undo(11)); assertNull(h.undo(10))
    }

    @Test fun toneStepsSurviveJson() {
        val p = project(voice("a", 2f).copy(toneSteps = 2, sourceFile = "orig.wav"))
        val back = PrincessLibrary.parse(PrincessLibrary.toJson(p).toString())
        assertEquals(2, back.cues[0].toneSteps); assertEquals("orig.wav", back.cues[0].sourceFile)
    }

    private fun hz(x: ShortArray, sr: Int, from: Int, to: Int): Double {
        var first = -1; var last = -1; var n = 0
        for (i in from + 1 until to) if (x[i - 1] < 0 && x[i] >= 0) { if (first < 0) first = i; last = i; n++ }
        return if (n > 1) (n - 1) * sr.toDouble() / (last - first) else 0.0
    }

    @Test fun toneChangesPitchButNotDuration() {
        val n = rate * 3
        val tone = ShortArray(n) { (9000 * sin(2 * PI * 440.0 * it / rate)).toInt().toShort() }
        val up = PitchShifter.shift(tone, rate, PitchShifter.semitonesFor(1).toFloat())   // +2 semitonos
        assertEquals(n.toDouble(), up.size.toDouble(), n * 0.02)
        assertEquals(493.9, hz(up, rate, rate, 2 * rate), 12.0)
        val down = PitchShifter.shift(tone, rate, PitchShifter.semitonesFor(-2).toFloat())  // −4 semitonos
        assertEquals(n.toDouble(), down.size.toDouble(), n * 0.02)
        assertEquals(349.2, hz(down, rate, rate, 2 * rate), 12.0)
        assertTrue("el volumen se mantiene", down.drop(rate).take(rate).maxOf { abs(it.toInt()) } > 6000)
        assertEquals(tone, PitchShifter.shift(tone, rate, 0f))
    }
}
