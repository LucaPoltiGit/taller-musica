package com.tallermusica.loopera.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.tallermusica.loopera.audio.LoopEngine
import com.tallermusica.loopera.core.BeatPosition
import com.tallermusica.loopera.core.LoopMath
import com.tallermusica.loopera.core.TapTempo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

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


    fun clock(): ClockSnapshot = ClockSnapshot(
        beat = engine.currentBeat(),
        bars = engine.bars,
        inputLevel = 0f,
    )

    // ------------------------------------------------------------ Ciclo de vida

    fun onForeground() {
        engine.startOutput()
        refreshLatency()
    }


    /** La app pasó a segundo plano: se detiene el pulso y se libera el micrófono. */
    fun onBackground() {
        engine.stopTransport()
        engine.stopOutput()
        _state.update { it.copy(playing = false) }
    }

    override fun onCleared() {
        engine.release()
    }

    private fun refreshLatency() {
        val sr = engine.sampleRate
        _state.update {
            it.copy(
                outputLatencyMs = engine.outputLatencyFrames * 1000 / sr,
                usesTimestamps = engine.outputUsesTimestamp,
            )
        }
    }

    // ------------------------------------------------------------ Pulso

    fun togglePlay() {
        if (engine.isPlaying) {
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

    private fun send(message: String) {
        _messages.trySend(message)
    }
}
