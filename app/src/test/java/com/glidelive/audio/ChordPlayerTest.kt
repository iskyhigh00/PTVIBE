package com.glidelive.audio

import com.glidelive.theory.Chord
import com.glidelive.theory.ChordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordPlayerTest {
    private val cmds = ArrayList<SynthCommand>()
    private val player = ChordPlayer({ cmds.add(it) })

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
}
