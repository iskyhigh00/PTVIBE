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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Captura de lo que suena para guardarlo como sonido de Princess. */
data class CaptureUi(val recording: Boolean = false, val seconds: Int = 0, val pending: File? = null)

data class SessionUi(val recording: Boolean = false, val seconds: Int = 0, val last: RecordingResult? = null)

@OptIn(FlowPreview::class)
class SessionViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui
    private val recorder = SessionRecorder(app)
    val midi = MidiInput(app)
    private val _capture = MutableStateFlow(CaptureUi())
    val capture: StateFlow<CaptureUi> = _capture
    private var capturing = false
    var scenes: SceneManager? = null
        private set

    fun attach(chords: ChordsViewModel, solo: SoloViewModel, engine: EngineViewModel, transport: TransportViewModel) {
        if (scenes == null) {
            val mgr = SceneManager(app, chords, solo, engine, transport)
            scenes = mgr
            mgr.restoreLast()   // vuelve todo como lo dejaste (también tras una actualización)
            viewModelScope.launch {
                combine(chords.ui, solo.ui, engine.kit, transport.ui) { _, _, _, _ -> Unit }
                    .debounce(800)
                    .collect { mgr.saveLast() }
            }
        }
        midi.start()
    }

    /** Guarda ya mismo (p. ej. al pasar la app a segundo plano). */
    fun flush() { scenes?.saveLast() }

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

    /** Empieza o termina de grabar lo que suena en cualquier pestaña, para guardarlo como sonido de Princess. */
    fun toggleCapture(bpm: Int, sampleRate: Int) {
        if (capturing) {
            viewModelScope.launch {
                val r = recorder.stop(bpm)
                capturing = false
                r?.midi?.delete()
                _capture.value = CaptureUi(false, 0, r?.wav)
            }
        } else {
            if (recorder.isRecording) return  // ya se está grabando la sesión
            capturing = true
            recorder.start(viewModelScope, if (sampleRate > 0) sampleRate else 48000)
            _capture.value = CaptureUi(true, 0, null)
            viewModelScope.launch {
                while (isActive && capturing) { _capture.value = _capture.value.copy(seconds = recorder.seconds); delay(500) }
            }
        }
    }

    /** Tira la captura pendiente. */
    fun discardCapture() { _capture.value.pending?.delete(); _capture.value = CaptureUi() }
    fun captureHandled() { _capture.value = CaptureUi() }

    /** La app pasa a segundo plano: se cierra la grabación para que los archivos queden completos. */
    fun stopIfRecording(bpm: Int) {
        if (capturing) toggleCapture(bpm, 0) else if (recorder.isRecording) toggleRecord(bpm, 0)
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
