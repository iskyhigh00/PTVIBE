package com.glidelive.viz

import com.glidelive.audio.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Evento del motor: golpe de batería o cambio en las voces de acordes. */
data class EngineEvent(
    val type: Int, val a: Int, val b: Int, val f: Float, val glideMs: Float, val curve: Int, val tMs: Double,
)

/**
 * Único consumidor de la cola nativa de eventos (motor -> UI). Reparte cada evento a la visualización
 * y al registro de sesión (exportación MIDI). Nunca bloquea el hilo de audio.
 */
object EventHub {
    val timeline = ChordTimeline()
    @Volatile var ghostLoopMs: Double = 0.0

    private val listeners = ArrayList<(EngineEvent) -> Unit>()
    private var job: Job? = null
    private val buf = DoubleArray(6 * 128)

    @Synchronized fun addListener(l: (EngineEvent) -> Unit) { listeners.add(l) }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val n = NativeEngine.pollViz(buf)
                for (i in 0 until n) {
                    val o = i * 6
                    val curveBits = buf[o + 4].toInt()
                    val ev = EngineEvent(
                        type = buf[o].toInt(), a = buf[o + 1].toInt(), b = curveBits shr 8, f = buf[o + 2].toFloat(),
                        glideMs = buf[o + 3].toFloat(), curve = curveBits and 0xFF, tMs = buf[o + 5],
                    )
                    if (ev.type >= 10) timeline.onEvent(ev.type, ev.a, ev.b, ev.f, ev.glideMs, ev.curve, ev.tMs)
                    val snapshot = synchronized(this@EventHub) { listeners.toList() }
                    for (l in snapshot) l(ev)
                }
                delay(if (n > 0) 4 else 12)
            }
        }
    }

    fun nowMs(): Double = System.nanoTime() / 1.0e6
}
