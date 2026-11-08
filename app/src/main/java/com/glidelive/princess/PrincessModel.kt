package com.glidelive.princess

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class CueType(val label: String) { VOICE("Voz"), MUSIC("Música"), SFX("Efecto"), SILENCE("Silencio") }

/** Cómo entra una pista dentro de la obra. */
enum class CueMode(val label: String) {
    /** Después de la anterior (más el espacio indicado). */
    SEQ("Después de la anterior"),
    /** A la vez que la anterior, con el desfase indicado (un ladrido mientras alguien habla). */
    WITH_PREV("Junto a la anterior"),
    /** Música de fondo: suena por debajo de las siguientes pistas y se repite si hace falta. */
    BED("De fondo"),
}

data class Character(val id: String, val name: String, val color: Long, val pitch: Float = 0f, val volume: Float = 1f, val pan: Float = 0f)

data class Cue(
    val id: String,
    val type: CueType,
    val characterId: String? = null,
    /** Texto del diálogo o nombre del sonido. */
    val text: String = "",
    val file: String? = null,
    /** Duración del archivo en segundos (para el silencio, su largo). */
    val seconds: Float = 0f,
    /** Espacio antes (segundos). En "Junto a la anterior" es el desfase de entrada. */
    val gap: Float = 0.5f,
    val mode: CueMode = CueMode.SEQ,
    val volume: Float = 1f,
    /** Música de fondo: cuántas pistas siguientes acompaña (0 = hasta el final de la obra). */
    val span: Int = 0,
    val fadeIn: Float = 0f,
    val fadeOut: Float = 0f,
)

data class Project(
    val id: String,
    val title: String,
    val characters: List<Character>,
    val cues: List<Cue>,
    /** Iguala el volumen de las voces grabadas. */
    val normalizeVoices: Boolean = true,
    /** Baja la música de fondo mientras alguien habla. */
    val duckMusic: Boolean = true,
)

object PrincessLibrary {
    val PALETTE = longArrayOf(0xFFFF6B9D, 0xFF60A5FA, 0xFFFBBF24, 0xFF34D399, 0xFFA78BFA, 0xFFF87171, 0xFF22D3EE, 0xFFFB923C)

    fun newId() = UUID.randomUUID().toString().take(8)

    private fun root(ctx: Context) = File(ctx.filesDir, "princess").also { it.mkdirs() }
    fun dir(ctx: Context, p: Project) = File(root(ctx), p.id).also { it.mkdirs() }

    fun create(ctx: Context, title: String): Project {
        val p = Project(newId(), title.ifBlank { "Mi obra" }, listOf(Character(newId(), "Narrador", PALETTE[0])), emptyList())
        save(ctx, p)
        return p
    }

    fun list(ctx: Context): List<Project> = root(ctx).listFiles()?.mapNotNull { d -> runCatching { parse(File(d, "project.json").readText()) }.getOrNull() }
        ?.sortedByDescending { File(dir(ctx, it), "project.json").lastModified() } ?: emptyList()

    @Synchronized fun save(ctx: Context, p: Project) { File(dir(ctx, p), "project.json").writeText(toJson(p).toString()) }

    fun delete(ctx: Context, p: Project) { dir(ctx, p).deleteRecursively() }

    fun toJson(p: Project): JSONObject = JSONObject().apply {
        put("id", p.id); put("title", p.title); put("normalizeVoices", p.normalizeVoices); put("duckMusic", p.duckMusic)
        put("characters", JSONArray().also { a -> for (c in p.characters) a.put(JSONObject().put("id", c.id).put("name", c.name).put("color", c.color)
            .put("pitch", c.pitch.toDouble()).put("volume", c.volume.toDouble()).put("pan", c.pan.toDouble())) })
        put("cues", JSONArray().also { a -> for (c in p.cues) a.put(JSONObject().apply {
            put("id", c.id); put("type", c.type.name); put("character", c.characterId ?: ""); put("text", c.text); put("file", c.file ?: "")
            put("seconds", c.seconds.toDouble()); put("gap", c.gap.toDouble()); put("mode", c.mode.name); put("volume", c.volume.toDouble())
            put("span", c.span); put("fadeIn", c.fadeIn.toDouble()); put("fadeOut", c.fadeOut.toDouble())
        }) })
    }

    fun parse(json: String): Project {
        val o = JSONObject(json)
        val cs = o.getJSONArray("characters")
        val qs = o.getJSONArray("cues")
        return Project(
            o.getString("id"), o.getString("title"),
            (0 until cs.length()).map { i -> cs.getJSONObject(i).let { Character(it.getString("id"), it.getString("name"), it.getLong("color"),
                it.optDouble("pitch", 0.0).toFloat(), it.optDouble("volume", 1.0).toFloat(), it.optDouble("pan", 0.0).toFloat()) } },
            (0 until qs.length()).map { i -> qs.getJSONObject(i).let { Cue(
                it.getString("id"), CueType.valueOf(it.getString("type")), it.optString("character").ifEmpty { null }, it.optString("text"),
                it.optString("file").ifEmpty { null }, it.optDouble("seconds", 0.0).toFloat(), it.optDouble("gap", 0.5).toFloat(),
                CueMode.valueOf(it.optString("mode", "SEQ")), it.optDouble("volume", 1.0).toFloat(), it.optInt("span", 0),
                it.optDouble("fadeIn", 0.0).toFloat(), it.optDouble("fadeOut", 0.0).toFloat()) } },
            o.optBoolean("normalizeVoices", true), o.optBoolean("duckMusic", true),
        )
    }
}
