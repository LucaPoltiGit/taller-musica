package com.tallermusica.loopera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapTempoTest {

    @Test
    fun unSoloToqueNoAlcanza() {
        assertNull(TapTempo().tap(1_000))
    }

    @Test
    fun toquesRegularesDanElTempo() {
        val tap = TapTempo()
        var bpm: Int? = null
        // 500 ms entre toques = 120 BPM
        for (i in 0 until 5) bpm = tap.tap(10_000L + i * 500L)
        assertEquals(120, bpm)
    }

    @Test
    fun promediaLosIntervalos() {
        val tap = TapTempo()
        tap.tap(0)
        tap.tap(600)
        // intervalos 600 y 400 → promedio 500 ms → 120 BPM
        assertEquals(120, tap.tap(1_000))
    }

    @Test
    fun descartaToquesDespuesDeUnaPausaLarga() {
        val tap = TapTempo()
        tap.tap(0)
        tap.tap(1_000) // 60 BPM
        // Pasan más de 2,5 s: se empieza de nuevo y el toque viejo no cuenta
        assertNull(tap.tap(4_000))
        assertEquals(100, tap.tap(4_600))
    }

    @Test
    fun usaSoloLosUltimosToques() {
        val tap = TapTempo(maxTaps = 4)
        // Primeros toques lentos (1000 ms), después rápidos (400 ms = 150 BPM)
        tap.tap(0)
        tap.tap(1_000)
        tap.tap(2_000)
        tap.tap(2_400)
        tap.tap(2_800)
        assertEquals(150, tap.tap(3_200))
    }

    @Test
    fun respetaLosLimites() {
        val rapido = TapTempo()
        rapido.tap(0)
        assertEquals(LoopMath.MAX_BPM, rapido.tap(100))

        val lento = TapTempo()
        lento.tap(0)
        assertEquals(LoopMath.MIN_BPM, lento.tap(2_400)) // 25 BPM → 40
    }
}
