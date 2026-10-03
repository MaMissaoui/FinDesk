package de.findesk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.findesk.ui.theme.DarkGray
import de.findesk.ui.theme.LightGray
import de.findesk.ui.theme.MediumGray

@Composable
fun TopBar(
    title: String,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Color.White)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left side: Title
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = DarkGray,
            fontWeight = FontWeight.SemiBold,
        )

        // Right side: Actions
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sync Status
            SyncStatusIndicator(isSyncing = false)

            // Theme Toggle
            IconButton(onClick = onThemeToggle) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Default.Star else Icons.Default.FavoriteBorder,
                    contentDescription = "Toggle theme",
                    tint = MediumGray,
                )
            }

            // Notifications
            IconButton(onClick = { /* TODO */ }) {
                BadgedBox(
                    badge = {
                        Badge {
                            Text("3")
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Notifications",
                        tint = MediumGray,
                    )
                }
            }

            // User Avatar
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MaterialTheme.shapes.small,
                color = LightGray,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "User",
                        tint = MediumGray,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncStatusIndicator(isSyncing: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isSyncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = "Syncing...",
                style = MaterialTheme.typography.bodySmall,
                color = MediumGray,
            )
        } else {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Synced",
                tint = Color(0xFF10B981),
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Up to date",
                style = MaterialTheme.typography.bodySmall,
                color = MediumGray,
            )
        }
    }
}
