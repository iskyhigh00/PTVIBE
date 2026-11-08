package com.glidelive.stems.sep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SeparationDspTest {
    private fun naiveDft(re: FloatArray, im: FloatArray): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val or = DoubleArray(n); val oi = DoubleArray(n)
        for (k in 0 until n) for (j in 0 until n) {
            val a = -2.0 * PI * ((j.toLong() * k) % n) / n
            or[k] += re[j] * cos(a) - im[j] * sin(a)
            oi[k] += re[j] * sin(a) + im[j] * cos(a)
        }
        return or to oi
    }

    @Test fun fftMatchesTheDefinitionForPowerOfTwoAndMixedSizes() {
        for (n in listOf(8, 64, 60, 120, 480, 1024)) {
            val re = FloatArray(n) { sin(it * 0.37f) + 0.5f * cos(it * 1.1f) }
            val im = FloatArray(n) { cos(it * 0.21f) }
            val (er, ei) = naiveDft(re, im)
            val fr = re.copyOf(); val fi = im.copyOf()
            Fft(n).forward(fr, fi)
            for (k in 0 until n) {
                assertEquals("n=$n k=$k re", er[k], fr[k].toDouble(), 1e-2 * sqrt(n.toDouble()))
                assertEquals("n=$n k=$k im", ei[k], fi[k].toDouble(), 1e-2 * sqrt(n.toDouble()))
            }
        }
    }

    @Test fun inverseUndoesForward() {
        for (n in listOf(512, 7680)) {
            val fft = Fft(n)
            val re = FloatArray(n) { sin(it * 0.013f) * 0.5f }
            val im = FloatArray(n) { cos(it * 0.007f) * 0.25f }
            val a = re.copyOf(); val b = im.copyOf()
            fft.forward(a, b); fft.inverse(a, b)
            for (i in 0 until n) {
                assertEquals(re[i], a[i] / n, 1e-4f)
                assertEquals(im[i], b[i] / n, 1e-4f)
            }
        }
    }

    private val x = FloatArray(4096) { (sin(0.05 * it) + 0.3 * cos(0.31 * it)).toFloat() }
    private val y = FloatArray(4096) { (0.5 * x[it] + 0.2 * sin(0.9 * it)).toFloat() }

    @Test fun stftMatchesLibrosa() {
        val st = Stft(256, 64)
        assertEquals(65, st.frames(4096))
        val got = HashMap<Triple<Int, Int, Int>, Float>()
        st.forward(x, y, 4096, 129) { t, k, lr, li, rr, ri ->
            if ((t == 5 && (k == 3 || k == 10)) || (t == 30 && k == 40) || (t == 64 && (k == 0 || k == 128))) {
                got[Triple(t, k, 0)] = lr; got[Triple(t, k, 1)] = li; got[Triple(t, k, 2)] = rr; got[Triple(t, k, 3)] = ri
            }
        }
        fun chk(t: Int, k: Int, lr: Float, li: Float, rr: Float, ri: Float) {
            assertEquals("L re $t,$k", lr, got[Triple(t, k, 0)]!!, 2e-3f)
            assertEquals("L im $t,$k", li, got[Triple(t, k, 1)]!!, 2e-3f)
            assertEquals("R re $t,$k", rr, got[Triple(t, k, 2)]!!, 2e-3f)
            assertEquals("R im $t,$k", ri, got[Triple(t, k, 3)]!!, 2e-3f)
        }
        chk(5, 3, 9.7315216f, -32.33459f, 4.865887f, -16.167274f)
        chk(5, 10, -0.08659288f, 0.35504326f, -0.043108564f, 0.1775998f)
        chk(30, 40, -1.99e-5f, -0.00029509f, 0.01291227f, -0.10380419f)
        chk(64, 0, 45.813805f, 0f, 23.286402f, 0f)
        chk(64, 128, 0.0328042f, 0f, 0.10493164f, 0f)
    }

    @Test fun stftThenIstftRebuildsTheSignal() {
        val st = Stft(512, 128)
        val frames = st.frames(4096)
        val spec = Array(frames) { FloatArray(st.bins * 4) }
        st.forward(x, y, 4096, st.bins) { t, k, a, b, c, d -> spec[t][4 * k] = a; spec[t][4 * k + 1] = b; spec[t][4 * k + 2] = c; spec[t][4 * k + 3] = d }
        val ol = FloatArray(4096); val orr = FloatArray(4096)
        st.inverse(4096, frames, st.bins, { t, k, dst -> for (q in 0 until 4) dst[q] = spec[t][4 * k + q] }, ol, orr)
        for (i in 0 until 4096) {
            assertEquals("L $i", x[i], ol[i], 2e-3f)
            assertEquals("R $i", y[i], orr[i], 2e-3f)
        }
    }

    @Test fun hpssSendsClicksToDrumsAndLowToneToBass() {
        val n = 44100 * 3
        val l = FloatArray(n) { (0.4 * sin(2 * PI * 80 * it / 44100) + 0.2 * sin(2 * PI * 1000 * it / 44100)).toFloat() }
        // Golpes: ráfagas de ruido cada 0,5 s.
        val rnd = java.util.Random(3)
        for (c in 0 until 6) for (j in 0 until 600) l[c * 22050 + 5000 + j] += (rnd.nextGaussian() * 0.5 * (1 - j / 600.0)).toFloat()
        val res = Hpss().split(l, l.copyOf())
        fun e(a: FloatArray, from: Int, to: Int): Double { var s = 0.0; for (i in from until to) s += a[i] * a[i]; return s }
        val hit = 5000..5700
        val steady = 12000..16000
        // Los golpes están sobre todo en batería; el tono grave en bajo; el de 1 kHz en otros.
        assertTrue("golpe en batería", e(res.drumsL, hit.first, hit.last) > 3 * e(res.otherL, hit.first, hit.last) * 0.2)
        assertTrue("bajo con el tono grave", e(res.bassL, steady.first, steady.last) > 5 * e(res.drumsL, steady.first, steady.last))
        assertTrue("otros con el tono de 1 kHz", e(res.otherL, steady.first, steady.last) > e(res.bassL, steady.first, steady.last) * 0.05)
        // La suma de las tres reconstruye la entrada.
        var maxErr = 0f
        for (i in 0 until n step 97) maxErr = maxOf(maxErr, abs(l[i] - (res.drumsL[i] + res.bassL[i] + res.otherL[i])))
        assertTrue("suma exacta ($maxErr)", maxErr < 1e-3f)
    }

    @Test fun resampleKeepsDurationAndShape() {
        val pcm = ShortArray(48000 * 2) { (8000 * sin(2 * PI * 440 * (it / 2) / 48000)).toInt().toShort() }
        val (l, r) = Separator.resample(pcm, 2, 48000)
        assertEquals(44100, l.size)
        assertEquals(l[1000], r[1000], 1e-6f)
        var peak = 0f
        for (v in l) peak = maxOf(peak, abs(v))
        assertEquals(8000f / 32768f, peak, 0.01f)
    }
}
