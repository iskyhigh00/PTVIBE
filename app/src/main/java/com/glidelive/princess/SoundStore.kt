package com.glidelive.princess

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Sonidos que trae la app para armar la obra: efectos (animales, ambiente, puertas…) y música de fondo. */
object SoundCatalog {
    data class Sound(val id: String, val label: String)
    data class Group(val name: String, val sounds: List<Sound>)
    data class Track(val file: String, val label: String)

    // Categorías de ESC-50 (https://github.com/karolpiczak/ESC-50), en castellano.
    val groups = listOf(
        Group("Animales", listOf(
            Sound("dog", "Perro"), Sound("cat", "Gato"), Sound("rooster", "Gallo"), Sound("hen", "Gallina"), Sound("pig", "Cerdo"),
            Sound("cow", "Vaca"), Sound("sheep", "Oveja"), Sound("frog", "Rana"),
            Sound("chirping_birds", "Pajaritos"), Sound("insects", "Insectos"), Sound("crickets", "Grillos"),
        )),
        Group("Naturaleza", listOf(
            Sound("rain", "Lluvia"), Sound("thunderstorm", "Tormenta"), Sound("wind", "Viento"), Sound("sea_waves", "Olas del mar"),
            Sound("crackling_fire", "Fogata"), Sound("water_drops", "Gotitas"), Sound("pouring_water", "Agua"),
        )),
        Group("Personas", listOf(
            Sound("laughing", "Risas"), Sound("crying_baby", "Bebé llora"), Sound("clapping", "Aplausos"), Sound("footsteps", "Pasos"),
            Sound("breathing", "Respiración"), Sound("brushing_teeth", "Cepillado"), Sound("drinking_sipping", "Sorbito"),
        )),
        Group("Casa", listOf(
            Sound("door_wood_knock", "Toc toc en la puerta"), Sound("door_wood_creaks", "Puerta que cruje"),
            Sound("clock_alarm", "Despertador"), Sound("clock_tick", "Reloj"),
        )),
        Group("Cosas que suenan", listOf(
            Sound("church_bells", "Campanas"), Sound("car_horn", "Bocina"), Sound("engine", "Motor"),
            Sound("train", "Tren"), Sound("airplane", "Avión"), Sound("helicopter", "Helicóptero"),
        )),
    )

    /** Temas de Kevin MacLeod (incompetech.com), licencia Creative Commons Atribución 3.0. */
    val music = listOf(
        Track("plucky_daisy.webm", "Margarita alegre · alegre"), Track("merry_go_slower.webm", "Carrusel lento · tranquila"),
        Track("thatched_villagers.mp3", "Aldea tranquila · tranquila"), Track("fluffing_a_duck.mp3", "Patito travieso · cómica"),
        Track("hyperfun.mp3", "Mucha diversión · animada"), Track("monkeys_spinning_monkeys.mp3", "Monitos que giran · juguetona"),
        Track("if_i_had_a_chicken.mp3", "Si yo tuviera una gallina · campestre"), Track("amazing_plan.m4a", "Un plan increíble · misterio suave"),
        Track("scheming_weasel.mp3", "Comadreja traviesa · travieso"), Track("meatball_parade.webm", "Desfile alegre · desfile"),
        Track("the_builder.mp3", "El constructor · animada"), Track("batty_mcfaddlin.mp3", "Aventura · aventura"),
    )

    const val MUSIC_CREDIT = "Música: Kevin MacLeod (incompetech.com), licencia Creative Commons Atribución 3.0."
    const val SFX_CREDIT = "Efectos: ESC-50 (K. Piczak), sonidos de Freesound con licencias Creative Commons; uso personal / no comercial."
}

data class MySound(val id: String, val name: String, val file: String, val seconds: Float)

/**
 * Descarga y guarda los sonidos en el almacenamiento propio de la app (no en la caché que el sistema puede vaciar):
 * quedan en el teléfono hasta que se borre la app.
 */
object SoundStore {
    private const val ESC_BASE = "https://raw.githubusercontent.com/karolpiczak/ESC-50/master/"
    private const val MUSIC_BASE = "https://raw.githubusercontent.com/cjthomas-opensource/mcmusic-kevin-macleod/master/music/"

    fun root(ctx: Context) = File(ctx.filesDir, "sounds").also { it.mkdirs() }
    private fun escDir(ctx: Context, category: String) = File(root(ctx), "esc50/$category")
    fun musicDir(ctx: Context) = File(root(ctx), "music").also { it.mkdirs() }
    fun mineDir(ctx: Context) = File(root(ctx), "mine").also { it.mkdirs() }

    fun clips(ctx: Context, category: String): List<File> =
        escDir(ctx, category).listFiles { f -> f.extension == "wav" }?.sortedBy { it.name } ?: emptyList()

    /** Los efectos están listos solo si casi todos los sonidos tienen al menos un audio (una descarga a medias no cuenta). */
    fun hasSfx(ctx: Context): Boolean {
        val all = SoundCatalog.groups.flatMap { it.sounds }
        val have = all.count { clips(ctx, it.id).isNotEmpty() }
        return have >= all.size * 8 / 10
    }

    /** Hay algo bajado pero incompleto: se ofrece "Completar descarga". */
    fun sfxPartial(ctx: Context): Boolean = !hasSfx(ctx) && SoundCatalog.groups.flatMap { it.sounds }.any { clips(ctx, it.id).isNotEmpty() }

    fun musicFiles(ctx: Context): List<Pair<SoundCatalog.Track, File>> =
        SoundCatalog.music.mapNotNull { t -> File(musicDir(ctx), t.file).takeIf { it.exists() && it.length() > 0 }?.let { t to it } }

