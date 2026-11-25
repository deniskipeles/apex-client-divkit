package com.example.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.enginehost.TenantWorkspaceSpec
import engine.workspace.DevWorkspaceImport
import engine.workspace.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dev-channel authoring surface.
 *
 * Loads a DivKit card, a collection schema or a zip of icons into the selected workspace,
 * and unloads an imported copy to fall back to the packaged one. Production builds never
 * show this: the importer itself refuses to run outside the dev channel.
 */
@Composable
fun DevWorkspaceToolsDialog(
    workspaces: List<TenantWorkspaceSpec>,
    loadErrors: Map<String, String>,
    onClose: () -> Unit,
    onReload: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filesDir: File = context.filesDir
    var selected by remember(workspaces) { mutableStateOf(workspaces.firstOrNull()?.tenantId.orEmpty()) }
    var status by remember { mutableStateOf("") }
    var imported by remember { mutableStateOf(WorkspaceRepository.importedIds(filesDir)) }

    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (text == null) {
                status = "Could not read that file"
                return@launch
            }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "card.json"
            val outcome = withContext(Dispatchers.IO) {
                DevWorkspaceImport.importCard(selected, name, text, filesDir)
            }
            status = "DivKit card: ${outcome.message}"
            imported = withContext(Dispatchers.IO) { WorkspaceRepository.importedIds(filesDir) }
            if (outcome.ok) onReload()
        }
    }

    val collectionPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (text == null) {
                status = "Could not read that file"
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                DevWorkspaceImport.importCollection(selected, text, filesDir)
            }
            status = "Collection: ${outcome.message}"
            imported = withContext(Dispatchers.IO) { WorkspaceRepository.importedIds(filesDir) }
            if (outcome.ok) onReload()
        }
    }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                status = "Could not read that file"
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                DevWorkspaceImport.importIconPack(selected, bytes, filesDir)
            }
            status = "Icon pack: ${outcome.message}"
            imported = withContext(Dispatchers.IO) { WorkspaceRepository.importedIds(filesDir) }
            if (outcome.ok) onReload()
        }
    }

    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Workspace packages", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Channel: ${WorkspaceRepository.mode()} · imports are stored under filesDir/workspaces",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Target workspace", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    workspaces.forEach { spec ->
                        if (spec.tenantId == selected) {
                            Button(onClick = { selected = spec.tenantId }, modifier = Modifier.height(40.dp)) {
                                Text(spec.displayName)
                            }
                        } else {
                            OutlinedButton(onClick = { selected = spec.tenantId }, modifier = Modifier.height(40.dp)) {
                                Text(spec.displayName)
                            }
                        }
                    }
                }

                Button(
                    onClick = { jsonPicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Load DivKit card JSON") }
                Button(
                    onClick = { collectionPicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Load collection schema JSON") }
                Button(
                    onClick = { zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Load icon pack (.zip)") }

                if (imported.isNotEmpty()) {
                    Text("Imported copies", style = MaterialTheme.typography.labelLarge)
                    imported.forEach { id ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(id, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                val outcome = DevWorkspaceImport.unload(id, filesDir)
                                status = outcome.message
                                imported = WorkspaceRepository.importedIds(filesDir)
                                onReload()
                            }) { Text("Unload") }
                        }
                    }
                }

                if (loadErrors.isNotEmpty()) {
                    Text("Package errors", style = MaterialTheme.typography.labelLarge)
                    loadErrors.forEach { (id, reason) ->
                        Text(
                            "$id: $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
