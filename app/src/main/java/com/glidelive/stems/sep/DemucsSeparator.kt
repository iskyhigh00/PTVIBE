package com.glidelive.stems.sep

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.min

/**
 * HTDemucs (ONNX, "embedded": entra la onda y el espectrograma, salen la rama temporal y la de frecuencia) separando
 * batería, bajo, otros y voz. Procesa por tramos de 7,8 s con solapamiento y escribe cada pista a medida que se completa.
 */
class DemucsSeparator(modelPath: String) : AutoCloseable {
    companion object {
        const val N = DemucsDsp.N
        const val BINS = DemucsDsp.BINS
        const val FRAMES = DemucsDsp.FRAMES
        const val OVERLAP = 0.25
        const val TRACKS = 4  // batería, bajo, otros, voz
    }

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelPath,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8)) },
    )
    private val dsp = DemucsDsp()

    /** Peso del tramo: sube y baja linealmente en los bordes (igual que la referencia). */
    private fun window(segLen: Int, stride: Int): FloatArray = FloatArray(segLen) { i ->
        val fadeIn = min(i / (stride * 0.5f), 1f)
        val fadeOut = min((segLen - i) / (stride * 0.5f), 1f)
        min(fadeIn, fadeOut)
    }

    /**
     * Separa [l]/[r] (44,1 kHz) y entrega cada pista terminada a [sink] en orden de tiempo: sink(pista, L, R, desde, cantidad).
     * Las pistas son 0 batería, 1 bajo, 2 otros, 3 voz.
     */
    fun separate(l: FloatArray, r: FloatArray, sink: (Int, FloatArray, FloatArray, Int, Int) -> Unit, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val total = l.size
        val stride = (N * (1 - OVERLAP)).toInt()
        val segments = maxOf(1, ceil((total - N).toDouble() / stride).toInt() + 1)
        val inputName0 = session.inputNames.first()
        val inputName1 = session.inputNames.drop(1).firstOrNull()

        val segL = FloatArray(N); val segR = FloatArray(N)
        val wave = FloatArray(2 * N)
        val spec = FloatArray(4 * BINS * FRAMES)
        val tl = FloatArray(DemucsDsp.ISTFT_LEN + DemucsDsp.FFT); val tr = FloatArray(DemucsDsp.ISTFT_LEN + DemucsDsp.FFT)
        val fl = FloatArray(N); val fr = FloatArray(N)
        // Búfer rodante: [pista][canal][N] y pesos; cubre desde la muestra absoluta [base].
        val accL = Array(TRACKS) { FloatArray(N) }
        val accR = Array(TRACKS) { FloatArray(N) }
        val weight = FloatArray(N)
        val outL = FloatArray(N); val outR = FloatArray(N)
        var base = 0
        var start = 0
        var seg = 0
        while (start < total) {
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelado")
            val segLen = min(N, total - start)
            java.util.Arrays.fill(segL, 0f); java.util.Arrays.fill(segR, 0f)
            System.arraycopy(l, start, segL, 0, segLen)
            System.arraycopy(r, start, segR, 0, segLen)
            java.util.Arrays.fill(spec, 0f)
            dsp.prepare(segL, segR, wave, spec)

            val feeds = HashMap<String, OnnxTensor>()
            feeds[inputName0] = OnnxTensor.createTensor(env, FloatBuffer.wrap(wave), longArrayOf(1, 2, N.toLong()))
            if (inputName1 != null) feeds[inputName1] = OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), longArrayOf(1, 4, BINS.toLong(), FRAMES.toLong()))
            var time: FloatArray? = null
            var freq: FloatArray? = null
            try {
                session.run(feeds).use { res ->
                    for (i in 0 until res.size()) {
                        val t = res.get(i) as OnnxTensor
                        val shape = t.info.shape
                        if (shape.size == 4 && shape[2] == 2L) { time = FloatArray(t.floatBuffer.remaining()).also { t.floatBuffer.get(it) } }
                        else if (shape.size == 5 && shape[2] == 4L) { freq = FloatArray(t.floatBuffer.remaining()).also { t.floatBuffer.get(it) } }
                    }
                }
            } finally { feeds.values.forEach { it.close() } }
            val timeOut = time ?: error("El modelo no devolvió la rama temporal")

            val ow = window(segLen, stride)
            val off = start - base
            for (tk in 0 until TRACKS) {
                if (freq != null) dsp.ispec(freq!!, tk, fl, fr, tl, tr) else { java.util.Arrays.fill(fl, 0f); java.util.Arrays.fill(fr, 0f) }
                val tb = tk * 2 * N
                for (i in 0 until segLen) {
                    accL[tk][off + i] += (timeOut[tb + i] + fl[i]) * ow[i]
                    accR[tk][off + i] += (timeOut[tb + N + i] + fr[i]) * ow[i]
                }
            }
            for (i in 0 until segLen) weight[off + i] += ow[i]

            // Todo lo anterior al inicio del siguiente tramo ya está completo.
            val last = start + stride >= total
            val done = if (last) (total - base) else (start + stride - base)
            for (tk in 0 until TRACKS) {
                for (i in 0 until done) {
                    val w = weight[i]
                    outL[i] = if (w > 0f) accL[tk][i] / w else accL[tk][i]
                    outR[i] = if (w > 0f) accR[tk][i] / w else accR[tk][i]
                }
                sink(tk, outL, outR, 0, done)
            }
            if (!last) {
                val keep = N - done
                for (tk in 0 until TRACKS) {
                    System.arraycopy(accL[tk], done, accL[tk], 0, keep); java.util.Arrays.fill(accL[tk], keep, N, 0f)
                    System.arraycopy(accR[tk], done, accR[tk], 0, keep); java.util.Arrays.fill(accR[tk], keep, N, 0f)
                }
                System.arraycopy(weight, done, weight, 0, keep); java.util.Arrays.fill(weight, keep, N, 0f)
                base += done
            }
            seg++
            progress(seg.toFloat() / segments)
            start += stride
        }
    }

    override fun close() { session.close() }
}
