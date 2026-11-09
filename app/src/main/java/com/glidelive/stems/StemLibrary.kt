package com.glidelive.stems

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.UUID

enum class StemKind(val label: String, val color: Long, val keys: List<String>) {
    VOCALS("Voz", 0xFFA78BFA, listOf("vocal", "voz", "voice", "vox", "acapella", "canto")),
    DRUMS("Batería", 0xFFF87171, listOf("drum", "bateria", "perc", "drums")),
    BASS("Bajo", 0xFF60A5FA, listOf("bass", "bajo")),
    GUITAR("Guitarra", 0xFFF472B6, listOf("guitar", "guitarra", "gtr")),
    PIANO("Piano", 0xFFFBBF24, listOf("piano", "keys", "teclado", "synth")),
    OTHER("Otros", 0xFF34D399, listOf("other", "otros", "otro", "rest", "instrumental", "accomp")),
    MIX("Mezcla", 0xFF9CA3AF, emptyList());

    companion object {
        fun guess(fileName: String): StemKind {
            val n = Normalizer.normalize(fileName.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            return entries.firstOrNull { k -> k.keys.any { n.contains(it) } } ?: MIX
        }
    }
}

data class StemTrack(
    val id: String,
    val name: String,
    val kind: StemKind,
    val file: String,
    val volume: Float = 1f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
)

/** [key] identifica el archivo de origen (nombre + tamaño) para no guardar dos veces la misma canción. */
data class StemSong(val id: String, val title: String, val tracks: List<StemTrack>, val bpm: Int = 0, val key: String = "") {
    /** Ya está separada en instrumentos (o se importó ya en pistas). */
    val separated: Boolean get() = tracks.size > 1
}

/** Biblioteca de canciones con sus pistas: copia los archivos a la app y guarda la mezcla de cada una. */
object StemLibrary {
    private fun root(ctx: Context) = File(ctx.filesDir, "songs").also { it.mkdirs() }
    private fun index(ctx: Context) = File(root(ctx), "library.json")
    fun dir(ctx: Context, song: StemSong) = File(root(ctx), song.id)

    fun load(ctx: Context): List<StemSong> = runCatching {
        val arr = JSONObject(index(ctx).readText()).getJSONArray("songs")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ts = o.getJSONArray("tracks")
            StemSong(
                o.getString("id"), o.getString("title"),
                (0 until ts.length()).map { k ->
                    val t = ts.getJSONObject(k)
                    StemTrack(
                        t.getString("id"), t.getString("name"), runCatching { StemKind.valueOf(t.getString("kind")) }.getOrDefault(StemKind.OTHER),
                        t.getString("file"), t.optDouble("volume", 1.0).toFloat(), t.optDouble("pan", 0.0).toFloat(),
                        t.optBoolean("mute"), t.optBoolean("solo"),
                    )
                },
                o.optInt("bpm", 0), o.optString("key", ""),
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized fun save(ctx: Context, songs: List<StemSong>) {
        val arr = JSONArray()
        for (s in songs) arr.put(JSONObject().apply {
            put("id", s.id); put("title", s.title); put("bpm", s.bpm); put("key", s.key)
            put("tracks", JSONArray().also { ts ->
                for (t in s.tracks) ts.put(JSONObject().apply {
                    put("id", t.id); put("name", t.name); put("kind", t.kind.name); put("file", t.file)
                    put("volume", t.volume.toDouble()); put("pan", t.pan.toDouble()); put("mute", t.mute); put("solo", t.solo)
                })
            })
        })
        index(ctx).writeText(JSONObject().put("songs", arr).toString())
    }

    fun displayName(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "audio"

    /** Clave estable de un conjunto de archivos: mismos nombres y tamaños = misma canción. */
    fun keyOf(names: List<String>, sizes: List<Long>): String =
        names.zip(sizes).map { (n, s) -> "${n.lowercase()}|$s" }.sorted().joinToString(";")

    fun sizeOf(ctx: Context, uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
    }.getOrNull() ?: -1L

    fun keyOf(ctx: Context, uris: List<Uri>): String = keyOf(uris.map { displayName(ctx, it) }, uris.map { sizeOf(ctx, it) })

    /** Lo que ocupa la canción en el teléfono (bytes). */
    fun bytesOf(ctx: Context, song: StemSong): Long = dir(ctx, song).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Título sugerido: el nombre común de los archivos sin la palabra de la pista. */
    fun suggestTitle(names: List<String>): String {
        val base = names.map { it.substringBeforeLast('.') }
        val first = base.firstOrNull() ?: return "Canción"
        var t = first
        for (k in StemKind.entries) for (w in k.keys) t = t.replace(Regex("(?i)[\\s_\\-.()\\[\\]]*\\b$w(?:s|es)?\\b[\\s_\\-.()\\[\\]]*"), " ")
        t = t.replace(Regex("[_]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', '_')
        return t.ifEmpty { first }
    }

    /** Copia los archivos elegidos y crea la canción. Un solo archivo = "Mezcla". */
    fun import(ctx: Context, uris: List<Uri>, title: String, key: String = ""): StemSong {
        val id = UUID.randomUUID().toString().take(8)
        val dir = File(root(ctx), id).also { it.mkdirs() }
        val tracks = uris.mapIndexed { i, uri ->
            val name = displayName(ctx, uri)
            val ext = name.substringAfterLast('.', "audio").lowercase().take(5)
            val f = File(dir, "t$i.$ext")
            ctx.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
            val kind = if (uris.size == 1) StemKind.MIX else StemKind.guess(name)
            StemTrack("t$i", if (kind == StemKind.MIX && uris.size > 1) name.substringBeforeLast('.') else kind.label, kind, f.name)
        }
        val song = StemSong(id, title.ifBlank { "Canción" }, tracks, 0, key)
        save(ctx, listOf(song) + load(ctx))
        return song
    }

    fun delete(ctx: Context, song: StemSong) {
        File(root(ctx), song.id).deleteRecursively()
        save(ctx, load(ctx).filter { it.id != song.id })
    }
}
