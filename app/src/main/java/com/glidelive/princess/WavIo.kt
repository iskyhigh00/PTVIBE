package com.glidelive.princess

import com.glidelive.stems.Decoded
import java.io.File
import java.io.RandomAccessFile

/** Lectura y escritura directa de WAV PCM 16 bit (sin pasar por los códecs de Android). */
object WavIo {
    fun write(file: File, pcm: ShortArray, channels: Int, rate: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val bytes = pcm.size * 2L
            fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            raf.write("RIFF".toByteArray()); raf.write(le32(36 + bytes)); raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray()); raf.write(le32(16)); raf.write(le16(1)); raf.write(le16(channels))
            raf.write(le32(rate.toLong())); raf.write(le32(rate.toLong() * channels * 2)); raf.write(le16(channels * 2)); raf.write(le16(16))
            raf.write("data".toByteArray()); raf.write(le32(bytes))
            val buf = ByteArray(1 shl 16)
            var i = 0
            while (i < pcm.size) {
                val n = minOf(buf.size / 2, pcm.size - i)
                for (k in 0 until n) { val v = pcm[i + k].toInt(); buf[2 * k] = v.toByte(); buf[2 * k + 1] = (v shr 8).toByte() }
                raf.write(buf, 0, n * 2)
                i += n
            }
        }
    }

    /** Escribe un WAV por partes (sin tener todo el audio en memoria). Llamar a [close] al terminar. */
    class Writer(file: File, private val channels: Int, private val rate: Int) : java.io.Closeable {
        private val raf = RandomAccessFile(file, "rw")
        private var bytes = 0L
        private val buf = ByteArray(1 shl 16)
        init { try { raf.setLength(0); header(0) } catch (t: Throwable) { raf.close(); throw t } }
        private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        private fun header(dataBytes: Long) {
            raf.seek(0)
            raf.write("RIFF".toByteArray()); raf.write(le32(36 + dataBytes)); raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray()); raf.write(le32(16)); raf.write(le16(1)); raf.write(le16(channels))
            raf.write(le32(rate.toLong())); raf.write(le32(rate.toLong() * channels * 2)); raf.write(le16(channels * 2)); raf.write(le16(16))
            raf.write("data".toByteArray()); raf.write(le32(dataBytes))
        }
        /** Agrega [count] muestras (no cuadros) de [pcm]. */
        fun write(pcm: ShortArray, count: Int = pcm.size) {
            var i = 0
            while (i < count) {
                val n = minOf(buf.size / 2, count - i)
                for (k in 0 until n) { val v = pcm[i + k].toInt(); buf[2 * k] = v.toByte(); buf[2 * k + 1] = (v shr 8).toByte() }
                raf.write(buf, 0, n * 2)
                i += n
            }
            bytes += count * 2L
        }
        override fun close() { try { header(bytes) } finally { raf.close() } }
    }

    /** Lee un WAV PCM 16 bit; null si el archivo es de otro tipo (entonces se usa el decodificador del sistema). */
    fun read(file: File): Decoded? = runCatching {
        val b = file.readBytes()
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
        var pos = 12
        var channels = 0; var rate = 0; var bits = 0; var format = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val rawSize = u32(pos + 4)
            val size = if (id == "data" && (rawSize == 0L || rawSize > b.size - pos - 8)) (b.size - pos - 8) else rawSize.toInt().coerceAtLeast(0)   // WAV "en streaming": el tamaño real es lo que queda
            if (id == "fmt ") { format = u16(pos + 8); channels = u16(pos + 10); rate = u32(pos + 12).toInt(); bits = u16(pos + 22) }
            else if (id == "data") {
                if (format != 1 || bits != 16 || channels !in 1..2 || rate <= 0) return null
                val n = minOf(size, b.size - pos - 8) / 2
                val out = ShortArray(n)
                for (i in 0 until n) out[i] = (u16(pos + 8 + 2 * i)).toShort()
                return Decoded(out, channels, rate)
            }
            pos += 8 + size + (size and 1)
        }
        null
    }.getOrNull()

    /** Recorta el silencio del principio y del final (con un margen), para que las voces entren justo. [channels] evita cortar a mitad de un cuadro estéreo. */
    fun trimSilence(pcm: ShortArray, rate: Int, threshold: Int = 500, marginSec: Float = 0.12f, channels: Int = 1): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = pcm.size / ch
        fun loud(f: Int): Boolean { for (c in 0 until ch) if (kotlin.math.abs(pcm[f * ch + c].toInt()) >= threshold) return true; return false }
        var a = 0
        while (a < frames && !loud(a)) a++
        if (a >= frames) return pcm
        var b = frames - 1
        while (b > a && !loud(b)) b--
        val m = (marginSec * rate).toInt()
        return pcm.copyOfRange(maxOf(0, a - m) * ch, minOf(frames, b + m + 1) * ch)
    }
}
