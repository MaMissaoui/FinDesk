package de.findesk.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.Strings
import de.findesk.i18n.strings
import de.findesk.ui.theme.Error
import de.findesk.ui.theme.Success
import de.findesk.util.Formats
import de.findesk.viewmodel.ChartSlice
import de.findesk.viewmodel.MonthBar
import java.math.BigDecimal
import java.time.Month
import java.time.format.TextStyle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Donut chart with a legend; slices beyond [maxSlices] are folded into "Other". */
@Composable
fun DonutChart(slices: List<ChartSlice>, title: String, modifier: Modifier = Modifier, maxSlices: Int = 6, currency: String = "EUR") {
    val s = strings()
    val shown = if (slices.size > maxSlices) {
        slices.take(maxSlices - 1) + ChartSlice(s[StringKey.ChartOther], slices.drop(maxSlices - 1).fold(BigDecimal.ZERO) { a, s -> a + s.amount }, "#9E9E9E")
    } else {
        slices
    }
    val total = shown.fold(BigDecimal.ZERO) { a, s -> a + s.amount }.toFloat()
    val colors = shown.map { categoryColor(it.color, it.label) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val description = buildDonutDescription(title, shown, currency, strings())

    Row(modifier = modifier.semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
            Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val stroke = size.minDimension * 0.18f
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                if (total <= 0f) {
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                } else {
                    var start = -90f
                    shown.forEachIndexed { i, slice ->
                        val sweep = slice.amount.toFloat() / total * 360f
                        drawArc(colors[i], start, sweep - 1f.coerceAtMost(sweep), false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                        start += sweep
                    }
                }
            }
            Text(
                Formats.money(shown.fold(BigDecimal.ZERO) { a, x -> a + x.amount }, currency),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f, fill = false).widthIn(max = 360.dp)) {
            shown.forEachIndexed { i, slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(colors[i], CircleShape))
                    Spacer(Modifier.width(8.dp))
                    // Ellipsis and a gap, so a long name never runs into its amount in a narrow window
                    Text(slice.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(Formats.money(slice.amount, currency), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** The smallest 1, 2, 2.5 or 5 times a power of ten that is at least [value], so axis labels read as round numbers. */
internal fun niceCeiling(value: Double): Double {
    if (value <= 0.0) return 1.0
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(value)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= value * (1 - 1e-9) } * magnitude
}

/**
 * Round axis ticks covering [min]..[max], lowest first: the step is a 1/2/2.5/5 × 10ⁿ value, the first tick is the
 * largest multiple of it at or below [min], and ticks continue upwards until one reaches [max]. A flat range is
 * widened by one unit either side so the line still has height.
 */
internal fun niceTicks(min: Double, max: Double, count: Int = 3): List<Double> {
    if (count <= 0) return emptyList()
    var low = min
    var high = max
    if (low > high) {
        val swap = low
        low = high
        high = swap
    }
    if (low == high) {
        low -= 1.0
        high += 1.0
    }
    val step = niceCeiling((high - low) / count)
    val ticks = mutableListOf<Double>()
    var tick = floor(low / step) * step
    fun clean(value: Double) = if (abs(value) < step * 1e-9) 0.0 else value
    ticks += clean(tick)
    while (tick < high - step * 1e-9) {
        tick += step
        ticks += clean(tick)
    }
    return ticks
}

/** The index of the point nearest to a pointer at [x] when [count] points are spread evenly from 0 to [width]. */
internal fun nearestIndex(x: Float, width: Float, count: Int): Int {
    if (count <= 1 || width <= 0f) return 0
    return (x / width * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

/** Grouped income/expense bars per month, on an axis from 0 to a round maximum. */
@Composable
fun IncomeExpenseBars(months: List<MonthBar>, title: String, modifier: Modifier = Modifier, currency: String = "EUR") {
    val s = strings()
    val max = months.flatMap { listOf(it.income, it.expenses) }.maxOfOrNull { it.toDouble() } ?: 0.0
    val top = niceCeiling(max)
    // Without data only the baseline is labelled; a scale of €0.5 steps would just repeat "€1"
    val ticks = if (max > 0.0) listOf(top, top / 2, 0.0) else listOf(0.0)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val hoverBand = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val description = buildBarsDescription(title, months, currency, s)
    val axisWidth = 64.dp
    // Gridlines sit at the vertical centre of their labels
    val labelInset = 8.dp
    val hoverStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var hovered by remember(months) { mutableStateOf<Int?>(null) }

    Column(modifier.semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(axisWidth).fillMaxHeight().padding(end = 8.dp), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.End) {
                ticks.forEachIndexed { i, tick ->
                    if (i > 0) Spacer(Modifier.weight(1f))
                    Text(
                        // Below €10 the half-way tick needs cents to differ from its neighbours
                        if (top < 10) Formats.money(BigDecimal.valueOf(tick), currency) else Formats.wholeMoney(BigDecimal.valueOf(tick), currency),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(vertical = labelInset)) {
                val plotWidth = constraints.maxWidth.toFloat()
                val plotHeight = constraints.maxHeight.toFloat()
                Canvas(
                    Modifier.fillMaxSize().pointerInput(months.size) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Move -> {
                                        if (months.isEmpty()) {
                                            hovered = null
                                        } else {
                                            val x = event.changes.firstOrNull()?.position?.x ?: 0f
                                            val groupWidth = size.width / months.size
                                            hovered = (x / groupWidth).toInt().coerceIn(0, months.lastIndex)
                                        }
                                    }

                                    PointerEventType.Exit -> hovered = null
                                }
                            }
                        }
                    },
                ) {
                    val groupWidth = size.width / months.size.coerceAtLeast(1)
                    val barWidth = groupWidth * 0.3f
                    if (max > 0.0) listOf(0f, 0.5f).forEach { f -> drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), strokeWidth = 1f) }
                    hovered?.let { index ->
                        drawRect(hoverBand, Offset(index * groupWidth, 0f), Size(groupWidth, size.height))
                    }
                    months.forEachIndexed { i, m ->
                        val x = i * groupWidth + groupWidth * 0.18f
                        val incomeH = (m.income.toDouble() / top).toFloat() * size.height
                        val expenseH = (m.expenses.toDouble() / top).toFloat() * size.height
                        drawRoundedTopBar(Success, x, barWidth, incomeH)
                        drawRoundedTopBar(Error, x + barWidth + 2f, barWidth, expenseH)
                    }
                    drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
                }
                val hover = hovered
                if (hover != null && hover in months.indices) {
                    val month = months[hover]
                    val titleText = "${Month.of(month.month).getDisplayName(TextStyle.SHORT, s.language.locale)} ${month.year}"
                    val incomeText = Formats.money(month.income, currency, signed = true)
                    val expenseText = Formats.money(month.expenses.negate(), currency, signed = true)
                    val lines = listOf(titleText, incomeText, expenseText)
                    val sizes = lines.map { measurer.measure(it, hoverStyle) }
                    val paddingX = with(density) { 8.dp.toPx() }
                    val paddingY = with(density) { 6.dp.toPx() }
                    val labelWidth = sizes.maxOf { it.size.width }.toFloat() + paddingX * 2
                    val labelHeight = sizes.sumOf { it.size.height }.toFloat() + paddingY * 2
                    val gap = with(density) { 8.dp.toPx() }
                    val centerX = (hover + 0.5f) * (plotWidth / months.size)
                    val wantedX = if (centerX + gap + labelWidth <= plotWidth) centerX + gap else centerX - gap - labelWidth
                    val left = wantedX.coerceIn(0f, (plotWidth - labelWidth).coerceAtLeast(0f)).roundToInt()
                    val incomeH = (month.income.toDouble() / top).toFloat() * plotHeight
                    val expenseH = (month.expenses.toDouble() / top).toFloat() * plotHeight
                    val topY = plotHeight - maxOf(incomeH, expenseH)
                    val topOffset = (topY - labelHeight - gap).coerceIn(0f, (plotHeight - labelHeight).coerceAtLeast(0f)).roundToInt()
                    Surface(
                        modifier = Modifier.offset { IntOffset(left, topOffset) },
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(titleText, style = hoverStyle, color = MaterialTheme.colorScheme.inverseOnSurface)
                            Text(incomeText, style = hoverStyle, color = MaterialTheme.colorScheme.inverseOnSurface)
                            Text(expenseText, style = hoverStyle, color = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = axisWidth, top = 2.dp)) {
            months.forEach { m ->
                Text(
                    Month.of(m.month).getDisplayName(TextStyle.SHORT, s.language.locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
            Legend(Success, s[StringKey.ChartIncome])
            Legend(Error, s[StringKey.ChartExpenses])
        }
    }
}

/** A bar with 3 px rounded top corners and a square base, so it sits on the axis. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundedTopBar(color: Color, x: Float, width: Float, height: Float) {
    if (height <= 0f || width <= 0f) return
    val radius = min(3f, min(width / 2f, height))
    val corner = CornerRadius(radius, radius)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                Rect(x, size.height - height, x + width, size.height),
                topLeft = corner,
                topRight = corner,
                bottomRight = CornerRadius.Zero,
                bottomLeft = CornerRadius.Zero,
            ),
        )
    }
    drawPath(path, color)
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Line chart of dated values on a round axis, with the current balance marked and a pointer guide on hover. */
@Composable
fun BalanceLineChart(
    points: List<Pair<java.time.LocalDate, BigDecimal>>,
    title: String = strings()[StringKey.BalanceHistory],
    modifier: Modifier = Modifier,
    currency: String = "EUR",
) {
    val s = strings()
    if (points.size < 2) {
        Text(s[StringKey.NotEnoughData], modifier = modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val values = points.map { it.second.toDouble() }
    val ticks = niceTicks(values.min(), values.max())
    val low = ticks.first()
    val high = ticks.last()
    val span = (high - low).takeIf { it > 0.0 } ?: 1.0
    val step = if (ticks.size > 1) ticks[1] - ticks[0] else 0.0
    val labels = ticks.asReversed().map { tick ->
        if (step < 10.0) Formats.money(BigDecimal.valueOf(tick), currency) else Formats.wholeMoney(BigDecimal.valueOf(tick), currency)
    }
    val line = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val zero = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val outline = MaterialTheme.colorScheme.outline
    val description = buildLineDescription(title, points, currency, s)
    val valueStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val hoverStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val axisWidth = 64.dp
    val labelInset = 8.dp
    val horizontalInset = 4.dp
    val lastValueText = Formats.money(points.last().second, currency)
    val valueSize = measurer.measure(lastValueText, valueStyle)
    var hovered by remember(points) { mutableStateOf<Int?>(null) }

    Column(modifier.semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            ChartYAxis(labels, Modifier.width(axisWidth).fillMaxHeight().padding(end = 8.dp), labelInset)
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(vertical = labelInset)) {
                val plotWidth = constraints.maxWidth.toFloat()
                val plotHeight = constraints.maxHeight.toFloat()
                val sideInset = with(density) { horizontalInset.toPx() }
                val usableWidth = (plotWidth - 2 * sideInset).coerceAtLeast(1f)
                fun xAt(index: Int): Float = sideInset + index * usableWidth / (points.size - 1)
                fun yAt(value: Double): Float = plotHeight - ((value - low) / span).toFloat() * plotHeight

                Canvas(
                    Modifier.fillMaxSize().pointerInput(points.size, plotWidth) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Move -> {
                                        val x = event.changes.firstOrNull()?.position?.x ?: continue
                                        hovered = nearestIndex(x - sideInset, usableWidth, points.size)
                                    }

                                    PointerEventType.Exit -> hovered = null
                                }
                            }
                        }
                    },
                ) {
                    ticks.forEach { tick ->
                        drawLine(grid, Offset(0f, yAt(tick)), Offset(size.width, yAt(tick)), strokeWidth = 1f)
                    }
                    if (low < 0.0 && high > 0.0) {
                        drawLine(zero, Offset(0f, yAt(0.0)), Offset(size.width, yAt(0.0)), strokeWidth = 1.5f)
                    }
                    val linePath = Path()
                    values.forEachIndexed { index, value ->
                        if (index == 0) linePath.moveTo(xAt(index), yAt(value)) else linePath.lineTo(xAt(index), yAt(value))
                    }
                    val fillPath = Path().apply {
                        addPath(linePath)
                        lineTo(xAt(points.lastIndex), size.height)
                        lineTo(xAt(0), size.height)
                        close()
                    }
                    drawPath(
                        fillPath,
                        Brush.verticalGradient(
                            listOf(line.copy(alpha = 0.18f), line.copy(alpha = 0.02f)),
                            startY = 0f,
                            endY = size.height,
                        ),
                    )
                    drawPath(linePath, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

                    val lastX = xAt(points.lastIndex)
                    val lastY = yAt(values.last())
                    // An 8 dp dot in a 2 dp surface ring: visible at a glance, and set off from the line under it
                    drawCircle(surface, radius = 6.dp.toPx(), center = Offset(lastX, lastY))
                    drawCircle(line, radius = 4.dp.toPx(), center = Offset(lastX, lastY))

                    hovered?.let { index ->
                        if (index in points.indices) {
                            val x = xAt(index)
                            val y = yAt(values[index])
                            drawLine(outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                            drawCircle(surface, radius = 6.dp.toPx(), center = Offset(x, y))
                            drawCircle(line, radius = 4.dp.toPx(), center = Offset(x, y))
                        }
                    }
                }

                val hover = hovered
                if (hover != null && hover in points.indices) {
                    val dateText = Formats.date(points[hover].first)
                    val moneyText = Formats.money(points[hover].second, currency)
                    val dateSize = measurer.measure(dateText, hoverStyle)
                    val moneySize = measurer.measure(moneyText, hoverStyle)
                    val paddingX = with(density) { 8.dp.toPx() }
                    val paddingY = with(density) { 6.dp.toPx() }
                    val labelWidth = maxOf(dateSize.size.width, moneySize.size.width).toFloat() + paddingX * 2
                    val labelHeight = (dateSize.size.height + moneySize.size.height).toFloat() + paddingY * 2
                    val gap = with(density) { 8.dp.toPx() }
                    val pointX = xAt(hover)
                    val pointY = yAt(values[hover])
                    val wantedX = if (pointX + gap + labelWidth <= plotWidth) pointX + gap else pointX - gap - labelWidth
                    val left = wantedX.coerceIn(0f, (plotWidth - labelWidth).coerceAtLeast(0f)).roundToInt()
                    val top = (pointY - labelHeight / 2).coerceIn(0f, (plotHeight - labelHeight).coerceAtLeast(0f)).roundToInt()
                    Surface(
                        modifier = Modifier.offset { IntOffset(left, top) },
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(dateText, style = hoverStyle, color = MaterialTheme.colorScheme.inverseOnSurface)
                            Text(moneyText, style = hoverStyle, color = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                    }
                } else {
                    val lastX = xAt(points.lastIndex)
                    val lastY = yAt(values.last())
                    val gap = with(density) { 4.dp.toPx() }
                    val topLimit = with(density) { 20.dp.toPx() }
                    val valueWidth = valueSize.size.width.toFloat()
                    val valueHeight = valueSize.size.height.toFloat()
                    val valueX = (lastX - valueWidth - gap).coerceAtLeast(0f)
                    val valueY = if (lastY < topLimit) {
                        (lastY + gap).coerceAtMost((plotHeight - valueHeight).coerceAtLeast(0f))
                    } else {
                        (lastY - valueHeight - gap).coerceAtLeast(0f)
                    }
                    Text(
                        lastValueText,
                        style = valueStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.offset { IntOffset(valueX.roundToInt(), valueY.roundToInt()) },
                    )
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = axisWidth, top = 2.dp)) {
            Text(
                Formats.date(points.first().first),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            if (maxWidth > 360.dp) {
                Text(
                    Formats.date(points[points.size / 2].first),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Text(
                Formats.date(points.last().first),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** Right-aligned y-axis labels, each vertically centred on its gridline. */
@Composable
private fun ChartYAxis(labels: List<String>, modifier: Modifier, inset: Dp) {
    val insetPx = with(LocalDensity.current) { inset.toPx() }
    Layout(
        content = {
            labels.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            val span = (height - 2 * insetPx).coerceAtLeast(0f)
            placeables.forEachIndexed { index, placeable ->
                val fraction = if (placeables.lastIndex == 0) 0f else index.toFloat() / placeables.lastIndex
                val y = (insetPx + fraction * span - placeable.height / 2f).roundToInt()
                placeable.placeRelative(width - placeable.width, y)
            }
        }
    }
}

private fun buildBarsDescription(title: String, months: List<MonthBar>, currency: String, s: Strings): String {
    if (months.isEmpty()) return "$title: ${s[StringKey.NotEnoughData]}"
    val parts = months.take(5).map {
        "${Month.of(it.month).getDisplayName(TextStyle.SHORT, s.language.locale)} ${Formats.money(it.income, currency)} / ${Formats.money(it.expenses, currency)}"
    }
    val more = months.size - 5
    val suffix = if (more > 0) " · ${s.format(StringKey.AndMore, more)}" else ""
    return "$title: ${parts.joinToString(", ")}$suffix"
}

private fun buildDonutDescription(title: String, slices: List<ChartSlice>, currency: String, s: Strings): String {
    if (slices.isEmpty()) return "$title: ${s[StringKey.NotEnoughData]}"
    val parts = slices.take(5).map { "${it.label} ${Formats.money(it.amount, currency)}" }
    val more = slices.size - 5
    val suffix = if (more > 0) " · ${s.format(StringKey.AndMore, more)}" else ""
    return "$title: ${parts.joinToString(", ")}$suffix · ${Formats.money(slices.fold(BigDecimal.ZERO) { a, x -> a + x.amount }, currency)}"
}

private fun buildLineDescription(title: String, points: List<Pair<java.time.LocalDate, BigDecimal>>, currency: String, s: Strings): String {
    if (points.size < 2) return "$title: ${s[StringKey.NotEnoughData]}"
    val min = points.minOf { it.second }
    val max = points.maxOf { it.second }
    return "$title: ${Formats.date(points.first().first)} – ${Formats.date(points.last().first)} · ${s.format(StringKey.MinAmount, Formats.money(min, currency))} · ${s[StringKey.Max]} ${Formats.money(max, currency)}"
}
