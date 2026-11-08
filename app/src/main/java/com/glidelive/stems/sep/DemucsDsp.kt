package com.glidelive.stems.sep

import kotlin.math.ceil
import kotlin.math.min

/** Parte de señal de HTDemucs (ONNX "embedded"): prepara el espectrograma de entrada y reconstruye la rama de frecuencia. */
class DemucsDsp {
    companion object {
        const val N = 343980
        const val FFT = 4096
        const val HOP = 1024
        const val BINS = 2048
        const val FRAMES = 336
        const val PAD = HOP / 2 * 3
        const val ISTFT_LEN = (FRAMES + 4 - 1) * HOP + FFT - 2 * (FFT / 2)  // 347136: Stft.inverse suma FFT/2 a cada lado
        private const val SPEC_SCALE = 1f / 64f  // 1 / sqrt(FFT)

        fun reflectPad(x: FloatArray, left: Int, right: Int): FloatArray {
            val len = x.size
            val out = FloatArray(left + len + right)
            for (i in 0 until left) out[i] = x[min(left - i, len - 1)]
            System.arraycopy(x, 0, out, left, len)
            for (i in 0 until right) out[left + len + i] = x[maxOf(0, len - 2 - i)]
            return out
        }
    }

    private val stft = Stft(FFT, HOP)

    /** Onda [1,2,N] y espectrograma [1,4,BINS,FRAMES] de un tramo de exactamente N muestras. */
    fun prepare(l: FloatArray, r: FloatArray, wave: FloatArray, spec: FloatArray) {
        val le = ceil(N.toDouble() / HOP).toInt()
        val padRight = PAD + le * HOP - N
        val a = reflectPad(reflectPad(l, PAD, padRight), FFT / 2, FFT / 2)
        val b = reflectPad(reflectPad(r, PAD, padRight), FFT / 2, FFT / 2)
        val frames = (a.size - FFT) / HOP + 1
        stft.forwardRaw(a, b, frames, BINS, SPEC_SCALE) { t, k, lr, li, rr, ri ->
            val f = t - 2
            if (f in 0 until FRAMES) {
                spec[(0 * BINS + k) * FRAMES + f] = lr
                spec[(1 * BINS + k) * FRAMES + f] = li
                spec[(2 * BINS + k) * FRAMES + f] = rr
                spec[(3 * BINS + k) * FRAMES + f] = ri
            }
        }
        System.arraycopy(l, 0, wave, 0, N)
        System.arraycopy(r, 0, wave, N, N)
    }

    /** iSTFT de la pista [track] de la salida de frecuencia [freq] ([4 pistas][4 canales][BINS][FRAMES]) a N muestras por canal. */
    fun ispec(freq: FloatArray, track: Int, outL: FloatArray, outR: FloatArray, tl: FloatArray, tr: FloatArray) {
        val base = track * 4 * BINS * FRAMES
        stft.inverse(ISTFT_LEN, FRAMES + 4, BINS, { t, k, dst ->
            val f = t - 2
            if (f in 0 until FRAMES) {
                dst[0] = freq[base + (0 * BINS + k) * FRAMES + f]
                dst[1] = freq[base + (1 * BINS + k) * FRAMES + f]
                dst[2] = freq[base + (2 * BINS + k) * FRAMES + f]
                dst[3] = freq[base + (3 * BINS + k) * FRAMES + f]
            } else { dst[0] = 0f; dst[1] = 0f; dst[2] = 0f; dst[3] = 0f }
        }, tl, tr, 0, 64f)
        val off = PAD  // (FFT/2 + PAD) - FFT/2
        System.arraycopy(tl, off, outL, 0, N)
        System.arraycopy(tr, off, outR, 0, N)
    }
}
