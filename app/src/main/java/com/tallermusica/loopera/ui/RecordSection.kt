package com.tallermusica.loopera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Botón de micrófono, medidor de nivel y aviso de permiso. */
@Composable
fun MicControls(
    state: LooperUiState,
    clock: State<ClockSnapshot>,
    onEnableMic: () -> Unit,
    onDisableMic: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.micActive) {
                OutlinedButton(onClick = onDisableMic) { Text("Apagar micrófono") }
            } else {
                Button(onClick = onEnableMic) { Text("Activar micrófono") }
            }
            Spacer(Modifier.width(12.dp))
            LevelMeter(clock, state.micActive, Modifier.weight(1f).height(14.dp))
        }
        if (state.micPermissionDenied) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Sin permiso para usar el micrófono no se puede grabar. " +
                            "Podés habilitarlo en los ajustes de la app, en «Permisos» → «Micrófono».",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onOpenSettings) { Text("Abrir ajustes") }
                }
            }
        }
    }
}

@Composable
private fun LevelMeter(clock: State<ClockSnapshot>, active: Boolean, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.clip(MaterialTheme.shapes.small)) {
        drawRect(track)
        if (!active) return@Canvas
        // Escala en dB de -60 a 0
        val level = clock.value.inputLevel
        val db = if (level <= 0.001f) -60f else (20f * kotlin.math.log10(level)).coerceIn(-60f, 0f)
        val frac = (db + 60f) / 60f
        val color = when {
            db > -3f -> Color(0xFFE53935)
            db > -12f -> Color(0xFFFFB300)
            else -> Color(0xFF43A047)
        }
        drawRoundRect(color, size = Size(size.width * frac, size.height), cornerRadius = CornerRadius(4f, 4f))
    }
}

/**
 * Botón GRABAR: graba mientras está apretado. Usa pointerInput directo (sin clickable ni
 * combinedClickable) así no hay menú contextual ni vibración de pulsación larga, y el
 * soltar se detecta aunque el dedo se haya ido del botón.
 */
@Composable
fun RecordButton(
    recording: Boolean,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val container by animateColorAsState(
        when {
            recording -> Color(0xFFD32F2F)
            enabled -> Color(0xFFE53935)
            else -> Color(0xFFE53935).copy(alpha = 0.45f)
        },
        label = "grabar-color",
    )
    val scale by animateFloatAsState(if (recording) 1.08f else 1f, label = "grabar-escala")
    Box(
        modifier = modifier
            .size(148.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(container)
            .border(
                width = if (recording) 6.dp else 3.dp,
                color = if (recording) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.35f),
                shape = CircleShape,
            )
            .semantics {
                role = Role.Button
                contentDescription = "Grabar: mantené apretado para grabar una capa"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    press()
                    try {
                        // Seguir hasta que se levanten todos los dedos, estén donde estén.
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        // También si el gesto se cancela (por ejemplo, la app pasa a segundo plano).
                        release()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(if (recording) 18.dp else 14.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (recording) "GRABANDO" else "GRABAR",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun HeadphonesNotice(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                "Usá auriculares: si el loop sale por el parlante, el micrófono lo vuelve a grabar.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Sección plegable para corregir a mano si las capas quedan corridas. */
@Composable
fun SyncAdjustSection(
    state: LooperUiState,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ajuste de sincronía", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (state.syncAdjustMs > 0 && !open) {
                Text(
                    "${state.syncAdjustMs} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "Plegar" else "Desplegar",
            )
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Si las capas nuevas quedan atrasadas respecto del pulso, subí este valor " +
                        "y grabá de nuevo. Se aplica a las próximas grabaciones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = state.syncAdjustMs.toFloat(),
                        onValueChange = { onChange(it.roundToInt()) },
                        valueRange = 0f..300f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${state.syncAdjustMs} ms",
                        modifier = Modifier.width(64.dp),
                        textAlign = TextAlign.End,
                    )
                }
                TextButton(onClick = { onChange(0) }, enabled = state.syncAdjustMs != 0) {
                    Text("Volver a 0")
                }
                val medido = if (state.usesTimestamps) "medida con marcas de tiempo del sistema" else "estimada por el tamaño de los buffers"
                Text(
                    "Latencia de salida: ${state.outputLatencyMs} ms ($medido)" +
                        (if (state.inputSource.isNotEmpty()) " · entrada: ${state.inputSource}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
