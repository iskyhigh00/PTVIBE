package com.glidelive.session

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import com.glidelive.audio.NativeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Entrada MIDI USB / Bluetooth ya conectada: notas -> voces del solista (con pitch bend y glide suave),
 * canal 10 -> pads de batería (mapa General MIDI). Los mensajes se procesan directamente en el hilo del
 * receptor MIDI (sin saltar al hilo principal); el estado compartido va protegido y las llamadas al motor
 * pasan por los cerrojos de NativeEngine, que mantienen un único productor lógico.
 */
class MidiInput(ctx: Context) {
    private val mm = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow("MIDI: sin dispositivos")
    val status: StateFlow<String> = _status
    private val names = LinkedHashSet<String>()

    private class Open(val device: MidiDevice, val port: MidiOutputPort, val name: String)

    private val opened = HashMap<Int, Open>()      // id de dispositivo -> abiertos (solo hilo principal)
    private val opening = HashSet<Int>()
    private var started = false

    private val lock = Any()
    private val voiceOf = HashMap<Int, Int>()   // (canal*128+nota) -> voz
    private val baseNote = IntArray(8) { -1 }
    private val voiceCh = IntArray(8) { -1 }
    private val bend = FloatArray(16)
    private val used = BooleanArray(8)

    private val padOfNote = HashMap<Int, Int>().apply {
        for ((n, p) in listOf(35 to 0, 36 to 0, 38 to 1, 40 to 1, 37 to 2, 42 to 3, 46 to 4, 44 to 5, 50 to 6, 48 to 6,
            47 to 7, 45 to 7, 43 to 8, 41 to 8, 49 to 9, 57 to 9, 51 to 10, 59 to 10, 53 to 11, 55 to 12, 52 to 12)) put(n, p)
    }

    private val sink = object : MidiParser.Sink {
        override fun noteOn(ch: Int, note: Int, vel: Int, timestamp: Long) = this@MidiInput.noteOn(ch, note, vel, timestamp)
        override fun noteOff(ch: Int, note: Int, timestamp: Long) = this@MidiInput.noteOff(ch, note)
        override fun pitchBend(ch: Int, value: Int, timestamp: Long) = this@MidiInput.pitchBend(ch, value)
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) = close(device.id)
    }

    private fun nameOf(d: MidiDeviceInfo) =
        d.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "dispositivo MIDI"

    fun start() {
        val m = mm ?: return
        if (started) return
        started = true
        m.devices.forEach(::open)
        m.registerDeviceCallback(callback, main)
    }

    fun stop() {
        if (!started) return
        started = false
        mm?.unregisterDeviceCallback(callback)
        opened.keys.toList().forEach(::close)
        opening.clear()
        releaseAllVoices()
    }

    private fun refresh() {
        _status.value = if (names.isEmpty()) "MIDI: sin dispositivos" else "MIDI: " + names.joinToString(", ")
    }

    private fun open(info: MidiDeviceInfo) {
        if (info.outputPortCount == 0 || info.id in opened || !opening.add(info.id)) return
        mm?.openDevice(info, { dev ->
            opening.remove(info.id)
            if (dev == null) return@openDevice
            val port = dev.openOutputPort(0)
            if (port == null || !started) {
                runCatching { dev.close() }
                return@openDevice
            }
            val parser = MidiParser(sink)
            port.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    parser.feed(msg, offset, count, timestamp)
                }
            })
            opened[info.id] = Open(dev, port, nameOf(info))
            names.add(nameOf(info)); refresh()
        }, main)
    }

    private fun close(id: Int) {
        opening.remove(id)
        val o = opened.remove(id) ?: return
        runCatching { o.port.close() }
        runCatching { o.device.close() }
        names.remove(o.name); refresh()
        releaseAllVoices()
    }

    private fun releaseAllVoices() = synchronized(lock) {
        for (v in 0 until 8) if (used[v]) { NativeEngine.soloOff(v); used[v] = false; baseNote[v] = -1 }
        voiceOf.clear()
    }

    private fun noteOn(ch: Int, note: Int, vel: Int, ts: Long) {
        if (vel > 0 && com.glidelive.ui.PadBindings.learnMidi(note)) return  // asignando una tecla a un pad: no suena
        if (ch != 9) {
            // Teclas asignadas a pads (cualquier pad, también combos); lo que no está asignado sigue tocando el solista.
            val bound = com.glidelive.ui.PadBindings.padForMidi(note)
            if (bound != null) {
                val ns = if (ts > 0 && ts <= System.nanoTime()) ts else 0L
                val v = (vel / 127f).coerceAtLeast(0.05f)
                for (m in com.glidelive.ui.Pads.members(bound)) NativeEngine.triggerNs(m, v, ns)
                return
            }
        }
        if (ch == 9) {
            val pad = padOfNote[note] ?: return
            // Instante del mensaje (base System.nanoTime = reloj monotónico del motor); 0 = ahora.
            val ns = if (ts > 0 && ts <= System.nanoTime()) ts else 0L
            NativeEngine.triggerNs(pad, (vel / 127f).coerceAtLeast(0.05f), ns)
            return
        }
        synchronized(lock) {
            var v = 0
            while (v < 8 && used[v]) v++
            if (v >= 8) return
            used[v] = true; baseNote[v] = note; voiceCh[v] = ch; voiceOf[ch * 128 + note] = v
            val pitch = note + bend[ch]
            NativeEngine.soloSet(v, pitch, 1f, 0f, 0.4f + 0.6f * vel / 127f)
            NativeEngine.soloOn(v, pitch)
        }
    }

    private fun noteOff(ch: Int, note: Int) {
        if (ch == 9) return
        synchronized(lock) {
            val v = voiceOf.remove(ch * 128 + note) ?: return
            used[v] = false; baseNote[v] = -1
            NativeEngine.soloOff(v)
        }
    }

    private fun pitchBend(ch: Int, value: Int) {
        synchronized(lock) {
            bend[ch] = value / 8192f * 2f  // ±2 semitonos
            for (v in 0 until 8) if (used[v] && voiceCh[v] == ch) NativeEngine.soloSet(v, baseNote[v] + bend[ch], 1f, 0f, 1f)
        }
    }
}
