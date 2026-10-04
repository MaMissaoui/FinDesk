package de.findesk.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.viewmodel.BankingPrompt
import de.findesk.viewmodel.UiBankingInteraction

/** Shows the dialog for whatever the bank is currently asking, if anything. */
@Composable
fun BankingPromptHost(interaction: UiBankingInteraction) {
    val prompt by interaction.prompt.collectAsState()
    val info by interaction.info.collectAsState()
    val s = strings()

    when (val p = prompt) {
        null -> Unit

        is BankingPrompt.Pin -> SecretDialog(
            title = s.format(StringKey.PinFor, p.bankName),
            text = s.format(StringKey.PinExplanation, p.userId),
            label = s[StringKey.Pin],
            onAnswer = interaction::answer,
        )

        is BankingPrompt.ChooseTanMethod -> ChoiceDialog(
            title = s[StringKey.ChooseTanProcedure],
            options = p.options.map { it.id to it.name },
            onAnswer = interaction::answer,
        )

        is BankingPrompt.ChooseTanMedium -> ChoiceDialog(
            title = s[StringKey.ChooseTanMedium],
            options = p.options.map { it to it },
            onAnswer = interaction::answer,
        )

        is BankingPrompt.Tan -> {
            val bitmap = remember(p.challenge) {
                p.challenge.image?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
            }
            SecretDialog(
                title = s[StringKey.TanRequired],
                text = p.challenge.message.ifBlank { s[StringKey.TanDefaultMessage] },
                label = "TAN",
                masked = false,
                onAnswer = interaction::answer,
            ) {
                p.challenge.flickerCode?.let { FlickerCodeView(it, Modifier.align(Alignment.CenterHorizontally)) }
                if (bitmap != null) {
                    Image(bitmap, contentDescription = s[StringKey.TanChallenge], modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally))
                }
            }
        }

        is BankingPrompt.Decoupled -> AlertDialog(
            onDismissRequest = {},
            title = { Text(s[StringKey.ConfirmInBankingApp]) },
            text = { Text(p.message) },
            confirmButton = { Button(onClick = { interaction.answer("ok") }) { Text(s[StringKey.IConfirmedIt]) } },
            dismissButton = { TextButton(onClick = { interaction.answer(null) }) { Text(s[StringKey.Cancel]) } },
        )

        is BankingPrompt.ConfirmPayee -> AlertDialog(
            onDismissRequest = {},
            title = { Text(s[StringKey.CheckRecipient]) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s[StringKey.BankComparedName], fontWeight = FontWeight.Medium)
                    Text(p.message)
                    Text(
                        s[StringKey.NameMismatchWarning],
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { interaction.answer("ok") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(s[StringKey.SendAnyway]) }
            },
            dismissButton = { Button(onClick = { interaction.answer(null) }) { Text(s[StringKey.CancelTransfer]) } },
        )
    }

    info?.let { message ->
        AlertDialog(
            onDismissRequest = interaction::clearInfo,
            title = { Text(s[StringKey.MessageFromBank]) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = interaction::clearInfo) { Text(s[StringKey.Ok]) } },
        )
    }
}

@Composable
private fun SecretDialog(
    title: String,
    text: String,
    label: String,
    masked: Boolean = true,
    onAnswer: (String?) -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text)
                extra()
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardActions = KeyboardActions(onDone = { if (value.isNotEmpty()) onAnswer(value) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(enabled = value.isNotEmpty(), onClick = { onAnswer(value) }) { Text(strings()[StringKey.Ok]) } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(strings()[StringKey.Cancel]) } },
    )
}

@Composable
private fun ChoiceDialog(title: String, options: List<Pair<String, String>>, onAnswer: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (id, name) ->
                    Text(name, modifier = Modifier.fillMaxWidth().clickable { onAnswer(id) }.padding(vertical = 10.dp))
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(strings()[StringKey.Cancel]) } },
    )
}
