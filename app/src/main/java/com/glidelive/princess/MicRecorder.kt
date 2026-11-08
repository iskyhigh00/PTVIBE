package com.glidelive.princess

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs

/** Graba del micrófono a 44,1 kHz mono. Hace falta el permiso de micrófono antes de llamar a [start]. */
class MicRecorder(private val onLevel: (Float) -> Unit) {
    companion object { const val RATE = 44100 }

    private var rec: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var data = ShortArray(RATE * 30)
    private var n = 0

    val seconds: Float get() = n.toFloat() / RATE

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 2))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        rec = r
        n = 0
        running = true
        r.startRecording()
        thread = Thread {
            val buf = ShortArray(2048)
            while (running) {
                val got = r.read(buf, 0, buf.size)
                if (got <= 0) continue
                if (n + got > data.size) data = data.copyOf(maxOf(data.size * 2, n + got))
                System.arraycopy(buf, 0, data, n, got)
                n += got
                var peak = 0
                for (i in 0 until got) peak = maxOf(peak, abs(buf[i].toInt()))
                onLevel(peak / 32768f)
            }
        }.also { it.start() }
        return true
    }

    /** Termina y devuelve lo grabado. */
    fun stop(): ShortArray {
        running = false
        thread?.join(500)
        runCatching { rec?.stop() }
        rec?.release(); rec = null
        return data.copyOf(n)
    }
}
