package com.glidelive.session

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.glidelive.audio.NativeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Entrada MIDI USB / Bluetooth ya conectada: notas -> voces del solista (con pitch bend y glide suave),
 * canal 10 -> pads de batería (mapa General MIDI). Se procesa en el hilo principal para respetar el
 * único productor de la cola de audio.
 */
class MidiInput(ctx: Context) {
    private val mm = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow("MIDI: sin dispositivos")
    val status: StateFlow<String> = _status
    private val names = LinkedHashSet<String>()

    private val voiceOf = HashMap<Int, Int>()   // (canal*128+nota) -> voz
    private val baseNote = IntArray(8) { -1 }
    private val voiceCh = IntArray(8) { -1 }
    private val bend = FloatArray(16)
    private val used = BooleanArray(8)

    private val padOfNote = HashMap<Int, Int>().apply {
        for ((n, p) in listOf(35 to 0, 36 to 0, 38 to 1, 40 to 1, 37 to 2, 42 to 3, 46 to 4, 44 to 5, 50 to 6, 48 to 6,
            47 to 7, 45 to 7, 43 to 8, 41 to 8, 49 to 9, 57 to 9, 51 to 10, 59 to 10, 53 to 11, 55 to 12, 52 to 12)) put(n, p)
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            names.remove(nameOf(device)); refresh()
        }
    }

    private fun nameOf(d: MidiDeviceInfo) =
        d.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "dispositivo MIDI"

    fun start() {
        val m = mm ?: return
        m.devices.forEach(::open)
        m.registerDeviceCallback(callback, main)
    }

    fun stop() { mm?.unregisterDeviceCallback(callback) }

    private fun refresh() {
        _status.value = if (names.isEmpty()) "MIDI: sin dispositivos" else "MIDI: " + names.joinToString(", ")
    }

    private fun open(info: MidiDeviceInfo) {
        if (info.outputPortCount == 0) return
        mm?.openDevice(info, { dev ->
            val port = dev?.openOutputPort(0) ?: return@openDevice
            port.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    val copy = msg.copyOfRange(offset, offset + count)
                    main.post { parse(copy) }
                }
            })
            names.add(nameOf(info)); refresh()
        }, main)
    }

    private fun parse(m: ByteArray) {
        var i = 0
        while (i < m.size) {
            val st = m[i].toInt() and 0xFF
            if (st < 0x80) { i++; continue }
            val cmd = st and 0xF0
            val ch = st and 0x0F
            val len = if (cmd == 0xC0 || cmd == 0xD0) 2 else 3
            if (i + len > m.size) return
            val d1 = m[i + 1].toInt() and 0x7F
            val d2 = if (len > 2) m[i + 2].toInt() and 0x7F else 0
            when (cmd) {
                0x90 -> if (d2 > 0) noteOn(ch, d1, d2) else noteOff(ch, d1)
                0x80 -> noteOff(ch, d1)
                0xE0 -> pitchBend(ch, ((d2 shl 7) or d1) - 8192)
            }
            i += len
        }
    }

    private fun noteOn(ch: Int, note: Int, vel: Int) {
        if (ch == 9) {
            padOfNote[note]?.let { NativeEngine.trigger(it, (vel / 127f).coerceAtLeast(0.05f), SystemClock.uptimeMillis()) }
            return
        }
        val v = (0 until 8).firstOrNull { !used[it] } ?: return
        used[v] = true; baseNote[v] = note; voiceCh[v] = ch; voiceOf[ch * 128 + note] = v
        val pitch = note + bend[ch]
        NativeEngine.soloSet(v, pitch, 1f, 0f, 0.4f + 0.6f * vel / 127f)
        NativeEngine.soloOn(v, pitch)
    }

    private fun noteOff(ch: Int, note: Int) {
        if (ch == 9) return
        val v = voiceOf.remove(ch * 128 + note) ?: return
        used[v] = false; baseNote[v] = -1
        NativeEngine.soloOff(v)
    }

    private fun pitchBend(ch: Int, value: Int) {
        bend[ch] = value / 8192f * 2f  // ±2 semitonos
        for (v in 0 until 8) if (used[v] && voiceCh[v] == ch) NativeEngine.soloSet(v, baseNote[v] + bend[ch], 1f, 0f, 1f)
    }
}
