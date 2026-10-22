package de.findesk.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.util.AppInfo

/** "About FinDesk" from the macOS app menu: name, version and what the app is (and is not yet) for. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val s = strings()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Image(painterResource("icon-64.png"), contentDescription = null, modifier = Modifier.size(56.dp)) },
        title = { Text(AppInfo.NAME) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.format(StringKey.AboutVersion, AppInfo.VERSION), style = MaterialTheme.typography.titleSmall)
                Text(s[StringKey.AboutText], style = MaterialTheme.typography.bodyMedium)
                Text(AppInfo.HOMEPAGE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s[StringKey.AboutTypeface], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Close]) } },
    )
}
