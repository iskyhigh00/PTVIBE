package com.glidelive.session

import com.glidelive.viz.EngineEvent
import com.glidelive.viz.EventHub

sealed interface LogEv {
    val tMs: Double

    data class Chord(override val tMs: Double, val type: Int, val slot: Int, val fromSlot: Int, val midi: Float, val glideMs: Float, val curve: Int) : LogEv
    data class Drum(override val tMs: Double, val pad: Int, val vel: Float) : LogEv

    /** kind: 0 nota, 1 altura, 2 fin. */
    data class Solo(override val tMs: Double, val idx: Int, val kind: Int, val midi: Float) : LogEv
}

/** Registro de lo tocado durante la grabación de sesión (acordes, batería y solista) para exportarlo a MIDI. */
object SessionLog {
    @Volatile var active = false
        private set
    private val events = ArrayList<LogEv>()
    var startMs = 0.0
        private set
    private var hooked = false

    @Synchronized
    fun start() {
        events.clear()
        startMs = EventHub.nowMs()
        active = true
        if (!hooked) {
            hooked = true
            EventHub.addListener(::onEngine)
        }
    }

    @Synchronized fun stop(): List<LogEv> { active = false; return events.toList() }

    private fun onEngine(e: EngineEvent) {
        if (!active) return
        synchronized(this) {
            when {
                e.type == 0 -> events.add(LogEv.Drum(e.tMs, e.a, e.f))
                e.type in 10..15 -> events.add(LogEv.Chord(e.tMs, e.type, e.a, e.b, e.f, e.glideMs, e.curve))
            }
        }
    }

    fun solo(idx: Int, kind: Int, midi: Float) {
        if (!active) return
        synchronized(this) { events.add(LogEv.Solo(EventHub.nowMs(), idx, kind, midi)) }
    }
}
