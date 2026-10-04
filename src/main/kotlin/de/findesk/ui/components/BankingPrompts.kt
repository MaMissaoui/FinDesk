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
import de.findesk.viewmodel.BankingPrompt
import de.findesk.viewmodel.UiBankingInteraction

/** Shows the dialog for whatever the bank is currently asking, if anything. */
@Composable
fun BankingPromptHost(interaction: UiBankingInteraction) {
    val prompt by interaction.prompt.collectAsState()
    val info by interaction.info.collectAsState()

    when (val p = prompt) {
        null -> Unit

        is BankingPrompt.Pin -> SecretDialog(
            title = "PIN for ${p.bankName}",
            text = "Online banking PIN for user ${p.userId}. FinDesk does not store it.",
            label = "PIN",
            onAnswer = interaction::answer,
        )

        is BankingPrompt.ChooseTanMethod -> ChoiceDialog(
            title = "Choose TAN procedure",
            options = p.options.map { it.id to it.name },
            onAnswer = interaction::answer,
        )

        is BankingPrompt.ChooseTanMedium -> ChoiceDialog(
            title = "Choose TAN medium",
            options = p.options.map { it to it },
            onAnswer = interaction::answer,
        )

        is BankingPrompt.Tan -> {
            val bitmap = remember(p.challenge) {
                p.challenge.image?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
            }
            SecretDialog(
                title = "TAN required",
                text = p.challenge.message.ifBlank { "Enter the TAN shown by your TAN procedure." },
                label = "TAN",
                masked = false,
                onAnswer = interaction::answer,
            ) {
                p.challenge.flickerCode?.let { FlickerCodeView(it, Modifier.align(Alignment.CenterHorizontally)) }
                if (bitmap != null) {
                    Image(bitmap, contentDescription = "TAN challenge", modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally))
                }
            }
        }

        is BankingPrompt.Decoupled -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Confirm in your banking app") },
            text = { Text(p.message) },
            confirmButton = { Button(onClick = { interaction.answer("ok") }) { Text("I confirmed it") } },
            dismissButton = { TextButton(onClick = { interaction.answer(null) }) { Text("Cancel") } },
        )

        is BankingPrompt.ConfirmPayee -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Check the recipient") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your bank compared the recipient name with the account holder:", fontWeight = FontWeight.Medium)
                    Text(p.message)
                    Text(
                        "If the name doesn't match, the money may go to someone else and may not be recoverable.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { interaction.answer("ok") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Send anyway") }
            },
            dismissButton = { Button(onClick = { interaction.answer(null) }) { Text("Cancel transfer") } },
        )
    }

    info?.let { message ->
        AlertDialog(
            onDismissRequest = interaction::clearInfo,
            title = { Text("Message from your bank") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = interaction::clearInfo) { Text("OK") } },
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
        confirmButton = { Button(enabled = value.isNotEmpty(), onClick = { onAnswer(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text("Cancel") } },
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
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text("Cancel") } },
    )
}
