package com.glidelive.stems.sep

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Nota o golpe transcrito. kind: 0 batería (note = pad), 1 bajo, 2 melodía (note = MIDI). */
data class Note(val t: Double, val dur: Float, val note: Int, val vel: Float, val kind: Int)

/** Pasa pistas a notas: golpes de batería (bombo, caja, hi-hat, crash) y líneas de una sola voz (bajo, melodía) con YIN. */
object Transcriber {
    const val DRUM = 0
    const val BASS = 1
    const val MELODY = 2

    /** Mezcla a mono y lleva a [target] Hz (promedio + interpolación lineal). */
    fun toMono(pcm: ShortArray, channels: Int, rate: Int, target: Int): FloatArray {
        val frames = pcm.size / channels
        val mono = FloatArray(frames) { i ->
            var s = 0f
            for (c in 0 until channels) s += pcm[i * channels + c]
            s / channels / 32768f
        }
        if (rate == target) return mono
        val ratio = rate.toDouble() / target
        val win = max(1, ratio.roundToInt())
        val out = FloatArray((frames / ratio).toInt())
        for (i in out.indices) {
            val c = (i * ratio).toInt()
            val a = max(0, c - win / 2)
            val b = min(frames, a + win)
            var s = 0f
            for (j in a until b) s += mono[j]
            out[i] = s / (b - a).coerceAtLeast(1)
        }
        return out
    }

    // ---------------------------------------------------------------- una sola voz (YIN)

    /**
     * Línea monofónica: bajo (35-400 Hz) o melodía (100-1100 Hz). [x] ya viene a [sr] Hz (11025 para bajo, 22050 para melodía).
     */
    fun monophonic(x: FloatArray, sr: Int, fmin: Float, fmax: Float, window: Int, hop: Int, kind: Int, progress: (Float) -> Unit = {}): List<Note> {
        val tauMin = (sr / fmax).toInt().coerceAtLeast(2)
        val tauMax = (sr / fmin).toInt()
        val need = window + tauMax + 1
        val frames = if (x.size < need) 0 else (x.size - need) / hop + 1
        if (frames == 0) return emptyList()
        val f0 = FloatArray(frames)
        val rms = FloatArray(frames)
        val d = FloatArray(tauMax + 1)
        val cm = FloatArray(tauMax + 1)
        var peak = 1e-6f
        for (t in 0 until frames) {
            val s = t * hop
            var e = 0f
            for (j in 0 until window) e += x[s + j] * x[s + j]
            rms[t] = sqrt(e / window)
            if (rms[t] > peak) peak = rms[t]
        }
        val gate = peak * 0.04f
        for (t in 0 until frames) {
            if (t % 256 == 0) progress(t.toFloat() / frames)
            if (rms[t] < gate) continue
            val s = t * hop
            for (tau in 1..tauMax) {
                var sum = 0f
                for (j in 0 until window) { val v = x[s + j] - x[s + j + tau]; sum += v * v }
                d[tau] = sum
            }
            var run = 0f
            cm[0] = 1f
            for (tau in 1..tauMax) { run += d[tau]; cm[tau] = if (run > 0f) d[tau] * tau / run else 1f }
            var tau = -1
            var k = tauMin
            while (k <= tauMax) {
                if (cm[k] < 0.15f) {
                    while (k + 1 <= tauMax && cm[k + 1] < cm[k]) k++
                    tau = k; break
                }
                k++
            }
            if (tau < 0) continue
            var tf = tau.toFloat()
            if (tau in 2 until tauMax) {  // interpolación parabólica
                val a = cm[tau - 1]; val b = cm[tau]; val c = cm[tau + 1]
                val den = a - 2 * b + c
                if (abs(den) > 1e-9f) tf += 0.5f * (a - c) / den
            }
            f0[t] = sr / tf
        }
        progress(1f)
        return segment(f0, rms, peak, hop.toDouble() / sr, kind)
    }

