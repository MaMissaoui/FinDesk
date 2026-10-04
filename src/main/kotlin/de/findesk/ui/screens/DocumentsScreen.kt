package de.findesk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.findesk.document.DocumentService
import de.findesk.model.DocumentItem
import de.findesk.model.TransactionItem
import de.findesk.ui.components.*
import de.findesk.ui.theme.Success
import de.findesk.util.Formats
import de.findesk.viewmodel.DocumentTab
import de.findesk.viewmodel.DocumentsState
import de.findesk.viewmodel.DocumentsViewModel
import java.awt.Desktop
import java.io.File

@Composable
fun DocumentsScreen() {
    val vm = rememberViewModel { DocumentsViewModel() }
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.loadDocuments() }

    var confirmDelete by remember { mutableStateOf<DocumentItem?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Documents", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (!state.isOcrAvailable) {
                    Text(
                        "OCR for images and scanned PDFs needs Tesseract (brew install tesseract tesseract-lang). PDFs with text work without it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.isUploading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Button(
                enabled = !state.isUploading,
                onClick = {
                    val files = FileDialogs.openFiles("Upload documents", DocumentService.SUPPORTED_EXTENSIONS)
                    if (files.isNotEmpty()) vm.uploadDocuments(files)
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Upload")
            }
        }

        Spacer(Modifier.height(16.dp))
        MessageBanner(state.message, state.error, vm::clearMessages)

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { vm.searchDocuments(it) },
            placeholder = { Text("Search documents by name or text content…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        TabRow(selectedTabIndex = state.tab.ordinal) {
            DocumentTab.entries.forEach { tab ->
                Tab(selected = state.tab == tab, onClick = { vm.selectTab(tab) }, text = { Text(tab.label) })
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(modifier = Modifier.weight(3f).fillMaxHeight(), shape = RoundedCornerShape(12.dp)) {
                if (!state.isLoading && state.documents.isEmpty()) {
                    EmptyState(
                        Icons.Default.Email,
                        if (state.searchQuery.isBlank()) "No documents" else "No matches",
                        if (state.searchQuery.isBlank()) "Upload receipts, invoices and statements (PDF, JPG, PNG, TIFF)." else "Try another search term.",
                    )
                }
                LazyColumn {
                    items(state.documents, key = { it.id }) { doc ->
                        DocumentRow(doc, selected = state.selectedDocument?.id == doc.id) { vm.selectDocument(doc) }
                        HorizontalDivider()
                    }
                }
            }
            state.selectedDocument?.let { doc ->
                DocumentDetail(
                    doc = doc,
                    state = state,
                    modifier = Modifier.weight(2f).fillMaxHeight(),
                    onClose = { vm.selectDocument(null) },
                    onLink = { vm.linkToTransaction(doc.id, it) },
                    onUnlink = { vm.unlinkFromTransaction(doc.id) },
                    onReprocess = { vm.reprocessOcr(doc.id) },
                    onDelete = { confirmDelete = doc },
                )
            }
        }
    }

    confirmDelete?.let { doc ->
        ConfirmDialog(
            title = "Delete document?",
            text = "${doc.fileName} and its stored copy will be deleted.",
            onConfirm = { vm.deleteDocument(doc.id) },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun DocumentRow(doc: DocumentItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (doc.isPdf) Icons.Default.Email else Icons.Default.Face,
            contentDescription = doc.mimeType,
            tint = if (doc.isPdf) Color(0xFFE53935) else Color(0xFF43A047),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Formats.dateTime(doc.uploadDate) + " · " + (doc.linkedTransactionLabel ?: "not linked"),
                style = MaterialTheme.typography.bodySmall,
                color = if (doc.linkedTransactionLabel != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (doc.hasOcr) {
            Icon(Icons.Default.CheckCircle, contentDescription = "Text extracted", tint = Success, modifier = Modifier.size(16.dp))
        } else {
            Icon(Icons.Default.Clear, contentDescription = "No text", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DocumentDetail(
    doc: DocumentItem,
    state: DocumentsState,
    modifier: Modifier,
    onClose: () -> Unit,
    onLink: (Int) -> Unit,
    onUnlink: () -> Unit,
    onReprocess: () -> Unit,
    onDelete: () -> Unit,
) {
    var pickerQuery by remember(doc.id) { mutableStateOf("") }

    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(doc.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openExternally(File(doc.filePath)) }) { Text("Open") }
                OutlinedButton(onClick = onReprocess) { Text("Re-extract text") }
                OutlinedButton(onClick = onDelete) { Text("Delete") }
            }

            Text("Linked transaction", style = MaterialTheme.typography.labelLarge)
            if (doc.linkedTransactionLabel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(doc.linkedTransactionLabel, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = onUnlink) { Text("Unlink") }
                }
            } else {
                if (state.suggestions.isNotEmpty()) {
                    Text("Suggested matches (amount and date from the document)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.suggestions.forEach { s -> LinkCandidateRow(s.transaction, "${(s.score * 100).toInt()}%") { onLink(s.transaction.id) } }
                }
                OutlinedTextField(
                    value = pickerQuery,
                    onValueChange = { pickerQuery = it },
                    placeholder = { Text("Find a transaction to link…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pickerQuery.isNotBlank()) {
                    state.linkCandidates
                        .filter { it.description.contains(pickerQuery, true) || it.purpose.orEmpty().contains(pickerQuery, true) || Formats.money(it.amount).contains(pickerQuery) }
                        .take(8)
                        .forEach { LinkCandidateRow(it, null) { onLink(it.id) } }
                }
            }

            Text(
                "Extracted text" + (doc.ocrConfidence?.let { " (confidence ${it.toInt()}%)" } ?: ""),
                style = MaterialTheme.typography.labelLarge,
            )
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Text(
                    state.selectedOcrText ?: "No text extracted.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun LinkCandidateRow(txn: TransactionItem, badge: String?, onLink: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(txn.description, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Formats.date(txn.date)} · ${Formats.money(txn.amount, txn.currency)}" + (badge?.let { " · match $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onLink) { Text("Link") }
    }
}

private fun openExternally(file: File) {
    if (file.exists() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Thread { Desktop.getDesktop().open(file) }.start()
    }
}
