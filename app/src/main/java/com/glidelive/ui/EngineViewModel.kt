package com.glidelive.ui

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glidelive.audio.LatencyStats
import com.glidelive.audio.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class EngineViewModel(app: Application) : AndroidViewModel(app) {
    private val _stats = MutableStateFlow(LatencyStats())
    val stats: StateFlow<LatencyStats> = _stats

    private val _auto = MutableStateFlow(true)
    val auto: StateFlow<Boolean> = _auto

    private val _bluetooth = MutableStateFlow(false)
    val bluetooth: StateFlow<Boolean> = _bluetooth

    init {
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
