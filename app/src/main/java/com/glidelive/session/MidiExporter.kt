package com.glidelive.session

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Exporta la sesión a un archivo MIDI (formato 1): un canal por voz de acorde (0-7) con pitch bend para el glide,
 * batería en el canal 10 con el mapa General MIDI y un canal por dedo del solista (11-16).
 */
object MidiExporter {
    const val PPQ = 480
    private const val BEND_RANGE = 12f  // semitonos (se fija con RPN)

    private val gmDrum = intArrayOf(36, 38, 37, 42, 46, 44, 48, 45, 43, 49, 51, 53, 55)

    private class Ev(val tick: Long, val order: Int, val bytes: ByteArray)

    private fun curveK(c: Int, p: Float) = when (c) { 1 -> p * p * (3 - 2 * p); 2 -> p * p; else -> p }

    private fun bend(ch: Int, note: Int, pitch: Float): ByteArray {
        val v = (8192 + (pitch - note) / BEND_RANGE * 8192f).roundToInt().coerceIn(0, 16383)
        return byteArrayOf((0xE0 or ch).toByte(), (v and 0x7F).toByte(), ((v shr 7) and 0x7F).toByte())
    }

    private fun on(ch: Int, n: Int, v: Int) = byteArrayOf((0x90 or ch).toByte(), n.coerceIn(0, 127).toByte(), v.coerceIn(1, 127).toByte())
    private fun off(ch: Int, n: Int) = byteArrayOf((0x80 or ch).toByte(), n.coerceIn(0, 127).toByte(), 0)

