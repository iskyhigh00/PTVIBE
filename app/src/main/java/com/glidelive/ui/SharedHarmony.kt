package com.glidelive.ui

import com.glidelive.theory.HarmonyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tonalidad, escala y acorde que suena: lo comparten el modo Acordes y el Solista. */
object SharedHarmony {
    private val _state = MutableStateFlow(HarmonyState())
    val state: StateFlow<HarmonyState> = _state

    fun update(f: (HarmonyState) -> HarmonyState) { _state.value = f(_state.value) }
}
