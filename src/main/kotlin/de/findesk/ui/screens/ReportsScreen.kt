package de.findesk.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
fun ReportsScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    var selectedYear by remember { mutableStateOf(2026) }

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
                text = "Reports & Tax",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { /* TODO: Export CSV */ }) {
                    Icon(Icons.Default.Share, contentDescription = "Export CSV")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export CSV")
                }
                Button(onClick = { /* TODO: Export PDF */ }) {
                    Icon(Icons.Default.Share, contentDescription = "Export PDF")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export PDF")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Year Selector
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Tax Year:",
                style = MaterialTheme.typography.titleMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedYear-- }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Previous Year")
                }
                Text(
                    text = selectedYear.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { selectedYear++ }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Next Year")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tabs
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Tax Summary") },
                icon = { Icon(Icons.Default.Info, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Monthly Breakdown") },
                icon = { Icon(Icons.Default.DateRange, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Category Analysis") },
                icon = { Icon(Icons.Default.Star, contentDescription = null) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Content
        when (selectedTab) {
            0 -> TaxSummaryTab(selectedYear)
            1 -> MonthlyBreakdownTab(selectedYear)
            2 -> CategoryAnalysisTab(selectedYear)
        }
    }
}

@Composable
private fun TaxSummaryTab(year: Int) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Summary Cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ReportSummaryCard(
                    title = "Total Income",
                    value = "€38,400.00",
                    icon = Icons.Default.ArrowDropDown,
                    color = Success,
                    modifier = Modifier.weight(1f)
                )
                ReportSummaryCard(
                    title = "Total Expenses",
                    value = "€25,800.00",
                    icon = Icons.Default.Warning,
                    color = Error,
                    modifier = Modifier.weight(1f)
                )
                ReportSummaryCard(
                    title = "Net Income",
                    value = "€12,600.00",
                    icon = Icons.Default.AccountBox,
                    color = PrimaryBlue,
                    modifier = Modifier.weight(1f)
                )
                ReportSummaryCard(
                    title = "Tax-Deductible",
                    value = "€8,450.00",
                    icon = Icons.Default.Info,
                    color = Warning,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Tax-Deductible Expenses
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Tax-Deductible Expenses",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val taxDeductibleItems = listOf(
                        "Office Supplies" to "€1,200.00",
                        "Travel Expenses" to "€2,400.00",
                        "Professional Development" to "€1,800.00",
                        "Insurance Premiums" to "€1,650.00",
                        "Home Office" to "€1,400.00"
                    )

                    taxDeductibleItems.forEach { (category, amount) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = amount,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        // Quick Stats
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Quick Statistics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem("Transactions", "1,247")
                        StatItem("Categories Used", "24")
                        StatItem("Avg Monthly", "€2,150.00")
                        StatItem("Highest Month", "€3,200.00")
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyBreakdownTab(year: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Monthly Income & Expenses - $year",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Table Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Month",
                    style = MaterialTheme.typography.labelLarge,
                    color = MediumGray,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Income",
                    style = MaterialTheme.typography.labelLarge,
                    color = MediumGray,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
                Text(
                    text = "Expenses",
                    style = MaterialTheme.typography.labelLarge,
                    color = MediumGray,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
                Text(
                    text = "Net",
                    style = MaterialTheme.typography.labelLarge,
                    color = MediumGray,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
            HorizontalDivider()

            // Monthly Data
            val months = listOf(
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"
            )

            val sampleData = listOf(
                Triple("€3,200.00", "€2,100.00", "+€1,100.00"),
                Triple("€3,200.00", "€2,350.00", "+€850.00"),
                Triple("€3,200.00", "€1,980.00", "+€1,220.00"),
                Triple("€3,200.00", "€2,450.00", "+€750.00"),
                Triple("€3,200.00", "€2,200.00", "+€1,000.00"),
                Triple("€3,200.00", "€2,500.00", "+€700.00"),
                Triple("€3,200.00", "€2,100.00", "+€1,100.00"),
                Triple("€3,200.00", "€2,800.00", "+€400.00"),
                Triple("€3,200.00", "€2,150.00", "+€1,050.00"),
                Triple("€3,200.00", "€2,300.00", "+€900.00"),
                Triple("€3,200.00", "€2,000.00", "+€1,200.00"),
                Triple("€3,200.00", "€2,600.00", "+€600.00")
            )

            months.forEachIndexed { index, month ->
                val (income, expenses, net) = sampleData[index]

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = month,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = income,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Success,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                    Text(
                        text = expenses,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Error,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                    Text(
                        text = net,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Success,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CategoryAnalysisTab(year: Int) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Category Breakdown
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Spending by Category",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val categories = listOf(
                        Triple("Groceries", "€6,240.00", 24.2f),
                        Triple("Transport", "€3,600.00", 14.0f),
                        Triple("Entertainment", "€2,400.00", 9.3f),
                        Triple("Shopping", "€4,800.00", 18.6f),
                        Triple("Utilities", "€3,200.00", 12.4f),
                        Triple("Insurance", "€2,400.00", 9.3f),
                        Triple("Other", "€3,160.00", 12.2f)
                    )

                    categories.forEach { (category, amount, percentage) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = category,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = amount,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { percentage / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = when (category) {
                                    "Groceries" -> CategoryColors[0]
                                    "Transport" -> CategoryColors[1]
                                    "Entertainment" -> CategoryColors[2]
                                    "Shopping" -> CategoryColors[3]
                                    "Utilities" -> CategoryColors[4]
                                    "Insurance" -> CategoryColors[5]
                                    else -> CategoryColors[6]
                                }
                            )
                            Text(
                                text = "${percentage}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MediumGray
                            )
                        }
                    }
                }
            }
        }

        // Top Vendors
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Top Vendors",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val vendors = listOf(
                        "REWE Supermarket" to "€2,450.00",
                        "Amazon.de" to "€1,890.00",
                        "Deutsche Bahn" to "€1,200.00",
                        "EDEKA" to "€980.00",
                        "ALDI" to "€870.00"
                    )

                    vendors.forEachIndexed { index, (vendor, amount) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MediumGray,
                                    modifier = Modifier.width(24.dp)
                                )
                                Text(
                                    text = vendor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Text(
                                text = amount,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (index < vendors.size - 1) HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportSummaryCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MediumGray
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = DarkGray
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = PrimaryBlue
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MediumGray
        )
    }
}