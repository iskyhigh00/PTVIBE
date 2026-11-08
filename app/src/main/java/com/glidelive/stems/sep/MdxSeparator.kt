package com.glidelive.stems.sep

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** Modelo MDX-Net (voz) en ONNX: espectrograma [1, 4, dimF, dimT] -> mismo tamaño (voz aislada). */
class MdxSeparator(
    private val modelPath: String,
    private val nFft: Int = 7680,
    private val hop: Int = 1024,
    private val dimF: Int = 3072,
    private val dimT: Int = 256,
    private val compensate: Float = 1.009f,
) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelPath,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6)) },
    )
    private val inputName = session.inputNames.first()
    private val stft = Stft(nFft, hop)
    private val trim = nFft / 2
    private val chunk = hop * (dimT - 1)
    private val gen = chunk - 2 * trim

    /** Pasadas necesarias para [samples] muestras (para estimar el progreso). */
    fun chunksFor(samples: Int) = (samples + gen - 1) / gen

    /**
     * Aísla la voz de una mezcla estéreo a 44,1 kHz. [l]/[r] en float; devuelve la voz en los mismos arreglos nuevos.
     * [progress] recibe 0..1; [cancelled] se consulta entre pasadas.
     */
    fun vocals(l: FloatArray, r: FloatArray, progress: (Float) -> Unit, cancelled: () -> Boolean): Pair<FloatArray, FloatArray> {
        val n = l.size
        val outL = FloatArray(n)
        val outR = FloatArray(n)
        val total = chunksFor(n)
        val cl = FloatArray(chunk)
        val cr = FloatArray(chunk)
        val tl = FloatArray(chunk)
        val tr = FloatArray(chunk)
        val input = FloatArray(4 * dimF * dimT)
        var done = 0
        var pos = 0
        while (pos < n) {
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelado")
            // Ventana [pos - trim, pos - trim + chunk) de la señal con ceros fuera de rango.
            for (i in 0 until chunk) {
                val src = pos - trim + i
                if (src in 0 until n) { cl[i] = l[src]; cr[i] = r[src] } else { cl[i] = 0f; cr[i] = 0f }
            }
            stft.forward(cl, cr, chunk, dimF) { t, k, lr, li, rr, ri ->
                input[(0 * dimF + k) * dimT + t] = lr
                input[(1 * dimF + k) * dimT + t] = li
                input[(2 * dimF + k) * dimT + t] = rr
                input[(3 * dimF + k) * dimT + t] = ri
            }
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 4, dimF.toLong(), dimT.toLong()))
            val out = FloatArray(4 * dimF * dimT)
            tensor.use {
                session.run(mapOf(inputName to tensor)).use { res ->
                    (res[0] as OnnxTensor).floatBuffer.get(out)
                }
            }
            stft.inverse(chunk, dimT, dimF, { t, k, dst ->
                dst[0] = out[(0 * dimF + k) * dimT + t]
                dst[1] = out[(1 * dimF + k) * dimT + t]
                dst[2] = out[(2 * dimF + k) * dimT + t]
                dst[3] = out[(3 * dimF + k) * dimT + t]
            }, tl, tr, 0, compensate)
            val take = minOf(gen, n - pos)
            System.arraycopy(tl, trim, outL, pos, take)
            System.arraycopy(tr, trim, outR, pos, take)
            pos += gen
            done++
            progress(done.toFloat() / total)
        }
        return outL to outR
    }

    override fun close() { session.close() }
}
