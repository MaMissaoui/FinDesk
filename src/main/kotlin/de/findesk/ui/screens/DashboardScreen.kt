package de.findesk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.findesk.ui.theme.*

@Composable
fun DashboardScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Summary Cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SummaryCard(
                    title = "Total Balance",
                    value = "€12,450.00",
                    icon = Icons.Default.AccountBox,
                    color = PrimaryBlue,
                    modifier = Modifier.weight(1f),
                )
                SummaryCard(
                    title = "Monthly Income",
                    value = "€3,200.00",
                    icon = Icons.Default.Add,
                    color = Success,
                    modifier = Modifier.weight(1f),
                )
                SummaryCard(
                    title = "Monthly Expenses",
                    value = "€2,150.00",
                    icon = Icons.Default.Delete,
                    color = Error,
                    modifier = Modifier.weight(1f),
                )
                SummaryCard(
                    title = "Pending Tasks",
                    value = "5",
                    icon = Icons.Default.Warning,
                    color = Warning,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Charts Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Expense Chart
                Card(
                    modifier = Modifier.weight(1f).height(300.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = "Expenses by Category",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Chart Placeholder",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MediumGray,
                            )
                        }
                    }
                }

                // Tax Deductions Chart
                Card(
                    modifier = Modifier.weight(1f).height(300.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = "Tax Deductions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Chart Placeholder",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MediumGray,
                            )
                        }
                    }
                }
            }
        }

        // Recent Transactions
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Recent Transactions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = { /* Navigate to transactions */ }) {
                            Text("View All")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    // Sample transactions
                    TransactionItem(
                        date = "Today",
                        description = "REWE Supermarket",
                        amount = "-€45.67",
                        category = "Groceries",
                        isExpense = true,
                    )
                    TransactionItem(
                        date = "Yesterday",
                        description = "Salary Deposit",
                        amount = "+€3,200.00",
                        category = "Income",
                        isExpense = false,
                    )
                    TransactionItem(
                        date = "2 days ago",
                        description = "Amazon Purchase",
                        amount = "-€89.99",
                        category = "Shopping",
                        isExpense = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MediumGray,
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = DarkGray,
            )
        }
    }
}

@Composable
private fun TransactionItem(
    date: String,
    description: String,
    amount: String,
    category: String,
    isExpense: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "$date • $category",
                style = MaterialTheme.typography.bodySmall,
                color = MediumGray,
            )
        }
        Text(
            text = amount,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (isExpense) Error else Success,
        )
    }
    HorizontalDivider()
}
