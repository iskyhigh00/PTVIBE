package com.glidelive.princess

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

data class NarrationRequest(
    val text: String, val speaker: Int, val language: String, val pace: Float, val pitch: Int,
    val texture: NarrationTexture = NarrationTexture.NATURAL,
)
data class NarratedAudio(val text: String, val file: File, val seconds: Float)

object NeuralNarrator {
    const val VOICES = 10

    suspend fun render(
        context: Context,
        request: NarrationRequest,
        destination: File,
        progress: (Float) -> Unit,
    ): NarratedAudio = withContext(Dispatchers.Default) {
        require(request.text.isNotBlank() && request.text.length <= NarrationText.MAX_SELECTION) {
            "Selecciona hasta ${NarrationText.MAX_SELECTION} caracteres para cada audio."
        }
        check(NarrationPack.isReady(context)) { "Descarga el paquete de voces primero." }
        val dir = NarrationPack.directory(context)
        fun path(file: String) = File(dir, file).absolutePath
        val model = OfflineTtsSupertonicModelConfig(
            durationPredictor = path("duration_predictor.int8.onnx"),
            textEncoder = path("text_encoder.int8.onnx"),
            vectorEstimator = path("vector_estimator.int8.onnx"),
            vocoder = path("vocoder.int8.onnx"),
            ttsJson = path("tts.json"),
            unicodeIndexer = path("unicode_indexer.bin"),
            voiceStyle = path("voice.bin"),
        )
        val tts = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(supertonic = model, numThreads = 2)))
        try {
            check(request.speaker in 0 until tts.numSpeakers()) { "Esta voz no existe en el paquete instalado." }
            val rate = tts.sampleRate()
            check(rate in 8000..48000) { "El motor de voces devolvió una frecuencia inválida." }
            val parts = NarrationText.chunks(request.text, Locale.forLanguageTag(request.language))
            val pitch = DramaRenderer.pitchFactor(request.pitch.toFloat())
            val config = GenerationConfig(
                speed = (request.pace / pitch).toFloat().coerceIn(0.7f, 1.7f),
                sid = request.speaker,
                numSteps = 8,
                extra = mapOf("lang" to request.language),
            )
            val blocks = ArrayList<ShortArray>(parts.size)
            val silence = rate / 9
            var frames = 0
            for ((index, part) in parts.withIndex()) {
                currentCoroutineContext().ensureActive()
                val audio = tts.generateWithConfig(part, config)
                currentCoroutineContext().ensureActive()
                check(audio.samples.isNotEmpty() && audio.sampleRate == rate) { "No se pudo narrar el fragmento ${index + 1}." }
                val size = (audio.samples.size / pitch).roundToInt().coerceAtLeast(1)
                frames += size + if (index == parts.lastIndex) 0 else silence
                check(frames <= rate * 300) { "El audio supera cinco minutos. Selecciona un fragmento más corto." }
                val block = ShortArray(size) { i ->
                    val at = (i * pitch).coerceAtMost(audio.samples.lastIndex.toDouble())
                    val before = at.toInt()
                    val after = minOf(before + 1, audio.samples.lastIndex)
                    val sample = audio.samples[before] + (audio.samples[after] - audio.samples[before]) * (at - before)
                    (sample.coerceIn(-1.0, 1.0) * 32767).roundToInt().toShort()
                }
                check(block.any { it != 0.toShort() }) { "El fragmento ${index + 1} no generó sonido." }
                NarrationEffects.apply(block, rate, request.texture)
                blocks += block
                progress((index + 1f) / parts.size)
            }
            val pcm = ShortArray(frames)
            var offset = 0
            for (block in blocks) {
                block.copyInto(pcm, offset)
                offset += block.size + silence
            }
            WavIo.write(destination, pcm, 1, rate)
            NarratedAudio(request.text, destination, frames.toFloat() / rate)
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            tts.release()
        }
    }
}
