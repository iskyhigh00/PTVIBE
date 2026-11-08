package com.glidelive.stems.sep

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FFT compleja en el sitio para tamaños N = r * 2^k (r impar pequeño, p. ej. 7680 = 15 * 512). Sin normalizar.
 * No es seguro entre hilos (usa buffers propios).
 */
class Fft(val n: Int) {
    private val m: Int = Integer.lowestOneBit(n)
    private val r: Int = n / m
    private val cosM = FloatArray(m / 2 + 1) { cos(2.0 * PI * it / m).toFloat() }
    private val sinM = FloatArray(m / 2 + 1) { sin(2.0 * PI * it / m).toFloat() }
    private val rev = IntArray(m).also { rv ->
        val bits = Integer.numberOfTrailingZeros(m)
        for (i in 0 until m) rv[i] = if (bits == 0) 0 else Integer.reverse(i) ushr (32 - bits)
    }
    private val cosN = if (r > 1) FloatArray(n) { cos(2.0 * PI * it / n).toFloat() } else FloatArray(0)
    private val sinN = if (r > 1) FloatArray(n) { sin(2.0 * PI * it / n).toFloat() } else FloatArray(0)
    private val fr = if (r > 1) FloatArray(n) else FloatArray(0)
    private val fi = if (r > 1) FloatArray(n) else FloatArray(0)
    private val tr = FloatArray(m)
    private val ti = FloatArray(m)

    init { require(n > 0 && r < 64) { "tamaño de FFT no soportado: $n" } }

    /** X[k] = sum x[j] e^{-2 pi i jk/N} */
    fun forward(re: FloatArray, im: FloatArray) {
        if (r == 1) { radix2(re, im, 0, 1); return }
        for (a in 0 until r) {
            for (j in 0 until m) { tr[j] = re[r * j + a]; ti[j] = im[r * j + a] }
            radix2(tr, ti, 0, 1)
            System.arraycopy(tr, 0, fr, a * m, m)
            System.arraycopy(ti, 0, fi, a * m, m)
        }
        for (k in 0 until n) {
            val km = k % m
            var sr = fr[km]
            var si = fi[km]
            for (a in 1 until r) {
                val idx = (a * k) % n
                val wr = cosN[idx]
                val wi = -sinN[idx]
                val xr = fr[a * m + km]
                val xi = fi[a * m + km]
                sr += wr * xr - wi * xi
                si += wr * xi + wi * xr
            }
            re[k] = sr
            im[k] = si
        }
    }

    /** x[j] = sum X[k] e^{+2 pi i jk/N} (sin dividir por N). */
    fun inverse(re: FloatArray, im: FloatArray) = forward(im, re)

    private fun radix2(re: FloatArray, im: FloatArray, off: Int, @Suppress("UNUSED_PARAMETER") stride: Int) {
        for (i in 0 until m) {
            val j = rev[i]
            if (j > i) {
                val a = re[off + i]; re[off + i] = re[off + j]; re[off + j] = a
                val b = im[off + i]; im[off + i] = im[off + j]; im[off + j] = b
            }
        }
        var len = 2
        while (len <= m) {
            val half = len / 2
            val step = m / len
            var i = 0
            while (i < m) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosM[k]
                    val wi = -sinM[k]
                    val p = off + i + j
                    val q = p + half
                    val xr = re[q] * wr - im[q] * wi
                    val xi = re[q] * wi + im[q] * wr
                    re[q] = re[p] - xr; im[q] = im[p] - xi
                    re[p] += xr; im[p] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
