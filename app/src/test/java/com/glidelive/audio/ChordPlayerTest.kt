package com.glidelive.audio

import com.glidelive.theory.Chord
import com.glidelive.theory.ChordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordPlayerTest {
    private val cmds = ArrayList<SynthCommand>()
    private class Task(val delayMs: Long, val action: () -> Unit) { var cancelled = false }
    private val tasks = ArrayList<Task>()
    private val player = ChordPlayer({ cmds.add(it) }, { d, a ->
        val t = Task(d, a); tasks.add(t); Cancelable { t.cancelled = true }
    })

    private fun play(c: Chord) = player.play(c.tones, null, emptyMap(), 0, 45, 72)

    @Test fun firstChordTriggersNotesThenGlides() {
        play(Chord(0, ChordType.MAJ))
        assertEquals(3, cmds.filterIsInstance<SynthCommand.NoteOn>().size)
        cmds.clear()
        play(Chord(5, ChordType.MAJ))
        assertEquals(3, cmds.filterIsInstance<SynthCommand.GlideTo>().size)
        assertEquals(setOf(0, 1, 2), cmds.filterIsInstance<SynthCommand.GlideTo>().map { it.slot }.toSet())
    }

    @Test fun addingVoicesSpawnsFromExisting() {
        play(Chord(0, ChordType.MAJ))
        cmds.clear()
        play(Chord(0, ChordType.MAJ9))
        val moves = cmds.count { it is SynthCommand.GlideTo || it is SynthCommand.Spawn }
        assertEquals(5, moves)
        assertTrue(cmds.any { it is SynthCommand.Spawn })
    }

    @Test fun removingVoicesMergesAndReleases() {
        play(Chord(0, ChordType.MAJ9))
        cmds.clear()
        play(Chord(0, ChordType.MAJ))
        val moves = cmds.count { it is SynthCommand.GlideTo || it is SynthCommand.Spawn }
        assertEquals(3, moves)
        assertTrue(cmds.any { it is SynthCommand.GlideRelease })
    }

    @Test fun stopAllThenNewChordRetriggers() {
        play(Chord(0, ChordType.MAJ))
        player.stopAll()
        assertTrue(cmds.last() is SynthCommand.ReleaseAll)
        cmds.clear()
        play(Chord(7, ChordType.DOM7))
        assertEquals(4, cmds.filterIsInstance<SynthCommand.NoteOn>().size)
    }

    private val cm9 = Chord(0, ChordType.MAJ9)

    @Test fun blendConvergesInnerVoicesAndSchedulesTail() {
        play(cm9)
        val notes = player.lastVoicing.sorted()
        assertEquals(5, notes.size)
        cmds.clear()
        player.stopAll(ExitMode.BLEND, 250, 1500)
        val gr = cmds.filterIsInstance<SynthCommand.GlideRelease>()
        assertEquals(3, gr.size)
        assertTrue(gr.all { it.midi == notes[2] && it.glideMs >= 600 })
        assertTrue(cmds.none { it is SynthCommand.ReleaseAll })
        assertEquals(1, tasks.size)
        assertEquals(1500L, tasks[0].delayMs)
        cmds.clear()
        tasks[0].action()
        assertEquals(2, cmds.filterIsInstance<SynthCommand.Release>().size)
    }

    @Test fun blendKeepsLongerUserGlide() {
        play(cm9)
        cmds.clear()
        player.stopAll(ExitMode.BLEND, 1200, 1500)
        assertTrue(cmds.filterIsInstance<SynthCommand.GlideRelease>().all { it.glideMs == 1200 })
    }

    @Test fun playAfterBlendCancelsTailAndReleasesBeforeNoteOn() {
        play(cm9)
        player.stopAll(ExitMode.BLEND, 250, 1500)
        cmds.clear()
        play(Chord(7, ChordType.DOM7))
        assertTrue(tasks[0].cancelled)
        val firstOn = cmds.indexOfFirst { it is SynthCommand.NoteOn }
        val releases = cmds.take(firstOn).filterIsInstance<SynthCommand.Release>()
        assertEquals(2, releases.size)
        assertEquals(4, cmds.filterIsInstance<SynthCommand.NoteOn>().size)
    }

    @Test fun cutSendsReleaseAll() {
        play(cm9)
        cmds.clear()
        player.stopAll(ExitMode.CUT)
        assertTrue(cmds.single() is SynthCommand.ReleaseAll)
        assertTrue(tasks.isEmpty())
    }

    @Test fun cutCancelsPendingTail() {
        play(cm9)
        player.stopAll(ExitMode.BLEND, 250, 1500)
        cmds.clear()
        player.stopAll(ExitMode.CUT)
        assertTrue(tasks[0].cancelled)
        assertEquals(2, cmds.filterIsInstance<SynthCommand.Release>().size)
    }

    @Test fun singleVoiceReleasesAfterTail() {
        player.play(listOf(0), null, emptyMap(), 0, 45, 72)
        cmds.clear()
        player.stopAll(ExitMode.BLEND, 250, 800)
        assertTrue(cmds.isEmpty())
        assertEquals(800L, tasks[0].delayMs)
        tasks[0].action()
        assertEquals(1, cmds.filterIsInstance<SynthCommand.Release>().size)
    }
}
