package de.findesk.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import java.util.Locale

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
                    Text(slice.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(Formats.money(slice.amount, currency), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Grouped income/expense bars per month. */
@Composable
fun IncomeExpenseBars(months: List<MonthBar>, title: String, modifier: Modifier = Modifier, currency: String = "EUR") {
    val max = months.flatMap { listOf(it.income, it.expenses) }.maxOrNull()?.toFloat()?.takeIf { it > 0f } ?: 1f
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = buildBarsDescription(title, months, currency, strings())

    Column(modifier.semantics { contentDescription = description }) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val groupWidth = size.width / months.size.coerceAtLeast(1)
            val barWidth = groupWidth * 0.3f
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            months.forEachIndexed { i, m ->
                val x = i * groupWidth + groupWidth * 0.18f
                val incomeH = m.income.toFloat() / max * size.height
                val expenseH = m.expenses.toFloat() / max * size.height
                drawRoundRect(Success, Offset(x, size.height - incomeH), Size(barWidth, incomeH), CornerRadius(3f, 3f))
                drawRoundRect(Error, Offset(x + barWidth + 2f, size.height - expenseH), Size(barWidth, expenseH), CornerRadius(3f, 3f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            months.forEach { m ->
                Text(
                    Month.of(m.month).getDisplayName(TextStyle.SHORT, strings().language.locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
            Legend(Success, strings()[StringKey.ChartIncome])
            Legend(Error, strings()[StringKey.ChartExpenses])
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Simple line chart of dated values with min/max labels and a zero line when the range crosses zero. */
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
    val values = points.map { it.second.toFloat() }
    val min = values.min()
    val max = values.max()
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val line = MaterialTheme.colorScheme.primary
    val zero = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = buildLineDescription(title, points, currency, s)

    Column(modifier.semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth()) {
            Text(Formats.money(points.maxOf { it.second }, currency), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(s[StringKey.Max], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp)) {
            fun y(v: Float) = size.height - (v - min) / span * size.height
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            if (min < 0f && max > 0f) drawLine(zero, Offset(0f, y(0f)), Offset(size.width, y(0f)), 1.5f)
            val stepX = size.width / (points.size - 1)
            val path = androidx.compose.ui.graphics.Path().apply {
                values.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(i * stepX, y(v)) }
            }
            drawPath(path, line, style = Stroke(width = 2.5f))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Formats.date(points.first().first), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(s.format(StringKey.MinAmount, Formats.money(points.minOf { it.second }, currency)), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(Formats.date(points.last().first), style = MaterialTheme.typography.labelSmall)
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
