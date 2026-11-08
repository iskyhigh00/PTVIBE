package com.glidelive.stems.sep

import android.content.Context
import com.glidelive.stems.AudioDecoder
import com.glidelive.stems.StemKind
import com.glidelive.stems.StemLibrary
import com.glidelive.stems.StemSong
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Transcribe las pistas de una canción a notas (batería, bajo y melodía) y las guarda junto a ella. */
object ReplicaBuilder {
    private fun file(ctx: Context, song: StemSong) = File(StemLibrary.dir(ctx, song), "replica.json")

    fun has(ctx: Context, song: StemSong) = file(ctx, song).exists()

    fun load(ctx: Context, song: StemSong): List<Note> = runCatching {
        val arr = JSONObject(file(ctx, song).readText()).getJSONArray("notes")
        (0 until arr.length()).map { i ->
            val a = arr.getJSONArray(i)
            Note(a.getDouble(0), a.getDouble(1).toFloat(), a.getInt(2), a.getDouble(3).toFloat(), a.getInt(4))
        }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, song: StemSong, notes: List<Note>, melody: StemKind) {
        val arr = JSONArray()
        for (n in notes) arr.put(JSONArray().put(n.t).put(n.dur.toDouble()).put(n.note).put(n.vel.toDouble()).put(n.kind))
        file(ctx, song).writeText(JSONObject().put("melody", melody.name).put("notes", arr).toString())
    }

    fun melodySource(ctx: Context, song: StemSong): StemKind? = runCatching {
        StemKind.valueOf(JSONObject(file(ctx, song).readText()).getString("melody"))
    }.getOrNull()

    /** Pistas que se pueden usar como melodía, en orden de preferencia. */
    fun melodyChoices(song: StemSong): List<StemKind> =
        listOf(StemKind.VOCALS, StemKind.GUITAR, StemKind.OTHER, StemKind.PIANO).filter { k -> song.tracks.any { it.kind == k } }

    fun canReplicate(song: StemSong) = song.tracks.any { it.kind == StemKind.DRUMS || it.kind == StemKind.BASS } || melodyChoices(song).isNotEmpty()

    fun build(ctx: Context, song: StemSong, melody: StemKind?, progress: (String, Float) -> Unit, cancelled: () -> Boolean): List<Note> {
        val dir = StemLibrary.dir(ctx, song)
        val notes = ArrayList<Note>()
        fun check() { if (cancelled()) throw java.util.concurrent.CancellationException("cancelado") }

        song.tracks.firstOrNull { it.kind == StemKind.DRUMS }?.let { t ->
            progress("Batería: golpes", 0f)
            val d = AudioDecoder.decode(File(dir, t.file))
            val x = Transcriber.toMono(d.pcm, d.channels, d.rate, 44100)
            notes += Transcriber.drums(x, 44100) { progress("Batería: golpes", 0.25f * it) }
            check()
        }
        song.tracks.firstOrNull { it.kind == StemKind.BASS }?.let { t ->
            progress("Bajo: notas", 0.25f)
            val d = AudioDecoder.decode(File(dir, t.file))
            val x = Transcriber.toMono(d.pcm, d.channels, d.rate, 11025)
            notes += Transcriber.monophonic(x, 11025, 35f, 400f, 512, 256, Transcriber.BASS) { progress("Bajo: notas", 0.25f + 0.30f * it) }
            check()
        }
        if (melody != null) song.tracks.firstOrNull { it.kind == melody }?.let { t ->
            progress("Melodía: notas", 0.55f)
            val d = AudioDecoder.decode(File(dir, t.file))
            val x = Transcriber.toMono(d.pcm, d.channels, d.rate, 22050)
            notes += Transcriber.monophonic(x, 22050, 100f, 1100f, 768, 512, Transcriber.MELODY) { progress("Melodía: notas", 0.55f + 0.45f * it) }
            check()
        }
        notes.sortBy { it.t }
        save(ctx, song, notes, melody ?: StemKind.VOCALS)
        progress("Listo", 1f)
        return notes
    }
}
