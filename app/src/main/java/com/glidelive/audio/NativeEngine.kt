package com.glidelive.audio

import com.glidelive.session.SessionLog

data class LatencyStats(
    val sampleRate: Int = 0,
    val framesPerBurst: Int = 0,
    val bufferFrames: Int = 0,
    val bursts: Int = 1,
    val mmap: Boolean = false,
    val exclusive: Boolean = false,
    val outputLatencyMs: Double = -1.0,
    val lastTouchMs: Double = 0.0,
    val avgTouchMs: Double = 0.0,
    val maxTouchMs: Double = 0.0,
    val xruns: Int = 0,
    val running: Boolean = false,
    val aaudio: Boolean = false,
    val dropped: Int = 0,
    val recDropped: Int = 0,
) {
    val burstMs: Double get() = if (sampleRate > 0) framesPerBurst * 1000.0 / sampleRate else 0.0
    val totalMs: Double get() = if (outputLatencyMs >= 0) avgTouchMs + outputLatencyMs else -1.0
}

object NativeEngine {
    init {
        System.loadLibrary("glidelive")
    }

    private val buf = DoubleArray(15)

    fun start(): Boolean = nativeStart()
    fun stop() = nativeStop()

    /** eventTimeMs: MotionEvent.getEventTime() (reloj monotónico). */
    private val pushLock = Any()

    fun trigger(pad: Int, velocity: Float, eventTimeMs: Long) =
        synchronized(pushLock) { nativeTrigger(pad, velocity, eventTimeMs * 1_000_000L) }

    /** Igual que [trigger] pero con el instante exacto en ns (reloj monotónico), p. ej. el de un mensaje MIDI. */
    fun triggerNs(pad: Int, velocity: Float, eventNs: Long) =
        synchronized(pushLock) { nativeTrigger(pad, velocity, eventNs) }

    fun choke(pad: Int, eventTimeMs: Long) = synchronized(pushLock) { nativeChoke(pad, eventTimeMs * 1_000_000L) }
    fun setParam(id: Int, value: Float) = nativeSetParam(id, value)

    const val P_SNARE_TUNE = 0
    const val P_TOM_TUNE = 1
    const val P_ROOM = 2
    const val P_COMP = 3
    const val P_MASTER = 4
    const val P_PAD_VOL = 8

    fun synth(cmd: SynthCommand) = when (cmd) {
        is SynthCommand.NoteOn -> seq(10, cmd.slot, 0, cmd.midi.toFloat())
        is SynthCommand.GlideTo -> seq(11, cmd.slot, 0, cmd.midi.toFloat())
        is SynthCommand.Spawn -> seq(12, cmd.slot, cmd.fromSlot, cmd.midi.toFloat())
        is SynthCommand.GlideRelease -> seq(13, cmd.slot, cmd.glideMs, cmd.midi.toFloat())
        is SynthCommand.Release -> seq(14, cmd.slot, cmd.releaseMs, 0f)
        SynthCommand.ReleaseAll -> seq(15, 0, 0, 0f)
    }

    const val P_SOLO_INSTRUMENT = 31
    const val P_SOLO_GLIDE = 32

    // Solista: idx 0..7 (un dedo = una voz).
    fun soloOn(idx: Int, midi: Float) { SessionLog.solo(idx, 0, midi); seq(16, idx, 0, midi) }
    fun soloOff(idx: Int) { SessionLog.solo(idx, 2, 0f); seq(17, idx, 0, 0f) }
    fun soloSet(idx: Int, pitch: Float, filter: Float, vib: Float, vol: Float) {
        SessionLog.solo(idx, 1, pitch)
        nativeSoloSet(idx, pitch, filter, vib, vol)
    }

    const val P_CHORD_VOL = 21
    const val P_SOLO_VOL = 22
    const val P_DRUM_VOL = 23
    const val P_SYNTH_ROOM = 24
    const val P_CHORUS = 25
    const val P_CUTOFF = 26
    const val P_RESONANCE = 27
    const val P_INSTRUMENT = 28
    const val P_GLIDE_MS = 29
    const val P_GLIDE_CURVE = 30

    // --- reloj / secuenciador (órdenes 20..33 al hilo de audio) ---
    const val CMD_PLAY = 20
    const val CMD_STOP = 21
    const val CMD_REC_DRUM = 22
    const val CMD_REC_CHORD = 23
    const val CMD_DRUM_LEN = 24
    const val CMD_CHORD_LEN = 25
    const val CMD_DRUM_CLEAR = 26
    const val CMD_CHORD_CLEAR = 27
    const val CMD_DRUM_UNDO = 28
    const val CMD_CHORD_UNDO = 29
    const val CMD_DRUM_PLAY = 30
    const val CMD_CHORD_PLAY = 31
    const val CMD_PATTERN_PLAY = 32
    const val CMD_PATTERN_END = 33
    const val CMD_PATTERN_QUEUE = 34

    const val P_METRO_VOL = 33
    const val P_BPM = 34
    const val P_BEATS = 35
    const val P_BEAT_UNIT = 36
    const val P_SWING = 37
    const val P_METRO = 38
    const val P_REC_QUANT = 39
    const val P_CHORD_QUANT = 40
    const val P_MUTE_MASK = 41
    const val P_BASS_VOL = 42
    const val P_BASS_TONE = 43
    const val P_PAD_VOL2 = 44

