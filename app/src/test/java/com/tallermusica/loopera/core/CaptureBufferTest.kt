package com.tallermusica.loopera.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureBufferTest {

    @Test
    fun recuperaElTramoPorFramesDeSalida() {
        val buf = CaptureBuffer(capacityFrames = 100)
        // Bloques de 4 muestras; la primera se escuchó en el frame de salida 1000
        buf.write(floatArrayOf(0f, 1f, 2f, 3f), 4, 1000)
        buf.write(floatArrayOf(4f, 5f, 6f, 7f), 4, 1004)
        val e = buf.extract(1002, 1006)!!
        assertEquals(1002L, e.firstOutFrame)
        assertArrayEquals(floatArrayOf(2f, 3f, 4f, 5f), e.samples, 0f)
        assertEquals(1008L, buf.latestOutFrame())
    }

    @Test
    fun conJitterLasMuestrasSalenSeguidas() {
        val buf = CaptureBuffer(capacityFrames = 100)
        buf.write(floatArrayOf(0f, 1f, 2f, 3f), 4, 1000)
        // El segundo bloque quedó marcado un frame antes de lo esperado
        buf.write(floatArrayOf(4f, 5f, 6f, 7f), 4, 1003)
        val e = buf.extract(1000, 1007)!!
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f), e.samples, 0f)
    }

    @Test
    fun descartaLoQueYaSePiso() {
        val buf = CaptureBuffer(capacityFrames = 8)
        for (b in 0 until 4) {
            buf.write(FloatArray(4) { (b * 4 + it).toFloat() }, 4, b * 4L)
        }
        val e = buf.extract(0, 16)!!
        assertEquals(8L, e.firstOutFrame)
        assertArrayEquals(FloatArray(8) { (8 + it).toFloat() }, e.samples, 0f)
    }

    @Test
    fun sinDatosDevuelveNull() {
        val buf = CaptureBuffer(capacityFrames = 8)
        assertNull(buf.extract(0, 10))
        buf.write(floatArrayOf(1f), 1, 50)
        assertNull(buf.extract(0, 10))
    }
}
