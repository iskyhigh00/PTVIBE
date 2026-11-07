package com.glidelive.bass

import android.content.Context
import com.glidelive.drums.Compiled
import org.json.JSONObject

data class BassNote(val step: Int, val midi: Int, val len: Int, val vel: Float)

data class BassVariation(val name: String, val bars: Int, val notes: List<BassNote>)

data class BassLine(
    val id: String,
    val name: String,
    /** Tono (0 = Do … 11 = Si) en que está escrita la línea. */
    val root: Int,
    /** Ticks por paso (96 por negra). */
    val unit: Int,
    val barSteps: Int,
    val variations: List<BassVariation>,
    val end: List<BassNote>,
) {
    fun variation(i: Int): BassVariation = variations[Math.floorMod(i, variations.size)]
    fun lengthTicks(v: BassVariation): Int = v.bars * barSteps * unit
}

object Bass {
    /** Primer "pad" de las notas de bajo dentro de un patrón (debe coincidir con kBassPadBase del motor). */
    const val PAD_BASE = 64
    const val MAX_TICKS = 384 * 16
    private const val MAX_EVENTS = 1000

    val NOTE_NAMES = listOf("Do", "Do#", "Re", "Mib", "Mi", "Fa", "Fa#", "Sol", "Lab", "La", "Sib", "Si")
    private val PC = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)

    fun parseNote(s: String): Int {
        val pc = PC[s.substring(0, 1)] ?: error("nota $s")
        var i = 1
        var acc = 0
        while (i < s.length && (s[i] == '#' || s[i] == 'b')) { acc += if (s[i] == '#') 1 else -1; i++ }
        val oct = s.substring(i).toInt()
        return (oct + 1) * 12 + pc + acc
    }

    private fun vel(c: Char) = when (c) { 'X' -> 1.0f; 'x' -> 0.8f; 'o' -> 0.55f; 'g' -> 0.3f; else -> 0.8f }

    fun parseNotes(s: String): List<BassNote> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { tok ->
        val p = tok.split(":")
        BassNote(p[0].toInt(), parseNote(p[1]), p[2].toInt(), vel(p.getOrElse(3) { "x" }.first()))
    }

    fun parseAll(json: String): List<BassLine> {
        val arr = JSONObject(json).getJSONArray("lines")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val vs = o.getJSONArray("variations")
            BassLine(
                id = o.getString("id"), name = o.getString("name"),
                root = parseNote(o.getString("root") + "0") % 12,
                unit = o.optInt("unit", 24), barSteps = o.optInt("barSteps", 16),
                variations = (0 until vs.length()).map { k ->
                    val v = vs.getJSONObject(k)
                    BassVariation(v.getString("name"), v.optInt("bars", 1), parseNotes(v.getString("notes")))
                },
                end = parseNotes(o.optString("end", "")),
            )
        }
    }

    @Volatile var lines: List<BassLine> = emptyList()
        private set

    fun load(ctx: Context) {
        if (lines.isNotEmpty()) return
        lines = runCatching { ctx.assets.open("basslines.json").use { parseAll(it.readBytes().toString(Charsets.UTF_8)) } }
            .getOrDefault(emptyList())
    }

    /** Semitonos para llevar una línea escrita en [root] al tono [key], dentro de −5..+6. */
    fun shift(root: Int, key: Int): Int {
        var d = Math.floorMod(key - root, 12)
        if (d > 6) d -= 12
        return d
    }

    /** Eventos del motor para [notes] (duración en ticks + velocidad empaquetadas en vel). */
    fun compile(notes: List<BassNote>, unit: Int, shift: Int, volume: Float = 1f): Compiled {
        val t = IntArray(notes.size)
        val p = IntArray(notes.size)
        val v = FloatArray(notes.size)
        notes.forEachIndexed { i, n ->
            var m = n.midi + shift
            while (m < 23) m += 12
            while (m > 60) m -= 12
            t[i] = n.step * unit
            p[i] = PAD_BASE + m
            v[i] = (n.len * unit).toFloat() + (n.vel * volume).coerceIn(0.01f, 0.99f)
        }
        return Compiled(t, p, v)
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /**
     * Junta la batería ([drums], largo [drumLen]; en la última vuelta suena [drumsLast], p. ej. el relleno) con el bajo
     * ([bass], largo [bassLen]) en un patrón cuyo largo es el mínimo común múltiplo de ambos (si no cabe, el de la batería).
     */
    fun merge(drums: Compiled, drumsLast: Compiled, drumLen: Int, bass: Compiled?, bassLen: Int): Pair<Compiled, Int> {
        if (bass == null || bass.ticks.isEmpty() || bassLen <= 0) {
            return drumsLast to drumLen
        }
        var len = drumLen / gcd(drumLen, bassLen) * bassLen
        val reps = len / drumLen
        val events = drums.ticks.size * (reps - 1) + drumsLast.ticks.size + bass.ticks.size * (len / bassLen)
        if (len > MAX_TICKS || events > MAX_EVENTS) len = drumLen
        val t = ArrayList<Int>(); val p = ArrayList<Int>(); val v = ArrayList<Float>()
        val dReps = len / drumLen
        for (r in 0 until dReps) {
            val c = if (r == dReps - 1) drumsLast else drums
            for (i in c.ticks.indices) { t += c.ticks[i] + r * drumLen; p += c.pads[i]; v += c.vels[i] }
        }
        var off = 0
        while (off < len) {
            for (i in bass.ticks.indices) {
                val at = bass.ticks[i] + off
                if (at < len) { t += at; p += bass.pads[i]; v += bass.vels[i] }
            }
            off += bassLen
        }
        return Compiled(t.toIntArray(), p.toIntArray(), v.toFloatArray()) to len
    }
}