    /** Parámetro de volumen del pad (los de percusión, 13..20, viven en otro bloque). */
    fun padVolParam(pad: Int) = if (pad < 13) P_PAD_VOL + pad else P_PAD_VOL2 + pad - 13

    fun seq(cmd: Int, a: Int = 0, b: Int = 0, f: Float = 0f) = synchronized(pushLock) { nativeSynthCmd(cmd, a, b, f) }

    // El secuenciador nativo admite un único productor lógico: se serializan las llamadas de patrón.
    private val patLock = Any()

    fun setPattern(ticks: IntArray, pads: IntArray, vels: FloatArray, length: Int, restart: Boolean, oneShot: Boolean) =
        synchronized(patLock) { nativeSetPattern(ticks, pads, vels, ticks.size, length, restart, oneShot) }

    fun setEndPattern(ticks: IntArray, pads: IntArray, vels: FloatArray, length: Int) =
        synchronized(patLock) { nativeSetEndPattern(ticks, pads, vels, ticks.size, length) }

    /**
     * Encola una variación: se activa exactamente en el siguiente límite de compás del patrón actual
     * ([barTicks] = barSteps * unit del patrón). Si no hay base sonando se aplica de inmediato.
     */
    fun queuePattern(ticks: IntArray, pads: IntArray, vels: FloatArray, length: Int, barTicks: Int) =
        synchronized(patLock) {
            nativeSetQueuedPattern(ticks, pads, vels, ticks.size, length)
            seq(CMD_PATTERN_QUEUE, barTicks)
        }

    /** Cancela la variación en cola, si la hay. */
    fun cancelQueuedPattern() = seq(CMD_PATTERN_QUEUE, 0)

    /**
     * posTick, playing, vueltas del patrón, grabando batería, grabando acordes, eventos batería, eventos acordes,
     * posición local del patrón, patrón activo, acorde diferido en espera (0/1), contador de cambios de variación aplicados.
     * Array nuevo por llamada.
     */
    fun seqStatus(): DoubleArray = DoubleArray(11).also { nativeSeqStatus(it) }

    /** Eventos de batería/acordes ejecutados (6 doubles cada uno: tipo, a, f, glideMs, curva, tiempoMs). */
    fun pollViz(out: DoubleArray): Int = nativePollViz(out, out.size / 6)

    fun setRecording(on: Boolean) = nativeSetRecording(on)
    fun readRecording(dst: FloatArray): Int = nativeReadRecording(dst)

    fun kitBegin() = nativeKitBegin()
    fun kitSet(pad: Int, layer: Int, variant: Int, data: FloatArray, channels: Int, rate: Int) =
        nativeKitSet(pad, layer, variant, data, channels, rate)
    fun kitCommit(slot: Int) = nativeKitCommit(slot)
    fun kitSelect(slot: Int): Boolean = nativeKitSelect(slot)
    fun kitUseSampled(on: Boolean) = nativeKitUseSampled(on)

    fun level(): Float = nativeGetLevel()

    fun setBursts(n: Int) = nativeSetBursts(n)
    fun autoTune() = nativeAutoTune()
    fun resetStats() = nativeResetStats()

    fun readStats(): LatencyStats {
        nativeGetStats(buf)
        return LatencyStats(
            sampleRate = buf[0].toInt(),
            framesPerBurst = buf[1].toInt(),
            bufferFrames = buf[2].toInt(),
            bursts = buf[3].toInt(),
            mmap = buf[4] > 0,
            exclusive = buf[5] > 0,
            outputLatencyMs = buf[6],
            lastTouchMs = buf[7],
            avgTouchMs = buf[8],
            maxTouchMs = buf[9],
            xruns = buf[10].toInt(),
            running = buf[11] > 0,
            aaudio = buf[12] > 0,
            dropped = buf[13].toInt(),
            recDropped = buf[14].toInt(),
        )
    }

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeTrigger(pad: Int, velocity: Float, eventNs: Long)
    private external fun nativeChoke(pad: Int, eventNs: Long)
    private external fun nativeSetParam(id: Int, value: Float)
    private external fun nativeSynthCmd(type: Int, a: Int, b: Int, f: Float)
    private external fun nativeSetPattern(ticks: IntArray, pads: IntArray, vels: FloatArray, n: Int, length: Int, restart: Boolean, oneShot: Boolean)
    private external fun nativeSetEndPattern(ticks: IntArray, pads: IntArray, vels: FloatArray, n: Int, length: Int)
    private external fun nativeSetQueuedPattern(ticks: IntArray, pads: IntArray, vels: FloatArray, n: Int, length: Int)
    private external fun nativeSeqStatus(out: DoubleArray)
    private external fun nativePollViz(out: DoubleArray, maxEvents: Int): Int
    private external fun nativeSetRecording(on: Boolean)
    private external fun nativeReadRecording(dst: FloatArray): Int
    private external fun nativeKitBegin()
    private external fun nativeKitSet(pad: Int, layer: Int, variant: Int, data: FloatArray, channels: Int, rate: Int)
    private external fun nativeKitCommit(slot: Int)
    private external fun nativeKitSelect(slot: Int): Boolean
    private external fun nativeKitUseSampled(on: Boolean)
    private external fun nativeGetLevel(): Float
    private external fun nativeSoloSet(idx: Int, pitch: Float, filter: Float, vib: Float, vol: Float)
    private external fun nativeSetBursts(bursts: Int)
    private external fun nativeAutoTune()
    private external fun nativeResetStats()
    private external fun nativeGetStats(out: DoubleArray)
}
