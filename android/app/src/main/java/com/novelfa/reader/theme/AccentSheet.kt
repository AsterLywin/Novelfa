package com.novelfa.reader.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.novelfa.reader.R

/**
 * Accent-color picker: the 7 presets from ThemeManager.cs.
 * Selection is persisted by the caller via PrefsStore ACCENT ("accent").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentSheet(
    selectedHex: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "رنگ برنامه",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (preset in Accent.PRESETS) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val selected =
                                preset.hex.equals(selectedHex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(hexToColor(preset.hex))
                                    .then(
                                        if (selected) Modifier.border(
                                            3.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            CircleShape
                                        ) else Modifier
                                    )
                                    .clickable { onPick(preset.hex) }
                            )
                            Text(
                                text = preset.nameFa,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Custom color (WPF parity: HSV picker + hex entry + reset).
                Text(
                    text = stringResource(R.string.custom_color),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                HsvCustomPicker(
                    initialHex = selectedHex,
                    onPick = onPick
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { onPick(Accent.DEFAULT) }) {
                    Text(stringResource(R.string.default_color))
                }
            }
        }
    }
}

/** HSV custom-color picker: hue bar + saturation/value pad + hex entry. */
@Composable
private fun HsvCustomPicker(
    initialHex: String,
    onPick: (String) -> Unit
) {
    val start = remember(initialHex) { hexToHsv(initialHex) }
    var hue by remember(initialHex) { mutableFloatStateOf(start[0]) }
    var sat by remember(initialHex) { mutableFloatStateOf(start[1]) }
    var valV by remember(initialHex) { mutableFloatStateOf(start[2]) }
    var hexText by remember(initialHex) { mutableStateOf(initialHex) }
    var hexError by remember { mutableStateOf(false) }
    val customHex = hsvToHex(hue, sat, valV)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HueBar(hue = hue, onHue = { hue = it })
        SaturationValuePad(hue = hue, sat = sat, value = valV, onSv = { s, v ->
            sat = s
            valV = v
        })
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(hexToColor(customHex))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            OutlinedTextField(
                value = hexText,
                onValueChange = {
                    hexText = it
                    hexError = false
                },
                singleLine = true,
                placeholder = { Text("#7C3AED") },
                isError = hexError,
                supportingText = if (hexError) {
                    { Text(stringResource(R.string.invalid_hex)) }
                } else null,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                val parsed = parseHexColor(hexText)
                if (parsed != null) {
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(parsed, hsv)
                    hue = hsv[0]
                    sat = hsv[1]
                    valV = hsv[2]
                    hexError = false
                } else {
                    hexError = true
                }
            }) {
                Text(stringResource(R.string.apply_custom_color))
            }
        }
        Button(
            onClick = { onPick(customHex) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.pick_this_color))
        }
    }
}

@Composable
private fun HueBar(hue: Float, onHue: (Float) -> Unit) {
    var widthPx by remember { mutableIntStateOf(0) }
    val rainbow = remember {
        (0..360 step 30).map { h ->
            Color(android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), 1f, 1f)))
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .onSizeChanged { widthPx = it.width }
            .pointerInput(widthPx) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (widthPx > 0) onHue((offset.x / widthPx * 360f).coerceIn(0f, 360f))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (widthPx > 0) onHue((change.position.x / widthPx * 360f).coerceIn(0f, 360f))
                    }
                )
            }
    ) {
        drawRect(Brush.horizontalGradient(rainbow))
        val x = (hue / 360f).coerceIn(0f, 1f) * size.width
        drawCircle(Color.White, radius = 16f, center = Offset(x, size.height / 2f))
        drawCircle(
            Color.Black, radius = 16f, center = Offset(x, size.height / 2f),
            style = Stroke(width = 4f)
        )
    }
}

@Composable
private fun SaturationValuePad(
    hue: Float,
    sat: Float,
    value: Float,
    onSv: (Float, Float) -> Unit
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }
    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .onSizeChanged {
                widthPx = it.width
                heightPx = it.height
            }
            .pointerInput(widthPx, heightPx) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (widthPx > 0 && heightPx > 0) {
                            onSv(
                                (offset.x / widthPx).coerceIn(0f, 1f),
                                1f - (offset.y / heightPx).coerceIn(0f, 1f)
                            )
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (widthPx > 0 && heightPx > 0) {
                            onSv(
                                (change.position.x / widthPx).coerceIn(0f, 1f),
                                1f - (change.position.y / heightPx).coerceIn(0f, 1f)
                            )
                        }
                    }
                )
            }
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val x = sat.coerceIn(0f, 1f) * size.width
        val y = (1f - value.coerceIn(0f, 1f)) * size.height
        drawCircle(Color.White, radius = 16f, center = Offset(x, y))
        drawCircle(
            Color.Black, radius = 16f, center = Offset(x, y),
            style = Stroke(width = 4f)
        )
    }
}

private fun hexToHsv(hex: String): FloatArray {
    val hsv = FloatArray(3)
    return try {
        android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), hsv)
        hsv
    } catch (e: Exception) {
        hsv
    }
}

private fun hsvToHex(h: Float, s: Float, v: Float): String {
    val c = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    return String.format("#%06X", 0xFFFFFF and c)
}

private fun parseHexColor(hex: String): Int? = try {
    var clean = hex.trim()
    if (!clean.startsWith("#")) clean = "#$clean"
    android.graphics.Color.parseColor(clean)
} catch (e: Exception) {
    null
}
