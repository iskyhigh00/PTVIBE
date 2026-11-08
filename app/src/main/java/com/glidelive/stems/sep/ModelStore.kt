package com.glidelive.stems.sep

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Modelo de separación de voz (MDX-Net, ONNX): se baja una sola vez y queda en la app. */
object ModelStore {
    const val URL_MODEL = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/Kim_Vocal_2.onnx"
    const val SIZE = 66_759_214L
    const val SIZE_MB = 64

    fun file(ctx: Context) = File(File(ctx.filesDir, "models").also { it.mkdirs() }, "kim_vocal_2.onnx")
    fun isReady(ctx: Context) = file(ctx).let { it.exists() && it.length() == SIZE }

    fun download(ctx: Context, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        if (isReady(ctx)) return
        val part = File(file(ctx).path + ".part")
        var url = URL(URL_MODEL)
        var redirects = 0
        var conn: HttpURLConnection
        while (true) {  // GitHub responde con redirecciones al CDN
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000; conn.readTimeout = 30_000; conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 5) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
            }
            require(code == 200) { "No se pudo bajar el modelo (HTTP $code)" }
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: SIZE
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var got = 0L
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
        require(part.length() == SIZE) { "La descarga del modelo quedó incompleta" }
        part.renameTo(file(ctx))
    }
}
