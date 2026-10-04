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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.findesk.banking.BankingService
import de.findesk.banking.TransferOrder
import de.findesk.model.AccountItem
import de.findesk.ui.components.*
import de.findesk.util.Formats
import de.findesk.util.Iban
import de.findesk.viewmodel.PaymentsViewModel

@Composable
fun PaymentsScreen() {
    val vm = rememberViewModel { PaymentsViewModel() }
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    BankingPromptHost(vm.bankingInteraction)

    var from by remember { mutableStateOf<AccountItem?>(null) }
    var name by remember { mutableStateOf("") }
    var iban by remember { mutableStateOf("") }
    var bic by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    LaunchedEffect(state.accounts) { if (from == null) from = state.accounts.firstOrNull() }

    val parsedAmount = Formats.parseAmount(amount)
    val ibanOk = Iban.isValid(iban)
    val suggestedBic = if (ibanOk) vm.bicFor(iban) else null

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(3f).verticalScroll(rememberScrollState())) {
            Text("SEPA Transfer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            MessageBanner(state.message, state.error, vm::clearMessages)

            if (state.accounts.isEmpty()) {
                EmptyState(Icons.Default.Send, "No accounts", "Add and sync a bank account first.")
                return@Column
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("From", style = MaterialTheme.typography.labelLarge)
                    DropdownSelector(
                        label = from?.let { "${it.bankName} ${Formats.iban(it.iban)} (${Formats.money(it.balance)})" } ?: "Choose account",
                        options = state.accounts,
                        optionLabel = { "${it.bankName} ${Formats.iban(it.iban)} (${Formats.money(it.balance)})" },
                        onSelect = { from = it },
                    )
                    OutlinedTextField(name, { name = it.take(BankingService.MAX_NAME_LENGTH) }, label = { Text("Recipient name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        iban,
                        { iban = it.uppercase() },
                        label = { Text("Recipient IBAN") },
                        isError = iban.isNotBlank() && !ibanOk,
                        supportingText = { if (iban.isNotBlank() && !ibanOk) Text("IBAN is not valid") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        bic,
                        { bic = it.uppercase() },
                        label = { Text("BIC (optional)") },
                        placeholder = { suggestedBic?.let { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        amount,
                        { amount = it },
                        label = { Text("Amount (€)") },
                        isError = amount.isNotBlank() && (parsedAmount == null || parsedAmount.signum() <= 0),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        purpose,
                        { purpose = it.take(BankingService.MAX_PURPOSE_LENGTH) },
                        label = { Text("Purpose (Verwendungszweck)") },
                        supportingText = { Text("${purpose.length}/${BankingService.MAX_PURPOSE_LENGTH}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = from != null && name.isNotBlank() && ibanOk && parsedAmount != null && parsedAmount.signum() > 0 && !state.isSending,
                        onClick = {
                            vm.review(
                                from!!,
                                TransferOrder(name, iban, bic.ifBlank { suggestedBic.orEmpty() }.ifBlank { null }, parsedAmount!!, purpose),
                            )
                        },
                    ) { Text("Review transfer") }
                }
            }
        }

        SectionCard("Recent transfers", Modifier.weight(2f)) {
            if (state.recentTransfers.isEmpty()) Text("None yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.recentTransfers.forEach { e ->
                Text(Formats.dateTime(e.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(e.details.orEmpty(), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
            }
        }
    }

    state.review?.let { (account, order) ->
        var acknowledged by remember(order) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!state.isSending) vm.cancelReview() },
            title = { Text("Confirm transfer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(440.dp)) {
                    ReviewRow("From", "${account.bankName} ${Formats.iban(account.iban)}")
                    ReviewRow("To", order.recipientName)
                    ReviewRow("IBAN", Formats.iban(Iban.normalize(order.recipientIban)))
                    order.recipientBic?.let { ReviewRow("BIC", it) }
                    ReviewRow("Amount", Formats.money(order.amount))
                    ReviewRow("Purpose", order.purpose.ifBlank { "—" })
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it }, enabled = !state.isSending)
                        Text("I checked the recipient and amount. This sends real money.")
                    }
                    if (state.isSending) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Waiting for the bank…")
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = acknowledged && !state.isSending, onClick = { vm.confirmAndSend() }) { Text("Confirm & send") }
            },
            dismissButton = { TextButton(enabled = !state.isSending, onClick = vm::cancelReview) { Text("Back") } },
        )
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row {
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.width(90.dp))
        Text(value)
    }
}
