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
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Agrega [count] muestras (no cuadros) intercaladas de [pcm]. */
    fun write(pcm: ShortArray, count: Int = pcm.size) {
        var off = 0
        while (off < count) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                val n = minOf(buf.capacity() / 2, count - off)
                buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, off, n)
                codec.queueInputBuffer(idx, 0, n * 2, frames * 1_000_000L / rate, 0)
                frames += n / channels
                off += n
            }
            drain(false)
        }
    }

    private fun drain(end: Boolean) {
        var waits = 0
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!end || ++waits > 500) return }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                out >= 0 -> {
                    val b = codec.getOutputBuffer(out)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        muxer.writeSampleData(track, b, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun close() {
        try {
            val idx = codec.dequeueInputBuffer(1_000_000)
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, frames * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }
            runCatching { if (started) muxer.stop() }; runCatching { muxer.release() }
        }
    }
}
