package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.enginehost.FilePreview
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiEngineViewModel
import engine.fs.FsEntry
import engine.fs.WorkspaceFileSystem
import engine.fs.WorkspaceFolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SurfaceDark = Color(0xFF152033)
private val SurfaceDarker = Color(0xFF0F172A)
private val CardBorder = Color(0xFF334155)
private val AccentBlue = Color(0xFF38BDF8)
private val AccentGreen = Color(0xFF34D399)
private val AccentAmber = Color(0xFFFBBF24)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)

/**
 * Workspace file manager. Lists every tenant sandbox folder under `files/workspaces/`,
 * lets the user browse each one, and performs create / rename / delete on its contents.
 *
 * When [initialFolderId] is supplied (e.g. from an open tenant's overflow menu) the browser
 * jumps straight into that workspace's folder instead of showing the folder list.
 */
@Composable
fun FileManagerTab(
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel,
    initialFolderId: String? = null
) {
    LaunchedEffect(Unit) { viewModel.refreshWorkspaceFolders() }

    // Deep-link into the requested tenant once its folder is known. Keyed on the id so
    // reopening the sheet re-resolves the folder even if the list was already loaded.
    LaunchedEffect(initialFolderId, uiState.workspaceFolders) {
        val target = initialFolderId?.let { id ->
            uiState.workspaceFolders.firstOrNull { it.workspaceId == id }
        }
        if (target != null && uiState.fileBrowserFolder?.workspaceId != target.workspaceId) {
            viewModel.openWorkspaceFolder(target)
        }
    }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FsEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FsEntry?>(null) }

    val folder = uiState.fileBrowserFolder

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FileManagerHeader(
            folder = folder,
            folders = uiState.workspaceFolders,
            onRefresh = {
                if (folder == null) viewModel.refreshWorkspaceFolders()
                else viewModel.openWorkspaceFolder(folder)
            }
        )

        uiState.statusBannerMessage?.let { message ->
            Surface(color = SurfaceDarker, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = message,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AccentAmber,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }

        if (folder == null) {
            WorkspaceFolderList(
                folders = uiState.workspaceFolders,
                onOpen = { viewModel.openWorkspaceFolder(it) }
            )
        } else {
            FileBrowser(
                folder = folder,
                uiState = uiState,
                viewModel = viewModel,
                onNewFolder = { showNewFolderDialog = true },
                onNewFile = { showNewFileDialog = true },
                onRename = { renameTarget = it },
                onDelete = { deleteTarget = it }
            )
        }
    }

    if (showNewFolderDialog) {
        NameEntryDialog(
            title = "New folder",
            label = "Folder name",
            confirmLabel = "Create",
            onConfirm = { name ->
                viewModel.createFolder(name)
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    if (showNewFileDialog) {
        NameEntryDialog(
            title = "New file",
            label = "File name",
            confirmLabel = "Create",
            initial = "notes.txt",
            onConfirm = { name ->
                viewModel.createFile(name)
                showNewFileDialog = false
            },
            onDismiss = { showNewFileDialog = false }
        )
    }

    renameTarget?.let { target ->
        NameEntryDialog(
            title = "Rename",
            label = "New name",
            confirmLabel = "Rename",
            initial = target.name,
            onConfirm = { name ->
                viewModel.renameFileEntry(target, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete \"${target.name}\"?",
            message = if (target.isDirectory) {
                "This removes the folder and everything inside it. This cannot be undone."
            } else {
                "This permanently deletes the file. This cannot be undone."
            },
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.deleteFileEntry(target)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }

    uiState.filePreview?.let { preview ->
        FilePreviewDialog(preview = preview, onDismiss = { viewModel.closeFilePreview() })
    }
}

@Composable
private fun FileManagerHeader(
    folder: WorkspaceFolder?,
    folders: List<WorkspaceFolder>,
    onRefresh: () -> Unit
) {
    val totalBytes = folders.sumOf { it.sizeBytes }
    Surface(
        color = Color(0xFF1E293B),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, AccentBlue),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WORKSPACE FILE MANAGER",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue
                )
                Text(
                    text = if (folder == null) {
                        "${folders.size} workspace folders • ${WorkspaceFileSystem.humanSize(totalBytes)}"
                    } else {
                        folder.displayName
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary
                )
                if (folder != null) {
                    Text(
                        text = folder.dirPath,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onRefresh, modifier = Modifier.testTag("file_manager_refresh")) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentBlue)
            }
        }
    }
}

@Composable
private fun WorkspaceFolderList(
    folders: List<WorkspaceFolder>,
    onOpen: (WorkspaceFolder) -> Unit
) {
    if (folders.isEmpty()) {
        EmptyState("No workspace folders yet. Open a tenant to create its sandbox.")
        return
    }
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "WORKSPACE FOLDERS (files/workspaces)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = AccentGreen
            )
            folders.forEach { folder ->
                Surface(
                    color = SurfaceDarker,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(folder) }
                        .testTag("workspace_folder_${folder.workspaceId}")
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = AccentBlue)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = folder.displayName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${folder.workspaceId} • ${folder.fileCount} files • " +
                                    WorkspaceFileSystem.humanSize(folder.sizeBytes),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBrowser(
    folder: WorkspaceFolder,
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel,
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    onRename: (FsEntry) -> Unit,
    onDelete: (FsEntry) -> Unit
) {
    val atRoot = uiState.fileBrowserPath == folder.dirPath

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Breadcrumb + up navigation
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { if (atRoot) viewModel.closeWorkspaceFolder() else viewModel.navigateUp() },
                    modifier = Modifier.testTag("file_manager_up")
                ) {
                    Icon(
                        imageVector = if (atRoot) Icons.Default.Close else Icons.Default.ArrowUpward,
                        contentDescription = if (atRoot) "Close folder" else "Up one level",
                        tint = AccentBlue
                    )
                }
                Text(
                    text = breadcrumb(folder.dirPath, uiState.fileBrowserPath),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onNewFolder,
                    modifier = Modifier.weight(1f).testTag("file_manager_new_folder")
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Folder", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onNewFile,
                    modifier = Modifier.weight(1f).testTag("file_manager_new_file")
                ) {
                    Icon(Icons.Default.NoteAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New File", fontSize = 12.sp)
                }
            }

            if (uiState.fileEntries.isEmpty()) {
                EmptyState("This folder is empty.")
            } else {
                uiState.fileEntries.forEach { entry ->
                    FileEntryRow(
                        entry = entry,
                        onOpen = { viewModel.openFileEntry(entry) },
                        onRename = { onRename(entry) },
                        onDelete = { onDelete(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    entry: FsEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = SurfaceDarker,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .testTag("file_entry_${entry.name}")
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = iconFor(entry),
                contentDescription = null,
                tint = if (entry.isDirectory) AccentBlue else TextMuted
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(WorkspaceFileSystem.humanSize(entry.sizeBytes))
                        append(" • ")
                        append(formatTimestamp(entry.lastModified))
                        if (entry.isReadOnly) append(" • read-only")
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag("file_entry_menu_${entry.name}")
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions", tint = TextMuted)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        enabled = !entry.isReadOnly,
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        enabled = !entry.isReadOnly,
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Text(text = message, fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun NameEntryDialog(
    title: String,
    label: String,
    confirmLabel: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    val valid = WorkspaceFileSystem.isSafeName(value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                isError = value.isNotEmpty() && !valid,
                modifier = Modifier.fillMaxWidth().testTag("name_entry_field")
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(value.trim()) },
                enabled = valid,
                modifier = Modifier.testTag("name_entry_confirm")
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF87171)),
                modifier = Modifier.testTag("confirm_delete_button")
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun FilePreviewDialog(
    preview: FilePreview,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = AccentBlue)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preview.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = preview.sizeLabel + if (preview.truncated) " • preview truncated" else "",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close preview", tint = TextMuted)
                    }
                }
                HorizontalDivider(color = CardBorder)
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = preview.content.ifEmpty { "(empty file)" },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

private fun iconFor(entry: FsEntry) = when {
    entry.isDirectory -> Icons.Default.Folder
    entry.extension in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") -> Icons.Default.Image
    entry.extension == "db" || entry.extension == "sqlite" -> Icons.Default.Storage
    entry.isTextPreviewable -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}

private fun breadcrumb(rootPath: String, currentPath: String): String {
    if (currentPath == rootPath) return "workspaces/${rootPath.substringAfterLast('/')}"
    val relative = currentPath.removePrefix(rootPath).trimStart('/')
    return "workspaces/${rootPath.substringAfterLast('/')}/$relative"
}

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
}
