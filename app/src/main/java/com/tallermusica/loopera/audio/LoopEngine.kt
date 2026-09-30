package com.tallermusica.loopera.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.tallermusica.loopera.core.BeatPosition
import com.tallermusica.loopera.core.CaptureBuffer
import com.tallermusica.loopera.core.LoopMath
import kotlin.math.abs
import kotlin.math.max

/**
 * Motor de audio de la loopera.
 *
 * El reloj es el contador de frames de salida: el hilo de audio mezcla bloques chicos y los
 * escribe en un AudioTrack que nunca se detiene mientras la app está en primer plano. El frame
 * N del contador es el N-ésimo frame escrito, así que con AudioTrack.getTimestamp se sabe qué
 * frame está sonando en cada instante. La posición del loop es siempre
 * (frame - inicioDelPulso) % largoDelLoop; no hay timers de UI.
 *
 * El micrófono graba de forma continua en un [CaptureBuffer] donde cada bloque queda marcado
 * con el frame de salida que se estaba escuchando cuando se captó.
 */
class LoopEngine(context: Context) {

    val sampleRate = LoopMath.SAMPLE_RATE

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Tamaño de bloque nativo del equipo (PROPERTY_OUTPUT_FRAMES_PER_BUFFER). */
    val framesPerBurst: Int = audioManager
        .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: 256

    private val blockFrames = framesPerBurst.coerceIn(64, 1024)

    // ---------------------------------------------------------------- Pulso

    @Volatile var bpm: Int = 90
        private set
    @Volatile var bars: Int = 2
        private set
    @Volatile var loopFrames: Int = LoopMath.loopLengthFrames(bpm, bars)
        private set

    /** Frame de salida en el que empezó el pulso (tiempo 1 del compás 1). */
    @Volatile private var transportStart: Long = Long.MAX_VALUE
    @Volatile var isPlaying: Boolean = false
        private set

    fun setTempo(bpm: Int, bars: Int) {
        val b = bpm.coerceIn(LoopMath.MIN_BPM, LoopMath.MAX_BPM)
        val n = bars.coerceIn(LoopMath.MIN_BARS, LoopMath.MAX_BARS)
        // Mantener la fase dentro del compás no importa: sin capas no hay nada que desfasar.
        this.bpm = b
        this.bars = n
        loopFrames = LoopMath.loopLengthFrames(b, n)
    }

    /**
     * Arranca el pulso en el compás 1 un par de bloques en el futuro, así el hilo de audio
     * todavía no mezcló ese frame. Devuelve el frame de inicio.
     */
    fun startTransport(): Long {
        if (isPlaying) return transportStart
        val start = renderFrame + 2L * blockFrames
        transportStart = start
        isPlaying = true
        return start
    }

    fun stopTransport() {
        isPlaying = false
    }

    // ---------------------------------------------------------------- Capas

    private class EngineLayer(val id: Long, val samples: FloatArray, gain: Float) {
        @Volatile var targetGain: Float = gain
        /** Solo lo toca el hilo de audio. Arranca en 0 para que la capa entre con fundido. */
        var currentGain: Float = 0f
    }

    private val layersLock = Any()
    @Volatile private var layers: Array<EngineLayer> = emptyArray()

    fun addLayer(id: Long, samples: FloatArray, gain: Float) {
        synchronized(layersLock) { layers = layers + EngineLayer(id, samples, gain) }
    }

    /** Volumen efectivo de la capa (0 si está apagada). El cambio se hace con un fundido corto. */
    fun setLayerGain(id: Long, gain: Float) {
        layers.firstOrNull { it.id == id }?.targetGain = gain
    }

    fun removeLayer(id: Long) {
        // Primero se baja con fundido y después se saca de la mezcla.
        layers.firstOrNull { it.id == id }?.targetGain = 0f
        Thread {
            SystemClock.sleep(FADE_MS * 3L)
            synchronized(layersLock) { layers = layers.filter { it.id != id }.toTypedArray() }
        }.start()
    }

