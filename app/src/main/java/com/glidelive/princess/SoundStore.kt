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
            Sound("cow", "Vaca"), Sound("sheep", "Oveja"), Sound("frog", "Rana"), Sound("crow", "Cuervo"),
            Sound("chirping_birds", "Pájaros"), Sound("insects", "Insectos"), Sound("crickets", "Grillos"),
        )),
        Group("Naturaleza", listOf(
            Sound("rain", "Lluvia"), Sound("thunderstorm", "Tormenta"), Sound("wind", "Viento"), Sound("sea_waves", "Olas"),
            Sound("crackling_fire", "Fuego"), Sound("water_drops", "Gotas"), Sound("pouring_water", "Agua"),
        )),
        Group("Personas", listOf(
            Sound("laughing", "Risa"), Sound("crying_baby", "Bebé llora"), Sound("clapping", "Aplausos"), Sound("footsteps", "Pasos"),
            Sound("coughing", "Tos"), Sound("sneezing", "Estornudo"), Sound("breathing", "Respiración"), Sound("snoring", "Ronquido"),
            Sound("drinking_sipping", "Sorbo"), Sound("brushing_teeth", "Cepillado"),
        )),
        Group("Casa", listOf(
            Sound("door_wood_knock", "Golpe a la puerta"), Sound("door_wood_creaks", "Puerta que cruje"), Sound("glass_breaking", "Vidrio roto"),
            Sound("clock_alarm", "Despertador"), Sound("clock_tick", "Reloj"), Sound("can_opening", "Abrir lata"),
            Sound("keyboard_typing", "Teclado"), Sound("mouse_click", "Clic"), Sound("washing_machine", "Lavadora"),
            Sound("vacuum_cleaner", "Aspiradora"), Sound("toilet_flush", "Cisterna"),
        )),
        Group("Ciudad", listOf(
            Sound("church_bells", "Campanas"), Sound("siren", "Sirena"), Sound("car_horn", "Bocina"), Sound("engine", "Motor"),
            Sound("train", "Tren"), Sound("airplane", "Avión"), Sound("helicopter", "Helicóptero"), Sound("fireworks", "Fuegos artificiales"),
            Sound("chainsaw", "Motosierra"), Sound("hand_saw", "Serrucho"),
        )),
    )

    /** Temas de Kevin MacLeod (incompetech.com), licencia Creative Commons Atribución 3.0. */
    val music = listOf(
        Track("amazing_plan.m4a", "Amazing Plan · misterio"), Track("sneaky_snitch.mp3", "Sneaky Snitch · sigiloso"),
        Track("scheming_weasel.mp3", "Scheming Weasel · travieso"), Track("fluffing_a_duck.mp3", "Fluffing a Duck · cómico"),
        Track("plucky_daisy.webm", "Plucky Daisy · alegre"), Track("merry_go_slower.webm", "Merry Go Slower · carrusel"),
        Track("thatched_villagers.mp3", "Thatched Villagers · aldea"), Track("the_cannery.mp3", "The Cannery · tenso"),
        Track("hyperfun.mp3", "Hyperfun · animado"), Track("monkeys_spinning_monkeys.mp3", "Monkeys Spinning Monkeys · juguetón"),
        Track("the_builder.mp3", "The Builder"), Track("hand_trolley.webm", "Hand Trolley"), Track("look_busy.webm", "Look Busy"),
        Track("meatball_parade.webm", "Meatball Parade"), Track("hustle.mp3", "Hustle"), Track("show_must_be_go.mp3", "Show Must Be Go"),
        Track("if_i_had_a_chicken.mp3", "If I Had a Chicken"), Track("batty_mcfaddlin.mp3", "Batty McFaddlin"),
        Track("four_beers_polka.mp3", "Four Beers Polka"), Track("spazzmatica_polka.mp3", "Spazzmatica Polka"),
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

    fun hasSfx(ctx: Context) = File(root(ctx), "esc50").listFiles()?.any { d -> d.isDirectory && (d.list()?.isNotEmpty() == true) } == true

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
                part.renameTo(dest)
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
        if (!csv.exists() || csv.length() < 1000) csv.writeText(fetchText(ESC_BASE + "meta/esc50.csv", cancelled))
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
        mineIndex(ctx).writeText(JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("file", it.file).put("seconds", it.seconds.toDouble()) }).toString())
    }

    /** Guarda una grabación (WAV 16 bit) como sonido propio, recortándole el silencio de los extremos. */
    @Synchronized fun addMine(ctx: Context, wav: File, name: String): MySound? {
        val d = WavIo.read(wav) ?: return null
        val trimmed = WavIo.trimSilence(d.pcm, d.rate, threshold = 300, marginSec = 0.05f)
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
