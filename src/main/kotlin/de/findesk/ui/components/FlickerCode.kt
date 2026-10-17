package de.findesk.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import org.kapott.hbci.manager.FlickerRenderer

/**
 * chipTAN optical: five flickering bars read by the TAN generator. HBCI4Java's [FlickerRenderer] drives the
 * timing on its own thread; each frame updates the bar state. Speed and width are adjustable because generators
 * differ in how fast they read and need the bars aligned with their sensors (marked by the triangles).
 */
@Composable
fun FlickerCodeView(code: String, modifier: Modifier = Modifier) {
    var bars by remember { mutableStateOf(BooleanArray(5)) }
    var frequency by remember { mutableStateOf(FlickerRenderer.FREQUENCY_DEFAULT) }
    var width by remember { mutableStateOf(260f) }

    DisposableEffect(code, frequency) {
        val renderer = object : FlickerRenderer(code) {
            override fun paint(b1: Boolean, b2: Boolean, b3: Boolean, b4: Boolean, b5: Boolean) {
                bars = booleanArrayOf(b1, b2, b3, b4, b5)
            }

            // Loop until the dialog closes instead of stopping after a number of rounds
            override fun done(iterations: Int) {}
        }
        renderer.setFrequency(frequency)
        renderer.start()
        onDispose { renderer.stop() }
    }

    val description = strings()[StringKey.OpticalTanCode]
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.background(Color.Black).padding(12.dp)) {
            Canvas(Modifier.width(width.dp).height((width * 0.55f).dp).semantics { contentDescription = description }) {
                val gap = size.width * 0.04f
                val barWidth = (size.width - gap * 4) / 5
                val markerHeight = size.height * 0.12f
                // Positioning triangles above the first and last bar
                listOf(0, 4).forEach { i ->
                    val cx = i * (barWidth + gap) + barWidth / 2
                    drawPath(
                        Path().apply {
                            moveTo(cx - markerHeight / 2, 0f)
                            lineTo(cx + markerHeight / 2, 0f)
                            lineTo(cx, markerHeight)
                            close()
                        },
                        Color.White,
                    )
                }
                bars.forEachIndexed { i, on ->
                    drawRect(
                        if (on) Color.White else Color.Black,
                        topLeft = Offset(i * (barWidth + gap), markerHeight * 1.6f),
                        size = Size(barWidth, size.height - markerHeight * 1.6f),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings()[StringKey.FlickerSpeed], style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { frequency = (frequency - 2).coerceAtLeast(FlickerRenderer.FREQUENCY_MIN) }) { Text("−") }
            Text("$frequency Hz", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { frequency = (frequency + 2).coerceAtMost(FlickerRenderer.FREQUENCY_MAX) }) { Text("+") }
            Spacer(Modifier.width(12.dp))
            Text(strings()[StringKey.FlickerSize], style = MaterialTheme.typography.bodySmall)
            Slider(value = width, onValueChange = { width = it }, valueRange = 180f..420f, modifier = Modifier.width(140.dp))
        }
        Text(
            strings()[StringKey.FlickerHint],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