    private fun segment(f0: FloatArray, rms: FloatArray, peak: Float, frameSec: Double, kind: Int): List<Note> {
        val n = f0.size
        val midi = IntArray(n) { if (f0[it] > 0f) (69 + 12 * ln(f0[it] / 440.0) / ln(2.0)).roundToInt() else -1 }
        // Rellena huecos cortos sin altura (hasta 2 cuadros) entre la misma nota.
        for (i in 1 until n - 1) if (midi[i] < 0) {
            var j = i; while (j < n && midi[j] < 0 && j - i < 3) j++
            if (j < n && midi[i - 1] >= 0 && midi[i - 1] == midi[j]) for (q in i until j) midi[q] = midi[i - 1]
        }
        val out = ArrayList<Note>()
        var i = 0
        val minFrames = max(2, (0.05 / frameSec).toInt())
        while (i < n) {
            if (midi[i] < 0) { i++; continue }
            var j = i
            while (j + 1 < n && midi[j + 1] == midi[i]) {
                // Misma altura repetida: se parte si la energía vuelve a subir con fuerza (nuevo ataque).
                if (j + 1 - i > minFrames && rms[j + 1] > 1.6f * min(rms[j], rms[max(i, j - 1)]) && rms[j + 1] > peak * 0.1f) break
                j++
            }
            val len = j - i + 1
            if (len >= minFrames) {
                var m = 0f
                for (q in i..j) m = max(m, rms[q])
                out.add(Note(i * frameSec, (len * frameSec).toFloat(), midi[i], (0.3f + 0.7f * (m / peak).coerceIn(0f, 1f).pow(0.6f)).coerceIn(0.2f, 1f), kind))
            }
            i = j + 1
        }
        return out
    }

    // ---------------------------------------------------------------- batería

    /** Golpes de una pista de batería a 44,1 kHz mono: bombo (0), caja (1), hi-hat cerrado (3) / abierto (4) y crash (9). */
    fun drums(x: FloatArray, sr: Int, progress: (Float) -> Unit = {}): List<Note> {
        val nFft = 1024
        val hop = 256
        val st = Stft(nFft, hop)
        val frames = st.frames(x.size)
        val hz = sr.toFloat() / nFft
        fun bin(f: Float) = (f / hz).toInt()
        val kLo = bin(30f)..bin(110f)
        val sBody = bin(150f)..bin(320f)
        val sCrack = bin(1800f)..bin(4500f)
        val hiHat = bin(7000f)..bin(11000f)
        val eKick = FloatArray(frames); val eSnare = FloatArray(frames); val eHat = FloatArray(frames)
        st.forward(x, x, x.size, st.bins) { t, k, a, b, _, _ ->
            val p = a * a + b * b
            when (k) {
                in kLo -> eKick[t] += p
                in sBody -> eSnare[t] += p * 0.5f
                in sCrack -> eSnare[t] += p
                in hiHat -> eHat[t] += p
            }
        }
        progress(0.5f)
        val frameSec = hop.toDouble() / sr
        val out = ArrayList<Note>()
        fun flux(e: FloatArray): FloatArray {
            val l = FloatArray(frames) { ln(1f + 10f * sqrt(e[it])) }
            return FloatArray(frames) { if (it == 0) 0f else max(0f, l[it] - l[it - 1]) }
        }
        fun pick(f: FloatArray, minGapSec: Double, percentile: Float, floorRel: Float, emit: (Int, Float) -> Unit) {
            val sorted = f.copyOf().also { it.sort() }
            val p99 = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)]
            val pBase = sorted[(sorted.size * percentile).toInt().coerceAtMost(sorted.size - 1)]
            val thr = max(pBase + 0.25f * (p99 - pBase), p99 * floorRel)
            var last = -1e9
            for (t in 2 until frames - 2) {
                val v = f[t]
                if (v < thr || v < f[t - 1] || v < f[t + 1] || v < f[t - 2] || v < f[t + 2]) continue
                val time = t * frameSec
                if (time - last < minGapSec) continue
                last = time
                emit(t, (0.35f + 0.65f * (v / max(p99, 1e-6f)).coerceIn(0f, 1f)))
            }
        }
        pick(flux(eKick), 0.06, 0.75f, 0.25f) { t, v -> out.add(Note(t * frameSec, 0.2f, 0, v, DRUM)) }
        pick(flux(eSnare), 0.07, 0.80f, 0.30f) { t, v -> out.add(Note(t * frameSec, 0.2f, 1, v, DRUM)) }
        val decayFrames = (0.12 / frameSec).toInt()
        val longFrames = (0.30 / frameSec).toInt()
        pick(flux(eHat), 0.045, 0.80f, 0.25f) { t, v ->
            val here = eHat[t].coerceAtLeast(1e-9f)
            val after = if (t + decayFrames < frames) eHat[t + decayFrames] else 0f
            val long = if (t + longFrames < frames) eHat[t + longFrames] else 0f
            val pad = when {
                v > 0.95f && long > 0.25f * here -> 9          // plato largo y fuerte: crash
                after > 0.35f * here -> 4                       // sigue sonando: hi-hat abierto
                else -> 3
            }
            out.add(Note(t * frameSec, 0.2f, pad, v * (if (pad == 3) 0.8f else 1f), DRUM))
        }
        progress(1f)
        return out.sortedBy { it.t }
    }
}
