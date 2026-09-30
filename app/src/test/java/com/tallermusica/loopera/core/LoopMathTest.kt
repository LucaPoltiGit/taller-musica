package com.tallermusica.loopera.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopMathTest {

    private val sr = LoopMath.SAMPLE_RATE

    @Test
    fun largoDelLoop() {
        // 120 BPM: un tiempo = 0,5 s = 24000 frames; 2 compases de 4/4 = 8 tiempos
        assertEquals(24_000.0, LoopMath.framesPerBeat(120), 0.0)
        assertEquals(192_000, LoopMath.loopLengthFrames(120, 2))
        assertEquals(4 * 4 * 60 * sr / 40, LoopMath.loopLengthFrames(40, 4))
    }

    @Test
    fun posicionDelLoopEsFrameModuloLargo() {
        assertEquals(0, LoopMath.loopPosition(0, 1000))
        assertEquals(999, LoopMath.loopPosition(999, 1000))
        assertEquals(0, LoopMath.loopPosition(1000, 1000))
        assertEquals(234, LoopMath.loopPosition(5_234, 1000))
        assertEquals(990, LoopMath.loopPosition(-10, 1000))
    }

    @Test
    fun compasYTiempoSegunFrames() {
        // 120 BPM, 2 compases
        val inicio = LoopMath.beatPosition(0, 120, 2)
        assertEquals(0, inicio.bar)
        assertEquals(0, inicio.beatInBar)
        assertEquals(0f, inicio.beatPhase, 0f)

        val mitadDelSegundoTiempo = LoopMath.beatPosition(24_000 + 12_000, 120, 2)
        assertEquals(0, mitadDelSegundoTiempo.bar)
        assertEquals(1, mitadDelSegundoTiempo.beatInBar)
        assertEquals(0.5f, mitadDelSegundoTiempo.beatPhase, 1e-4f)

        val compas2tiempo4 = LoopMath.beatPosition(7L * 24_000 + 10, 120, 2)
        assertEquals(1, compas2tiempo4.bar)
        assertEquals(3, compas2tiempo4.beatInBar)

        // Después de varias vueltas completas vuelve al compás 1, tiempo 1
        val vuelta = LoopMath.beatPosition(192_000L * 3 + 5, 120, 2)
        assertEquals(0, vuelta.bar)
        assertEquals(0, vuelta.beatInBar)
        assertEquals(5, vuelta.loopFrame)
    }

    @Test
    fun elTempoCambiaLaPosicion() {
        // A 60 BPM un tiempo dura 48000 frames
        val p = LoopMath.beatPosition(48_000L * 5, 60, 4)
        assertEquals(1, p.bar)
        assertEquals(1, p.beatInBar)
        assertEquals(5f / 16f, p.loopProgress, 1e-6f)
    }

    @Test
    fun ubicaLaGrabacionDondeSeToco() {
        val rec = floatArrayOf(1f, 2f, 3f)
        val capa = LoopMath.placeRecording(rec, startFrame = 4, loopFrames = 10, fadeFrames = 0)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f, 1f, 2f, 3f, 0f, 0f, 0f), capa, 0f)
    }

    @Test
    fun daLaVueltaAlFinalDelLoop() {
        val rec = floatArrayOf(1f, 2f, 3f, 4f)
        val capa = LoopMath.placeRecording(rec, startFrame = 8, loopFrames = 10, fadeFrames = 0)
        assertArrayEquals(floatArrayOf(3f, 4f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 2f), capa, 0f)
    }

    @Test
    fun posicionNegativaOVueltasPreviasSeUbicanConModulo() {
        val rec = floatArrayOf(1f, 2f)
        val a = LoopMath.placeRecording(rec, startFrame = -1, loopFrames = 5, fadeFrames = 0)
        assertArrayEquals(floatArrayOf(2f, 0f, 0f, 0f, 1f), a, 0f)
        val b = LoopMath.placeRecording(rec, startFrame = 22, loopFrames = 5, fadeFrames = 0)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 2f, 0f), b, 0f)
    }

    @Test
    fun siDuraMasDeUnaVueltaSeQuedaConLaUltima() {
        // Loop de 4 frames, grabación de 10 que empieza en la posición 1.
        // Las últimas 4 muestras (7, 8, 9, 10) empiezan en el frame 1 + 6 = 7 → posición 3.
        val rec = FloatArray(10) { (it + 1).toFloat() }
        val capa = LoopMath.placeRecording(rec, startFrame = 1, loopFrames = 4, fadeFrames = 0)
        assertArrayEquals(floatArrayOf(8f, 9f, 10f, 7f), capa, 0f)
    }

    @Test
    fun grabacionDeExactamenteUnaVuelta() {
        val rec = FloatArray(6) { (it + 1).toFloat() }
        val capa = LoopMath.placeRecording(rec, startFrame = 2, loopFrames = 6, fadeFrames = 0)
        assertArrayEquals(floatArrayOf(5f, 6f, 1f, 2f, 3f, 4f), capa, 0f)
    }

    @Test
    fun aplicaFundidosEnLosBordes() {
        val rec = FloatArray(100) { 1f }
        val capa = LoopMath.placeRecording(rec, startFrame = 0, loopFrames = 200, fadeFrames = 10)
        assertTrue(capa[0] < 0.2f)
        assertTrue(capa[5] in 0.2f..0.9f)
        assertEquals(1f, capa[50], 0f)
        assertTrue(capa[99] < 0.2f)
        assertEquals(0f, capa[150], 0f)
        assertTrue(capa.all { it <= 1f })
    }

    @Test
    fun detectaSilencio() {
        assertTrue(LoopMath.isSilent(FloatArray(1000) { 0.0005f }))
        assertFalse(LoopMath.isSilent(FloatArray(1000) { if (it == 500) 0.2f else 0f }))
    }

    @Test
    fun limitadorNoPasaDeUno() {
        assertEquals(0.5f, LoopMath.softClip(0.5f), 0f)
        assertTrue(LoopMath.softClip(3f) <= 1f)
        assertTrue(LoopMath.softClip(-3f) >= -1f)
        assertTrue(LoopMath.softClip(0.9f) > LoopMath.softClip(0.85f))
    }
}
