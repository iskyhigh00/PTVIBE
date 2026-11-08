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
            val size = u32(pos + 4).toInt().coerceAtLeast(0)
            if (id == "fmt ") { format = u16(pos + 8); channels = u16(pos + 10); rate = u32(pos + 12).toInt(); bits = u16(pos + 22) }
            else if (id == "data") {
                if (format != 1 || bits != 16 || channels !in 1..2) return null
                val n = minOf(size, b.size - pos - 8) / 2
                val out = ShortArray(n)
                for (i in 0 until n) out[i] = (u16(pos + 8 + 2 * i)).toShort()
                return Decoded(out, channels, rate)
            }
            pos += 8 + size + (size and 1)
        }
        null
    }.getOrNull()

    /** Recorta el silencio del principio y del final (con un margen), para que las voces entren justo. */
    fun trimSilence(pcm: ShortArray, rate: Int, threshold: Int = 500, marginSec: Float = 0.12f): ShortArray {
        var a = 0
        while (a < pcm.size && kotlin.math.abs(pcm[a].toInt()) < threshold) a++
        if (a >= pcm.size) return pcm
        var b = pcm.size - 1
        while (b > a && kotlin.math.abs(pcm[b].toInt()) < threshold) b--
        val m = (marginSec * rate).toInt()
        return pcm.copyOfRange(maxOf(0, a - m), minOf(pcm.size, b + m + 1))
    }
}
