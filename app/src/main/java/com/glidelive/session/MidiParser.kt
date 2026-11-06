package com.glidelive.session

/**
 * Analizador MIDI incremental y puro (sin Android): running status, mensajes de tiempo real (0xF8–0xFF) que se
 * ignoran sin romper el mensaje en curso, SysEx que se salta hasta 0xF7 y mensajes de sistema con su longitud real.
 * Mantiene estado entre llamadas, así que un mensaje partido en dos paquetes se reconstruye.
 */
class MidiParser(private val sink: Sink) {
    interface Sink {
        fun noteOn(ch: Int, note: Int, vel: Int, timestamp: Long)
        fun noteOff(ch: Int, note: Int, timestamp: Long)
        fun pitchBend(ch: Int, value: Int, timestamp: Long)  // -8192..8191
    }

    private var running = 0     // último estado de canal (0x80..0xEF) o 0
    private var inSysex = false
    private var skip = 0        // bytes de datos de un mensaje de sistema que hay que saltar
    private var have = 0
    private var d1 = 0

    fun feed(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
        for (k in offset until offset + count) {
            val b = msg[k].toInt() and 0xFF
            if (b >= 0xF8) continue                      // tiempo real: 1 byte, se ignora
            if (b >= 0x80) {
                inSysex = false
                skip = 0
                when {
                    b == 0xF0 -> { inSysex = true; running = 0; have = 0 }
                    b == 0xF7 -> { running = 0; have = 0 }
                    b >= 0xF1 -> {                         // mensajes comunes de sistema
                        running = 0; have = 0
                        skip = when (b) { 0xF1, 0xF3 -> 1; 0xF2 -> 2; else -> 0 }
                    }
                    else -> { running = b; have = 0 }
                }
                continue
            }
            if (inSysex) continue
            if (skip > 0) { skip--; continue }
            if (running == 0) continue
            val cmd = running and 0xF0
            val need = if (cmd == 0xC0 || cmd == 0xD0) 1 else 2
            if (have == 0) d1 = b
            have++
            if (have < need) continue
            val d2 = if (need == 2) b else 0
            have = 0
            val ch = running and 0x0F
            when (cmd) {
                0x90 -> if (d2 > 0) sink.noteOn(ch, d1, d2, timestamp) else sink.noteOff(ch, d1, timestamp)
                0x80 -> sink.noteOff(ch, d1, timestamp)
                0xE0 -> sink.pitchBend(ch, ((d2 shl 7) or d1) - 8192, timestamp)
            }
        }
    }
}
