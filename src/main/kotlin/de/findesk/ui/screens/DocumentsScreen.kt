package de.findesk.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.findesk.ui.theme.*

@Composable
fun DocumentsScreen() {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Documents",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { /* TODO: Scan */ }) {
                    Icon(Icons.Default.Add, contentDescription = "Scan")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan")
                }
                Button(onClick = { /* TODO: Upload */ }) {
                    Icon(Icons.Default.Add, contentDescription = "Upload")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Upload")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search documents by name or OCR text...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Tabs
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("All Documents") },
                icon = { Icon(Icons.Default.List, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Unlinked") },
                icon = { Icon(Icons.Default.Lock, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Receipts") },
                icon = { Icon(Icons.Default.Info, contentDescription = null) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Documents List
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column {
                // Table Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Name",
                        style = MaterialTheme.typography.labelLarge,
                        color = MediumGray,
                        modifier = Modifier.weight(3f)
                    )
                    Text(
                        text = "Type",
                        style = MaterialTheme.typography.labelLarge,
                        color = MediumGray,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Date",
                        style = MaterialTheme.typography.labelLarge,
                        color = MediumGray,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Linked To",
                        style = MaterialTheme.typography.labelLarge,
                        color = MediumGray,
                        modifier = Modifier.weight(2f)
                    )
                    Text(
                        text = "OCR",
                        style = MaterialTheme.typography.labelLarge,
                        color = MediumGray,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Actions",
                        style = MaterialTheme.typography.labelLarge,
                        color = MediumGray,
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider()

                // Sample Documents
                LazyColumn {
                    items(sampleDocuments) { document ->
                        DocumentRow(document = document)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentRow(document: SampleDocument) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Name with icon
        Row(
            modifier = Modifier.weight(3f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (document.type) {
                    "PDF" -> Icons.Default.Share
                    "Image" -> Icons.Default.Star
                    else -> Icons.Default.Menu
                },
                contentDescription = document.type,
                tint = when (document.type) {
                    "PDF" -> Color(0xFFE53935)
                    "Image" -> Color(0xFF43A047)
                    else -> MediumGray
                },
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = document.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }

        // Type
        Text(
            text = document.type,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )

        // Date
        Text(
            text = document.date,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )

        // Linked To
        Text(
            text = document.linkedTo ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = if (document.linkedTo != null) PrimaryBlue else MediumGray,
            modifier = Modifier.weight(2f)
        )

        // OCR Status
        Box(modifier = Modifier.weight(1f)) {
            if (document.hasOcr) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "OCR processed",
                    tint = Success,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "No OCR",
                    tint = LightGray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Actions
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { /* TODO: Open */ },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Open",
                    tint = MediumGray,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = { /* TODO: More options */ },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More",
                    tint = MediumGray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// Sample data
private data class SampleDocument(
    val name: String,
    val type: String,
    val date: String,
    val linkedTo: String?,
    val hasOcr: Boolean
)

private val sampleDocuments = listOf(
    SampleDocument("Invoice_Amazon_2026-10-01.pdf", "PDF", "01.10.2026", "Amazon Purchase", true),
    SampleDocument("Receipt_REWE_2026-10-03.jpg", "Image", "03.10.2026", "REWE Supermarket", true),
    SampleDocument("Salary_Statement_September.pdf", "PDF", "30.09.2026", "Salary Deposit", false),
    SampleDocument("Insurance_Invoice_2026.pdf", "PDF", "28.09.2026", null, true),
    SampleDocument("Tax_Receipt_Donation.pdf", "PDF", "25.09.2026", null, false),
)