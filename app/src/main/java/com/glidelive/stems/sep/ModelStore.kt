package com.glidelive.stems.sep

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Modelos de separación (ONNX): se bajan una sola vez y quedan en la app. */
enum class SepModel(val fileName: String, val url: String, val sizeMb: Int, val exactSize: Long) {
    /** MDX-Net: solo voz; rápido (el resto se reparte con filtros). */
    VOICE("kim_vocal_2.onnx", "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/Kim_Vocal_2.onnx", 64, 66_759_214L),
    /** HTDemucs: batería, bajo, otros y voz con una red neuronal. */
    DEMUCS("htdemucs_embedded.onnx", "https://huggingface.co/timcsy/demucs-web-onnx/resolve/main/htdemucs_embedded.onnx", 172, 0L),
}

object ModelStore {
    fun file(ctx: Context, m: SepModel) = File(File(ctx.filesDir, "models").also { it.mkdirs() }, m.fileName)
    fun isReady(ctx: Context, m: SepModel) = file(ctx, m).let { it.exists() && (m.exactSize <= 0 || it.length() == m.exactSize) }

    fun download(ctx: Context, m: SepModel, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        if (isReady(ctx, m)) return
        val part = File(file(ctx, m).path + ".part")
        var url = URL(m.url)
        var redirects = 0
        var conn: HttpURLConnection
        while (true) {  // GitHub y Hugging Face responden con redirecciones al CDN
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000; conn.readTimeout = 30_000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "GlideLive")
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 6) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
            }
            require(code == 200) { "No se pudo bajar el modelo (HTTP $code)" }
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (if (m.exactSize > 0) m.exactSize else m.sizeMb * 1_000_000L)
        var got = 0L
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled()) { part.delete(); throw java.util.concurrent.CancellationException("cancelado") }
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    progress((got.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        conn.disconnect()
        require(got == total || conn.contentLengthLong <= 0) { "La descarga del modelo quedó incompleta" }
        require(m.exactSize <= 0 || part.length() == m.exactSize) { "La descarga del modelo quedó incompleta" }
        part.renameTo(file(ctx, m))
    }
}
