package com.glidelive.princess

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

data class PocketVoice(val id: String, val name: String, val filePath: String)

object EmbeddedPocketPack {
    private const val ZIP_BYTES = 207_487_086L
    private const val ZIP_SHA = "f83dc41bd0d5c7634d385fdadef496881d398ca99d1886ff25d90cb59e384412"
    private const val URL = "https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/download/v0.5.5/PocketTTS-spanish-FP32.zip"
    private val lock = Mutex()
    internal val files = mapOf(
        "models/mimi_decoder.onnx" to 41_412_445L,
        "models/flow_lm_main.onnx" to 302_364_168L,
        "models/mimi_encoder.onnx" to 39_283_560L,
        "models/tokenizer.model" to 60_895L,
        "models/text_conditioner.onnx" to 16_388_310L,
        "models/flow_lm_flow.onnx" to 39_081_891L,
        "voices/lola.wav" to 458_622L,
    )
    private val references = mapOf("grave" to ("Narrador grave" to 546_764L), "alegre" to ("Voz alegre" to 320_204L))

    fun directory(context: Context) = File(context.filesDir, "models/pocket-es-fp32-v1")
    fun supportsDevice() = Build.SUPPORTED_ABIS.contains("arm64-v8a")
    fun isReady(context: Context): Boolean {
        val root = directory(context)
        return File(root, "ready-v1").isFile && files.all { (path, size) -> File(root, path).length() == size } &&
            references.all { (id, value) -> File(root, "voices/$id.wav").length() == value.second }
    }
    fun voices(context: Context): List<PocketVoice> {
        if (!isReady(context)) return emptyList()
        val root = directory(context)
        return references.map { (id, value) -> PocketVoice(id, value.first, File(root, "voices/$id.wav").absolutePath) } +
            PocketVoice("lola", "Lola", File(root, "voices/lola.wav").absolutePath)
    }

    suspend fun install(context: Context, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            check(supportsDevice()) { "Pocket integrado necesita un dispositivo ARM64." }
            if (isReady(context)) { progress(1f); return@withLock }
            val target = directory(context)
            val parent = checkNotNull(target.parentFile).also { check(it.isDirectory || it.mkdirs()) }
            val temporary = Files.createTempDirectory(parent.toPath(), "pocket-download-").toFile()
            try {
                val zip = File(temporary, "spanish.zip")
                download(zip) { progress(it * .7f) }
                val extracted = File(temporary, "model").also { check(it.mkdir()) }
                zip.inputStream().use { input ->
                    PocketPackExtraction.extract(input, extracted, files) { currentCoroutineContext().ensureActive() }
                }
                progress(.95f)
                for ((id, value) in references) {
                    context.assets.open("pocket-voices/$id.wav").use { input ->
                        File(extracted, "voices/$id.wav").outputStream().use { input.copyTo(it) }
                    }
                    check(File(extracted, "voices/$id.wav").length() == value.second)
                }
                File(extracted, "ready-v1").writeText("Pocket TTS Spanish FP32 v0.5.5\n")
                currentCoroutineContext().ensureActive()
                if (target.exists()) check(target.deleteRecursively()) { "No se pudo reemplazar el paquete incompleto." }
                Files.move(extracted.toPath(), target.toPath(), ATOMIC_MOVE)
                progress(1f)
            } finally { temporary.deleteRecursively() }
        }
    }

    private suspend fun download(file: File, progress: (Float) -> Unit) {
        val connection = java.net.URL(URL).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            check(connection.responseCode == 200) { "No se pudieron descargar las voces (HTTP ${connection.responseCode})." }
            var size = 0L
            var percent = -1
            val hash = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    check(size <= ZIP_BYTES) { "El paquete supera el tamaño esperado." }
                    hash.update(buffer, 0, n); output.write(buffer, 0, n)
                    val next = (size * 100 / ZIP_BYTES).toInt()
                    if (next != percent) { progress(size.toFloat() / ZIP_BYTES); percent = next }
                }
            } }
            check(size == ZIP_BYTES && hash.digest().joinToString("") { "%02x".format(it) } == ZIP_SHA) { "La descarga está incompleta o dañada. Inténtalo de nuevo." }
        } finally { connection.disconnect() }
    }
}

internal object PocketPackExtraction {
    inline fun extract(input: java.io.InputStream, root: File, expected: Map<String, Long>, checkpoint: () -> Unit = {}) {
        val remaining = expected.keys.toMutableSet()
        ZipInputStream(input.buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkpoint()
                val entry = zip.nextEntry ?: break
                val name = entry.name.removePrefix("./")
                val required = expected[name]
                val license = name in setOf("MODEL_LICENSE.txt", "VOICE_ATTRIBUTION.md", "manifest.json")
                if (entry.isDirectory || (required == null && !license)) continue
                if (required != null) check(remaining.remove(name)) { "Archivo duplicado en el paquete." }
                val out = File(root, name)
                check(out.canonicalPath.startsWith(root.canonicalPath + File.separator))
                check(out.parentFile!!.isDirectory || out.parentFile!!.mkdirs())
                var copied = 0L
                out.outputStream().use { output ->
                    while (true) {
                        checkpoint()
                        val n = zip.read(buffer)
                        if (n < 0) break
                        copied += n
                        check(copied <= (required ?: 1024 * 1024L)) { "Archivo demasiado grande en el paquete." }
                        output.write(buffer, 0, n)
                    }
                }
                if (required != null) check(copied == required) { "Archivo incompleto en el paquete." }
            }
        }
        check(remaining.isEmpty()) { "Faltan archivos del motor." }
    }
}
