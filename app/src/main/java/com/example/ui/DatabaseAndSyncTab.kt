package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiEngineViewModel

@Composable
fun DatabaseAndSyncTab(
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel
) {
    val ws = uiState.activeWorkspace

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Physical SQLite Isolation & Sync Controls
        Surface(
            color = Color(0xFF1E293B),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFF38BDF8)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "PHYSICAL SQLITE TENANT ISOLATION",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                        Text(
                            "Active File: workspace_${ws.tenantId}.db",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFF8FAFC)
                        )
                    }
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${uiState.pendingSyncEntries.size} Pending Sync",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFBBF24),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                Text(
                    "Open SQLite Drivers: ${uiState.activeDbFiles.joinToString(", ").ifBlank { "workspace_${ws.tenantId}.db" }}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.triggerSyncNow() },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("sync_now_button")
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "Sync Now", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sync Outbox", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { viewModel.clearActiveWorkspace() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171)),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("nuclear_clear_workspace_button")
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = "Clear Workspace", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("workspace://clear_all", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                TextButton(
                    onClick = { viewModel.reseedActiveWorkspace() },
                    modifier = Modifier.align(Alignment.End).testTag("reseed_workspace_button")
                ) {
                    Icon(Icons.Default.Restore, contentDescription = "Re-seed", modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Restore Default Seed Data for ${ws.tenantId}", fontSize = 11.sp)
                }
            }
        }

        // 2. PRAGMA table_info Schema Inspector
        Surface(
            color = Color(0xFF152033),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "SCHEMA GUARD (PRAGMA table_info in workspace_${ws.tenantId}.db)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF34D399)
                )
                uiState.schemaTables.forEach { tbl ->
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = tbl.tableName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF38BDF8)
                            )
                            Text(
                                text = tbl.columns.joinToString(" • "),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }
        }

        // 3. Pending Sync Outbox (`pending_sync` table)
        Surface(
            color = Color(0xFF152033),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "PENDING_SYNC OUTBOX TABLE (${uiState.pendingSyncEntries.size} rows)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFBBF24)
                )
                if (uiState.pendingSyncEntries.isEmpty()) {
                    Text(
                        "Outbox is clean — all local-db mutations have been synced to ${uiState.activeConfig?.edgeEndpoint}.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                } else {
                    uiState.pendingSyncEntries.take(8).forEach { row ->
                        Surface(
                            color = Color(0xFF0F172A),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "${row.op} • uuid=${row.clientUuid.take(12)}... • attempts=${row.attempts}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFFDE68A)
                                )
                                Text(
                                    text = row.payload,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF94A3B8),
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. EngineRegistry Audit Log (`op_log` table)
        Surface(
            color = Color(0xFF152033),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "ENGINEREGISTRY OP_LOG AUDIT TRAIL (${uiState.opLogs.size} recent ops)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF818CF8)
                )
                uiState.opLogs.take(12).forEach { log ->
                    val isError = log.error != null
                    Surface(
                        color = if (isError) Color(0xFF3F1D24) else Color(0xFF0F172A),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "#${log.id} ${log.op} (${log.durationMs}ms)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isError) Color(0xFFFCA5A5) else Color(0xFFE2E8F0)
                                )
                                if (isError) {
                                    Text(
                                        text = "Blocked: ${log.error}",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFF87171)
                                    )
                                }
                            }
                            Text(
                                text = if (isError) "REJECTED" else "rows=${log.rowcount ?: 0}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isError) Color(0xFFF87171) else Color(0xFF34D399)
                            )
                        }
                    }
                }
            }
        }
    }
}