    fun build(events: List<LogEv>, bpm: Int, startMs: Double): ByteArray {
        fun tick(t: Double) = Math.round((t - startMs).coerceAtLeast(0.0) * bpm * PPQ / 60000.0)
        val ch = Array(16) { ArrayList<Ev>() }
        val used = BooleanArray(16)
        var seq = 0

        fun add(c: Int, t: Long, b: ByteArray) { used[c] = true; ch[c].add(Ev(t, seq++, b)) }

        // Estado por voz de acorde
        val note = IntArray(8) { -1 }
        val pitch = FloatArray(8)
        fun chordOff(s: Int, t: Long) { if (note[s] >= 0) { add(s, t, off(s, note[s])); note[s] = -1 } }
        fun glide(s: Int, t0: Double, ms: Float, curve: Int, to: Float) {
            val from = pitch[s]
            val steps = (ms / 15f).toInt().coerceIn(1, 400)
            for (i in 1..steps) {
                val p = i.toFloat() / steps
                val v = from + (to - from) * curveK(curve, p)
                add(s, tick(t0 + ms * p), bend(s, note[s], v))
            }
            pitch[s] = to
        }

        val soloNote = IntArray(6) { -1 }
        val soloPitch = FloatArray(6)
        val soloT = LongArray(6)

        for (e in events) {
            when (e) {
                is LogEv.Chord -> {
                    val s = e.slot
                    if (s !in 0..7 && e.type != 15) continue
                    val t = tick(e.tMs)
                    when (e.type) {
                        10 -> {
                            chordOff(s, t)
                            note[s] = e.midi.roundToInt(); pitch[s] = e.midi
                            add(s, t, bend(s, note[s], e.midi)); add(s, t, on(s, note[s], 80))
                        }
                        11, 13 -> {
                            if (note[s] < 0) { note[s] = e.midi.roundToInt(); pitch[s] = e.midi; add(s, t, on(s, note[s], 80)) }
                            else glide(s, e.tMs, e.glideMs, e.curve, e.midi)
                            if (e.type == 13) chordOff(s, tick(e.tMs + e.glideMs))
                        }
                        12 -> {
                            val src = if (e.fromSlot in 0..7 && note[e.fromSlot] >= 0) pitch[e.fromSlot] else e.midi
                            chordOff(s, t)
                            note[s] = src.roundToInt(); pitch[s] = src
                            add(s, t, bend(s, note[s], src)); add(s, t, on(s, note[s], 80))
                            glide(s, e.tMs, e.glideMs, e.curve, e.midi)
                        }
                        14 -> chordOff(s, t)
                        15 -> for (i in 0..7) chordOff(i, t)
                    }
                }
                is LogEv.Drum -> {
                    val n = gmDrum.getOrElse(e.pad) { 38 }
                    val t = tick(e.tMs)
                    add(9, t, on(9, n, (e.vel * 127).roundToInt().coerceAtLeast(1)))
                    add(9, t + PPQ / 8, off(9, n))
                }
                is LogEv.Solo -> {
                    val i = e.idx.coerceIn(0, 5)
                    val c = 10 + i
                    val t = tick(e.tMs)
                    when (e.kind) {
                        0 -> {
                            if (soloNote[i] >= 0) add(c, t, off(c, soloNote[i]))
                            soloNote[i] = e.midi.roundToInt(); soloPitch[i] = e.midi
                            add(c, t, bend(c, soloNote[i], e.midi)); add(c, t, on(c, soloNote[i], 90)); soloT[i] = t
                        }
                        1 -> if (soloNote[i] >= 0 && t - soloT[i] >= PPQ / 40 && abs(e.midi - soloPitch[i]) > 0.03f) {
                            add(c, t, bend(c, soloNote[i], e.midi)); soloPitch[i] = e.midi; soloT[i] = t
                        }
                        2 -> if (soloNote[i] >= 0) { add(c, t, off(c, soloNote[i])); soloNote[i] = -1 }
                    }
                }
            }
        }
        val endTick = (ch.maxOf { l -> l.maxOfOrNull { it.tick } ?: 0L }) + PPQ
        for (s in 0..7) chordOff(s, endTick)
        for (i in 0..5) if (soloNote[i] >= 0) add(10 + i, endTick, off(10 + i, soloNote[i]))

        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v) }
        fun u32(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
        fun varlen(os: ByteArrayOutputStream, v0: Long) {
            var v = v0
            val b = ArrayList<Int>(); b.add((v and 0x7F).toInt()); v = v shr 7
            while (v > 0) { b.add(((v and 0x7F) or 0x80).toInt()); v = v shr 7 }
            for (i in b.indices.reversed()) os.write(b[i])
        }
        fun track(body: ByteArray) { out.write("MTrk".toByteArray()); u32(body.size); out.write(body) }

        val tracks = ArrayList<ByteArray>()
        // Pista 0: tempo
        run {
            val t = ByteArrayOutputStream()
            val mpq = 60_000_000 / bpm
            t.write(byteArrayOf(0, 0xFF.toByte(), 0x51, 3, (mpq shr 16).toByte(), (mpq shr 8).toByte(), mpq.toByte()))
            t.write(byteArrayOf(0, 0xFF.toByte(), 0x58, 4, 4, 2, 24, 8))
            t.write(byteArrayOf(0, 0xFF.toByte(), 0x2F, 0))
            tracks.add(t.toByteArray())
        }
        for (c in 0..15) {
            if (!used[c]) continue
            val t = ByteArrayOutputStream()
            if (c != 9) {  // rango de pitch bend = 12 semitonos (RPN 0)
                for (b in listOf(101 to 0, 100 to 0, 6 to BEND_RANGE.toInt(), 38 to 0, 101 to 127, 100 to 127)) {
                    t.write(0); t.write(0xB0 or c); t.write(b.first); t.write(b.second)
                }
            }
            var last = 0L
            for (e in ch[c].sortedWith(compareBy<Ev>({ it.tick }, { it.order }))) {
                varlen(t, e.tick - last); last = e.tick
                t.write(e.bytes)
            }
            t.write(byteArrayOf(0, 0xFF.toByte(), 0x2F, 0))
            tracks.add(t.toByteArray())
        }
        out.write("MThd".toByteArray()); u32(6); u16(1); u16(tracks.size); u16(PPQ)
        tracks.forEach { track(it) }
        return out.toByteArray()
    }
}
