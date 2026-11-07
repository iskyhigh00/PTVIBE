package com.glidelive.ui

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glidelive.audio.KitLoader
import com.glidelive.audio.LatencyStats
import com.glidelive.audio.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class KitSettings(
    val snareTune: Float = 0f,   // semitonos
    val tomTune: Float = 0f,     // semitonos
    val room: Float = 0.25f,
    val comp: Float = 0.3f,
    val master: Float = 0.9f,
    val drumVol: Float = 1f,
    val pressure: Boolean = false,
    val padLayout: PadLayout = PadLayout.KIT,
    val padVolume: List<Float> = List(Pads.COUNT) { 1f },
)

class EngineViewModel(app: Application) : AndroidViewModel(app) {
    private val _stats = MutableStateFlow(LatencyStats())
    val stats: StateFlow<LatencyStats> = _stats

    private val _auto = MutableStateFlow(true)
    val auto: StateFlow<Boolean> = _auto

    private val _bluetooth = MutableStateFlow(false)
    val bluetooth: StateFlow<Boolean> = _bluetooth

    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _kit = MutableStateFlow(
        KitSettings(padLayout = PadLayout.entries.firstOrNull { it.name == prefs.getString("pad_layout", null) } ?: PadLayout.KIT),
    )
    val kit: StateFlow<KitSettings> = _kit

    /** Nivel de salida 0..1 para los visualizadores (~30 Hz). */
    val level = MutableStateFlow(0f)

    init {
        viewModelScope.launch { KitLoader.load(app) }
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                level.value = NativeEngine.level()
                delay(33)
            }
        }
        pushKit(_kit.value)
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (_auto.value) NativeEngine.autoTune()
                _stats.value = NativeEngine.readStats()
                _bluetooth.value = bluetoothOutputActive()
                delay(250)
            }
        }
    }

    fun setAuto(on: Boolean) { _auto.value = on }
    fun setBursts(n: Int) { _auto.value = false; NativeEngine.setBursts(n) }
    fun reset() = NativeEngine.resetStats()

    fun editKit(f: (KitSettings) -> KitSettings) {
        val before = _kit.value.padLayout
        _kit.update(f)
        if (_kit.value.padLayout != before) prefs.edit().putString("pad_layout", _kit.value.padLayout.name).apply()
        pushKit(_kit.value)
    }

    private fun pushKit(k: KitSettings) {
        NativeEngine.setParam(NativeEngine.P_SNARE_TUNE, k.snareTune)
        NativeEngine.setParam(NativeEngine.P_TOM_TUNE, k.tomTune)
        NativeEngine.setParam(NativeEngine.P_ROOM, k.room)
        NativeEngine.setParam(NativeEngine.P_COMP, k.comp)
        NativeEngine.setParam(NativeEngine.P_MASTER, k.master)
        NativeEngine.setParam(NativeEngine.P_DRUM_VOL, k.drumVol)
        k.padVolume.forEachIndexed { i, v -> NativeEngine.setParam(NativeEngine.P_PAD_VOL + i, v) }
    }

    private fun bluetoothOutputActive(): Boolean {
        val am = getApplication<Application>().getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
    }
}
