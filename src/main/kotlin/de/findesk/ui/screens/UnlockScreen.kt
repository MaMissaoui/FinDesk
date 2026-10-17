package de.findesk.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.findesk.data.database.DatabaseCrypto
import de.findesk.data.database.PlaintextLeftovers
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.viewmodel.UnlockError
import de.findesk.viewmodel.UnlockMode
import de.findesk.viewmodel.UnlockViewModel

/** The startup gate (see [UnlockViewModel]): unlock, change the passphrase, or choose one for new or plaintext data. */
@Composable
fun UnlockScreen(vm: UnlockViewModel, profileName: String?) {
    val s = strings()
    val state by vm.state.collectAsState()
    var current by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    val firstField = remember { FocusRequester() }
    val mode = state.mode
    val choosing = mode != UnlockMode.UNLOCK

    LaunchedEffect(mode) {
        current = ""
        passphrase = ""
        repeat = ""
        acknowledged = false
        runCatching { firstField.requestFocus() }
    }
    LaunchedEffect(state.error) {
        if (state.error == UnlockError.WRONG_PASSPHRASE) {
            if (mode == UnlockMode.CHANGE) current = "" else passphrase = ""
        }
    }

    val submit = {
        when (mode) {
            UnlockMode.UNLOCK -> vm.unlock(passphrase)
            UnlockMode.CHANGE -> vm.changePassphrase(current, passphrase, repeat, acknowledged)
            UnlockMode.CREATE, UnlockMode.ENCRYPT -> vm.setPassphrase(passphrase, repeat, acknowledged)
        }
        Unit
    }
    val onEnter = Modifier.onPreviewKeyEvent { e ->
        val enter = e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)
        if (enter && !state.isBusy) submit()
        enter
    }
    val transformation = if (show) VisualTransformation.None else PasswordVisualTransformation()

    // Surface, not a background modifier: it also sets the content colour, so text follows the theme
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(painterResource("icon-512.png"), contentDescription = null, modifier = Modifier.size(56.dp))
                Text(
                    when (mode) {
                        UnlockMode.UNLOCK -> s[StringKey.UnlockHeading]
                        UnlockMode.CHANGE -> s[StringKey.ChangePassphrase]
                        UnlockMode.CREATE -> s[StringKey.CreatePassphraseHeading]
                        UnlockMode.ENCRYPT -> s[StringKey.EncryptPassphraseHeading]
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                profileName?.let { Text(s.format(StringKey.UnlockProfile, it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                Text(
                    when (mode) {
                        UnlockMode.UNLOCK -> s[StringKey.UnlockText]
                        UnlockMode.CHANGE -> s[StringKey.ChangePassphraseText]
                        UnlockMode.CREATE -> s[StringKey.CreatePassphraseText]
                        UnlockMode.ENCRYPT -> s[StringKey.EncryptPassphraseText]
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.restored) {
                    Text(
                        if (mode == UnlockMode.ENCRYPT) s[StringKey.RestoredPlainHint] else s[StringKey.RestoredEncryptedHint],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                if (state.plaintextLeftovers.isNotEmpty()) {
                    Text(
                        s[StringKey.PlaintextLeftoversTitle],
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(s[StringKey.PlaintextLeftoversText], style = MaterialTheme.typography.bodySmall)
                    state.plaintextLeftovers.forEach { leftover ->
                        Text(
                            "${s[leftoverKindKey(leftover.kind)]} · ${leftover.path}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (mode == UnlockMode.CHANGE) {
                    PassphraseField(current, { current = it }, s[StringKey.CurrentPassphrase], transformation, state.error == UnlockError.WRONG_PASSPHRASE, onEnter.focusRequester(firstField), !state.isBusy)
                }
                PassphraseField(
                    passphrase,
                    { passphrase = it },
                    if (choosing) s[StringKey.NewPassphrase] else s[StringKey.Passphrase],
                    transformation,
                    state.error in setOf(UnlockError.TOO_SHORT, UnlockError.SPACES) || (mode == UnlockMode.UNLOCK && state.error == UnlockError.WRONG_PASSPHRASE),
                    if (mode == UnlockMode.CHANGE) onEnter else onEnter.focusRequester(firstField),
                    !state.isBusy,
                    supporting = if (choosing) s.format(StringKey.PassphraseHint, DatabaseCrypto.MIN_PASSPHRASE_LENGTH) else null,
                )
                if (choosing) {
                    PassphraseField(repeat, { repeat = it }, s[StringKey.RepeatPassphrase], transformation, state.error == UnlockError.MISMATCH, onEnter, !state.isBusy)
                }
                CheckRow(show, { show = it }, s[StringKey.ShowPassphrase], !state.isBusy)

                if (choosing) {
                    if (mode != UnlockMode.CHANGE) {
                        Text(s[StringKey.DocumentsNotEncrypted], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CheckRow(acknowledged, { acknowledged = it }, s[StringKey.PassphraseNoRecovery], !state.isBusy)
                }

                state.error?.let { error ->
                    Text(
                        when (error) {
                            UnlockError.WRONG_PASSPHRASE -> s[StringKey.WrongPassphrase]
                            UnlockError.TOO_SHORT -> s.format(StringKey.PassphraseTooShort, DatabaseCrypto.MIN_PASSPHRASE_LENGTH)
                            UnlockError.SPACES -> s[StringKey.PassphraseSpaces]
                            UnlockError.MISMATCH -> s[StringKey.PassphraseMismatch]
                            UnlockError.NOT_ACKNOWLEDGED -> s[StringKey.PassphraseAcknowledge]
                            UnlockError.FAILED -> s.format(StringKey.UnlockFailed, state.failure.orEmpty())
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = submit, enabled = !state.isBusy) {
                        Text(
                            when (mode) {
                                UnlockMode.UNLOCK -> s[StringKey.Unlock]
                                UnlockMode.CHANGE -> s[StringKey.ChangeAndOpen]
                                UnlockMode.CREATE, UnlockMode.ENCRYPT -> s[StringKey.EncryptAndOpen]
                            },
                        )
                    }
                    if (state.isBusy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(if (mode == UnlockMode.UNLOCK) s[StringKey.Unlocking] else s[StringKey.Encrypting], style = MaterialTheme.typography.bodySmall)
                    }
                }
                when (mode) {
                    UnlockMode.UNLOCK -> TextButton(onClick = { vm.showChange(true) }, enabled = !state.isBusy) { Text(s[StringKey.ChangePassphrase]) }
                    UnlockMode.CHANGE -> TextButton(onClick = { vm.showChange(false) }, enabled = !state.isBusy) { Text(s[StringKey.Back]) }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun PassphraseField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    transformation: VisualTransformation,
    isError: Boolean,
    modifier: Modifier,
    enabled: Boolean,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        visualTransformation = transformation,
        supportingText = supporting?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun leftoverKindKey(kind: PlaintextLeftovers.Kind): StringKey = when (kind) {
    PlaintextLeftovers.Kind.PRE_RESTORE -> StringKey.PlaintextLeftoverPreRestore
    PlaintextLeftovers.Kind.DELETED_PROFILE -> StringKey.PlaintextLeftoverDeletedProfile
    PlaintextLeftovers.Kind.PENDING_RESTORE -> StringKey.PlaintextLeftoverPendingRestore
    PlaintextLeftovers.Kind.OTHER_PROFILE -> StringKey.PlaintextLeftoverOtherProfile
}
