package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.TenantWorkspaceSpec
import com.example.enginehost.WorkspaceToolPermissions
import engine.state.EngineStateBridge

/**
 * The workspace's developer surface, opened from the ⋮ menu.
 *
 * It used to be a collapsible block at the bottom of every workspace card, which put debug
 * chrome under the operator's thumb. It now lives behind one explicit menu entry and only
 * carries what is actually useful on device: the origin-scoped client id (with a copyable cURL
 * sample), the native bridge permission notice, and the cached-schema ledger with its rollback
 * entry point.
 */
@Composable
fun DeveloperDiagnosticsSheet(
    uiState: SduiEngineUiState,
    spec: TenantWorkspaceSpec,
    permissions: WorkspaceToolPermissions,
    onOpenWorkspaceSettings: () -> Unit,
    onOpenSchemaRollback: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val bridgeVars by EngineStateBridge.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            color = colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "PUBLIC CLIENT ID (ORIGIN-SCOPED)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurfaceVariant
                )
                Surface(
                    color = colorScheme.background,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = spec.publicClientId,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
                Button(
                    onClick = { copyToSystemClipboard(context, "Public Client ID", spec.publicClientId) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("copy_public_client_id_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy client id", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Text(
                    text = "Dispatch a test push to this terminal:",
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariant
                )
                val curlSnippet = remember(spec.publicClientId, uiState.pushGatewayBase) {
                    """
                    curl -X POST ${uiState.pushGatewayBase}/api/v1/webhook/fcm/dispatch \
                      -H "Authorization: Bearer <gateway-token>" \
                      -H "Content-Type: application/json" \
                      -d '{"clientId": "${spec.publicClientId}", "event": "ORDER_READY", "notification": {"title": "Kitchen Alert", "body": "Order #4091 is ready for pick-up!"}}'
                    """.trimIndent()
                }
                Surface(
                    color = colorScheme.background,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = curlSnippet,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        Surface(
            color = colorScheme.primary.copy(alpha = 0.10f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenWorkspaceSettings)
        ) {
            Text(
                text = "Native Android bridge (${permissions.activeCount}/8 tools active). Haptics, beeper, scanner, " +
                    "biometrics, clipboard, push and realtime are off until you enable them in " +
                    "Workspace Settings. Tap to open it.",
                fontSize = 12.sp,
                color = colorScheme.onSurface,
                modifier = Modifier.padding(14.dp)
            )
        }

        Surface(
            color = colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CACHED SCHEMA • ${uiState.schemaVersions.size} VERSIONS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.primary
                        )
                        Text(
                            text = uiState.activeSchemaVersion?.changeSummary ?: "No cached schema version yet",
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onOpenSchemaRollback,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("open_schema_rollback_button")
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Rollback", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f))

                Text(
                    text = "ENGINESTATEBRIDGE (user.*) • ${bridgeVars.size} vars",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
                bridgeVars.entries
                    .filter { it.key != "user.orders_json" && it.key != "user.catalog_json" }
                    .forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = key,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = value.asDisplayString(),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.onSurface
                            )
                        }
                    }
            }
        }

        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colorScheme.outline.copy(alpha = 0.3f))
        )
    }
}
