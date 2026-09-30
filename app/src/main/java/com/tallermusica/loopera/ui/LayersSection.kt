package com.tallermusica.loopera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tallermusica.loopera.core.LoopMath
import com.tallermusica.loopera.ui.theme.layerColor
import kotlin.math.roundToInt

@Composable
fun LayersHeader(
    count: Int,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (count == 0) "Capas" else "Capas ($count)",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { confirm = true }, enabled = count > 0) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Borrar todas")
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Borrar todas las capas?") },
            text = { Text("Se pierden todas las grabaciones. No se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDeleteAll()
                }) { Text("Borrar todas") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
fun EmptyLayersHint(modifier: Modifier = Modifier) {
    Text(
        "Todavía no hay capas. Activá el micrófono, ponete los auriculares y mantené " +
            "apretado GRABAR mientras tocás: al soltar, lo que tocaste se suma como la primera " +
            "capa y empieza a repetirse en loop.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 12.dp),
    )
}

@Composable
fun LayerCard(
    layer: LayerUi,
    bars: Int,
    clock: State<ClockSnapshot>,
    onEnabledChange: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = layerColor(layer.colorIndex)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (layer.enabled) color else color.copy(alpha = 0.35f)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Capa ${layer.number}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = layer.enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = color),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Borrar capa ${layer.number}")
                }
            }
            Waveform(
                peaks = layer.peaks,
                color = color,
                enabled = layer.enabled,
                bars = bars,
                clock = clock,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volumen", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(60.dp))
                Slider(
                    value = layer.volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1.5f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
                )
                Text(
                    "${(layer.volume * 100).roundToInt()} %",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(48.dp).padding(start = 6.dp),
                )
            }
        }
    }
}

/**
 * Forma de onda de la capa sobre todo el loop, con una línea por tiempo (más marcada en cada
 * compás) y la posición actual.
 */
@Composable
private fun Waveform(
    peaks: FloatArray,
    color: Color,
    enabled: Boolean,
    bars: Int,
    clock: State<ClockSnapshot>,
    modifier: Modifier,
) {
    val background = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant
    val playheadColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        drawRect(background)
        val w = size.width
        val h = size.height
        val mid = h / 2f

        // Forma de onda
        val waveColor = if (enabled) color else color.copy(alpha = 0.3f)
        if (peaks.isNotEmpty()) {
            val colW = w / peaks.size
            val stroke = maxOf(1f, colW * 0.8f)
            for (i in peaks.indices) {
                // Raíz para que los pasajes suaves también se vean
                val a = kotlin.math.sqrt(peaks[i].coerceIn(0f, 1f)) * (mid - 2f)
                if (a < 0.5f) continue
                val x = (i + 0.5f) * colW
                drawLine(waveColor, Offset(x, mid - a), Offset(x, mid + a), strokeWidth = stroke)
            }
        }

        // Líneas de tiempo y de compás
        val beats = bars * LoopMath.BEATS_PER_BAR
        for (b in 0..beats) {
            val x = (w * b / beats).coerceIn(0.5f, w - 0.5f)
            val isBar = b % LoopMath.BEATS_PER_BAR == 0
            drawLine(
                color = gridColor.copy(alpha = if (isBar) 0.6f else 0.2f),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = if (isBar) 2.dp.toPx() else 1.dp.toPx(),
            )
        }

        // Posición actual
        val beat = clock.value.beat
        if (beat != null) {
            val x = beat.loopProgress * w
            drawLine(playheadColor, Offset(x, 0f), Offset(x, h), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
