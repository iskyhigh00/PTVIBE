package com.glidelive.drums

import org.json.JSONArray
import org.json.JSONObject

object DrumKeys {
    val keys = listOf(
        "k", "s", "r", "hc", "ho", "hp", "t1", "t2", "tf", "cr", "rd", "bl", "sp",
        "gc", "gl", "mr", "cg", "qu", "tu", "bg", "cb",
    )
    val labels = listOf(
        "Bombo", "Caja", "Aros (rim)", "Hi-hat cerrado", "Hi-hat abierto", "Pedal hi-hat",
        "Tom 1", "Tom 2", "Tom de piso", "Crash", "Ride", "Campana ride", "Splash / china",
        "Güiro corto", "Güiro largo", "Maracas", "Conga", "Quinto (alegre)", "Tumba", "Bongó", "Campana (cencerro)",
    )

    fun index(key: String) = keys.indexOf(key)
}

/** Caracteres de paso: '.' vacío, 'X' acento, 'x' normal, 'o' suave, 'g' ghost note. */
object Step {
    fun velocity(c: Char): Float = when (c) {
        'X' -> 1.0f
        'x' -> 0.78f
        'o' -> 0.5f
        'g' -> 0.2f
        else -> 0f
    }

    private val cycle = charArrayOf('.', 'x', 'X', 'o', 'g')
    fun next(c: Char): Char = cycle[(cycle.indexOf(c).let { if (it < 0) 0 else it } + 1) % cycle.size]
}

data class Variation(val name: String, val tracks: Map<String, String>)

class Compiled(val ticks: IntArray, val pads: IntArray, val vels: FloatArray)

data class DrumPattern(
    val id: String,
    val name: String,
    val meter: String,
    val beats: Int,
    val beatUnit: Int,
    /** Ticks por paso (96 por negra): 48 = corchea, 24 = semicorchea, 32 = tresillo de corchea, 12 = fusa. */
    val unit: Int,
    val barSteps: Int,
    val bars: Int,
    val bpmMin: Int,
    val bpmMax: Int,
    val variations: List<Variation>,
    val fill: Map<String, String>,
    val end: Map<String, String>,
    /** Breaks: último compás alternativo (paradas, golpes desplazados, toms) que entra como el relleno. */
    val breaks: List<Variation> = emptyList(),
    val factory: Boolean = true,
    /** Línea de bajo que acompaña a esta base (id en basslines.json), si la hay. */
    val bass: String? = null,
) {
    val steps: Int get() = barSteps * bars
    val lengthTicks: Int get() = steps * unit
    val barTicks: Int get() = barSteps * unit
    val bpmDefault: Int get() = (bpmMin + bpmMax) / 2

    /** Pistas de la variación [v] con el último compás sustituido por el relleno. */
    fun withFill(v: Int): Map<String, String> = withLastBar(v, fill)

    /** Pistas de la variación [v] con el último compás sustituido por el break [i]. */
    fun withBreak(v: Int, i: Int): Map<String, String> = withLastBar(v, breaks[i.coerceIn(0, breaks.size - 1)].tracks)

    private fun withLastBar(v: Int, bar: Map<String, String>): Map<String, String> {
        val base = variations[v.coerceIn(0, variations.size - 1)].tracks
        val head = steps - barSteps
        val out = LinkedHashMap<String, String>()
        for ((k, s) in base) out[k] = s.take(head).padEnd(head, '.') + ".".repeat(barSteps)
        for ((k, f) in bar) out[k] = (out[k] ?: ".".repeat(steps)).take(head) + f.padEnd(barSteps, '.').take(barSteps)
        return out
    }

    fun compile(tracks: Map<String, String>): Compiled = compileTracks(tracks, unit)

    fun compileEnd(): Compiled = compileTracks(end, unit)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("meter", meter)
        put("beats", beats); put("beatUnit", beatUnit); put("unit", unit)
        put("barSteps", barSteps); put("bars", bars)
        put("bpm", JSONArray(listOf(bpmMin, bpmMax)))
        put("variations", JSONArray(variations.map { v ->
            JSONObject().put("name", v.name).put("tracks", JSONObject(v.tracks))
        }))
        put("fill", JSONObject(fill))
        put("end", JSONObject(end))
        put("breaks", JSONArray(breaks.map { b -> JSONObject().put("name", b.name).put("tracks", JSONObject(b.tracks)) }))
        bass?.let { put("bass", it) }
    }

    companion object {
        fun compileTracks(tracks: Map<String, String>, unit: Int): Compiled {
            val t = ArrayList<Int>(); val p = ArrayList<Int>(); val v = ArrayList<Float>()
            for ((key, str) in tracks) {
                val pad = DrumKeys.index(key)
                if (pad < 0) continue
                str.forEachIndexed { i, c ->
                    val vel = Step.velocity(c)
                    if (vel > 0f) { t.add(i * unit); p.add(pad); v.add(vel) }
                }
            }
            return Compiled(t.toIntArray(), p.toIntArray(), v.toFloatArray())
        }

        private fun map(o: JSONObject?): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            o?.keys()?.forEach { out[it] = o.getString(it) }
            return out
        }

        fun fromJson(o: JSONObject, factory: Boolean): DrumPattern {
            val bpm = o.getJSONArray("bpm")
            val vars = o.getJSONArray("variations")
            return DrumPattern(
                id = o.getString("id"), name = o.getString("name"), meter = o.getString("meter"),
                beats = o.getInt("beats"), beatUnit = o.getInt("beatUnit"), unit = o.getInt("unit"),
                barSteps = o.getInt("barSteps"), bars = o.getInt("bars"),
                bpmMin = bpm.getInt(0), bpmMax = bpm.getInt(1),
                variations = (0 until vars.length()).map {
                    val vo = vars.getJSONObject(it)
                    Variation(vo.getString("name"), map(vo.getJSONObject("tracks")))
                },
                fill = map(o.optJSONObject("fill")), end = map(o.optJSONObject("end")),
                breaks = o.optJSONArray("breaks")?.let { a ->
                    (0 until a.length()).map { Variation(a.getJSONObject(it).getString("name"), map(a.getJSONObject(it).getJSONObject("tracks"))) }
                } ?: emptyList(),
                factory = factory,
                bass = o.optString("bass").ifEmpty { null },
            )
        }

        fun parseAll(json: String, factory: Boolean): List<DrumPattern> {
            val arr = JSONObject(json).getJSONArray("patterns")
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it), factory) }
        }

        fun toJsonAll(list: List<DrumPattern>): String =
            JSONObject().put("patterns", JSONArray(list.map { it.toJson() })).toString()
    }
}
