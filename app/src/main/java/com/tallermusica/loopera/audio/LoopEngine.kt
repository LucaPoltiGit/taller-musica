package com.tallermusica.loopera.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.tallermusica.loopera.core.BeatPosition
import com.tallermusica.loopera.core.LoopMath
import kotlin.math.max

/**
 * Motor de audio de la loopera.
 *
 * El reloj es el contador de frames de salida: el hilo de audio mezcla bloques chicos y los
 * escribe en un AudioTrack que nunca se detiene mientras la app está en primer plano. El frame
 * N del contador es el N-ésimo frame escrito, así que con AudioTrack.getTimestamp se sabe qué
 * frame está sonando en cada instante. La posición del loop es siempre
 * (frame - inicioDelPulso) % largoDelLoop; no hay timers de UI.
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

    fun release() {
        stopOutput()
    }

    companion object {
        private const val TAG = "LoopEngine"
        private const val FADE_MS = 10
    }
}
