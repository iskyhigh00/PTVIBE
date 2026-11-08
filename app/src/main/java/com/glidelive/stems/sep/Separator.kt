package com.glidelive.stems.sep

import android.content.Context
import com.glidelive.stems.AudioDecoder
import com.glidelive.stems.StemKind
import com.glidelive.stems.StemLibrary
import com.glidelive.stems.StemSong
import com.glidelive.stems.StemTrack
import java.io.File

/**
 * Separa una mezcla en Voz (red neuronal MDX-Net) + Batería, Bajo y Otros (reparto armónico/percusivo del resto).
 * Los resultados se guardan como WAV junto a la canción.
 */
object Separator {
    const val RATE = 44100

    fun resample(pcm: ShortArray, channels: Int, rate: Int): Pair<FloatArray, FloatArray> {
        val frames = pcm.size / channels
        val outFrames = if (rate == RATE) frames else (frames.toLong() * RATE / rate).toInt()
        val l = FloatArray(outFrames)
        val r = FloatArray(outFrames)
        val ratio = rate.toDouble() / RATE
        for (i in 0 until outFrames) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val f = (pos - i0).toFloat()
            val a0 = pcm[i0 * channels] / 32768f; val a1 = pcm[i1 * channels] / 32768f
            l[i] = a0 + (a1 - a0) * f
            if (channels == 1) { r[i] = l[i] } else {
                val b0 = pcm[i0 * channels + 1] / 32768f; val b1 = pcm[i1 * channels + 1] / 32768f
                r[i] = b0 + (b1 - b0) * f
            }
        }
        return l to r
    }

    /** [stage] recibe (texto, 0..1 del total). */
    fun run(ctx: Context, song: StemSong, stage: (String, Float) -> Unit, cancelled: () -> Boolean): List<StemTrack> {
        val dir = StemLibrary.dir(ctx, song)
        val src = song.tracks.first()

        if (!ModelStore.isReady(ctx)) {
            stage("Descargando el modelo (${ModelStore.SIZE_MB} MB)", 0f)
            ModelStore.download(ctx, { stage("Descargando el modelo (${ModelStore.SIZE_MB} MB)", it * 0.10f) }, cancelled)
        }
        stage("Leyendo la canción", 0.10f)
        val dec = AudioDecoder.decode(File(dir, src.file))
        val (l, r) = resample(dec.pcm, dec.channels, dec.rate)
        if (cancelled()) throw java.util.concurrent.CancellationException("cancelado")

        val (vl, vr) = MdxSeparator(ModelStore.file(ctx).path).use { mdx ->
            mdx.vocals(l, r, { stage("Separando la voz", 0.12f + it * 0.68f) }, cancelled)
        }
        val n = l.size
        // Instrumental = mezcla - voz (en el sitio, para no duplicar memoria).
        for (i in 0 until n) { l[i] -= vl[i]; r[i] -= vr[i] }

        val files = listOf("voz.wav", "bateria.wav", "bajo.wav", "otros.wav").map { File(dir, it) }
        WavWriter(files[0], RATE).use { it.write(vl, vr, 0, n) }

        val hpss = Hpss()
        val seg = 20 * RATE
        val ctxLen = 2 * RATE
        val writers = (1..3).map { WavWriter(files[it], RATE) }
        try {
            var start = 0
            val total = (n + seg - 1) / seg
            var idx = 0
            while (start < n) {
                if (cancelled()) throw java.util.concurrent.CancellationException("cancelado")
                val a = (start - ctxLen).coerceAtLeast(0)
                val b = (start + seg + ctxLen).coerceAtMost(n)
                val res = hpss.split(l.copyOfRange(a, b), r.copyOfRange(a, b))
                val off = start - a
                val cnt = minOf(seg, n - start)
                writers[0].write(res.drumsL, res.drumsR, off, cnt)
                writers[1].write(res.bassL, res.bassR, off, cnt)
                writers[2].write(res.otherL, res.otherR, off, cnt)
                start += seg
                idx++
                stage("Separando batería, bajo y otros", 0.80f + 0.20f * idx / total)
            }
        } finally {
            writers.forEach { it.close() }
        }
        return listOf(
            StemTrack("v", "Voz", StemKind.VOCALS, files[0].name),
            StemTrack("d", "Batería", StemKind.DRUMS, files[1].name),
            StemTrack("b", "Bajo", StemKind.BASS, files[2].name),
            StemTrack("o", "Otros (guitarras…)", StemKind.OTHER, files[3].name),
        )
    }
}
