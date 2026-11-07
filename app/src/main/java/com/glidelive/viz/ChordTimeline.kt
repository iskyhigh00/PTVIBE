package com.glidelive.viz

import com.glidelive.theory.ChordType
import com.glidelive.theory.NoteNames

/** Nombre de un acorde a partir de las notas que suenan (p. ej. F A♭ C E♭ -> Fm7). */
object ChordNamer {
    fun name(midis: Collection<Int>): String {
        if (midis.isEmpty()) return ""
        val pcs = midis.map { Math.floorMod(it, 12) }.toSet()
        val bass = Math.floorMod(midis.min(), 12)
        val roots = listOf(bass) + pcs.filter { it != bass }.sorted()
        for (r in roots) {
            val rel = pcs.map { Math.floorMod(it - r, 12) }.toSet()
            val t = ChordType.entries.firstOrNull { ty -> ty.intervals.map { it % 12 }.toSet() == rel } ?: continue
            return NoteNames.name(r) + t.symbol + if (r != bass) "/" + NoteNames.name(bass) else ""
        }
        return pcs.sorted().joinToString(" ") { NoteNames.name(it) }
    }

    /** Nombre dividido para dibujar la cabecera: (letra de la fundamental, "♭"/"♯"/"", calidad con bajo si es inversión). */
    fun parts(midis: Collection<Int>): Triple<String, String, String> {
        val full = name(midis)
        if (full.isEmpty() || !full[0].isLetter() || full.contains(' ')) return Triple("", "", full)
        var i = 1
        var acc = ""
        if (i < full.length && (full[i] == '♭' || full[i] == '♯')) { acc = full[i].toString(); i++ }
        return Triple(full[0].toString(), acc, full.substring(i))
    }
}

class Seg(val t0: Double, val t1: Double, val from: Float, val to: Float, val curve: Int)

/** Una nota sostenida de una voz, con su historial de glides. */
class Span(val slot: Int, val startMs: Double, startMidi: Float) {
    var endMs: Double = Double.POSITIVE_INFINITY
    /** Momento en que empezó a apagarse (para dibujarla desvaneciéndose); infinito = no se está apagando. */
    var relStartMs: Double = Double.POSITIVE_INFINITY

    /** Opacidad en [t]: 1 mientras suena y baja a 0 durante el apagado. */
    fun fadeAt(t: Double): Float {
        if (t <= relStartMs || endMs <= relStartMs) return 1f
        return (1.0 - (t - relStartMs) / (endMs - relStartMs)).coerceIn(0.0, 1.0).toFloat()
    }
    val segs = ArrayList<Seg>().apply { add(Seg(startMs, startMs, startMidi, startMidi, 0)) }

    val lastPitch: Float get() = segs.last().to

    fun pitchAt(t: Double): Float {
        var s = segs[0]
        for (i in segs.indices) { if (segs[i].t0 <= t) s = segs[i] else break }
        if (t >= s.t1 || s.t1 <= s.t0) return s.to
        val p = ((t - s.t0) / (s.t1 - s.t0)).toFloat().coerceIn(0f, 1f)
        val k = when (s.curve) { 1 -> p * p * (3 - 2 * p); 2 -> p * p; else -> p }
        return s.from + (s.to - s.from) * k
    }

    fun activeAt(t: Double) = t >= startMs && t <= endMs
}

class ChordMark(val tMs: Double) {
    val targets = HashMap<Int, Int>()
    var name = ""
    var root = ""
    var acc = ""
    var qual = ""
}

/**
 * Historial de voces de acordes para la visualización. Se alimenta con los eventos que ejecuta el motor
 * (en vivo y reproducidos por el looper); el dibujo nunca toca el hilo de audio.
 */
class ChordTimeline {
    val spans = ArrayList<Span>()
    val marks = ArrayList<ChordMark>()
    private val open = arrayOfNulls<Span>(16)

    /** Tipos: 10 NoteOn, 11 GlideTo, 12 Spawn, 13 GlideRelease, 14 Release, 15 ReleaseAll. */
    @Synchronized
    fun onEvent(type: Int, a: Int, b: Int, midi: Float, glideMs: Float, curve: Int, t: Double) {
        if (a !in open.indices && type != 15) return
        when (type) {
            10 -> {
                open[a]?.let { if (it.endMs.isInfinite()) it.endMs = t }
                val sp = Span(a, t, midi); spans.add(sp); open[a] = sp
                mark(t, a, midi)
            }
            11, 13 -> {
                val sp = open[a] ?: Span(a, t, midi).also { spans.add(it); open[a] = it }
                val from = sp.lastPitch.let { if (sp.segs.size == 1 && sp.segs[0].t1 <= t) it else sp.pitchAt(t) }
                sp.segs.add(Seg(t, t + glideMs, from, midi, curve))
                if (type == 13) { sp.endMs = t + glideMs + 250; open[a] = null }
                mark(t, a, midi)
            }
            12 -> {
                val src = open.getOrNull(b)
                val start = src?.pitchAt(t) ?: midi
                val sp = Span(a, t, start).also { spans.add(it); open[a] = it }
                sp.segs.add(Seg(t, t + glideMs, start, midi, curve))
                mark(t, a, midi)
            }
            14 -> open[a]?.let { it.relStartMs = t; it.endMs = t + maxOf(250.0, glideMs.toDouble()); open[a] = null }
            15 -> for (i in open.indices) open[i]?.let { it.endMs = t + 250; open[i] = null }
        }
        prune(t)
    }

    private fun mark(t: Double, slot: Int, midi: Float) {
        var m = marks.lastOrNull()
        if (m == null || t - m.tMs > 60) { m = ChordMark(t); marks.add(m) }
        m.targets[slot] = Math.round(midi)
        m.name = ChordNamer.name(m.targets.values)
        val (r, a, q) = ChordNamer.parts(m.targets.values)
        if (r.isEmpty()) { m.root = m.name; m.acc = ""; m.qual = "" } else { m.root = r; m.acc = a; m.qual = q }
    }

    private fun prune(now: Double) {
        spans.removeAll { it.endMs < now - 30_000 }
        marks.removeAll { it.tMs < now - 30_000 }
    }

    @Synchronized
    fun clear() { spans.clear(); marks.clear(); for (i in open.indices) open[i] = null }
}