    fun clearLayers() {
        val current = layers
        current.forEach { it.targetGain = 0f }
        val ids = current.map { it.id }.toSet()
        Thread {
            SystemClock.sleep(FADE_MS * 3L)
            synchronized(layersLock) { layers = layers.filter { it.id !in ids }.toTypedArray() }
        }.start()
    }

    // ---------------------------------------------------------------- Salida

    private var track: AudioTrack? = null
    private var renderThread: Thread? = null
    @Volatile private var outputRunning = false

    /** Próximo frame a mezclar. */
    @Volatile private var renderFrame = 0L

    /** Relación conocida entre un frame de salida y el instante en que suena. */
    private class ClockAnchor(val frame: Long, val nanos: Long, val fromTimestamp: Boolean)

    @Volatile private var anchor = ClockAnchor(0, System.nanoTime(), false)

    /** Latencia de salida estimada a partir del tamaño del buffer (se usa si no hay timestamp). */
    @Volatile var outputLatencyFrames: Int = 0
        private set

    /** Latencia de entrada estimada (tamaño del buffer del micrófono). */
    @Volatile var inputLatencyFrames: Int = 0
        private set

    val outputUsesTimestamp: Boolean get() = anchor.fromTimestamp

    @Synchronized
    fun startOutput() {
        if (outputRunning) return
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bytesPerFrame = 2 * 4
        val bufferBytes = max(minBytes, blockFrames * 4 * bytesPerFrame)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear el AudioTrack", e)
            return
        }
        track = t
        renderFrame = 0
        transportStart = Long.MAX_VALUE
        isPlaying = false
        outputLatencyFrames = t.bufferSizeInFrames + framesPerBurst
        anchor = ClockAnchor(-outputLatencyFrames.toLong(), System.nanoTime(), false)
        t.play()
        outputRunning = true
        renderThread = Thread({ renderLoop(t) }, "loopera-salida").also { it.start() }
    }

    @Synchronized
    fun stopOutput() {
        if (!outputRunning) return
        outputRunning = false
        isPlaying = false
        renderThread?.join(500)
        renderThread = null
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        track = null
    }

    private fun renderLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val mono = FloatArray(blockFrames)
        val stereo = FloatArray(blockFrames * 2)
        val ts = AudioTimestamp()
        var lastTimestampQuery = 0L
        while (outputRunning) {
            val start = renderFrame
            mix(start, mono)
            for (i in 0 until blockFrames) {
                stereo[2 * i] = mono[i]
                stereo[2 * i + 1] = mono[i]
            }
            val written = t.write(stereo, 0, stereo.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.e(TAG, "Error al escribir el AudioTrack: $written")
                break
            }
            renderFrame = start + blockFrames
            val now = System.nanoTime()
            if (now - lastTimestampQuery > 100_000_000L) {
                lastTimestampQuery = now
                anchor = if (t.getTimestamp(ts) && ts.framePosition > 0) {
                    ClockAnchor(ts.framePosition, ts.nanoTime, true)
                } else {
                    // Sin timestamp: lo último escrito suena después de lo que hay en el buffer.
                    ClockAnchor(renderFrame - outputLatencyFrames, now, false)
                }
            }
        }
    }

    // Estado del hilo de audio
    private var masterGain = 0f

    private fun mix(start: Long, out: FloatArray) {
        out.fill(0f)
        val playing = isPlaying
        val ts = transportStart
        val lf = loopFrames
        val ls = layers
        val masterTarget = if (playing) 1f else 0f
        if (!playing && masterGain <= 0f) {
            // Silencio, pero igual se acercan las ganancias de las capas a su objetivo.
            for (l in ls) l.currentGain = l.targetGain
            return
        }
        if (ts == Long.MAX_VALUE) return
        val fadeStep = 1f / (sampleRate * FADE_MS / 1000f)
        var pos = if (start >= ts) LoopMath.loopPosition(start - ts, lf) else -1
        for (i in out.indices) {
            val f = start + i
            if (f == ts) pos = 0
            if (masterGain < masterTarget) {
                masterGain = minOf(masterTarget, masterGain + fadeStep)
            } else if (masterGain > masterTarget) {
                masterGain = maxOf(masterTarget, masterGain - fadeStep)
            }
            if (pos < 0) continue
            var sum = 0f
            for (l in ls) {
                val target = l.targetGain
                var g = l.currentGain
                if (g < target) {
                    g = minOf(target, g + fadeStep)
                } else if (g > target) {
                    g = maxOf(target, g - fadeStep)
                }
                l.currentGain = g
                if (g > 0f && pos < l.samples.size) sum += l.samples[pos] * g
            }
            out[i] = LoopMath.softClip(sum * masterGain)
            pos++
            if (pos >= lf) pos = 0
        }
    }

    /** Frame de salida que está sonando en el instante [nanos] (System.nanoTime). */
    fun heardFrameAt(nanos: Long): Long {
        val a = anchor
        val f = a.frame + (nanos - a.nanos) * sampleRate / 1_000_000_000L
        return f.coerceAtMost(renderFrame)
    }

    fun heardFrameNow(): Long = heardFrameAt(System.nanoTime())

    /** Estado del pulso que se está escuchando ahora mismo, o null si está detenido. */
    fun currentBeat(): BeatPosition? {
        if (!isPlaying) return null
        val heard = heardFrameNow()
        val ts = transportStart
        if (ts == Long.MAX_VALUE) return null
        if (heard < ts) {
            // Todavía no llegó a sonar el compás 1: se muestra quieto en el tiempo 1.
            return BeatPosition(0, 0, 0f, 0f, 0)
        }
        return LoopMath.beatPosition(heard - ts, bpm, bars, sampleRate)
    }

    // ---------------------------------------------------------------- Micrófono

    private var record: AudioRecord? = null
    private var inputThread: Thread? = null
    @Volatile private var inputRunning = false
    private val effects = mutableListOf<AudioEffect>()

    /** Unos 43 s de audio: alcanza para más de una vuelta del loop más largo (4 compases a 40). */
    private val capture = CaptureBuffer(capacityFrames = 1 shl 21)

    /** Pico reciente del micrófono (0..1), para el medidor. */
    @Volatile var inputLevel: Float = 0f
        private set

    val isMicActive: Boolean get() = inputRunning

    /** Fuente de audio que se terminó usando, para mostrar. */
    @Volatile var inputSourceName: String = ""
        private set

    /** Requiere el permiso RECORD_AUDIO concedido. Devuelve false si no se pudo abrir. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startMic(): Boolean {
        if (inputRunning) return true
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) return false
        val bufferBytes = max(minBytes * 2, blockFrames * 4 * 4)

        val unprocessed = audioManager
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.toBoolean() == true
        val sources = buildList {
            if (unprocessed) add(MediaRecorder.AudioSource.UNPROCESSED to "sin procesar")
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "reconocimiento de voz")
            add(MediaRecorder.AudioSource.MIC to "micrófono")
        }
        var rec: AudioRecord? = null
        for ((source, name) in sources) {
            val r = try {
                AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufferBytes)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo abrir la fuente $name", e)
                null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                rec = r
                inputSourceName = name
                break
            }
            r?.release()
        }
        if (rec == null) return false

        disableEffects(rec.audioSessionId)
        inputLatencyFrames = bufferBytes / 4 / 2
        capture.clear()
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "No se pudo empezar a grabar", e)
            rec.release()
            releaseEffects()
            return false
        }
        record = rec
        inputRunning = true
        inputThread = Thread({ inputLoop(rec) }, "loopera-entrada").also { it.start() }
        return true
    }

    private fun disableEffects(session: Int) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(session)?.let { it.enabled = false; effects += it }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(session)?.let { it.enabled = false; effects += it }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(session)?.let { it.enabled = false; effects += it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron desactivar los efectos de entrada", e)
        }
    }

    private fun releaseEffects() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
    }

    @Synchronized
    fun stopMic() {
        if (!inputRunning) return
        inputRunning = false
        inputThread?.join(500)
        inputThread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        releaseEffects()
        inputLevel = 0f
    }

    private fun inputLoop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = FloatArray(blockFrames)
        val ts = AudioTimestamp()
        var inputFrames = 0L
        var level = 0f
        while (inputRunning) {
            val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                if (n < 0) Log.e(TAG, "Error al leer el micrófono: $n")
                SystemClock.sleep(2)
                continue
            }
            val first = inputFrames
            inputFrames += n
            val now = System.nanoTime()
            // Instante en que se captó la primera muestra del bloque.
            val captureNanos =
                if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    ts.nanoTime + (first - ts.framePosition) * 1_000_000_000L / sampleRate
                } else {
                    now - (n + inputLatencyFrames).toLong() * 1_000_000_000L / sampleRate
                }
            // Lo que el músico escuchaba en ese instante es el frame de salida del bloque.
            capture.write(chunk, n, heardFrameAt(captureNanos))

            var peak = 0f
            for (i in 0 until n) {
                val a = abs(chunk[i])
                if (a > peak) peak = a
            }
            level = if (peak > level) peak else level * 0.85f
            inputLevel = level.coerceIn(0f, 1f)
        }
    }

    // ---------------------------------------------------------------- Grabación de capas

    class CaptureToken internal constructor(
        val pressFrame: Long,
        val startedTransport: Boolean,
        val transportStart: Long,
    )

    sealed interface CaptureResult {
        class Layer(val samples: FloatArray) : CaptureResult
        data object TooShort : CaptureResult
        data object Silent : CaptureResult
        data object Failed : CaptureResult
    }

    /** Al apretar GRABAR. Si el pulso estaba parado, lo arranca en el compás 1. */
    fun beginCapture(): CaptureToken {
        val started = !isPlaying
        val ts = startTransport()
        val press = if (started) ts else heardFrameNow()
        return CaptureToken(press, started, ts)
    }

    /**
     * Al soltar GRABAR. Bloquea un momento (hasta que llegan las últimas muestras del
     * micrófono), así que hay que llamarlo fuera del hilo principal.
     *
     * @param manualAdjustFrames ajuste de sincronía extra: corre la capa hacia atrás.
     */
    fun finishCapture(token: CaptureToken, manualAdjustFrames: Int): CaptureResult {
        val release = heardFrameNow()
        if (release - token.pressFrame < sampleRate / 10) return CaptureResult.TooShort

        // Esperar a que el micrófono haya entregado todo lo tocado hasta el momento de soltar.
        val deadline = SystemClock.uptimeMillis() + 800
        while ((capture.latestOutFrame() ?: Long.MIN_VALUE) < release &&
            SystemClock.uptimeMillis() < deadline && inputRunning
        ) {
            SystemClock.sleep(5)
        }

        val preroll = sampleRate * PREROLL_MS / 1000
        val from = if (token.startedTransport) token.transportStart else token.pressFrame - preroll
        val extracted = capture.extract(from, release) ?: return CaptureResult.Failed
        if (extracted.samples.size < sampleRate / 10) return CaptureResult.TooShort
        if (LoopMath.isSilent(extracted.samples)) return CaptureResult.Silent

        // Los timestamps ya descuentan la latencia de entrada y salida medida; el ajuste
        // manual corrige lo que el equipo no informa bien.
        val startInLoop = extracted.firstOutFrame - token.transportStart - manualAdjustFrames
        val layer = LoopMath.placeRecording(
            recording = extracted.samples,
            startFrame = startInLoop,
            loopFrames = loopFrames,
            fadeFrames = sampleRate * FADE_EDGES_MS / 1000,
        )
        return CaptureResult.Layer(layer)
    }

    fun release() {
        stopMic()
        stopOutput()
    }

    companion object {
        private const val TAG = "LoopEngine"
        private const val FADE_MS = 10
        private const val FADE_EDGES_MS = 6
        private const val PREROLL_MS = 40
    }
}
