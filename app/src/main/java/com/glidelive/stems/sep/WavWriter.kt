package com.glidelive.stems.sep

import java.io.File
import java.io.RandomAccessFile

/** WAV PCM 16 bit estéreo escrito por partes (la cabecera se completa al cerrar). */
class WavWriter(file: File, private val rate: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").also { it.setLength(0); it.write(ByteArray(44)) }
    private var bytes = 0L

    fun write(l: FloatArray, r: FloatArray, from: Int, count: Int) {
        val buf = ByteArray(count * 4)
        for (i in 0 until count) {
            val a = (l[from + i] * 32767f).toInt().coerceIn(-32768, 32767)
            val b = (r[from + i] * 32767f).toInt().coerceIn(-32768, 32767)
            buf[4 * i] = a.toByte(); buf[4 * i + 1] = (a shr 8).toByte()
            buf[4 * i + 2] = b.toByte(); buf[4 * i + 3] = (b shr 8).toByte()
        }
        raf.write(buf)
        bytes += buf.size
    }

    override fun close() {
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        raf.seek(0)
        raf.write("RIFF".toByteArray()); raf.write(le32(36 + bytes)); raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray()); raf.write(le32(16)); raf.write(le16(1)); raf.write(le16(2))
        raf.write(le32(rate.toLong())); raf.write(le32(rate * 4L)); raf.write(le16(4)); raf.write(le16(16))
        raf.write("data".toByteArray()); raf.write(le32(bytes))
        raf.close()
    }
}
