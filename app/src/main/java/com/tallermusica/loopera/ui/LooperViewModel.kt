package com.tallermusica.loopera.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tallermusica.loopera.audio.LoopEngine
import com.tallermusica.loopera.core.BeatPosition
import com.tallermusica.loopera.core.LoopMath
import com.tallermusica.loopera.core.TapTempo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LayerUi(
    val id: Long,
    val number: Int,
    val colorIndex: Int,
    val enabled: Boolean,
    /** 0..1,5 */
    val volume: Float,
    val peaks: FloatArray,
)

data class LooperUiState(
    val playing: Boolean = false,
    val bpm: Int = 90,
    val bars: Int = 2,
    val micActive: Boolean = false,
    val micPermissionDenied: Boolean = false,
    val recording: Boolean = false,
    val processing: Boolean = false,
    val layers: List<LayerUi> = emptyList(),
    val syncAdjustMs: Int = 0,
    val outputLatencyMs: Int = 0,
    val inputLatencyMs: Int = 0,
    val usesTimestamps: Boolean = false,
    val inputSource: String = "",
) {
    /** Con capas grabadas, tempo y largo quedan fijos. */
    val tempoLocked: Boolean get() = layers.isNotEmpty()
}

/** Lo que cambia en cada cuadro de la pantalla. Se lee directo del motor, no pasa por el StateFlow. */
data class ClockSnapshot(
    val beat: BeatPosition?,
    val bars: Int,
    val inputLevel: Float,
)

class LooperViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = LoopEngine(app)
    private val tapTempo = TapTempo()

    private val _state = MutableStateFlow(
        LooperUiState(bpm = engine.bpm, bars = engine.bars),
    )
    val state: StateFlow<LooperUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var captureToken: LoopEngine.CaptureToken? = null
    private var nextLayerId = 1L
    private var nextLayerNumber = 1

    fun clock(): ClockSnapshot = ClockSnapshot(
        beat = engine.currentBeat(),
        bars = engine.bars,
        inputLevel = engine.inputLevel,
    )

    // ------------------------------------------------------------ Ciclo de vida

    fun onForeground() {
        engine.startOutput()
        // Si volvió de los ajustes con el permiso concedido, sacar el aviso.
        if (_state.value.micPermissionDenied && hasMicPermission()) {
            _state.update { it.copy(micPermissionDenied = false) }
        }
        refreshLatency()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** La app pasó a segundo plano: se detiene el pulso y se libera el micrófono. */
    fun onBackground() {
        captureToken = null
        engine.stopTransport()
        engine.stopMic()
        engine.stopOutput()
        _state.update { it.copy(playing = false, recording = false, micActive = false) }
    }

    override fun onCleared() {
        engine.release()
    }

    private fun refreshLatency() {
        val sr = engine.sampleRate
        _state.update {
            it.copy(
                outputLatencyMs = engine.outputLatencyFrames * 1000 / sr,
                inputLatencyMs = engine.inputLatencyFrames * 1000 / sr,
                usesTimestamps = engine.outputUsesTimestamp,
                inputSource = engine.inputSourceName,
            )
        }
    }

    // ------------------------------------------------------------ Pulso

    fun togglePlay() {
        if (engine.isPlaying) {
            if (captureToken != null) onRecordRelease()
            engine.stopTransport()
        } else {
            engine.startTransport()
        }
        _state.update { it.copy(playing = engine.isPlaying) }
        refreshLatency()
    }

    fun setBpm(bpm: Int) {
        if (_state.value.tempoLocked) return
        val b = bpm.coerceIn(LoopMath.MIN_BPM, LoopMath.MAX_BPM)
        engine.setTempo(b, engine.bars)
        _state.update { it.copy(bpm = b) }
    }

    fun changeBpm(delta: Int) = setBpm(_state.value.bpm + delta)

    fun tapTempo() {
        if (_state.value.tempoLocked) return
        tapTempo.tap(SystemClock.elapsedRealtime())?.let { setBpm(it) }
    }

    fun setBars(bars: Int) {
        if (_state.value.tempoLocked) return
        val n = bars.coerceIn(LoopMath.MIN_BARS, LoopMath.MAX_BARS)
        engine.setTempo(engine.bpm, n)
        _state.update { it.copy(bars = n) }
    }

    // ------------------------------------------------------------ Micrófono

    /** Se llama con el permiso ya concedido. */
    fun enableMic() {
        val ok = engine.startMic()
        _state.update { it.copy(micActive = ok, micPermissionDenied = false) }
        refreshLatency()
        if (!ok) send("No se pudo abrir el micrófono.")
    }

    fun disableMic() {
        if (captureToken != null) onRecordRelease()
        engine.stopMic()
        _state.update { it.copy(micActive = false) }
    }

    fun onMicPermissionDenied() {
        _state.update { it.copy(micPermissionDenied = true, micActive = false) }
    }

    fun setSyncAdjust(ms: Int) {
        _state.update { it.copy(syncAdjustMs = ms.coerceIn(0, 300)) }
    }

    // ------------------------------------------------------------ Grabación

    fun onRecordPress() {
        if (captureToken != null) return
        if (!engine.isMicActive) {
            send("Primero tocá «Activar micrófono».")
            return
        }
        if (_state.value.processing) return
        captureToken = engine.beginCapture()
        _state.update { it.copy(recording = true, playing = true) }
    }

    fun onRecordRelease() {
        val token = captureToken ?: return
        captureToken = null
        _state.update { it.copy(recording = false, processing = true) }
        val adjustFrames = _state.value.syncAdjustMs * engine.sampleRate / 1000
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                engine.finishCapture(token, adjustFrames)
            }
            when (result) {
                is LoopEngine.CaptureResult.Layer -> addLayer(result.samples)
                LoopEngine.CaptureResult.TooShort ->
                    send("La grabación fue muy corta: mantené apretado GRABAR mientras tocás.")
                LoopEngine.CaptureResult.Silent ->
                    send("No se escuchó nada: la grabación quedó en silencio y no se sumó.")
                LoopEngine.CaptureResult.Failed ->
                    send("No se pudo recuperar lo grabado. Probá de nuevo.")
            }
            _state.update { it.copy(processing = false) }
            refreshLatency()
        }
    }

    private suspend fun addLayer(samples: FloatArray) {
        val peaks = withContext(Dispatchers.Default) { LoopMath.waveformPeaks(samples, WAVEFORM_BINS) }
        val number = nextLayerNumber++
        val layer = LayerUi(
            id = nextLayerId++,
            number = number,
            colorIndex = number - 1,
            enabled = true,
            volume = 1f,
            peaks = peaks,
        )
        engine.addLayer(layer.id, samples, layer.volume)
        _state.update { it.copy(layers = it.layers + layer) }
    }

    // ------------------------------------------------------------ Capas

    fun setLayerEnabled(id: Long, enabled: Boolean) = updateLayer(id) {
        LayerUi(it.id, it.number, it.colorIndex, enabled, it.volume, it.peaks)
    }

    fun setLayerVolume(id: Long, volume: Float) = updateLayer(id) {
        LayerUi(it.id, it.number, it.colorIndex, it.enabled, volume.coerceIn(0f, 1.5f), it.peaks)
    }

    private fun updateLayer(id: Long, change: (LayerUi) -> LayerUi) {
        var updated: LayerUi? = null
        _state.update { s ->
            s.copy(layers = s.layers.map { if (it.id == id) change(it).also { n -> updated = n } else it })
        }
        updated?.let { engine.setLayerGain(it.id, if (it.enabled) it.volume else 0f) }
    }

    fun deleteLayer(id: Long) {
        engine.removeLayer(id)
        _state.update { s -> s.copy(layers = s.layers.filter { it.id != id }) }
        if (_state.value.layers.isEmpty()) nextLayerNumber = 1
    }

    fun deleteAllLayers() {
        engine.clearLayers()
        nextLayerNumber = 1
        _state.update { it.copy(layers = emptyList()) }
    }

    private fun send(message: String) {
        _messages.trySend(message)
    }

    companion object {
        const val WAVEFORM_BINS = 600
    }
}
