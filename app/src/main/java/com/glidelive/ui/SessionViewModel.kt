package com.glidelive.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glidelive.audio.NativeEngine
import com.glidelive.session.MidiInput
import com.glidelive.session.RecordingResult
import com.glidelive.session.SceneManager
import com.glidelive.session.SessionRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class SessionUi(val recording: Boolean = false, val seconds: Int = 0, val last: RecordingResult? = null)

class SessionViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui
    private val recorder = SessionRecorder(app)
    val midi = MidiInput(app)
    var scenes: SceneManager? = null
        private set

    fun attach(chords: ChordsViewModel, solo: SoloViewModel, engine: EngineViewModel, transport: TransportViewModel) {
        if (scenes == null) scenes = SceneManager(app, chords, solo, engine, transport)
        midi.start()
    }

    fun toggleRecord(bpm: Int, sampleRate: Int) {
        if (recorder.isRecording) {
            viewModelScope.launch {
                val r = recorder.stop(bpm)
                _ui.value = SessionUi(false, 0, r)
            }
        } else {
            recorder.start(viewModelScope, if (sampleRate > 0) sampleRate else 48000)
            _ui.value = SessionUi(true, 0, null)
            viewModelScope.launch {
                while (isActive && recorder.isRecording) {
                    _ui.value = _ui.value.copy(seconds = recorder.seconds)
                    delay(500)
                }
            }
        }
    }

    /** La app pasa a segundo plano: se cierra la grabación para que los archivos queden completos. */
    fun stopIfRecording(bpm: Int) {
        if (recorder.isRecording) toggleRecord(bpm, 0)
    }

    fun share(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(send, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCleared() { midi.stop() }
}

@Suppress("unused") private val keepNative = NativeEngine
