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
            val remaining = b.size - pos - 8
            val nextIsChunk = remaining >= 8 && (0 until 4).all { k -> (b[pos + 8 + k].toInt() and 0xFF) in 0x20..0x7E } &&
                u32(pos + 12).let { it >= 0 && it <= remaining - 8 }
            val size = if (id == "data" && ((rawSize == 0L && !nextIsChunk) || rawSize > remaining)) remaining else rawSize.toInt().coerceAtLeast(0)   // WAV "en streaming": el tamaño real es lo que queda
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

    /** Dónde están las muestras dentro de un WAV PCM 16 bit: posición, largo en bytes, canales y frecuencia (null si no es de ese tipo). */
    class DataRegion(val offset: Long, val bytes: Long, val channels: Int, val rate: Int) { val frames: Long get() = bytes / (2L * channels) }

    fun dataRegion(file: File): DataRegion? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12); raf.readFully(head)
            if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return null
            var channels = 0; var rate = 0; var bits = 0; var format = 0
            var pos = 12L
            val h = ByteArray(8)
            while (pos + 8 <= raf.length()) {
                raf.seek(pos); raf.readFully(h)
                val id = String(h, 0, 4)
                val raw = (h[4].toLong() and 0xFF) or ((h[5].toLong() and 0xFF) shl 8) or ((h[6].toLong() and 0xFF) shl 16) or ((h[7].toLong() and 0xFF) shl 24)
                if (id == "fmt ") {
                    val f = ByteArray(16); raf.readFully(f)
                    format = (f[0].toInt() and 0xFF) or ((f[1].toInt() and 0xFF) shl 8)
                    channels = (f[2].toInt() and 0xFF) or ((f[3].toInt() and 0xFF) shl 8)
                    rate = (f[4].toInt() and 0xFF) or ((f[5].toInt() and 0xFF) shl 8) or ((f[6].toInt() and 0xFF) shl 16) or ((f[7].toInt() and 0xFF) shl 24)
                    bits = (f[14].toInt() and 0xFF) or ((f[15].toInt() and 0xFF) shl 8)
                } else if (id == "data") {
                    if (format != 1 || bits != 16 || channels !in 1..2 || rate <= 0) return null
                    val remaining = raf.length() - pos - 8
                    val bytes = if (raw == 0L || raw > remaining) remaining else raw   // grabado en streaming o cortado: vale lo que haya
                    return DataRegion(pos + 8, bytes, channels, rate)
                }
                pos += 8 + raw + (raw and 1L)
            }
            null
        }
    }.getOrNull()

    /** Recorre el WAV por partes (sin cargarlo entero) y entrega cada bloque de muestras con su primer cuadro. */
    private inline fun forEachBlock(file: File, region: DataRegion, block: (ShortArray, Int, Long) -> Unit) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(region.offset)
            val ch = region.channels
            val buf = ByteArray(1 shl 16)
            val shorts = ShortArray(buf.size / 2)
            var left = region.frames * ch * 2
            var frame = 0L
            while (left > 0) {
                val want = minOf(buf.size.toLong(), left).toInt() / (2 * ch) * (2 * ch)
                if (want <= 0) break
                val got = raf.read(buf, 0, want)
                if (got <= 0) break
                val usable = got / (2 * ch) * (2 * ch)
                for (i in 0 until usable / 2) shorts[i] = ((buf[2 * i].toInt() and 0xFF) or (buf[2 * i + 1].toInt() shl 8)).toShort()
                block(shorts, usable / 2, frame)
                frame += usable / (2 * ch)
                left -= usable
            }
        }
    }

    /** Como [speechBounds] pero leyendo el archivo por partes: sirve para tomas largas sin gastar memoria. Devuelve (inicio, fin, cuadros totales). */
    fun scanBounds(file: File, threshold: Int = 500, marginSec: Float = 0.12f): Triple<Long, Long, Long>? {
        val region = dataRegion(file) ?: return null
        val ch = region.channels
        var first = -1L; var last = -1L
        forEachBlock(file, region) { samples, count, frame0 ->
            var i = 0
            while (i < count) {
                var loud = false
                for (c in 0 until ch) if (kotlin.math.abs(samples[i + c].toInt()) >= threshold) { loud = true; break }
                if (loud) { val f = frame0 + i / ch; if (first < 0) first = f; last = f }
                i += ch
            }
        }
        if (first < 0) return null
        val m = (marginSec * region.rate).toLong()
        return Triple(maxOf(0L, first - m), minOf(region.frames, last + m + 1), region.frames)
    }

    /** Forma de onda: [perSecond] puntos por segundo (entre 240 y 120000 en total), normalizada a 0..1. */
    fun peaksOfFile(file: File, perSecond: Int = 50): FloatArray? {
        val region = dataRegion(file) ?: return null
        val frames = region.frames
        if (frames < 240) return null
        val buckets = (frames * perSecond / region.rate).toInt().coerceIn(240, 120_000)
        val out = FloatArray(buckets)
        val ch = region.channels
        forEachBlock(file, region) { samples, count, frame0 ->
            var i = 0
            while (i < count) {
                val b = ((frame0 + i / ch) * buckets / frames).toInt().coerceIn(0, buckets - 1)
                val v = kotlin.math.abs(samples[i].toInt()) / 32768f
                if (v > out[b]) out[b] = v
                i += ch
            }
        }
        val top = out.max().coerceAtLeast(0.02f)
        for (i in out.indices) out[i] = (out[i] / top).coerceIn(0f, 1f)
        return out
    }

    /** Primer y último cuadro [a, b) con sonido (más un margen), o null si todo es silencio. */
    fun speechBounds(pcm: ShortArray, rate: Int, threshold: Int = 500, marginSec: Float = 0.12f, channels: Int = 1): Pair<Int, Int>? {
        val ch = channels.coerceAtLeast(1)
        val frames = pcm.size / ch
        fun loud(f: Int): Boolean { for (c in 0 until ch) if (kotlin.math.abs(pcm[f * ch + c].toInt()) >= threshold) return true; return false }
        var a = 0
        while (a < frames && !loud(a)) a++
        if (a >= frames) return null
        var b = frames - 1
        while (b > a && !loud(b)) b--
        val m = (marginSec * rate).toInt()
        return maxOf(0, a - m) to minOf(frames, b + m + 1)
    }

    /** Recorta el silencio del principio y del final (con un margen), para que las voces entren justo. [channels] evita cortar a mitad de un cuadro estéreo. */
    fun trimSilence(pcm: ShortArray, rate: Int, threshold: Int = 500, marginSec: Float = 0.12f, channels: Int = 1): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val (a, b) = speechBounds(pcm, rate, threshold, marginSec, ch) ?: return pcm
        return pcm.copyOfRange(a * ch, b * ch)
    }
}
