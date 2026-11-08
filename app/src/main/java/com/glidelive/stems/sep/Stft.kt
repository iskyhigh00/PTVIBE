package com.glidelive.stems.sep

import kotlin.math.PI
import kotlin.math.cos

/** STFT/iSTFT estéreo equivalentes a torch.stft / torch.istft (ventana de Hann periódica, center = true, relleno reflejado). */
class Stft(val nFft: Int, val hop: Int) {
    private val fft = Fft(nFft)
    val window = FloatArray(nFft) { (0.5 - 0.5 * cos(2.0 * PI * it / nFft)).toFloat() }
    private val re = FloatArray(nFft)
    private val im = FloatArray(nFft)
    val bins = nFft / 2 + 1

    fun frames(len: Int) = 1 + len / hop

    /**
     * STFT de dos canales con una sola FFT compleja por cuadro (L en la parte real, R en la imaginaria).
     * Devuelve [t][canal][2 * bins]: re0, im0, re1, im1 ... para los primeros [keep] bins.
     */
    fun forward(l: FloatArray, r: FloatArray, len: Int, keep: Int, out: (frame: Int, kBin: Int, lr: Float, li: Float, rr: Float, ri: Float) -> Unit) {
        val pad = nFft / 2
        val frames = frames(len)
        for (t in 0 until frames) {
            val start = t * hop - pad
            for (j in 0 until nFft) {
                val x = reflect(start + j, len)
                val w = window[j]
                re[j] = l[x] * w
                im[j] = r[x] * w
            }
            fft.forward(re, im)
            for (k in 0 until keep) {
                val k2 = if (k == 0) 0 else nFft - k
                val ar = re[k]; val ai = im[k]
                val br = re[k2]; val bi = im[k2]
                // L = (Z[k] + conj Z[N-k]) / 2 ; R = (Z[k] - conj Z[N-k]) / (2i)
                val lr = 0.5f * (ar + br)
                val li = 0.5f * (ai - bi)
                val rr = 0.5f * (ai + bi)
                val ri = -0.5f * (ar - br)
                out(t, k, lr, li, rr, ri)
            }
        }
    }

    /**
     * iSTFT estéreo. [spec] entrega para cada cuadro y bin (lr, li, rr, ri); los bins sin entregar quedan en cero.
     * Suma con solapamiento y normaliza por la suma de ventanas^2; devuelve [len] muestras por canal.
     */
    fun inverse(len: Int, frames: Int, keep: Int, spec: (frame: Int, kBin: Int, dst: FloatArray) -> Unit, outL: FloatArray, outR: FloatArray, outOffset: Int = 0, scale: Float = 1f) {
        val pad = nFft / 2
        val total = len + 2 * pad
        val accL = FloatArray(total)
        val accR = FloatArray(total)
        val wsum = FloatArray(total)
        val v = FloatArray(4)
        val inv = 1f / nFft
        for (t in 0 until frames) {
            java.util.Arrays.fill(re, 0f); java.util.Arrays.fill(im, 0f)
            for (k in 0 until keep) {
                spec(t, k, v)
                val lr = v[0]; val li = v[1]; val rr = v[2]; val ri = v[3]
                // Z = XL + i XR con simetría hermítica en cada canal.
                if (k == 0 || k == nFft / 2) {  // bins reales puros
                    re[k] += lr
                    im[k] += rr
                    continue
                }
                re[k] += lr - ri
                im[k] += li + rr
                run {
                    val k2 = nFft - k
                    re[k2] += lr + ri
                    im[k2] += -li + rr
                }
            }
            fft.inverse(re, im)
            val start = t * hop
            for (j in 0 until nFft) {
                val p = start + j
                if (p >= total) break
                val w = window[j]
                accL[p] += re[j] * inv * w
                accR[p] += im[j] * inv * w
                wsum[p] += w * w
            }
        }
        for (i in 0 until len) {
            val p = i + pad
            val d = if (wsum[p] > 1e-8f) wsum[p] else 1f
            outL[outOffset + i] = accL[p] / d * scale
            outR[outOffset + i] = accR[p] / d * scale
        }
    }

    private fun reflect(i: Int, len: Int): Int {
        var x = i
        if (len <= 1) return 0
        while (x < 0 || x >= len) {
            if (x < 0) x = -x
            if (x >= len) x = 2 * (len - 1) - x
        }
        return x
    }
}
