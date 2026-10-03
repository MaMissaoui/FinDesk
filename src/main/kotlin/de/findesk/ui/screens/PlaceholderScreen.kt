package de.findesk.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.findesk.ui.theme.MediumGray

@Composable
fun PlaceholderScreen(
    title: String,
    icon: ImageVector,
    description: String,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(64.dp),
                tint = MediumGray,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MediumGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 400.dp),
            )
            Button(onClick = { /* TODO */ }) {
                Text("Coming Soon")
            }
        }
    }
}

@Composable
fun PaymentsPlaceholderScreen() {
    PlaceholderScreen(
        title = "Payments",
        icon = Icons.Default.ShoppingCart,
        description = "SEPA transfers, standing orders, and payment management will be available here.",
    )
}

@Composable
fun SettingsPlaceholderScreen() {
    PlaceholderScreen(
        title = "Settings",
        icon = Icons.Default.Settings,
        description = "Configure application settings, manage bank connections, and customize your experience.",
    )
}
