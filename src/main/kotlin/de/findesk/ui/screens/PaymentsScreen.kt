package de.findesk.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.Screen
import de.findesk.util.Formats
import de.findesk.util.Iban
import de.findesk.viewmodel.PaymentsViewModel

/**
 * [createViewModel] lets the screenshot test open the screen with a prepared review. [onSendingChange] reports when an
 * order is in flight, so the app can keep the user on this screen until the bank has answered.
 */
@Composable
fun PaymentsScreen(
    createViewModel: () -> PaymentsViewModel = { PaymentsViewModel() },
    onSendingChange: (Boolean) -> Unit = {},
    onNavigate: (Screen, NavigationArgs) -> Unit = { _, _ -> },
) {
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
        Execution.INSTANT -> true
        Execution.ON_DATE -> date != null
        Execution.STANDING -> date != null && (lastDateText.isBlank() || lastDate != null)
    }
    // Only gate when the login is known not to support it; unknown capabilities keep everything enabled
    val capabilities = from?.let { state.bankCapabilities[it.bankAccessId] }
    val scheduledOk = capabilities?.supportsScheduledTransfer != false
    val standingOk = capabilities?.supportsStandingOrders != false
    // Instant transfers need the bank's explicit answer: an unknown set is not enough, because the order reaches the
    // bank within seconds
    val instantOk = capabilities?.supportsInstantTransfer == true
    LaunchedEffect(scheduledOk, standingOk, instantOk) {
        if (execution == Execution.ON_DATE && !scheduledOk) execution = Execution.NOW
        if (execution == Execution.STANDING && !standingOk) execution = Execution.NOW
        if (execution == Execution.INSTANT && !instantOk) execution = Execution.NOW
    }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        if (state.accounts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Default.Send, s[StringKey.NoAccounts], s[StringKey.AddAndSyncFirst]) {
                    Button(onClick = { onNavigate(Screen.ACCOUNTS, NavigationArgs()) }) { Text(s[StringKey.AddBank]) }
                }
            }
            return@Row
        }

        Column(Modifier.weight(3f).verticalScroll(rememberScrollState())) {
            MessageBanner(state.message, state.error, vm::clearMessages)

            SectionCard(s[StringKey.SepaTransfer]) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s[StringKey.From], style = MaterialTheme.typography.labelLarge)
                    AccountPicker(selected = from, accounts = state.accounts, onSelect = { from = it })
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
                    SingleChoiceRow(
                        options = Execution.entries,
                        selected = execution,
                        label = { s[it.label] },
                        onSelect = { execution = it },
                        enabled = { e ->
                            when (e) {
                                Execution.ON_DATE -> scheduledOk
                                Execution.STANDING -> standingOk
                                Execution.INSTANT -> instantOk
                                Execution.NOW -> true
                            }
                        },
                    )
                    if (!scheduledOk || !standingOk) {
                        Text(s[StringKey.NotOfferedByBank], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Why the Instant option is off: a bank not synced yet, or one that answered it doesn't offer it
                    if (!instantOk) {
                        Text(
                            if (capabilities == null) s[StringKey.InstantSyncUnknown] else s[StringKey.InstantNotOffered],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (execution == Execution.ON_DATE || execution == Execution.STANDING) {
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
                            (execution != Execution.ON_DATE || scheduledOk) && (execution != Execution.STANDING || standingOk) &&
                            (execution != Execution.INSTANT || instantOk),
                        onClick = {
                            val order = TransferOrder(name, iban, bic.ifBlank { suggestedBic.orEmpty() }.ifBlank { null }, parsedAmount!!, purpose)
                            when (execution) {
                                Execution.NOW -> vm.review(from!!, order)

                                Execution.INSTANT -> vm.review(from!!, order, instant = true)

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
                        IconButton(enabled = o.canChange && !state.isSending, onClick = { vm.requestEdit(o) }) {
                            Icon(Icons.Default.Edit, contentDescription = s[StringKey.Edit])
                        }
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
                            order.message?.let {
                                // An accepted order's stored detail is its raw bank status code, not an error
                                val accepted = order.status == PaymentOrderStatus.ACCEPTED
                                Text(
                                    if (accepted) s.format(StringKey.PaymentBankStatus, it) else it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (accepted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                )
                            }
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
        // One callout above the figure: the unconfirmed earlier order and, for an instant transfer, the fact that
        // the money is gone within seconds and cannot be recalled
        val warnings = buildList {
            review.unconfirmed?.let { earlier ->
                add(s.format(StringKey.WarningUnconfirmedOrder, earlier.recipientName.orEmpty(), Formats.money(order.amount), Formats.date(earlier.createdAt.toLocalDate())))
            }
            if (review.instant) add(s[StringKey.InstantTransferWarning])
        }
        AlertDialog(
            onDismissRequest = { if (!state.isSending) vm.cancelReview() },
            title = {
                Text(
                    s[
                        when {
                            review.editOf != null -> StringKey.ChangeStandingOrder
                            review.schedule != null -> StringKey.ConfirmStandingOrder
                            review.instant -> StringKey.InstantTransferReview
                            else -> StringKey.ConfirmTransfer
                        },
                    ],
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(440.dp)) {
                    ReviewHeader(
                        amount = Formats.money(order.amount),
                        recipient = order.recipientName,
                        warning = warnings.joinToString("\n").ifEmpty { null },
                    )
                    // An edit's change rows share the label column, so their labels size it too
                    val changeLabels = if (review.editOf != null) listOf(s[StringKey.AmountEuro], s[StringKey.IntervalLabel], s[StringKey.ScheduleDay], s[StringKey.LastExecution]) else emptyList()
                    val labels = labelColumnWidth(s[StringKey.From], s[StringKey.To], s[StringKey.Iban], "BIC", s[StringKey.Purpose], s[StringKey.Execution], *changeLabels.toTypedArray())
                    ReviewRow(s[StringKey.From], accountLabel(account), labels)
                    ReviewRow(s[StringKey.To], order.recipientName, labels)
                    ReviewRow(s[StringKey.Iban], Formats.iban(Iban.normalize(order.recipientIban)), labels)
                    order.recipientBic?.let { ReviewRow("BIC", it, labels) }
                    ReviewRow(s[StringKey.Purpose], order.purpose.ifBlank { "—" }, labels)
                    ReviewRow(
                        s[StringKey.Execution],
                        when {
                            review.schedule != null -> scheduleText(review.schedule, s)
                            review.instant -> s[StringKey.ExecuteInstant]
                            else -> review.date?.let { s.format(StringKey.OnDate, Formats.date(it)) } ?: s[StringKey.ExecuteNow]
                        },
                        labels,
                    )
                    // An edited standing order shows old and new values side by side, for every changed field
                    review.editOf?.let { existing ->
                        val newSchedule = review.schedule ?: return@let
                        Spacer(Modifier.height(4.dp))
                        val oldOrder = existing.order
                        val oldSchedule = existing.schedule
                        if (oldOrder.amount != order.amount) ReviewChangeRow(s[StringKey.AmountEuro], Formats.money(oldOrder.amount), Formats.money(order.amount), labels)
                        if (oldOrder.purpose.trim() != order.purpose.trim()) ReviewChangeRow(s[StringKey.Purpose], oldOrder.purpose.ifBlank { "—" }, order.purpose.ifBlank { "—" }, labels)
                        if (oldSchedule.interval != newSchedule.interval) ReviewChangeRow(s[StringKey.IntervalLabel], intervalText(oldSchedule, s), intervalText(newSchedule, s), labels)
                        if (oldSchedule.executionDay != newSchedule.executionDay) ReviewChangeRow(s[StringKey.ScheduleDay], "${oldSchedule.executionDay}", "${newSchedule.executionDay}", labels)
                        if (oldSchedule.lastDate != newSchedule.lastDate) ReviewChangeRow(s[StringKey.LastExecution], oldSchedule.lastDate?.let(Formats::date) ?: "—", newSchedule.lastDate?.let(Formats::date) ?: "—", labels)
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

    // While the change review of an edit is open it sits on top; the form stays for the way back
    state.editReview?.takeIf { state.review == null }?.let { o ->
        val account = state.accounts.firstOrNull { Iban.normalize(it.iban) == Iban.normalize(o.fromIban) }
        // The form's values live in the view model's draft, so Back from the change review restores them
        val draft = state.editDraft ?: return@let
        // Amount, purpose and schedule are editable; the recipient is not (standingOrderForm holds the current values)
        val parsedAmount = Formats.parseAmount(draft.amountText)
        val firstDate = Formats.parseDate(draft.firstDateText)
        val lastDate = draft.lastDateText.takeIf { it.isNotBlank() }?.let(Formats::parseDate)
        val schedule = vm.editedSchedule(o, firstDate, draft.interval, draft.executionDayText, lastDate)
        val order = parsedAmount?.let {
            TransferOrder(o.order.recipientName, o.order.recipientIban, o.order.recipientBic, it, draft.purpose)
        }
        val editError = order?.let { o2 -> schedule?.let { s2 -> vm.validateEdited(o, o2, s2) } }
        val unchanged = order?.let { o2 -> schedule?.let { s2 -> vm.editUnchanged(o, o2, s2) } } == true
        // A running order's start must stay as it is; only a future start can be moved
        val firstDateEditable = o.schedule.firstDate.isAfter(java.time.LocalDate.now())

        AlertDialog(
            onDismissRequest = { if (!state.isSending) vm.cancelEdit() },
            title = { Text(s[StringKey.EditStandingOrder]) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.width(440.dp).verticalScroll(rememberScrollState()),
                ) {
                    // The recipient identifies the order at the bank and cannot be edited
                    OutlinedTextField(
                        o.order.recipientName,
                        {},
                        label = { Text(s[StringKey.RecipientName]) },
                        enabled = false,
                        supportingText = { Text(s[StringKey.ErrorEditRecipientFixed]) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        o.order.recipientIban,
                        {},
                        label = { Text(s[StringKey.RecipientIban]) },
                        enabled = false,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        o.order.recipientBic.orEmpty(),
                        {},
                        label = { Text(s[StringKey.BicOptional]) },
                        enabled = false,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        draft.amountText,
                        { vm.updateEditDraft(draft.copy(amountText = it)) },
                        label = { Text(s[StringKey.AmountEuro]) },
                        isError = draft.amountText.isNotBlank() && (parsedAmount == null || parsedAmount.signum() <= 0),
                        supportingText = if (draft.amountText.isNotBlank() && (parsedAmount == null || parsedAmount.signum() <= 0)) ({ Text(s[StringKey.EnterAmountGreaterThan0]) }) else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        draft.purpose,
                        { vm.updateEditDraft(draft.copy(purpose = it.take(BankingService.MAX_PURPOSE_LENGTH))) },
                        label = { Text(s[StringKey.PurposeVerwendungszweck]) },
                        supportingText = { Text("${draft.purpose.length}/${BankingService.MAX_PURPOSE_LENGTH}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            draft.firstDateText,
                            { vm.updateEditDraft(draft.copy(firstDateText = it)) },
                            label = { Text(s[StringKey.FirstExecution]) },
                            placeholder = { Text("dd.mm.yyyy") },
                            isError = draft.firstDateText.isNotBlank() && firstDate == null,
                            supportingText = { if (draft.firstDateText.isNotBlank() && firstDate == null) Text(s[StringKey.EnterValidDate]) },
                            singleLine = true,
                            enabled = firstDateEditable,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            draft.lastDateText,
                            { vm.updateEditDraft(draft.copy(lastDateText = it)) },
                            label = { Text(s[StringKey.LastExecutionOptional]) },
                            placeholder = { Text("dd.mm.yyyy") },
                            isError = draft.lastDateText.isNotBlank() && lastDate == null,
                            supportingText = { if (draft.lastDateText.isNotBlank() && lastDate == null) Text(s[StringKey.EnterValidDate]) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (o.schedule.interval == null) {
                        // The bank's interval is not one FinDesk offers (e.g. every 2 months): it stays as reported, so read-only
                        OutlinedTextField(
                            intervalText(o.schedule, s),
                            {},
                            label = { Text(s[StringKey.IntervalLabel]) },
                            enabled = false,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        DropdownSelector(
                            label = s[intervalLabel(draft.interval)],
                            options = StandingOrderInterval.entries,
                            optionLabel = { s[intervalLabel(it)] },
                            onSelect = { vm.updateEditDraft(draft.copy(interval = it)) },
                        )
                    }
                    OutlinedTextField(
                        draft.executionDayText,
                        { vm.updateEditDraft(draft.copy(executionDayText = it.filter { c -> c.isDigit() }.take(2))) },
                        label = { Text(s[StringKey.ScheduleDay]) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (editError != null) Text(editError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    enabled = account != null && parsedAmount != null && parsedAmount.signum() > 0 && schedule != null && editError == null && !state.isSending && !unchanged,
                    onClick = {
                        account?.let { vm.reviewEdit(it, o, order!!, schedule!!) }
                    },
                ) { Text(s[StringKey.ReviewTransfer]) }
            },
            dismissButton = { TextButton(enabled = !state.isSending, onClick = vm::cancelEdit) { Text(s[StringKey.Back]) } },
        )
    }

    state.deleteReview?.let { o ->
        var acknowledged by remember(o) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!state.isSending) vm.cancelDelete() },
            title = { Text(s[StringKey.DeleteStandingOrder]) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(440.dp)) {
                    ReviewHeader(
                        amount = Formats.money(o.order.amount),
                        recipient = o.order.recipientName,
                        warning = null,
                    )
                    val labels = labelColumnWidth(s[StringKey.To], s[StringKey.Iban], s[StringKey.Execution])
                    ReviewRow(s[StringKey.To], o.order.recipientName, labels)
                    ReviewRow(s[StringKey.Iban], Formats.iban(Iban.normalize(o.order.recipientIban)), labels)
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

private enum class Execution(val label: StringKey) { NOW(StringKey.ExecuteNow), INSTANT(StringKey.ExecuteInstant), ON_DATE(StringKey.ExecuteOnDate), STANDING(StringKey.ExecuteStanding) }

private fun intervalLabel(i: StandingOrderInterval): StringKey = when (i) {
    StandingOrderInterval.WEEKLY -> StringKey.IntervalWeekly
    StandingOrderInterval.MONTHLY -> StringKey.IntervalMonthly
    StandingOrderInterval.QUARTERLY -> StringKey.IntervalQuarterly
    StandingOrderInterval.HALF_YEARLY -> StringKey.IntervalHalfYearly
    StandingOrderInterval.YEARLY -> StringKey.IntervalYearly
}

/** "Monthly"; intervals FinDesk doesn't offer show as "every 2 M". */
private fun intervalText(schedule: StandingOrderSchedule, s: Strings): String = schedule.interval?.let { s[intervalLabel(it)] } ?: s.format(StringKey.EveryN, schedule.step, schedule.timeUnit)

/** "Monthly from 01.11.2026, day 1, until 31.12.2027". */
private fun scheduleText(schedule: StandingOrderSchedule, s: Strings): String {
    val base = s.format(StringKey.ScheduleFrom, intervalText(schedule, s), Formats.date(schedule.firstDate), schedule.executionDay)
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

/**
 * The figure the user is about to approve: amount first, then the recipient, with the unconfirmed-order warning
 * directly underneath as a callout so it cannot be read as just another row.
 */
@Composable
internal fun ReviewHeader(amount: String, recipient: String, warning: String?) {
    val s = strings()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // The dialog's text slot is onSurfaceVariant; the figure being approved takes the full text colour
        Text(
            amount,
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(s.format(StringKey.ReviewTo, recipient), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (warning != null) {
            Spacer(Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(warning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String, labelWidth: Dp) {
    Row {
        Text(label, fontWeight = FontWeight.Medium, softWrap = false, modifier = Modifier.width(labelWidth))
        Text(value)
    }
}

/** What a changed field looked like before and what it becomes now, side by side. */
@Composable
private fun ReviewChangeRow(label: String, oldValue: String, newValue: String, labelWidth: Dp) {
    Row {
        Text(label, fontWeight = FontWeight.Medium, softWrap = false, modifier = Modifier.width(labelWidth))
        Text(oldValue)
        Text("  →  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(newValue)
    }
}

@Composable
private fun paymentStatusColor(status: String) = when (status) {
    PaymentOrderStatus.ACCEPTED -> de.findesk.ui.theme.Success
    PaymentOrderStatus.REJECTED, PaymentOrderStatus.UNKNOWN -> MaterialTheme.colorScheme.error
    PaymentOrderStatus.PENDING, PaymentOrderStatus.TAN_REQUIRED -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
