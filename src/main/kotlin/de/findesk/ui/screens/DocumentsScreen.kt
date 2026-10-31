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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.findesk.document.DocumentService
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.model.DocumentItem
import de.findesk.model.TransactionItem
import de.findesk.ui.components.*
import de.findesk.ui.platform.Platform
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
    val s = strings()
    LaunchedEffect(Unit) { vm.loadDocuments() }

    var confirmDelete by remember { mutableStateOf<DocumentItem?>(null) }
    val upload: () -> Unit = {
        val files = FileDialogs.openFiles(s[StringKey.UploadDocuments], DocumentService.SUPPORTED_EXTENSIONS)
        if (files.isNotEmpty()) vm.uploadDocuments(files)
    }
    // The empty library shows one Upload button, in its empty state; the header's field and button would only repeat it
    val libraryEmpty = !state.isLoading && state.documents.isEmpty() && state.searchQuery.isBlank() && state.tab == DocumentTab.ALL

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Search is the toolbar's context, Upload its action; the OCR hint rides under the field
            Column(Modifier.weight(1f)) {
                if (!libraryEmpty) {
                    CompactSearchField(
                        value = state.searchQuery,
                        onValueChange = { vm.searchDocuments(it) },
                        placeholder = s[StringKey.SearchDocuments],
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!state.isOcrAvailable) {
                    if (!libraryEmpty) Spacer(Modifier.height(4.dp))
                    Text(
                        s.format(StringKey.OcrNotInstalled, "${s[StringKey.NavSettings]} → ${s[StringKey.DataAndOcr]}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (state.isUploading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            if (!libraryEmpty) {
                Button(
                    enabled = !state.isUploading,
                    onClick = upload,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(s[StringKey.Upload])
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        MessageBanner(state.message, state.error, vm::clearMessages)

        Spacer(Modifier.height(12.dp))
        // These filter the list rather than switch views, so they read as an exclusive choice, left-aligned
        SingleChoiceRow(
            options = DocumentTab.entries,
            selected = state.tab,
            label = { s.documentTab(it) },
            onSelect = { vm.selectTab(it) },
        )
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FinDeskCard(modifier = Modifier.weight(3f).fillMaxHeight()) {
                if (!state.isLoading && state.documents.isEmpty()) {
                    EmptyState(
                        AppIcons.Description,
                        if (state.searchQuery.isBlank()) s[StringKey.NoDocuments] else s[StringKey.NoMatches],
                        if (state.searchQuery.isBlank()) s[StringKey.UploadHint] else s[StringKey.TryAnotherSearch],
                        // Upload only when there is nothing at all; a search or tab with no hits keeps just the hint
                        action = if (state.searchQuery.isBlank() && state.tab == DocumentTab.ALL) {
                            { Button(enabled = !state.isUploading, onClick = upload) { Text(s[StringKey.Upload]) } }
                        } else {
                            null
                        },
                    )
                }
                LazyColumn {
                    items(state.documents, key = { it.id }) { doc ->
                        RowContextMenu(
                            menuItem(s[StringKey.Open]) { openExternally(File(doc.filePath)) },
                            menuItem(s[revealLabel()]) { revealInFileManager(File(doc.filePath)) },
                            doc.linkedTransactionId?.let { menuItem(s[StringKey.Unlink]) { vm.unlinkFromTransaction(doc.id) } },
                            menuItem(s[StringKey.Delete]) { confirmDelete = doc },
                        ) {
                            DocumentRow(doc, selected = state.selectedDocument?.id == doc.id) { vm.selectDocument(doc) }
                        }
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
            title = s[StringKey.DeleteDocumentTitle],
            text = s.format(StringKey.DeleteDocumentText, doc.fileName),
            onConfirm = { vm.deleteDocument(doc.id) },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun DocumentRow(doc: DocumentItem, selected: Boolean, onClick: () -> Unit) {
    val s = strings()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(
                onClick = onClick,
                onClickLabel = s[StringKey.OpenDocument],
                role = Role.Button,
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (doc.isPdf) AppIcons.Description else AppIcons.Image,
            contentDescription = doc.mimeType,
            tint = if (doc.isPdf) Color(0xFFE53935) else Color(0xFF43A047),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Formats.dateTime(doc.uploadDate) + " · " + (doc.linkedTransactionLabel ?: s[StringKey.NotLinked]),
                style = MaterialTheme.typography.bodySmall,
                color = if (doc.linkedTransactionLabel != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (doc.hasOcr) {
            Icon(Icons.Default.CheckCircle, contentDescription = s[StringKey.TextExtracted], tint = Success, modifier = Modifier.size(16.dp))
        } else {
            Icon(Icons.Default.Clear, contentDescription = s[StringKey.NoText], tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
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
    val s = strings()

    FinDeskCard(modifier = modifier) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(doc.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = s[StringKey.Close]) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { openExternally(File(doc.filePath)) },
                    colors = OutlinedButtonStyle.colors(),
                    border = OutlinedButtonStyle.border(),
                ) { Text(s[StringKey.Open]) }
                OutlinedButton(
                    onClick = onReprocess,
                    colors = OutlinedButtonStyle.colors(),
                    border = OutlinedButtonStyle.border(),
                ) { Text(s[StringKey.ReExtractText]) }
                OutlinedButton(
                    onClick = onDelete,
                    colors = OutlinedButtonStyle.colors(),
                    border = OutlinedButtonStyle.border(),
                ) { Text(s[StringKey.Delete]) }
            }

            val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, doc.id) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    de.findesk.document.DocumentPreview.render(File(doc.filePath))?.toComposeImageBitmap()
                }
            }
            preview?.let {
                androidx.compose.foundation.Image(
                    bitmap = it,
                    contentDescription = s.format(StringKey.PreviewOf, doc.fileName),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clickable(
                            onClick = { openExternally(File(doc.filePath)) },
                            onClickLabel = s.format(StringKey.OpenPreviewNamed, doc.fileName),
                            role = Role.Button,
                        ),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }

            Text(s[StringKey.LinkedTransaction], style = MaterialTheme.typography.labelLarge)
            if (doc.linkedTransactionLabel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(doc.linkedTransactionLabel, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = onUnlink) { Text(s[StringKey.Unlink]) }
                }
            } else {
                if (state.suggestions.isNotEmpty()) {
                    Text(s[StringKey.SuggestedMatches], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.suggestions.forEach { s2 -> LinkCandidateRow(s2.transaction, s.format(StringKey.MatchPercent, "${(s2.score * 100).toInt()}%")) { onLink(s2.transaction.id) } }
                }
                OutlinedTextField(
                    value = pickerQuery,
                    onValueChange = { pickerQuery = it },
                    placeholder = { Text(s[StringKey.FindTransactionToLink]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pickerQuery.isNotBlank()) {
                    state.linkCandidates
                        .filter { it.description.contains(pickerQuery, true) || it.purpose.orEmpty().contains(pickerQuery, true) || Formats.money(it.amount, it.currency).contains(pickerQuery) }
                        .take(8)
                        .forEach { LinkCandidateRow(it, null) { onLink(it.id) } }
                }
            }

            Text(
                s[StringKey.ExtractedText] + (doc.ocrConfidence?.let { s.format(StringKey.ExtractedTextConfidence, it.toInt()) } ?: ""),
                style = MaterialTheme.typography.labelLarge,
            )
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Text(
                    state.selectedOcrText ?: s[StringKey.NoTextExtracted],
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
                "${Formats.date(txn.date)} · ${Formats.money(txn.amount, txn.currency)}" + (badge?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onLink) { Text(strings()[StringKey.Link]) }
    }
}

/** What the file manager is called here: Finder, Explorer, or a plain folder on Linux. */
private fun revealLabel(): StringKey = when {
    Platform.isMac -> StringKey.ShowInFinder
    Platform.isWindows -> StringKey.ShowInExplorer
    else -> StringKey.ShowInFolder
}

/** Shows [file] selected in the file manager; where the JDK can't select it (Linux), opens its folder instead. */
private fun revealInFileManager(file: File) {
    if (!file.exists()) return
    Thread {
        runCatching {
            val desktop = Desktop.getDesktop()
            when {
                desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) -> desktop.browseFileDirectory(file)
                Platform.isWindows -> ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
                else -> desktop.open(file.parentFile)
            }
        }
    }.start()
}

private fun openExternally(file: File) {
    if (file.exists() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Thread { Desktop.getDesktop().open(file) }.start()
    }
}
