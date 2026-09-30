package com.tallermusica.loopera.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Rojo del tiempo 1 de cada compás. */
val DownbeatRed = Color(0xFFE53935)

/** Celeste de los demás tiempos. */
val BeatCeleste = Color(0xFF29B6F6)

/** Paleta de 8 colores para las capas. */
val LayerPalette = listOf(
    Color(0xFFEF5350),
    Color(0xFF42A5F5),
    Color(0xFF66BB6A),
    Color(0xFFFFA726),
    Color(0xFFAB47BC),
    Color(0xFF26C6DA),
    Color(0xFFD4E157),
    Color(0xFFEC407A),
)

fun layerColor(index: Int): Color = LayerPalette[Math.floorMod(index, LayerPalette.size)]

private val LightColors = lightColorScheme(
    primary = Color(0xFF00658E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7E7FF),
    onPrimaryContainer = Color(0xFF001E2E),
    secondary = Color(0xFF4F616E),
    tertiary = Color(0xFFB3261E),
    background = Color(0xFFF8FAFD),
    surface = Color(0xFFF8FAFD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF84CFFF),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF004C6C),
    onPrimaryContainer = Color(0xFFC7E7FF),
    secondary = Color(0xFFB6C9D8),
    tertiary = Color(0xFFFFB4AB),
    background = Color(0xFF0F1419),
    surface = Color(0xFF0F1419),
)

@Composable
fun LooperaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
