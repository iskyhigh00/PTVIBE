package com.glidelive.princess

/** One owner, synchronous render; close only after synthesize returns. */
internal class NativePocketTts(modelsDir: String, voicesDir: String) : AutoCloseable {
    private var handle: Long
    init {
        System.loadLibrary("glidelive_pocket")
        handle = nativeCreate(modelsDir, voicesDir)
        check(handle != 0L) { "No se pudo abrir el modelo Pocket en este dispositivo." }
    }
    interface AudioSink { fun onAudio(samples: FloatArray): Boolean }
    fun synthesize(text: String, voiceFile: String, sink: AudioSink): Boolean =
        nativeSynthesize(handle, text.toByteArray(Charsets.UTF_8), voiceFile, sink)
    override fun close() { if (handle != 0L) { nativeDestroy(handle); handle = 0 } }
    private external fun nativeCreate(modelsDir: String, voicesDir: String): Long
    private external fun nativeSynthesize(handle: Long, text: ByteArray, voice: String, sink: AudioSink): Boolean
    private external fun nativeDestroy(handle: Long)
}
