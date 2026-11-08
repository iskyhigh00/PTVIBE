package com.glidelive.stems.sep

/**
 * Reparte lo que no es voz en batería, bajo y otros: separación armónica/percusiva (filtros de mediana en tiempo y en
 * frecuencia) y el bajo como la parte armónica por debajo de ~250 Hz. Es una aproximación, no una red neuronal.
 */
class Hpss(private val nFft: Int = 2048, private val hop: Int = 512, private val kernel: Int = 31) {
    private val stft = Stft(nFft, hop)
    private val bins = stft.bins
    private val bassLo = (200.0 * nFft / SR).toInt()
    private val bassHi = (300.0 * nFft / SR).toInt()

    class Result(val drumsL: FloatArray, val drumsR: FloatArray, val bassL: FloatArray, val bassR: FloatArray, val otherL: FloatArray, val otherR: FloatArray)

    fun split(l: FloatArray, r: FloatArray): Result {
        val len = l.size
        val frames = stft.frames(len)
        // Espectros (guardados por bin: [bin][frame]) y magnitud media.
        val lr = Array(bins) { FloatArray(frames) }
        val li = Array(bins) { FloatArray(frames) }
        val rr = Array(bins) { FloatArray(frames) }
        val ri = Array(bins) { FloatArray(frames) }
        stft.forward(l, r, len, bins) { t, k, a, b, c, d -> lr[k][t] = a; li[k][t] = b; rr[k][t] = c; ri[k][t] = d }
        val mag = Array(bins) { k -> FloatArray(frames) { t -> 0.5f * (Math.hypot(lr[k][t].toDouble(), li[k][t].toDouble()).toFloat() + Math.hypot(rr[k][t].toDouble(), ri[k][t].toDouble()).toFloat()) } }
        // Mediana en el tiempo (armónico) para cada bin.
        val harm = Array(bins) { k -> slidingMedian(mag[k], kernel) }
        // Mediana en frecuencia (percusivo) para cada cuadro.
        val perc = Array(bins) { FloatArray(frames) }
        val col = FloatArray(bins)
        for (t in 0 until frames) {
            for (k in 0 until bins) col[k] = mag[k][t]
            val m = slidingMedian(col, kernel)
            for (k in 0 until bins) perc[k][t] = m[k]
        }
        // Máscaras suaves (potencia 2): percusivo P²/(H²+P²); el bajo es lo armónico bajo ~250 Hz.
        val maskP = Array(bins) { FloatArray(frames) }
        for (k in 0 until bins) for (t in 0 until frames) {
            val h = harm[k][t]; val p = perc[k][t]
            val d = h * h + p * p + 1e-12f
            maskP[k][t] = p * p / d
        }
        val drumsL = FloatArray(len); val drumsR = FloatArray(len)
        stft.inverse(len, frames, bins, { t, k, dst ->
            val m = maskP[k][t]
            dst[0] = lr[k][t] * m; dst[1] = li[k][t] * m; dst[2] = rr[k][t] * m; dst[3] = ri[k][t] * m
        }, drumsL, drumsR)
        val bassL = FloatArray(len); val bassR = FloatArray(len)
        stft.inverse(len, frames, bins, { t, k, dst ->
            val lowpass = when {
                k <= bassLo -> 1f
                k >= bassHi -> 0f
                else -> 1f - (k - bassLo).toFloat() / (bassHi - bassLo)
            }
            val m = (1f - maskP[k][t]) * lowpass
            dst[0] = lr[k][t] * m; dst[1] = li[k][t] * m; dst[2] = rr[k][t] * m; dst[3] = ri[k][t] * m
        }, bassL, bassR)
        val otherL = FloatArray(len) { l[it] - drumsL[it] - bassL[it] }
        val otherR = FloatArray(len) { r[it] - drumsR[it] - bassR[it] }
        return Result(drumsL, drumsR, bassL, bassR, otherL, otherR)
    }

    /** Mediana deslizante centrada de ventana [k] (impar); los bordes usan la ventana recortada. */
    private fun slidingMedian(src: FloatArray, k: Int): FloatArray {
        val n = src.size
        val out = FloatArray(n)
        val half = k / 2
        val win = FloatArray(k + 1)
        var size = 0
        // Ventana ordenada con inserción / borrado binario.
        fun insert(v: Float) {
            var lo = 0; var hi = size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (win[mid] < v) lo = mid + 1 else hi = mid }
            System.arraycopy(win, lo, win, lo + 1, size - lo)
            win[lo] = v; size++
        }
        fun remove(v: Float) {
            var lo = 0; var hi = size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (win[mid] < v) lo = mid + 1 else hi = mid }
            System.arraycopy(win, lo + 1, win, lo, size - lo - 1)
            size--
        }
        for (i in 0 until minOf(n, half)) insert(src[i])
        for (i in 0 until n) {
            val drop = i - half - 1
            if (drop >= 0) remove(src[drop])
            val add = i + half
            if (add < n) insert(src[add])
            out[i] = win[size / 2]
        }
        return out
    }

    private companion object { const val SR = 44100.0 }
}
