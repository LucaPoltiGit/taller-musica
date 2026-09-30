@file:OptIn(ExperimentalMaterial3Api::class)

package com.tallermusica.loopera.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tallermusica.loopera.core.BeatPosition
import com.tallermusica.loopera.core.LoopMath
import com.tallermusica.loopera.ui.theme.BeatCeleste
import com.tallermusica.loopera.ui.theme.DownbeatRed
import kotlin.math.exp
import kotlin.math.roundToInt

/** Brillo del pulso: máximo al empezar el tiempo y se apaga suave hasta el siguiente. */
private fun glow(beat: BeatPosition?): Float = if (beat == null) 0f else exp(-beat.beatPhase * 4.5f)

private fun accentFor(beat: BeatPosition?): Color =
    if (beat == null || beat.beatInBar == 0) DownbeatRed else BeatCeleste

/** Pulso visual: círculo que late, texto de compás y tiempo, puntitos y barra del loop. */
@Composable
fun PulseDisplay(clock: State<ClockSnapshot>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseCircle(clock, Modifier.size(132.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val c = clock.value
            val beat = c.beat
            Text(
                text = if (beat == null) {
                    "Pulso detenido"
                } else {
                    "Compás ${beat.bar + 1} de ${c.bars} · tiempo ${beat.beatInBar + 1}"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            BeatDots(clock, Modifier.fillMaxWidth().height(22.dp))
            LinearProgressIndicator(
                progress = { clock.value.beat?.loopProgress ?: 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        }
    }
}

@Composable
private fun PulseCircle(clock: State<ClockSnapshot>, modifier: Modifier) {
    val rest = MaterialTheme.colorScheme.surfaceVariant
    val onRest = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val beat = clock.value.beat
            val g = glow(beat)
            val accent = accentFor(beat)
            val maxR = size.minDimension / 2f
            val color = if (beat == null) rest else lerp(lerp(rest, accent, 0.35f), accent, g)
            drawCircle(color, radius = maxR * (0.80f + 0.16f * g))
        }
        val beat = clock.value.beat
        Text(
            text = ((beat?.beatInBar ?: 0) + 1).toString(),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = if (beat == null) onRest else Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

/** Puntitos agrupados por compás (4 por compás), con el tiempo actual iluminado. */
@Composable
private fun BeatDots(clock: State<ClockSnapshot>, modifier: Modifier) {
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val c = clock.value
        val beat = c.beat
        val bars = c.bars
        val perBar = LoopMath.BEATS_PER_BAR
        val gap = 14.dp.toPx()
        val r = minOf(6.dp.toPx(), size.height / 2f)
        val barWidth = (size.width - gap * (bars - 1)) / bars
        val step = minOf(barWidth / perBar, r * 3.6f)
        for (b in 0 until bars) {
            val x0 = b * (barWidth + gap)
            for (i in 0 until perBar) {
                val current = beat != null && beat.bar == b && beat.beatInBar == i
                val center = Offset(x0 + step * (i + 0.5f), size.height / 2f)
                val accent = if (i == 0) DownbeatRed else BeatCeleste
                val radius = if (i == 0) r else r * 0.8f
                if (current) {
                    drawCircle(accent, radius = radius, center = center)
                } else {
                    drawCircle(if (i == 0) lerp(off, DownbeatRed, 0.3f) else off, radius = radius * 0.85f, center = center)
                }
            }
        }
    }
}

/** Controles del pulso: iniciar/detener, tempo y largo. */
@Composable
fun PulseControls(
    state: LooperUiState,
    onTogglePlay: () -> Unit,
    onBpmChange: (Int) -> Unit,
    onBpmStep: (Int) -> Unit,
    onTap: () -> Unit,
    onBarsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locked = state.tempoLocked
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onTogglePlay,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = if (state.playing) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            if (state.playing) {
                StopGlyph(Modifier.size(18.dp))
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (state.playing) "Detener pulso" else "Iniciar pulso", style = MaterialTheme.typography.titleMedium)
        }

        // Tempo
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tempo", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
            FilledTonalIconButton(onClick = { onBpmStep(-2) }, enabled = !locked && state.bpm > LoopMath.MIN_BPM) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = "${state.bpm} BPM",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
                color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            FilledTonalIconButton(onClick = { onBpmStep(2) }, enabled = !locked && state.bpm < LoopMath.MAX_BPM) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
        Slider(
            value = state.bpm.toFloat(),
            onValueChange = { onBpmChange(it.roundToInt()) },
            valueRange = LoopMath.MIN_BPM.toFloat()..LoopMath.MAX_BPM.toFloat(),
            enabled = !locked,
        )
        OutlinedButton(onClick = onTap, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
            Text("Marcar tempo")
        }

        // Largo
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Largo", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                for (n in LoopMath.MIN_BARS..LoopMath.MAX_BARS) {
                    SegmentedButton(
                        selected = state.bars == n,
                        onClick = { onBarsChange(n) },
                        enabled = !locked,
                        shape = SegmentedButtonDefaults.itemShape(n - 1, LoopMath.MAX_BARS),
                        label = { Text("$n") },
                    )
                }
            }
        }
        Text(
            text = if (state.bars == 1) "1 compás de 4/4" else "${state.bars} compases de 4/4",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 64.dp),
        )
        if (locked) {
            Text(
                text = "Tempo y largo quedan fijos mientras haya capas grabadas. " +
                    "Para cambiarlos, borrá todas las capas.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Cuadradito de "detener" (el ícono no está en material-icons-core). */
@Composable
private fun StopGlyph(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSecondaryContainer
    Canvas(modifier) { drawRect(color, topLeft = Offset(size.width * 0.1f, size.height * 0.1f), size = size * 0.8f) }
}
