package com.glidelive.princess

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/** Escribe un M4A (AAC, ~128 kbps) por partes: un cuento de 10 minutos pesa ~10 MB en vez de ~100 MB y se manda bien por WhatsApp. */
class AacWriter(file: File, private val rate: Int = 44100, private val channels: Int = 2, bitrate: Int = 128_000) : java.io.Closeable {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer: MediaMuxer = try { MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) } catch (t: Throwable) { codec.release(); throw t }
    private var sawEnd = false
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var frames = 0L

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        try { codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start() } catch (t: Throwable) { codec.release(); muxer.release(); throw t }
    }

    /** Agrega [count] muestras (no cuadros) intercaladas de [pcm]. */
    fun write(pcm: ShortArray, count: Int = pcm.size) {
        var off = 0
        var waits = 0
        while (off < count) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0 && ++waits > 1000) throw java.io.IOException("El codificador no responde")
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                val n = minOf(buf.capacity() / 2, count - off) / channels * channels
                if (n <= 0) throw java.io.IOException("Audio con muestras incompletas")
                buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, off, n)
                codec.queueInputBuffer(idx, 0, n * 2, frames * 1_000_000L / rate, 0)
                frames += n / channels
                off += n
                waits = 0
            }
            drain(false)
        }
    }

    private fun drain(end: Boolean) {
        var waits = 0
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!end || ++waits > 3000) return }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                out >= 0 -> {
                    val b = codec.getOutputBuffer(out)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        muxer.writeSampleData(track, b, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { sawEnd = true; return }
                }
            }
        }
    }

    /** Cierra el archivo. Lanza un error si el M4A no quedó completo (para que quien lo usa pueda caer al WAV). */
    override fun close() {
        var failure: Throwable? = null
        try {
            // Si no hay un búfer de entrada libre, es porque faltan por vaciar los de salida: se vacían mientras se espera.
            var idx = -1
            var tries = 0
            while (idx < 0 && tries++ < 1500) {
                idx = codec.dequeueInputBuffer(10_000)
                if (idx < 0) drain(false)
            }
            if (idx < 0) throw java.io.IOException("El codificador no aceptó el final del audio")
            codec.queueInputBuffer(idx, 0, 0, frames * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            if (!sawEnd || !started) throw java.io.IOException("El M4A quedó incompleto")
        } catch (t: Throwable) {
            failure = t
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }
            try { if (started) muxer.stop() } catch (t: Throwable) { if (failure == null) failure = t }
            runCatching { muxer.release() }
        }
        failure?.let { throw it }
    }
}
