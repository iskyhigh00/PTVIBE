package com.glidelive.session

import android.content.Context
import com.glidelive.drums.DrumPattern
import com.glidelive.theory.Bank
import com.glidelive.theory.ChordType
import com.glidelive.theory.Scale
import com.glidelive.theory.ScaleChoice
import com.glidelive.ui.ChordsViewModel
import com.glidelive.ui.ChordQuant
import com.glidelive.ui.EngineViewModel
import com.glidelive.ui.Expression
import com.glidelive.ui.GlideCurve
import com.glidelive.ui.Meter
import com.glidelive.ui.PadEdit
import com.glidelive.ui.RecQuant
import com.glidelive.ui.SoloLayout
import com.glidelive.ui.SoloViewModel
import com.glidelive.ui.TransportViewModel
import com.glidelive.ui.TriggerMode
import com.glidelive.ui.Tuning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 8 escenas guardables (tonalidad, acordes, instrumento, kit, patrón, BPM y ajustes); cargan sin cortar el sonido. */
class SceneManager(
    ctx: Context,
    private val chords: ChordsViewModel,
    private val solo: SoloViewModel,
    private val engine: EngineViewModel,
    private val transport: TransportViewModel,
) {
    private val prefs = ctx.getSharedPreferences("scenes", Context.MODE_PRIVATE)
    private val _names = MutableStateFlow(List(8) { "" })
    val names: StateFlow<List<String>> = _names

    init { refresh() }

    private fun slotJson(i: Int): JSONObject? =
        prefs.getString("slot_$i", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Presets.all.getOrNull(i)

    private fun refresh() {
        _names.value = List(8) { slotJson(it)?.optString("name").orEmpty().ifEmpty { "Escena ${it + 1}" } }
    }

    fun save(i: Int) {
        val o = capture()
        o.put("name", "Mi escena ${i + 1}")
        prefs.edit().putString("slot_$i", o.toString()).apply()
        refresh()
    }

    fun load(i: Int) { slotJson(i)?.let { apply(it) } }

    fun resetSlot(i: Int) { prefs.edit().remove("slot_$i").apply(); refresh() }

    // ---------- capturar ----------
    fun capture(): JSONObject {
        val c = chords.ui.value
        val s = solo.ui.value
        val k = engine.kit.value
        val t = transport.ui.value
        return JSONObject().apply {
            put("chords", JSONObject().apply {
                put("key", c.key); put("scale", c.scale.name); put("transpose", c.transpose); put("bank", c.bank.name)
                put("quality", c.quality?.name ?: ""); put("instrument", c.instrument); put("glideMs", c.glideMs)
                put("curve", c.curve.name); put("trigger", c.trigger.name); put("rangeLo", c.rangeLo); put("rangeHi", c.rangeHi)
                put("cutoff", c.cutoff.toDouble()); put("resonance", c.resonance.toDouble()); put("chorus", c.chorus.toDouble())
                put("room", c.room.toDouble()); put("volume", c.volume.toDouble())
                put("edits", JSONObject().also { eo ->
                    for ((id, e) in c.edits) eo.put(id, JSONObject().apply {
                        put("type", e.type?.name ?: ""); put("inversion", e.inversion); put("octave", e.octave)
                        put("fixed", JSONObject().also { f -> e.fixed.forEach { (pc, m) -> f.put(pc.toString(), m) } })
                    })
                })
            })
            put("solo", JSONObject().apply {
                put("layout", s.layout.name); put("lock", s.lock.toDouble()); put("follow", s.followChord)
                put("scale", s.scaleOverride?.label ?: ""); put("expression", s.expression.name); put("instrument", s.instrument)
                put("volume", s.volume.toDouble()); put("smoothMs", s.smoothMs); put("lo", s.loMidi); put("octaves", s.octaves)
                put("bend", s.bendRange.toDouble()); put("tuning", s.tuning.name)
            })
            put("kit", JSONObject().apply {
                put("snareTune", k.snareTune.toDouble()); put("tomTune", k.tomTune.toDouble()); put("room", k.room.toDouble())
                put("comp", k.comp.toDouble()); put("master", k.master.toDouble()); put("drumVol", k.drumVol.toDouble())
                put("pressure", k.pressure); put("padVolume", JSONArray(k.padVolume.map { it.toDouble() }))
            })
            put("transport", JSONObject().apply {
                put("bpm", t.bpm); put("beats", t.meter.beats); put("unit", t.meter.unit); put("swing", t.swing.toDouble())
                put("metro", t.metro); put("pattern", t.patternId ?: ""); put("variation", t.variation)
                put("recQuant", t.recQuant.name); put("chordQuant", t.chordQuant.name)
            })
        }
    }

    // ---------- aplicar (los campos ausentes no cambian) ----------
    private inline fun <T> pick(f: () -> T): T? = runCatching(f).getOrNull()

    fun apply(o: JSONObject) {
        o.optJSONObject("chords")?.let { j ->
            chords.edit { c ->
                var u = c
                if (j.has("key")) u = u.copy(key = j.getInt("key"))
                pick { Scale.valueOf(j.getString("scale")) }?.let { u = u.copy(scale = it) }
                if (j.has("transpose")) u = u.copy(transpose = j.getInt("transpose"))
                pick { Bank.valueOf(j.getString("bank")) }?.let { u = u.copy(bank = it) }
                if (j.has("quality")) u = u.copy(quality = pick { ChordType.valueOf(j.getString("quality")) })
                if (j.has("instrument")) u = u.copy(instrument = j.getInt("instrument"))
                if (j.has("glideMs")) u = u.copy(glideMs = j.getInt("glideMs"))
                pick { GlideCurve.valueOf(j.getString("curve")) }?.let { u = u.copy(curve = it) }
                pick { TriggerMode.valueOf(j.getString("trigger")) }?.let { u = u.copy(trigger = it) }
                if (j.has("rangeLo")) u = u.copy(rangeLo = j.getInt("rangeLo"))
                if (j.has("rangeHi")) u = u.copy(rangeHi = j.getInt("rangeHi"))
                if (j.has("cutoff")) u = u.copy(cutoff = j.getDouble("cutoff").toFloat())
                if (j.has("resonance")) u = u.copy(resonance = j.getDouble("resonance").toFloat())
                if (j.has("chorus")) u = u.copy(chorus = j.getDouble("chorus").toFloat())
                if (j.has("room")) u = u.copy(room = j.getDouble("room").toFloat())
                if (j.has("volume")) u = u.copy(volume = j.getDouble("volume").toFloat())
                j.optJSONObject("edits")?.let { eo ->
                    val m = HashMap<String, PadEdit>()
                    eo.keys().forEach { id ->
                        val e = eo.getJSONObject(id)
                        val fixed = HashMap<Int, Int>()
                        e.optJSONObject("fixed")?.let { f -> f.keys().forEach { k -> fixed[k.toInt()] = f.getInt(k) } }
                        m[id] = PadEdit(pick { ChordType.valueOf(e.getString("type")) }, e.optInt("inversion"), e.optInt("octave"), fixed)
                    }
                    u = u.copy(edits = m)
                }
                u
            }
        }
        o.optJSONObject("solo")?.let { j ->
            solo.edit { s ->
                var u = s
                pick { SoloLayout.valueOf(j.getString("layout")) }?.let { u = u.copy(layout = it) }
                if (j.has("lock")) u = u.copy(lock = j.getDouble("lock").toFloat())
                if (j.has("follow")) u = u.copy(followChord = j.getBoolean("follow"))
                if (j.has("scale")) u = u.copy(scaleOverride = ScaleChoice.all.firstOrNull { it.label == j.getString("scale") })
                pick { Expression.valueOf(j.getString("expression")) }?.let { u = u.copy(expression = it) }
                if (j.has("instrument")) u = u.copy(instrument = j.getInt("instrument"))
                if (j.has("volume")) u = u.copy(volume = j.getDouble("volume").toFloat())
                if (j.has("smoothMs")) u = u.copy(smoothMs = j.getInt("smoothMs"))
                if (j.has("lo")) u = u.copy(loMidi = j.getInt("lo"))
                if (j.has("octaves")) u = u.copy(octaves = j.getInt("octaves"))
                if (j.has("bend")) u = u.copy(bendRange = j.getDouble("bend").toFloat())
                pick { Tuning.valueOf(j.getString("tuning")) }?.let { u = u.copy(tuning = it) }
                u
            }
        }
        o.optJSONObject("kit")?.let { j ->
            engine.editKit { k ->
                var u = k
                if (j.has("snareTune")) u = u.copy(snareTune = j.getDouble("snareTune").toFloat())
                if (j.has("tomTune")) u = u.copy(tomTune = j.getDouble("tomTune").toFloat())
                if (j.has("room")) u = u.copy(room = j.getDouble("room").toFloat())
                if (j.has("comp")) u = u.copy(comp = j.getDouble("comp").toFloat())
                if (j.has("master")) u = u.copy(master = j.getDouble("master").toFloat())
                if (j.has("drumVol")) u = u.copy(drumVol = j.getDouble("drumVol").toFloat())
                if (j.has("pressure")) u = u.copy(pressure = j.getBoolean("pressure"))
                j.optJSONArray("padVolume")?.let { a ->
                    if (a.length() == u.padVolume.size) u = u.copy(padVolume = List(a.length()) { a.getDouble(it).toFloat() })
                }
                u
            }
        }
        o.optJSONObject("transport")?.let { j ->
            transport.applyScene(
                bpm = if (j.has("bpm")) j.getInt("bpm") else null,
                meter = if (j.has("beats") || j.has("unit")) Meter(j.optInt("beats", 4), j.optInt("unit", 4)) else null,
                swing = if (j.has("swing")) j.getDouble("swing").toFloat() else null,
                metro = if (j.has("metro")) j.getBoolean("metro") else null,
                patternId = j.optString("pattern").ifEmpty { null },
                variation = if (j.has("variation")) j.getInt("variation") else null,
                recQuant = pick { RecQuant.valueOf(j.getString("recQuant")) } ?: RecQuant.FREE,
                chordQuant = pick { ChordQuant.valueOf(j.getString("chordQuant")) } ?: ChordQuant.OFF,
            )
        }
    }
}

/** Escenas de fábrica: las 8 escenas arrancan con estos ajustes (se pueden sobrescribir). */
object Presets {
    private fun scene(name: String, chords: String, solo: String, transport: String, kit: String = "{}") =
        JSONObject("""{"name":"$name","chords":$chords,"solo":$solo,"transport":$transport,"kit":$kit}""").also { o ->
            // Todas las escenas de fábrica entran sin cuantizar (acordes inmediatos, grabación libre),
            // salvo que la escena lo pida expresamente (el poliritmo mantiene "BAR").
            o.getJSONObject("transport").apply {
                if (!has("chordQuant")) put("chordQuant", "OFF")
                if (!has("recQuant")) put("recQuant", "FREE")
            }
        }

    val all: List<JSONObject> = listOf(
        scene("Cadena ii–V–I · piano eléctrico",
            """{"bank":"II_V_I","instrument":0,"glideMs":350,"curve":"SMOOTH","trigger":"HOLD","transpose":0,"volume":0.8}""",
            """{"layout":"SURFACE","lock":0.8,"instrument":4,"scale":"","follow":false}""",
            """{"bpm":84,"beats":4,"unit":4,"pattern":"minimal","variation":0,"swing":0.0}"""),
        scene("Pad en La menor · 7/8",
            """{"key":9,"scale":"NATURAL_MINOR","bank":"DIATONIC","quality":"","instrument":2,"glideMs":900,"curve":"SLOW","trigger":"HOLD","transpose":0}""",
            """{"layout":"SURFACE","lock":0.9,"instrument":3,"scale":"Pentatónica menor","follow":false}""",
            """{"bpm":112,"beats":7,"unit":8,"pattern":"o78","variation":0}"""),
        scene("Cuerdas · 6/8 a medio tiempo",
            """{"key":2,"scale":"DORIAN","bank":"DIATONIC","instrument":3,"glideMs":600,"curve":"SMOOTH","trigger":"HOLD","transpose":0}""",
            """{"layout":"FINGERBOARD","tuning":"VIOLIN","lock":0.7,"instrument":3}""",
            """{"bpm":70,"beats":6,"unit":8,"pattern":"seis8","variation":1}"""),
        scene("Órgano · 5/4",
            """{"key":7,"scale":"MIXOLYDIAN","bank":"DIATONIC","instrument":1,"glideMs":120,"curve":"LINEAR","trigger":"GATE","transpose":0}""",
            """{"layout":"SURFACE","lock":0.6,"instrument":4,"expression":"VIBRATO"}""",
            """{"bpm":88,"beats":5,"unit":4,"pattern":"cinco4","variation":0}"""),
        scene("Lead libre · lineal 4/4",
            """{"key":0,"scale":"MAJOR","bank":"DIATONIC","instrument":0,"glideMs":250,"curve":"SMOOTH","trigger":"HOLD","transpose":0}""",
            """{"layout":"FINGERBOARD","tuning":"GUITAR","lock":0.5,"instrument":4,"bend":2.0}""",
            """{"bpm":95,"beats":4,"unit":4,"pattern":"lineal44","variation":1}"""),
        scene("Balada 12/8",
            """{"key":5,"scale":"MAJOR","bank":"DIATONIC","instrument":3,"glideMs":1200,"curve":"SLOW","trigger":"HOLD","transpose":0}""",
            """{"layout":"SURFACE","lock":0.85,"instrument":3,"scale":""}""",
            """{"bpm":56,"beats":12,"unit":8,"pattern":"doce8","variation":0}"""),
        scene("Poliritmo 3 contra 4",
            """{"key":4,"scale":"HARMONIC_MINOR","bank":"DIATONIC","instrument":0,"glideMs":200,"curve":"SMOOTH","trigger":"HOLD","transpose":0}""",
            """{"layout":"SURFACE","lock":1.0,"instrument":4,"scale":"Blues"}""",
            """{"bpm":80,"beats":4,"unit":4,"pattern":"tres_cuatro","variation":0,"chordQuant":"BAR"}"""),
        scene("Rápido · hi-hat en 16avos",
            """{"key":0,"scale":"MIXOLYDIAN","bank":"DIATONIC","instrument":1,"glideMs":80,"curve":"LINEAR","trigger":"GATE","transpose":0}""",
            """{"layout":"SURFACE","lock":0.8,"instrument":4,"scale":"Pentatónica mayor"}""",
            """{"bpm":122,"beats":4,"unit":4,"pattern":"rapido16","variation":0}"""),
    )
}
