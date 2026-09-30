package com.tallermusica.loopera.core

/**
 * Buffer circular de lo que capta el micrófono, con marca de tiempo por frame.
 *
 * Cada bloque que llega del micrófono se guarda junto con el frame de salida (del reloj del
 * loop) que se estaba escuchando cuando se captó su primera muestra. El frame de salida de
 * cada muestra es el del bloque más su índice dentro del bloque. Así, a partir de un rango de
 * frames de salida se recuperan exactamente las muestras que se tocaron en ese momento.
 */
class CaptureBuffer(
    val capacityFrames: Int,
    private val maxChunks: Int = 8192,
) {
    private val samples = FloatArray(capacityFrames)
    private val chunkStart = LongArray(maxChunks)   // índice absoluto de entrada del bloque
    private val chunkOutFrame = LongArray(maxChunks) // frame de salida de la primera muestra
    private val chunkLength = IntArray(maxChunks)
    private var chunkCount = 0L
    private var written = 0L

    @Synchronized
    fun clear() {
        chunkCount = 0
        written = 0
    }

    @Synchronized
    fun write(src: FloatArray, count: Int, outFrameOfFirst: Long) {
        if (count <= 0) return
        val slot = (chunkCount % maxChunks).toInt()
        chunkStart[slot] = written
        chunkOutFrame[slot] = outFrameOfFirst
        chunkLength[slot] = count
        chunkCount++
        var pos = (written % capacityFrames).toInt()
        for (i in 0 until count) {
            samples[pos] = src[i]
            pos++
            if (pos == capacityFrames) pos = 0
        }
        written += count
    }

    /** Frame de salida que corresponde al final de lo último captado, o null si no hay nada. */
    @Synchronized
    fun latestOutFrame(): Long? {
        if (chunkCount == 0L) return null
        val slot = ((chunkCount - 1) % maxChunks).toInt()
        return chunkOutFrame[slot] + chunkLength[slot]
    }

    /**
     * Devuelve el tramo continuo de muestras que va del frame de salida [fromOut] a [toOut],
     * junto con el frame de salida de la primera. Las muestras se copian seguidas tal como
     * entraron (sin saltos ni repeticiones aunque las marcas de tiempo tengan algo de jitter).
     * Null si no hay nada captado en ese rango.
     */
    @Synchronized
    fun extract(fromOut: Long, toOut: Long): Extracted? {
        if (toOut <= fromOut || chunkCount == 0L) return null
        val firstChunk = maxOf(0L, chunkCount - maxChunks)
        var startAbs = -1L
        var endAbs = -1L
        for (c in firstChunk until chunkCount) {
            val slot = (c % maxChunks).toInt()
            val cOut = chunkOutFrame[slot]
            val len = chunkLength[slot]
            if (startAbs < 0 && cOut + len > fromOut) {
                startAbs = chunkStart[slot] + (fromOut - cOut).coerceIn(0L, len - 1L)
            }
            if (cOut < toOut) {
                endAbs = chunkStart[slot] + minOf(len.toLong(), toOut - cOut)
            }
        }
        if (startAbs < 0) return null
        startAbs = maxOf(startAbs, written - capacityFrames, 0L)
        if (endAbs <= startAbs) return null
        val firstOut = outFrameOf(startAbs, firstChunk) ?: return null
        val n = (endAbs - startAbs).toInt()
        val out = FloatArray(n)
        var pos = (startAbs % capacityFrames).toInt()
        for (i in 0 until n) {
            out[i] = samples[pos]
            pos++
            if (pos == capacityFrames) pos = 0
        }
        return Extracted(firstOut, out)
    }

    private fun outFrameOf(absIndex: Long, firstChunk: Long): Long? {
        for (c in chunkCount - 1 downTo firstChunk) {
            val slot = (c % maxChunks).toInt()
            if (absIndex >= chunkStart[slot]) return chunkOutFrame[slot] + (absIndex - chunkStart[slot])
        }
        return null
    }

    class Extracted(val firstOutFrame: Long, val samples: FloatArray)
}
