package com.glidelive.princess

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.glidelive.stems.AudioDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

/** Uses a separately installed Android TTS engine, without GlideLive pitch/stretch effects. */
object AndroidNarrator {
    const val POCKET_PACKAGE = "org.pockettts.android.engine"
    const val POCKET_RELEASES = "https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/tag/v0.5.5"
    data class Engine(val packageName: String, val label: String)
    data class Voice(val name: String, val languageTag: String) {
        val label: String get() = "$name · $languageTag"
    }

    @Suppress("DEPRECATION")
    fun engines(context: Context): List<Engine> = context.packageManager.queryIntentServices(
        Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0,
    ).map { Engine(it.serviceInfo.packageName, it.loadLabel(context.packageManager).toString()) }
        .distinctBy { it.packageName }.sortedBy { if (it.packageName == POCKET_PACKAGE) "" else it.label }

    private suspend fun <T> connected(context: Context, engine: String, block: suspend (TextToSpeech) -> T): T =
        withContext(Dispatchers.Main.immediate) {
            check(engines(context).any { it.packageName == engine }) { "Este motor ya no está instalado. Actualiza la lista." }
            val ready = CompletableDeferred<Int>()
            var tts: TextToSpeech? = null
            try {
                // Deferred also handles implementations that invoke onInit synchronously.
                val instance = TextToSpeech(context.applicationContext, { ready.complete(it) }, engine)
                tts = instance
                check(withTimeout(30_000) { ready.await() } == TextToSpeech.SUCCESS) { "No se pudo iniciar este motor. Revisa su paquete de voces." }
                block(instance)
            } finally {
                tts?.stop()
                tts?.shutdown()
            }
        }

    suspend fun voices(context: Context, engine: String): List<Voice> = connected(context, engine) { tts ->
        tts.voices.orEmpty().filter { it.locale.language == "es" && !it.isNetworkConnectionRequired &&
            (engine != POCKET_PACKAGE || it.name.contains("::")) }
            .map { Voice(it.name, it.locale.toLanguageTag()) }.sortedBy { it.label }
    }

    suspend fun render(context: Context, engine: String, voiceName: String, text: String, destination: File): NarratedAudio {
        require(text.isNotBlank() && text.length <= minOf(NarrationText.MAX_SELECTION, TextToSpeech.getMaxSpeechInputLength())) {
            "Selecciona un fragmento de hasta ${minOf(NarrationText.MAX_SELECTION, TextToSpeech.getMaxSpeechInputLength())} caracteres."
        }
        try {
            connected(context, engine) { tts ->
                val voice = tts.voices.orEmpty().firstOrNull {
                    it.name == voiceName && it.locale.language == "es" && !it.isNetworkConnectionRequired &&
                        (engine != POCKET_PACKAGE || it.name.contains("::"))
                } ?: error("La voz española sin conexión ya no está disponible. Actualiza las voces del motor.")
                check(tts.setVoice(voice) == TextToSpeech.SUCCESS && tts.voice?.name == voiceName) { "El motor no pudo seleccionar esta voz." }
                check(tts.setPitch(1f) == TextToSpeech.SUCCESS && tts.setSpeechRate(1f) == TextToSpeech.SUCCESS) {
                    "El motor no pudo activar la voz sin transformaciones."
                }
                val id = UUID.randomUUID().toString()
                val finished = CompletableDeferred<Unit>()
                val listener = object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { if (utteranceId == id) finished.complete(Unit) }
                    @Deprecated("Android callback compatibility")
                    override fun onError(utteranceId: String?) { fail(utteranceId) }
                    override fun onError(utteranceId: String?, errorCode: Int) { fail(utteranceId) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { fail(utteranceId) }
                    private fun fail(utteranceId: String?) {
                        if (utteranceId == id) finished.completeExceptionally(IllegalStateException(
                            "El motor no pudo generar el audio. Revisa que el paquete español y la voz estén instalados en su aplicación.",
                        ))
                    }
                }
                check(tts.setOnUtteranceProgressListener(listener) == TextToSpeech.SUCCESS) { "El motor no pudo preparar la generación." }
                check(tts.synthesizeToFile(text, Bundle(), destination, id) == TextToSpeech.SUCCESS) {
                    "El motor rechazó la generación del audio."
                }
                withTimeout(10 * 60_000L) { finished.await() }
            }
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                check(destination.length() in 44..(128L * 1024 * 1024)) { "El motor devolvió un archivo vacío o demasiado grande." }
                val decoded = WavIo.read(destination) ?: AudioDecoder.decode(destination)
                require(decoded.channels in 1..2 && decoded.rate in 8000..48000 && decoded.pcm.isNotEmpty()) { "El formato de audio del motor no es compatible." }
                val seconds = decoded.pcm.size.toFloat() / decoded.channels / decoded.rate
                require(seconds <= 300f && decoded.pcm.any { it != 0.toShort() }) { "El motor generó silencio o más de cinco minutos de audio." }
                WavIo.write(destination, decoded.pcm, decoded.channels, decoded.rate)
                NarratedAudio(text, destination, seconds)
            }
        } catch (e: Throwable) {
            destination.delete()
            throw e
        }
    }
}
