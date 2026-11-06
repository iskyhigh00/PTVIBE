package com.glidelive.audio

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
) {
    val burstMs: Double get() = if (sampleRate > 0) framesPerBurst * 1000.0 / sampleRate else 0.0
    val totalMs: Double get() = if (outputLatencyMs >= 0) avgTouchMs + outputLatencyMs else -1.0
}

object NativeEngine {
    init {
        System.loadLibrary("glidelive")
    }

    private val buf = DoubleArray(13)

    fun start(): Boolean = nativeStart()
    fun stop() = nativeStop()

    /** eventTimeMs: MotionEvent.getEventTime() (reloj monotónico). */
    fun trigger(pad: Int, velocity: Float, eventTimeMs: Long) =
        nativeTrigger(pad, velocity, eventTimeMs * 1_000_000L)

    fun choke(pad: Int, eventTimeMs: Long) = nativeChoke(pad, eventTimeMs * 1_000_000L)
    fun setParam(id: Int, value: Float) = nativeSetParam(id, value)

    const val P_SNARE_TUNE = 0
    const val P_TOM_TUNE = 1
    const val P_ROOM = 2
    const val P_COMP = 3
    const val P_MASTER = 4
    const val P_PAD_VOL = 8

    fun synth(cmd: SynthCommand) = when (cmd) {
        is SynthCommand.NoteOn -> nativeSynthCmd(10, cmd.slot, 0, cmd.midi.toFloat())
        is SynthCommand.GlideTo -> nativeSynthCmd(11, cmd.slot, 0, cmd.midi.toFloat())
        is SynthCommand.Spawn -> nativeSynthCmd(12, cmd.slot, cmd.fromSlot, cmd.midi.toFloat())
        is SynthCommand.GlideRelease -> nativeSynthCmd(13, cmd.slot, 0, cmd.midi.toFloat())
        is SynthCommand.Release -> nativeSynthCmd(14, cmd.slot, 0, 0f)
        SynthCommand.ReleaseAll -> nativeSynthCmd(15, 0, 0, 0f)
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
        )
    }

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeTrigger(pad: Int, velocity: Float, eventNs: Long)
    private external fun nativeChoke(pad: Int, eventNs: Long)
    private external fun nativeSetParam(id: Int, value: Float)
    private external fun nativeSynthCmd(type: Int, a: Int, b: Int, f: Float)
    private external fun nativeGetLevel(): Float
    private external fun nativeSetBursts(bursts: Int)
    private external fun nativeAutoTune()
    private external fun nativeResetStats()
    private external fun nativeGetStats(out: DoubleArray)
}
