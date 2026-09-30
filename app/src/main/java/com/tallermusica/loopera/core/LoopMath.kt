package com.tallermusica.loopera.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tanh

/** Dónde está el loop en un momento dado. Todo se deriva de un contador de frames. */
data class BeatPosition(
    /** Compás actual, empezando en 0. */
    val bar: Int,
    /** Tiempo dentro del compás, empezando en 0. */
    val beatInBar: Int,
    /** Cuánto avanzó dentro del tiempo actual (0 recién empieza, 1 a punto de terminar). */
    val beatPhase: Float,
    /** Avance dentro del loop completo (0..1). */
    val loopProgress: Float,
    /** Frame dentro del loop. */
    val loopFrame: Int,
)

/** Cálculos puros de tiempo y audio, sin nada de Android (se testean en la JVM). */
object LoopMath {
    const val SAMPLE_RATE = 48_000
    const val BEATS_PER_BAR = 4
    const val MIN_BPM = 40
    const val MAX_BPM = 200
    const val MIN_BARS = 1
    const val MAX_BARS = 4

    fun framesPerBeat(bpm: Int, sampleRate: Int = SAMPLE_RATE): Double = sampleRate * 60.0 / bpm

    fun loopLengthFrames(bpm: Int, bars: Int, sampleRate: Int = SAMPLE_RATE): Int =
        (framesPerBeat(bpm, sampleRate) * bars * BEATS_PER_BAR).roundToInt()

    /** Posición dentro del loop: frame % largo, siempre positiva (también para frames negativos). */
    fun loopPosition(frame: Long, loopFrames: Int): Int =
        Math.floorMod(frame, loopFrames.toLong()).toInt()

    /**
     * Compás y tiempo que corresponden a [framesSinceStart] frames desde el inicio del pulso
     * (el frame 0 es el tiempo 1 del compás 1).
     */
    fun beatPosition(
        framesSinceStart: Long,
        bpm: Int,
        bars: Int,
        sampleRate: Int = SAMPLE_RATE,
    ): BeatPosition {
        val loopFrames = loopLengthFrames(bpm, bars, sampleRate)
        val pos = loopPosition(framesSinceStart, loopFrames)
        val fpb = framesPerBeat(bpm, sampleRate)
        val totalBeats = bars * BEATS_PER_BAR
        val beatIndex = (pos / fpb).toInt().coerceIn(0, totalBeats - 1)
        val phase = ((pos - beatIndex * fpb) / fpb).toFloat().coerceIn(0f, 1f)
        return BeatPosition(
            bar = beatIndex / BEATS_PER_BAR,
            beatInBar = beatIndex % BEATS_PER_BAR,
            beatPhase = phase,
            loopProgress = pos.toFloat() / loopFrames,
            loopFrame = pos,
        )
    }

    /**
     * Convierte un tramo grabado en una capa del largo exacto del loop.
     *
     * @param recording muestras grabadas, en orden.
     * @param startFrame posición (en frames relativos al inicio del pulso) que corresponde a la
     *   primera muestra de [recording]. Puede ser negativa o mayor al loop: se ubica con módulo.
     * @param loopFrames largo del loop.
     * @param fadeFrames largo del fundido de entrada y de salida en los bordes del tramo.
     *
     * Si el tramo dura más de una vuelta, se queda con la última vuelta. Si llega al final del
     * loop, sigue desde el principio.
     */
    fun placeRecording(
        recording: FloatArray,
        startFrame: Long,
        loopFrames: Int,
        fadeFrames: Int,
    ): FloatArray {
        require(loopFrames > 0)
        val out = FloatArray(loopFrames)
        val keep = min(recording.size, loopFrames)
        if (keep == 0) return out
        val skip = recording.size - keep
        val fade = fadeFrames.coerceIn(0, keep / 2)
        var index = loopPosition(startFrame + skip, loopFrames)
        for (i in 0 until keep) {
            var s = recording[skip + i]
            if (fade > 0) {
                if (i < fade) {
                    s *= (i + 1f) / (fade + 1f)
                } else if (i >= keep - fade) {
                    s *= (keep - i).toFloat() / (fade + 1f)
                }
            }
            out[index] = s
            index++
            if (index == loopFrames) index = 0
        }
        return out
    }

    fun peak(samples: FloatArray): Float {
        var p = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > p) p = a
        }
        return p
    }

    /** Menos de -50 dBFS de pico se considera silencio. */
    fun isSilent(samples: FloatArray, threshold: Float = 0.00316f): Boolean = peak(samples) < threshold

    /** Picos por tramo, para dibujar la forma de onda. */
    fun waveformPeaks(samples: FloatArray, bins: Int): FloatArray {
        val out = FloatArray(bins)
        if (samples.isEmpty() || bins <= 0) return out
        for (b in 0 until bins) {
            val from = (samples.size.toLong() * b / bins).toInt()
            val to = maxOf(from + 1, (samples.size.toLong() * (b + 1) / bins).toInt()).coerceAtMost(samples.size)
            var p = 0f
            for (i in from until to) {
                val a = abs(samples[i])
                if (a > p) p = a
            }
            out[b] = p
        }
        return out
    }

    /** Limitador suave: lineal hasta 0,8 y después se curva sin pasar nunca de 1. */
    fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (x < 0f) -y else y
    }

    private const val KNEE = 0.8f
}
