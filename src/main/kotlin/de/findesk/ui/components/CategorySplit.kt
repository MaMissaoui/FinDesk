package de.findesk.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.model.CategoryShare
import de.findesk.util.Formats
import de.findesk.viewmodel.DashboardViewModel
import java.math.BigDecimal

/**
 * One horizontal 100 % bar plus one legend row per category: share, name, percentage and amount. Categories beyond
 * [maxRows] fold into one muted "more" row. Rows memo the category they came from, so [onSelect] can deep-link into
 * the booking list ([categoryId] == null means uncategorized).
 */
@Composable
fun CategorySplit(
    slices: List<CategoryShare>,
    currency: String,
    excludedTransfers: BigDecimal,
    onSelect: ((CategoryShare) -> Unit)?,
    maxRows: Int = 8,
    modifier: Modifier = Modifier,
) {
    val s = strings()
    // Largest first, like the amounts underneath; sorting here keeps it true whatever the caller did
    val sorted = slices.sortedByDescending { it.amount }
    val total = sorted.fold(BigDecimal.ZERO) { acc, slice -> acc + slice.amount }
    val shares = Formats.shares(sorted.map { it.amount })

    Column(modifier) {
        val track = MaterialTheme.colorScheme.surfaceVariant
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(track),
        ) {
            if (total.signum() > 0) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    sorted.forEach { slice ->
                        val fraction = slice.amount.toFloat() / total.toFloat()
                        if (fraction > 0f) {
                            Box(Modifier.weight(fraction).fillMaxHeight().background(color = colorOf(slice)))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val visible = sorted.take(maxRows)
            val rest = sorted.drop(maxRows)
            visible.forEachIndexed { i, slice -> SplitRow(slice, shares[i], currency, onSelect) }
            if (rest.isNotEmpty()) {
                val restPercent = shares.drop(maxRows).sum()
                MutedRow(
                    label = s.format(StringKey.CategorySplitMore, rest.size),
                    percent = Formats.percent(restPercent / 100.0),
                    amount = Formats.money(rest.fold(BigDecimal.ZERO) { acc, slice -> acc + slice.amount }, currency),
                )
            }
        }
        if (excludedTransfers.signum() > 0) {
            Text(
                s.format(StringKey.CategorySplitTransfers, Formats.money(excludedTransfers, currency)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** One legend row: colour dot, name, fixed-width right-aligned percentage, right-aligned amount. */
@Composable
private fun SplitRow(slice: CategoryShare, percent: Int, currency: String, onSelect: ((CategoryShare) -> Unit)?) {
    val s = strings()
    val name = slice.categoryId?.let { slice.name } ?: s[StringKey.Uncategorized]
    val percentText = Formats.percent(percent / 100.0)
    val amountText = Formats.money(slice.amount, currency)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                // Only a clickable row takes focus and highlights on hover; clickable() makes it focusable
                if (onSelect != null) {
                    Modifier
                        .hoverable(interactionSource = interaction)
                        .background(if (hovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable(interactionSource = interaction, indication = LocalIndication.current) { onSelect(slice) }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$name, $percentText, $amountText" },
    ) {
        Box(Modifier.size(10.dp).background(colorOf(slice), CircleShape))
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            percentText,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
        Text(
            amountText,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(96.dp),
        )
        if (onSelect != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun MutedRow(label: String, percent: String, amount: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            percent,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
        Text(
            amount,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(96.dp),
        )
    }
}

/** The category's colour; uncategorized always the neutral grey of the dashboard's badge. */
private fun colorOf(slice: CategoryShare): Color = if (slice.categoryId == null) {
    categoryColor(DashboardViewModel.UNCATEGORIZED_COLOR)
} else {
    categoryColor(slice.color, slice.name)
}
