package com.glidelive.session

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    private val events = ArrayList<String>()
    private val parser = MidiParser(object : MidiParser.Sink {
        override fun noteOn(ch: Int, note: Int, vel: Int, timestamp: Long) { events.add("on $ch $note $vel") }
        override fun noteOff(ch: Int, note: Int, timestamp: Long) { events.add("off $ch $note") }
        override fun pitchBend(ch: Int, value: Int, timestamp: Long) { events.add("bend $ch $value") }
    })

    private fun feed(vararg b: Int) {
        val a = ByteArray(b.size) { b[it].toByte() }
        parser.feed(a, 0, a.size, 0L)
    }

    @Test fun activeSensingBeforeNoteOn() {
        feed(0xFE, 0x90, 0x3C, 0x64)
        assertEquals(listOf("on 0 60 100"), events)
    }

    @Test fun runningStatusGivesTwoNotes() {
        feed(0x90, 0x3C, 0x64, 0x3E, 0x64)
        assertEquals(listOf("on 0 60 100", "on 0 62 100"), events)
    }

    @Test fun realtimeInterleavedDoesNotBreakMessage() {
        feed(0x91, 0xF8, 0x3C, 0xF8, 0x64, 0xF8, 0x81, 0x3C, 0x00)
        assertEquals(listOf("on 1 60 100", "off 1 60"), events)
    }

    @Test fun noteOnVelocityZeroIsNoteOff() {
        feed(0x90, 0x3C, 0x64, 0x3C, 0x00)
        assertEquals(listOf("on 0 60 100", "off 0 60"), events)
    }

    @Test fun sysexIsSkippedUntilF7() {
        feed(0xF0, 0x7E, 0x00, 0x09, 0x01, 0xF7, 0x90, 0x40, 0x7F)
        assertEquals(listOf("on 0 64 127"), events)
    }

    @Test fun systemCommonMessagesUseTheirRealLength() {
        feed(0xF2, 0x10, 0x20, 0x90, 0x3C, 0x64)   // song position (2 datos)
        feed(0xF1, 0x05, 0x90, 0x3D, 0x64)          // quarter frame (1 dato)
        assertEquals(listOf("on 0 60 100", "on 0 61 100"), events)
    }

    @Test fun pitchBendCenterIsZeroAndMessageCanBeSplit() {
        feed(0xE2, 0x00)
        feed(0x40)
        assertEquals(listOf("bend 2 0"), events)
    }

    @Test fun orphanDataBytesAreIgnored() {
        feed(0x3C, 0x64)
        assertEquals(emptyList<String>(), events)
    }
}
