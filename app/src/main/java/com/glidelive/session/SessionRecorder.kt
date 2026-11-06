package com.glidelive.session

import android.content.Context
import android.os.Environment
import com.glidelive.audio.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingResult(val wav: File, val midi: File?)

/** Graba la mezcla final a WAV (16 bit estéreo) y el registro de sesión a MIDI. */
class SessionRecorder(private val ctx: Context) {
    private var job: Job? = null
    private var wav: File? = null
    private var bytes = 0L
    private var rate = 48000
    @Volatile var seconds = 0
        private set

    fun dir(): File = (ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir).also { it.mkdirs() }

    fun start(scope: CoroutineScope, sampleRate: Int) {
        if (job?.isActive == true) return
        rate = sampleRate
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir(), "GlideLive_$stamp.wav")
        wav = f
        bytes = 0
        seconds = 0
        SessionLog.start()
        NativeEngine.setRecording(true)
        job = scope.launch(Dispatchers.IO) {
            BufferedOutputStream(FileOutputStream(f), 1 shl 16).use { os ->
                os.write(header(0, rate))
                val buf = FloatArray(16384)
                val pcm = ByteArray(buf.size * 2)
                while (isActive) {
                    drain(os, buf, pcm)
                    delay(25)
                }
                NativeEngine.setRecording(false)
                drain(os, buf, pcm)
            }
        }
    }

    private fun drain(os: BufferedOutputStream, buf: FloatArray, pcm: ByteArray) {
        while (true) {
            val n = NativeEngine.readRecording(buf)
            if (n <= 0) break
            for (i in 0 until n) {
                val v = (buf[i].coerceIn(-1f, 1f) * 32767f).toInt()
                pcm[2 * i] = v.toByte(); pcm[2 * i + 1] = (v shr 8).toByte()
            }
            os.write(pcm, 0, n * 2)
            bytes += n * 2L
            seconds = (bytes / (rate * 4L)).toInt()
        }
    }

    /** Detiene la grabación y devuelve los archivos (WAV con cabecera final y MIDI). */
    suspend fun stop(bpm: Int): RecordingResult? {
        val j = job ?: return null
        val events = SessionLog.stop()
        j.cancel()
        j.join()
        job = null
        val f = wav ?: return null
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(0); raf.write(header(bytes, rate))
        }
        val mid = if (events.isNotEmpty()) {
            File(f.parentFile, f.nameWithoutExtension + ".mid").also {
                it.writeBytes(MidiExporter.build(events, bpm, SessionLog.startMs))
            }
        } else null
        return RecordingResult(f, mid)
    }

    val isRecording get() = job?.isActive == true

    private fun header(dataBytes: Long, sr: Int): ByteArray {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2)
        h.putInt(sr); h.putInt(sr * 4); h.putShort(4); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
        return h.array()
    }
}
