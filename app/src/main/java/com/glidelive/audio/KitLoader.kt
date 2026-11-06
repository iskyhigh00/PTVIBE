package com.glidelive.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface KitState {
    data object Idle : KitState
    data class Loading(val percent: Int) : KitState
    data object Ready : KitState
    data object Missing : KitState
}

/** Carga el kit acústico (WAV estéreo 16 bit en assets/kit) y lo entrega al motor nativo. */
object KitLoader {
    private val _state = MutableStateFlow<KitState>(KitState.Idle)
    val state: StateFlow<KitState> = _state

    private const val PADS = 13
    private const val LAYERS = 3
    private const val VARIANTS = 3

    class Wav(val data: FloatArray, val channels: Int, val rate: Int)

    suspend fun load(ctx: Context) = withContext(Dispatchers.IO) {
        if (_state.value is KitState.Loading || _state.value is KitState.Ready) return@withContext
        val files = ctx.assets.list("kit")?.toSet().orEmpty()
        if (files.isEmpty()) { _state.value = KitState.Missing; return@withContext }
        _state.value = KitState.Loading(0)
        try {
            NativeEngine.kitBegin()
            var done = 0
            val total = PADS * LAYERS
            for (pad in 0 until PADS) for (layer in 0 until LAYERS) {
                val loaded = HashMap<Int, Wav>()
                for (v in 0 until VARIANTS) {
                    val name = "p${pad}_l${layer}_v$v.wav"
                    if (name in files) ctx.assets.open("kit/$name").use { loaded[v] = parseWav(it.readBytes()) }
                }
                if (loaded.isNotEmpty()) {
                    val have = loaded.keys.sorted()
                    for (v in 0 until VARIANTS) {
                        // Variantes que faltan: se repite una existente (el motor evita repetir seguidas).
                        val w = loaded[v] ?: loaded[have[v % have.size]]!!
                        NativeEngine.kitSet(pad, layer, v, w.data, w.channels, w.rate)
                    }
                }
                done++
                _state.value = KitState.Loading(done * 100 / total)
            }
            NativeEngine.kitCommit()
            _state.value = KitState.Ready
        } catch (e: Exception) {
            _state.value = KitState.Missing  // se mantiene el kit sintético
        }
    }

    fun parseWav(bytes: ByteArray): Wav {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1
        var rate = 44100
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12)
                bits = bb.getShort(pos + 22).toInt()
            } else if (id == "data") {
                require(bits == 16) { "solo PCM 16 bit" }
                val n = minOf(size, bytes.size - pos - 8) / 2
                val out = FloatArray(n)
                val start = pos + 8
                for (i in 0 until n) out[i] = bb.getShort(start + 2 * i) / 32768f
                return Wav(out, channels, rate)
            }
            pos += 8 + size + (size and 1)
        }
        error("WAV sin datos")
    }
}
