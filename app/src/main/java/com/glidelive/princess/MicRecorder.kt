package com.glidelive.princess

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs

/** Graba del micrófono a 44,1 kHz mono. Hace falta el permiso de micrófono antes de llamar a [start]. */
class MicRecorder(private val onLevel: (Float) -> Unit) {
    private var rec: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var data = ShortArray(RATE * 30)
    @Volatile private var n = 0

    /** Pico más alto que se oyó (0..32767): si es muy bajo, el micrófono estaba tapado, silenciado u ocupado. */
    @Volatile var peak = 0
        private set
    /** Algo falló en medio de la grabación (por ejemplo otra app tomó el micrófono). */
    @Volatile var failed = false
        private set
    /** Se llegó al tope de duración. */
    @Volatile var full = false
        private set

    val seconds: Float get() = n.toFloat() / RATE

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        return try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return false
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 2))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            try { r.startRecording() } catch (t: Throwable) { r.release(); throw t }
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return false }
            rec = r
            n = 0; peak = 0; failed = false; full = false
            running = true
            thread = Thread {
                val buf = ShortArray(2048)
                while (running) {
                    val got = r.read(buf, 0, buf.size)
                    if (got < 0) { failed = true; running = false; break }   // error del sistema: no girar en vacío
                    if (got == 0) continue
                    if (n + got > MAX_SECONDS * RATE) { full = true; running = false; break }
                    if (n + got > data.size) data = data.copyOf(minOf(maxOf(data.size * 2, n + got), MAX_SECONDS * RATE))
                    System.arraycopy(buf, 0, data, n, got)
                    n += got
                    var p = 0
                    for (i in 0 until got) p = maxOf(p, abs(buf[i].toInt()))
                    if (p > peak) peak = p
                    onLevel(p / 32768f)
                }
            }.also { it.start() }
            true
        } catch (t: Throwable) {
            runCatching { rec?.release() }; rec = null
            false
        }
    }

    val isRunning: Boolean get() = running

    /** Termina y devuelve lo grabado. */
    fun stop(): ShortArray {
        running = false
        thread?.join(800)
        runCatching { rec?.stop() }
        runCatching { rec?.release() }; rec = null
        return data.copyOf(n)
    }

    companion object {
        const val RATE = 44100
        /** Tope de una sola grabación (minutos de voz seguidos): evita llenar la memoria. */
        const val MAX_SECONDS = 10 * 60
    }
}
