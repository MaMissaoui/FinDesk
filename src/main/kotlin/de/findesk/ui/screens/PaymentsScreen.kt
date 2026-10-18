package de.findesk.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.findesk.banking.BankingService
import de.findesk.banking.StandingOrderInterval
import de.findesk.banking.StandingOrderSchedule
import de.findesk.banking.TransferOrder
import de.findesk.i18n.StringKey
import de.findesk.i18n.Strings
import de.findesk.i18n.strings
import de.findesk.model.AccountItem
import de.findesk.model.PaymentAction
import de.findesk.model.PaymentOrderStatus
import de.findesk.ui.components.*
import de.findesk.util.Formats
import de.findesk.util.Iban
import de.findesk.viewmodel.PaymentsViewModel

/**
 * [createViewModel] lets the screenshot test open the screen with a prepared review. [onSendingChange] reports when an
 * order is in flight, so the app can keep the user on this screen until the bank has answered.
 */
@Composable
fun PaymentsScreen(createViewModel: () -> PaymentsViewModel = { PaymentsViewModel() }, onSendingChange: (Boolean) -> Unit = {}) {
    val vm = rememberViewModel(createViewModel)
    val state by vm.state.collectAsState()
    LaunchedEffect(state.isSending) { onSendingChange(state.isSending) }
    DisposableEffect(Unit) { onDispose { onSendingChange(false) } }
    val s = strings()
    LaunchedEffect(Unit) { vm.load() }
    BankingPromptHost(vm.bankingInteraction)

    var from by remember { mutableStateOf<AccountItem?>(null) }
    var name by remember { mutableStateOf("") }
    var iban by remember { mutableStateOf("") }
    var bic by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var execution by remember { mutableStateOf(Execution.NOW) }
    var dateText by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf(StandingOrderInterval.MONTHLY) }
    var lastDateText by remember { mutableStateOf("") }
    LaunchedEffect(state.accounts) { if (from == null) from = state.accounts.firstOrNull() }

    val parsedAmount = Formats.parseAmount(amount)
    val ibanOk = Iban.isValid(iban)
    val suggestedBic = if (ibanOk) vm.bicFor(iban) else null
    val date = Formats.parseDate(dateText)
    val lastDate = lastDateText.takeIf { it.isNotBlank() }?.let(Formats::parseDate)
    val scheduleOk = when (execution) {
        Execution.NOW -> true
        Execution.ON_DATE -> date != null
        Execution.STANDING -> date != null && (lastDateText.isBlank() || lastDate != null)
    }
    // Only gate when the login is known not to support it; unknown capabilities keep everything enabled
    val capabilities = from?.let { state.bankCapabilities[it.bankAccessId] }
    val scheduledOk = capabilities?.supportsScheduledTransfer != false
    val standingOk = capabilities?.supportsStandingOrders != false
    LaunchedEffect(scheduledOk, standingOk) {
        if (execution == Execution.ON_DATE && !scheduledOk) execution = Execution.NOW
        if (execution == Execution.STANDING && !standingOk) execution = Execution.NOW
    }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(3f).verticalScroll(rememberScrollState())) {
            Text(
                s[StringKey.SepaTransfer],
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(16.dp))
            MessageBanner(state.message, state.error, vm::clearMessages)

            if (state.accounts.isEmpty()) {
                EmptyState(Icons.Default.Send, s[StringKey.NoAccounts], s[StringKey.AddAndSyncFirst])
                return@Column
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s[StringKey.From], style = MaterialTheme.typography.labelLarge)
                    DropdownSelector(
                        label = from?.let { "${it.bankName} ${Formats.iban(it.iban)} (${Formats.money(it.balance, it.currency)})" } ?: s[StringKey.ChooseAccount],
                        options = state.accounts,
                        optionLabel = { "${it.bankName} ${Formats.iban(it.iban)} (${Formats.money(it.balance, it.currency)})" },
                        onSelect = { from = it },
                    )
                    OutlinedTextField(name, { name = it.take(BankingService.MAX_NAME_LENGTH) }, label = { Text(s[StringKey.RecipientName]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        iban,
                        { iban = it.uppercase() },
                        label = { Text(s[StringKey.RecipientIban]) },
                        isError = iban.isNotBlank() && !ibanOk,
                        // Only with an error: an always-present slot reserves an empty line and makes the gaps uneven
                        supportingText = if (iban.isNotBlank() && !ibanOk) ({ Text(s[StringKey.IbanNotValid]) }) else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        bic,
                        { bic = it.uppercase() },
                        label = { Text(s[StringKey.BicOptional]) },
                        placeholder = { suggestedBic?.let { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        amount,
                        { amount = it },
                        label = { Text(s[StringKey.AmountEuro]) },
                        isError = amount.isNotBlank() && (parsedAmount == null || parsedAmount.signum() <= 0),
                        supportingText = if (amount.isNotBlank() && (parsedAmount == null || parsedAmount.signum() <= 0)) ({ Text(s[StringKey.EnterAmountGreaterThan0]) }) else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        purpose,
                        { purpose = it.take(BankingService.MAX_PURPOSE_LENGTH) },
                        label = { Text(s[StringKey.PurposeVerwendungszweck]) },
                        supportingText = { Text("${purpose.length}/${BankingService.MAX_PURPOSE_LENGTH}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(s[StringKey.Execution], style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Execution.entries.forEach { e ->
                            val supported = when (e) {
                                Execution.ON_DATE -> scheduledOk
                                Execution.STANDING -> standingOk
                                Execution.NOW -> true
                            }
                            FilterChip(selected = execution == e, enabled = supported, onClick = { execution = e }, label = { Text(s[e.label]) })
                        }
                    }
                    if (!scheduledOk || !standingOk) {
                        Text(s[StringKey.NotOfferedByBank], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (execution != Execution.NOW) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                dateText,
                                { dateText = it },
                                label = { Text(s[if (execution == Execution.STANDING) StringKey.FirstExecution else StringKey.ExecutionDate]) },
                                placeholder = { Text("dd.mm.yyyy") },
                                isError = dateText.isNotBlank() && date == null,
                                supportingText = { if (dateText.isNotBlank() && date == null) Text(s[StringKey.EnterValidDate]) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            if (execution == Execution.STANDING) {
                                OutlinedTextField(
                                    lastDateText,
                                    { lastDateText = it },
                                    label = { Text(s[StringKey.LastExecutionOptional]) },
                                    placeholder = { Text("dd.mm.yyyy") },
                                    isError = lastDateText.isNotBlank() && lastDate == null,
                                    supportingText = { if (lastDateText.isNotBlank() && lastDate == null) Text(s[StringKey.EnterValidDate]) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (execution == Execution.STANDING) {
                            DropdownSelector(
                                label = s[intervalLabel(interval)],
                                options = StandingOrderInterval.entries,
                                optionLabel = { s[intervalLabel(it)] },
                                onSelect = { interval = it },
                            )
                        }
                    }
                    Button(
                        enabled = from != null && name.isNotBlank() && ibanOk && parsedAmount != null && parsedAmount.signum() > 0 && scheduleOk && !state.isSending &&
                            (execution != Execution.ON_DATE || scheduledOk) && (execution != Execution.STANDING || standingOk),
                        onClick = {
                            val order = TransferOrder(name, iban, bic.ifBlank { suggestedBic.orEmpty() }.ifBlank { null }, parsedAmount!!, purpose)
                            when (execution) {
                                Execution.NOW -> vm.review(from!!, order)

                                Execution.ON_DATE -> vm.review(from!!, order, date = date)

                                // The execution day follows the first date (day of month, or weekday for weekly orders)
                                Execution.STANDING -> vm.review(
                                    from!!,
                                    order,
                                    schedule = StandingOrderSchedule.of(
                                        date!!,
                                        interval,
                                        if (interval == StandingOrderInterval.WEEKLY) date.dayOfWeek.value else date.dayOfMonth,
                                        lastDate,
                                    ),
                                )
                            }
                        },
                    ) { Text(s[StringKey.ReviewTransfer]) }
                }
            }
        }

        Column(Modifier.weight(2f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard(
                s[StringKey.StandingOrders],
                Modifier.fillMaxWidth(),
                actions = {
                    if (state.isLoadingStandingOrders) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    DisabledReason(
                        when {
                            from == null -> s[StringKey.HintNeedsAccount]
                            !standingOk -> s[StringKey.NotOfferedByBank]
                            else -> null
                        },
                    ) {
                        TextButton(enabled = from != null && standingOk && !state.isLoadingStandingOrders && !state.isSending, onClick = { from?.let(vm::loadStandingOrders) }) {
                            Text(s[StringKey.LoadFromBank])
                        }
                    }
                },
            ) {
                val orders = state.standingOrders?.takeIf { state.standingOrdersAccount == from?.id }
                when {
                    !standingOk -> Text(s[StringKey.NotOfferedByBank], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    orders == null -> Text(s[StringKey.StandingOrdersHint], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    orders.isEmpty() -> Text(s[StringKey.NoStandingOrders], color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                orders?.forEach { o ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(o.order.recipientName, fontWeight = FontWeight.Medium)
                            Text(
                                scheduleText(o.schedule, s) + (o.nextDate?.let { " · " + s.format(StringKey.NextOn, Formats.date(it)) } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Formats.money(o.order.amount), fontWeight = FontWeight.SemiBold)
                        IconButton(enabled = o.canDelete && !state.isSending, onClick = { vm.requestDelete(o) }) {
                            Icon(Icons.Default.Delete, contentDescription = s.format(StringKey.DeleteStandingOrderTo, o.order.recipientName))
                        }
                    }
                }
            }
            SectionCard(s[StringKey.RecentTransfersLabel], Modifier.fillMaxWidth()) {
                if (state.paymentOrders.isEmpty()) Text(s[StringKey.NoneYet], color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.paymentOrders.forEach { order ->
                    // Transfer again only fills the form; sending still goes through the review and its confirmation
                    val again = order.takeIf { it.recipientIban != null && it.amount != null && it.action != PaymentAction.STANDING_ORDER_DELETE }?.let { o ->
                        menuItem(s[StringKey.TransferAgain]) {
                            from = state.accounts.firstOrNull { it.iban == o.accountIban } ?: from
                            name = o.recipientName.orEmpty()
                            iban = o.recipientIban.orEmpty()
                            bic = ""
                            amount = o.amount?.toPlainString().orEmpty()
                            purpose = o.purpose.orEmpty()
                            execution = Execution.NOW
                        }
                    }
                    RowContextMenu(again) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(order.recipientName ?: s.paymentAction(order.action), style = MaterialTheme.typography.bodySmall)
                                order.amount?.let { Text(Formats.money(it, order.currency), style = MaterialTheme.typography.bodySmall) }
                                Text(
                                    s.paymentStatus(order.status),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = paymentStatusColor(order.status),
                                )
                            }
                            Text(
                                "${s.paymentAction(order.action)} · ${Formats.dateTime(order.createdAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            order.bankOrderId?.let { Text("${s[StringKey.PaymentOrderId]}: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            order.message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }

    state.review?.let { review ->
        val (account, order) = review
        var acknowledged by remember(review) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!state.isSending) vm.cancelReview() },
            title = { Text(s[if (review.schedule != null) StringKey.ConfirmStandingOrder else StringKey.ConfirmTransfer]) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(440.dp)) {
                    val labels = labelColumnWidth(s[StringKey.From], s[StringKey.To], s[StringKey.Iban], "BIC", s[StringKey.AmountEuro], s[StringKey.Purpose], s[StringKey.Execution])
                    ReviewRow(s[StringKey.From], "${account.bankName} ${Formats.iban(account.iban)}", labels)
                    ReviewRow(s[StringKey.To], order.recipientName, labels)
                    ReviewRow(s[StringKey.Iban], Formats.iban(Iban.normalize(order.recipientIban)), labels)
                    order.recipientBic?.let { ReviewRow("BIC", it, labels) }
                    ReviewRow(s[StringKey.AmountEuro], Formats.money(order.amount), labels)
                    ReviewRow(s[StringKey.Purpose], order.purpose.ifBlank { "—" }, labels)
                    ReviewRow(
                        s[StringKey.Execution],
                        review.schedule?.let { scheduleText(it, s) } ?: review.date?.let { s.format(StringKey.OnDate, Formats.date(it)) } ?: s[StringKey.ExecuteNow],
                        labels,
                    )
                    review.unconfirmed?.let { earlier ->
                        Text(
                            s.format(StringKey.WarningUnconfirmedOrder, earlier.recipientName.orEmpty(), Formats.money(order.amount), Formats.date(earlier.createdAt.toLocalDate())),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it }, enabled = !state.isSending)
                        Text(s[if (review.schedule != null) StringKey.StandingOrderAcknowledgement else StringKey.TransferAcknowledgement])
                    }
                    if (state.isSending) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(s[StringKey.WaitingForBank])
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = acknowledged && !state.isSending, onClick = { vm.confirmAndSend() }) { Text(s[StringKey.ConfirmAndSend]) }
            },
            dismissButton = { TextButton(enabled = !state.isSending, onClick = vm::cancelReview) { Text(s[StringKey.Back]) } },
        )
    }

    state.deleteReview?.let { o ->
        var acknowledged by remember(o) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!state.isSending) vm.cancelDelete() },
            title = { Text(s[StringKey.DeleteStandingOrder]) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(440.dp)) {
                    val labels = labelColumnWidth(s[StringKey.To], s[StringKey.Iban], s[StringKey.AmountEuro], s[StringKey.Execution])
                    ReviewRow(s[StringKey.To], o.order.recipientName, labels)
                    ReviewRow(s[StringKey.Iban], Formats.iban(Iban.normalize(o.order.recipientIban)), labels)
                    ReviewRow(s[StringKey.AmountEuro], Formats.money(o.order.amount), labels)
                    ReviewRow(s[StringKey.Execution], scheduleText(o.schedule, s), labels)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it }, enabled = !state.isSending)
                        Text(s[StringKey.DeleteStandingOrderAcknowledgement])
                    }
                    if (state.isSending) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(s[StringKey.WaitingForBank])
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = acknowledged && !state.isSending,
                    onClick = { vm.confirmDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(s[StringKey.Delete]) }
            },
            dismissButton = { TextButton(enabled = !state.isSending, onClick = vm::cancelDelete) { Text(s[StringKey.Back]) } },
        )
    }
}

private enum class Execution(val label: StringKey) { NOW(StringKey.ExecuteNow), ON_DATE(StringKey.ExecuteOnDate), STANDING(StringKey.ExecuteStanding) }

private fun intervalLabel(i: StandingOrderInterval): StringKey = when (i) {
    StandingOrderInterval.WEEKLY -> StringKey.IntervalWeekly
    StandingOrderInterval.MONTHLY -> StringKey.IntervalMonthly
    StandingOrderInterval.QUARTERLY -> StringKey.IntervalQuarterly
    StandingOrderInterval.HALF_YEARLY -> StringKey.IntervalHalfYearly
    StandingOrderInterval.YEARLY -> StringKey.IntervalYearly
}

/** "Monthly from 01.11.2026, day 1, until 31.12.2027"; intervals FinDesk doesn't offer show as "every 2 M". */
private fun scheduleText(schedule: StandingOrderSchedule, s: Strings): String {
    val interval = schedule.interval?.let { s[intervalLabel(it)] } ?: s.format(StringKey.EveryN, schedule.step, schedule.timeUnit)
    val base = s.format(StringKey.ScheduleFrom, interval, Formats.date(schedule.firstDate), schedule.executionDay)
    return base + (schedule.lastDate?.let { " " + s.format(StringKey.ScheduleUntil, Formats.date(it)) } ?: "")
}

/** Width of the widest review label in the current language, so values line up and no label breaks mid-word. */
@Composable
private fun labelColumnWidth(vararg labels: String): Dp {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontWeight = FontWeight.Medium)
    val widest = labels.maxOf { measurer.measure(it, style).size.width }
    return with(LocalDensity.current) { widest.toDp() } + 12.dp
}

@Composable
private fun ReviewRow(label: String, value: String, labelWidth: Dp) {
    Row {
        Text(label, fontWeight = FontWeight.Medium, softWrap = false, modifier = Modifier.width(labelWidth))
        Text(value)
    }
}

@Composable
private fun paymentStatusColor(status: String) = when (status) {
    PaymentOrderStatus.ACCEPTED -> de.findesk.ui.theme.Success
    PaymentOrderStatus.REJECTED, PaymentOrderStatus.UNKNOWN -> MaterialTheme.colorScheme.error
    PaymentOrderStatus.PENDING, PaymentOrderStatus.TAN_REQUIRED -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
