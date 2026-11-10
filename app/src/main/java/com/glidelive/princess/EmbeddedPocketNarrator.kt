package com.glidelive.princess

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

object EmbeddedPocketNarrator {
    private const val RATE = 24000
    private val rendering = Mutex()
    fun supportDevice(): Boolean = Build.SUPPORTED_ABIS.contains("arm64-v8a")

    suspend fun render(context: Context, text: String, voiceFile: String, destination: File,
                       progress: (Float) -> Unit): NarratedAudio = withContext(Dispatchers.Default) {
        require(supportDevice()) { "Pocket integrado requiere un teléfono Android ARM64." }
        require(text.isNotBlank() && text.length <= NarrationText.MAX_SELECTION) { "Selecciona hasta ${NarrationText.MAX_SELECTION} caracteres." }
        rendering.withLock {
            val dir = EmbeddedPocketPack.directory(context)
            val voices = File(dir, "voices")
            val voice = File(voiceFile).let { if (it.isAbsolute) it else File(voices, voiceFile) }
            check(voice.isFile) { "La referencia de este personaje no está instalada. Descarga sus voces primero." }
            val segments = PocketText.segments(text)
            check(segments.isNotEmpty()) { "No hay texto para narrar." }
            val coroutine = currentCoroutineContext()
            val blocks = ArrayList<ShortArray>()
            var frames = 0
            try {
                coroutine.ensureActive()
                NativePocketTts(File(dir, "models").absolutePath, voices.absolutePath).use { native ->
                    for ((index, segment) in segments.withIndex()) {
                        coroutine.ensureActive()
                        var accepted = false
                        for (attempt in 1..PocketCompletion.MAX_ATTEMPTS) {
                            coroutine.ensureActive()
                            val attemptBlocks = ArrayList<ShortArray>()
                            var attemptFrames = 0
                            var hasSignal = false
                            val ok = native.synthesize(segment.text, voice.absolutePath, object : NativePocketTts.AudioSink {
                                override fun onAudio(samples: FloatArray): Boolean {
                                    if (!coroutine.isActive || frames.toLong() + attemptFrames + samples.size > RATE * 300L) return false
                                    check(samples.all { it.isFinite() }) { "Pocket devolvió una señal de audio inválida." }
                                    hasSignal = hasSignal || samples.any { kotlin.math.abs(it) >= .001f }
                                    attemptBlocks += ShortArray(samples.size) { i -> (samples[i].coerceIn(-1f, 1f) * 32767).roundToInt().toShort() }
                                    attemptFrames += samples.size
                                    return true
                                }
                            })
                            coroutine.ensureActive()
                            check(ok) { "Pocket no pudo completar el fragmento ${index + 1}. No se guardó el audio parcial." }
                            if (!PocketCompletion.isObviouslyIncomplete(segment.text, attemptFrames, RATE, hasSignal)) {
                                blocks.addAll(attemptBlocks)
                                frames += attemptFrames
                                accepted = true
                                break
                            }
                            android.util.Log.w("PocketNarration", "Early ending rejected: segment=${index + 1}, attempt=$attempt, frames=$attemptFrames")
                            // Never append a rejected attempt. A new call advances
                            // the model's sampling state without changing the voice.
                        }
                        check(accepted) { "Pocket cortó antes de tiempo el fragmento ${index + 1} tras ${PocketCompletion.MAX_ATTEMPTS} intentos. No se guardó la toma. Prueba otra voz o Supertonic." }
                        progress((index + 1f) / segments.size)
                    }
                }
                coroutine.ensureActive()
                val pcm = ShortArray(frames)
                var offset = 0
                for (block in blocks) { block.copyInto(pcm, offset); offset += block.size }
                check(pcm.any { it != 0.toShort() }) { "Pocket no produjo sonido." }
                WavIo.write(destination, pcm, 1, RATE)
                NarratedAudio(text, destination, frames.toFloat() / RATE)
            } catch (error: Throwable) {
                destination.delete()
                throw error
            }
        }
    }
}
