package com.tallermusica.loopera.core

import kotlin.math.roundToInt

/**
 * "Marcar tempo": calcula el BPM promediando los intervalos de los últimos toques.
 * Si entre un toque y el anterior pasan más de [maxGapMs], se empieza de nuevo.
 */
class TapTempo(
    private val maxGapMs: Long = 2_500,
    private val maxTaps: Int = 8,
) {
    private val taps = ArrayDeque<Long>()

    /** Registra un toque y devuelve el BPM calculado, o null si todavía no alcanza. */
    fun tap(nowMs: Long): Int? {
        val last = taps.lastOrNull()
        if (last != null && (nowMs - last > maxGapMs || nowMs <= last)) taps.clear()
        taps.addLast(nowMs)
        while (taps.size > maxTaps) taps.removeFirst()
        if (taps.size < 2) return null
        val avgMs = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
        return (60_000.0 / avgMs).roundToInt().coerceIn(LoopMath.MIN_BPM, LoopMath.MAX_BPM)
    }

    val tapCount: Int get() = taps.size

    fun reset() = taps.clear()
}