    fun bytesUsed(ctx: Context): Long = root(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---- red ----
    private fun fetch(url: String, dest: File, cancelled: () -> Boolean) {
        if (dest.exists() && dest.length() > 0) return
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                var u = URL(url)
                var redirects = 0
                var conn: HttpURLConnection
                while (true) {
                    conn = u.openConnection() as HttpURLConnection
                    conn.connectTimeout = 20_000; conn.readTimeout = 30_000; conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "GlideLive")
                    val code = conn.responseCode
                    if (code in 300..399 && redirects++ < 5) { u = URL(u, conn.getHeaderField("Location")); conn.disconnect(); continue }
                    require(code == 200) { "HTTP $code" }
                    break
                }
                val total = conn.contentLengthLong
                var got = 0L
                conn.inputStream.use { input -> part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) { part.delete(); throw java.util.concurrent.CancellationException("cancelado") }
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                    }
                } }
                conn.disconnect()
                require(total <= 0 || got == total) { "descarga incompleta" }
                if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
                return
            } catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (e: Exception) { lastError = e; part.delete(); if (attempt < 2) Thread.sleep(800L * (attempt + 1)) }
        }
        throw lastError ?: IllegalStateException("no se pudo descargar")
    }

    private fun fetchText(url: String, cancelled: () -> Boolean): String {
        val tmp = File.createTempFile("snd", ".txt")
        try { tmp.delete(); fetch(url, tmp, cancelled); return tmp.readText() } finally { tmp.delete() }
    }

    /** Baja [perSound] clips de cada sonido (cada uno ~440 kB). Se puede repetir: salta lo que ya está. */
    suspend fun downloadSfx(ctx: Context, perSound: Int, progress: (Float) -> Unit, cancelled: () -> Boolean) = withContext(Dispatchers.IO) {
        val csv = File(root(ctx), "esc50.csv")
        if (!csv.exists() || csv.length() < 20_000) {
            val text = fetchText(ESC_BASE + "meta/esc50.csv", cancelled)
            check(text.lines().size >= 2000) { "Lista de sonidos incompleta" }
            val tmp = File(csv.path + ".part"); tmp.writeText(text)
            check(tmp.renameTo(csv)) { "No se pudo guardar la lista de sonidos" }
        }
        val wanted = SoundCatalog.groups.flatMap { it.sounds }.map { it.id }.toSet()
        val byCat = HashMap<String, MutableList<String>>()
        csv.readLines().drop(1).forEach { line ->
            val p = line.split(',')
            if (p.size >= 4 && p[3] in wanted) byCat.getOrPut(p[3]) { ArrayList() }.add(p[0])
        }
        val jobs = byCat.flatMap { (cat, files) -> files.take(perSound).map { cat to it } }
        val done = AtomicInteger(0)
        val gate = Semaphore(4)
        coroutineScope {
            jobs.map { (cat, name) ->
                async { gate.withPermit {
                    if (cancelled()) throw java.util.concurrent.CancellationException("cancelado")
                    fetch(ESC_BASE + "audio/$name", File(escDir(ctx, cat), name), cancelled)
                    progress(done.incrementAndGet().toFloat() / jobs.size)
                } }
            }.awaitAll()
        }
    }

    suspend fun downloadMusic(ctx: Context, progress: (Float) -> Unit, cancelled: () -> Boolean) = withContext(Dispatchers.IO) {
        val total = SoundCatalog.music.size
        SoundCatalog.music.forEachIndexed { i, t ->
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelado")
            fetch(MUSIC_BASE + t.file, File(musicDir(ctx), t.file), cancelled)
            progress((i + 1f) / total)
        }
    }

    // ---- mis sonidos (lo que se guarda desde las otras pestañas) ----
    private fun mineIndex(ctx: Context) = File(mineDir(ctx), "mine.json")

    fun mine(ctx: Context): List<MySound> = runCatching {
        val a = JSONArray(mineIndex(ctx).readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { MySound(it.getString("id"), it.getString("name"), it.getString("file"), it.optDouble("seconds", 0.0).toFloat()) } }
    }.getOrDefault(emptyList())

    private fun saveMine(ctx: Context, list: List<MySound>) {
        val text = JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("file", it.file).put("seconds", it.seconds.toDouble()) }).toString()
        val tmp = File.createTempFile("mine-", ".tmp", mineDir(ctx))
        try {
            tmp.writeText(text)
            java.nio.file.Files.move(tmp.toPath(), mineIndex(ctx).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }

    /** Guarda una grabación (WAV 16 bit) como sonido propio, recortándole el silencio de los extremos. */
    @Synchronized fun addMine(ctx: Context, wav: File, name: String): MySound? {
        val d = WavIo.read(wav) ?: return null
        val trimmed = WavIo.trimSilence(d.pcm, d.rate, threshold = 300, marginSec = 0.05f, channels = d.channels)
        val id = UUID.randomUUID().toString().take(8)
        val out = File(mineDir(ctx), "$id.wav")
        WavIo.write(out, trimmed, d.channels, d.rate)
        val s = MySound(id, name.ifBlank { "Mi sonido" }, out.name, trimmed.size.toFloat() / d.channels / d.rate)
        saveMine(ctx, mine(ctx) + s)
        wav.delete()
        return s
    }

    @Synchronized fun deleteMine(ctx: Context, id: String) {
        val list = mine(ctx)
        list.firstOrNull { it.id == id }?.let { File(mineDir(ctx), it.file).delete() }
        saveMine(ctx, list.filter { it.id != id })
    }
}
