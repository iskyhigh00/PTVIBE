package com.glidelive.princess

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

object NarrationPack {
    private const val NAME = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    private const val DOWNLOAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$NAME.tar.bz2"
    private const val SHA256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"
    private const val BYTES = 128_774_318L
    const val ATTRIBUTION = "Supertonic 3 · Supertone · OpenRAIL-M"

    private val files = mapOf(
        "duration_predictor.int8.onnx" to 3_700_147L,
        "text_encoder.int8.onnx" to 36_416_150L,
        "vector_estimator.int8.onnx" to 78_400_833L,
        "vocoder.int8.onnx" to 25_991_073L,
        "tts.json" to 8_253L,
        "unicode_indexer.bin" to 262_144L,
        "voice.bin" to 517_168L,
    )
    private val installation = Mutex()

    fun directory(context: Context) = File(context.filesDir, "models/$NAME")
    fun isReady(context: Context): Boolean {
        val dir = directory(context)
        return files.all { (name, size) -> File(dir, name).length() == size }
    }

    suspend fun install(context: Context, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        installation.withLock {
            if (isReady(context)) return@withLock
            val target = directory(context)
            val parent = requireNotNull(target.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "No se pudo preparar la carpeta de voces." }
            val temporary = Files.createTempDirectory(parent.toPath(), "descarga-voz-").toFile()
            try {
                val archive = File(temporary, "model.tar.bz2")
                download(archive, progress)
                val extracted = File(temporary, "model")
                check(extracted.mkdir())
                unpack(archive, extracted)
                currentCoroutineContext().ensureActive()
                if (target.exists()) check(target.deleteRecursively()) { "No se pudo reemplazar el paquete incompleto." }
                Files.move(extracted.toPath(), target.toPath(), ATOMIC_MOVE)
                progress(1f)
            } finally {
                temporary.deleteRecursively()
            }
        }
    }

    private suspend fun download(archive: File, progress: (Float) -> Unit) {
        val connection = URL(DOWNLOAD_URL).openConnection() as HttpsURLConnection
        val hash = MessageDigest.getInstance("SHA-256")
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            check(connection.responseCode == 200) { "No se pudo descargar el paquete de voces (HTTP ${connection.responseCode})." }
            connection.inputStream.use { input ->
                archive.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    var lastProgress = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        check(size <= BYTES) { "El paquete supera el tamaño esperado." }
                        hash.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        val percent = (size * 100 / BYTES).toInt()
                        if (percent != lastProgress) {
                            progress(size.toFloat() / BYTES * 0.9f)
                            lastProgress = percent
                        }
                    }
                    check(size == BYTES && hash.digest().joinToString("") { "%02x".format(it) } == SHA256) {
                        "La descarga del paquete está incompleta o dañada. Vuelve a intentarlo."
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun unpack(archive: File, output: File) {
        val expected = files.keys.associateBy { "$NAME/$it" }
        val remaining = files.keys.toMutableSet()
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                val name = expected[entry.name.removePrefix("./")] ?: continue
                check(entry.isFile && !entry.isSymbolicLink && !entry.isLink && entry.size == files[name] && remaining.remove(name)) {
                    "El paquete de voces no tiene el formato esperado."
                }
                File(output, name).outputStream().buffered().use { file ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = tar.read(buffer)
                        if (size < 0) break
                        file.write(buffer, 0, size)
                    }
                }
            }
        }
        check(remaining.isEmpty()) { "Faltan archivos en el paquete de voces." }
    }
}
