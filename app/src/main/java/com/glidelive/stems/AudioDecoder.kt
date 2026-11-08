package com.glidelive.stems

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File

class Decoded(val pcm: ShortArray, val channels: Int, val rate: Int) {
    val seconds: Double get() = if (rate > 0 && channels > 0) pcm.size.toDouble() / channels / rate else 0.0
}

/** Decodifica cualquier audio que entienda Android (mp3, m4a/aac, ogg, opus, flac, wav, webm) a PCM 16 bit. */
object AudioDecoder {
    private const val MAX_SECONDS = 20 * 60

    fun decode(ctx: Context, uri: Uri, onProgress: (Float) -> Unit = {}): Decoded {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        return run(ex, onProgress)
    }

    fun decode(file: File, onProgress: (Float) -> Unit = {}): Decoded {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        return run(ex, onProgress)
    }

    private fun run(ex: MediaExtractor, onProgress: (Float) -> Unit): Decoded {
        try {
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            require(track >= 0 && format != null) { "El archivo no tiene audio" }
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var out = ShortArray(maxOf(1 shl 20, ((durUs / 1_000_000.0) * rate * channels).toInt() + 4096))
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastReport = 0f
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val ib = codec.dequeueInputBuffer(10_000)
                        if (ib >= 0) {
                            val buf = codec.getInputBuffer(ib)!!
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(ib, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val ob = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        ob >= 0 -> {
                            val buf = codec.getOutputBuffer(ob)!!
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val sb = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                            val count = sb.remaining()
                            if (n + count > out.size) out = out.copyOf(maxOf(out.size * 2, n + count))
                            sb.get(out, n, count)
                            n += count
                            codec.releaseOutputBuffer(ob, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (durUs > 0) {
                                val p = (info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f)
                                if (p - lastReport > 0.02f) { lastReport = p; onProgress(p) }
                            }
                            require(n.toDouble() / maxOf(channels, 1) / maxOf(rate, 1) < MAX_SECONDS) { "Audio demasiado largo (máx. 20 min)" }
                        }
                        ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            } finally {
                codec.stop(); codec.release()
            }
            return downmix(out, n, channels, rate)
        } finally {
            ex.release()
        }
    }

    /** Deja mono o estéreo (si trae más canales, se usan los dos primeros). */
    private fun downmix(src: ShortArray, n: Int, channels: Int, rate: Int): Decoded {
        if (channels <= 2) return Decoded(if (src.size == n) src else src.copyOf(n), maxOf(channels, 1), rate)
        val frames = n / channels
        val o = ShortArray(frames * 2)
        for (i in 0 until frames) { o[2 * i] = src[i * channels]; o[2 * i + 1] = src[i * channels + 1] }
        return Decoded(o, 2, rate)
    }
}
