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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.findesk.ui.theme.*

@Composable
fun TransactionsScreen() {
    var selectedFilter by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }

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
                text = "Transactions",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Button(onClick = { /* TODO: Export */ }) {
                Icon(Icons.Default.Share, contentDescription = "Export")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Search and Filters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search transactions...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            // Date Range
            OutlinedButton(onClick = { /* TODO */ }) {
                Icon(Icons.Default.DateRange, contentDescription = "Date Range")
                Spacer(modifier = Modifier.width(8.dp))
                Text("This Month")
            }

            // Category Filter
            FilterChip(
                selected = selectedFilter == "All",
                onClick = { selectedFilter = "All" },
                label = { Text("All") }
            )
            FilterChip(
                selected = selectedFilter == "Income",
                onClick = { selectedFilter = "Income" },
                label = { Text("Income") }
            )
            FilterChip(
                selected = selectedFilter == "Expenses",
                onClick = { selectedFilter = "Expenses" },
                label = { Text("Expenses") }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Transaction List
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
                        text = "Date",
                        style = MaterialTheme.typography.labelLarge,
                        color = MediumGray,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Description",
                        style = MaterialTheme.typography.labelLarge,
                        color = MediumGray,
                        modifier = Modifier.weight(3f)
                    )
                    Text(
                        text = "Category",
                        style = MaterialTheme.typography.labelLarge,
                        color = MediumGray,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Amount",
                        style = MaterialTheme.typography.labelLarge,
                        color = MediumGray,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
                HorizontalDivider()

                // Transaction Rows
                LazyColumn {
                    items(sampleTransactions) { transaction ->
                        TransactionRow(transaction = transaction)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(transaction: SampleTransaction) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = transaction.date,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Column(modifier = Modifier.weight(3f)) {
            Text(
                text = transaction.description,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = transaction.partner,
                style = MaterialTheme.typography.bodySmall,
                color = MediumGray
            )
        }
        CategoryBadge(
            category = transaction.category,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = transaction.amount,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (transaction.isExpense) Error else Success,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
private fun CategoryBadge(category: String, modifier: Modifier = Modifier) {
    val color = when (category) {
        "Groceries" -> CategoryColors[0]
        "Income" -> CategoryColors[1]
        "Shopping" -> CategoryColors[2]
        "Transport" -> CategoryColors[3]
        "Entertainment" -> CategoryColors[4]
        else -> MediumGray
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Text(
            text = category,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// Sample data
private data class SampleTransaction(
    val date: String,
    val description: String,
    val partner: String,
    val category: String,
    val amount: String,
    val isExpense: Boolean
)

private val sampleTransactions = listOf(
    SampleTransaction("03.10.2026", "REWE Supermarket", "REWE SAGT DANKE", "Groceries", "-€45.67", true),
    SampleTransaction("02.10.2026", "Salary Deposit", "Employer GmbH", "Income", "+€3,200.00", false),
    SampleTransaction("01.10.2026", "Amazon Purchase", "AMAZON.DE", "Shopping", "-€89.99", true),
    SampleTransaction("30.09.2026", "Deutsche Bahn", "DB Vertrieb GmbH", "Transport", "-€23.50", true),
    SampleTransaction("29.09.2026", "Netflix Subscription", "NETFLIX.COM", "Entertainment", "-€12.99", true),
    SampleTransaction("28.09.2026", "Freelance Payment", "Client AG", "Income", "+€850.00", false),
)