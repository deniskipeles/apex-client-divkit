package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiEngineViewModel
import com.example.enginehost.TenantWorkspaceSpec
import engine.config.EngineConfig

/**
 * Home Page Multi-Tenant Grid Launcher (2 Columns).
 *
 * Adapts to the system theme (Light or Dark) and displays connected tenants
 * in a two-column grid. Clicking any tenant opens its full-screen self-contained
 * workspace rendered on the fly from its signed DivKit schema.
 */
@Composable
fun TenantLauncherScreen(
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel
) {
    var showCreateWorkspaceDialog by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .testTag("tenant_launcher_grid")
    ) {
        // Full-width Hero Banner & Connect Tenant Button
        item(span = { GridItemSpan(2) }) {
            Surface(
                color = colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = colorScheme.primary.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.Apps,
                                    contentDescription = "Tenant Launcher",
                                    tint = colorScheme.primary,
                                    modifier = Modifier.padding(8.dp).size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "CONNECTED TENANT HUB",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.primary
                                )
                                Text(
                                    text = "Select a Tenant to Launch Full-Screen SDUI",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Text(
                        text = "Each tenant runs as a self-contained full-screen app built on the fly from its signed DivKit schema, with a top-center standout pill for Hub controls.",
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.VerifiedUser,
                                contentDescription = "Ed25519 Ready",
                                tint = colorScheme.tertiary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "${uiState.workspaces.size} Connected Tenants • Ed25519 Active",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.tertiary
                            )
                        }

                        FilledTonalButton(
                            onClick = { showCreateWorkspaceDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 40.dp)
                                .testTag("launcher_add_tenant_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Tenant", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("New Tenant", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2-Column Grid of Connected Tenants
        items(
            items = uiState.workspaces,
            key = { it.tenantId }
        ) { spec ->
            ConnectedTenantGridCard(
                spec = spec,
                onClick = { viewModel.openTenantWorkspace(spec) }
            )
        }
    }

    if (showCreateWorkspaceDialog) {
        ProvisionTenantDialog(
            onDismiss = { showCreateWorkspaceDialog = false },
            onCreate = { id, name, term, mode, caps ->
                viewModel.createNewWorkspace(id, name, term, mode, caps)
                showCreateWorkspaceDialog = false
            }
        )
    }
}

@Composable
private fun ConnectedTenantGridCard(
    spec: TenantWorkspaceSpec,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.luminance() < 0.5f
    val accentColor = remember(spec.accentHex, isDark) {
        val raw = parseLauncherHexColor(spec.accentHex)
        if (!isDark && spec.accentHex.uppercase() == "#38BDF8") Color(0xFF0284C7)
        else if (!isDark && spec.accentHex.uppercase() == "#34D399") Color(0xFF059669)
        else raw
    }

    Surface(
        color = colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.2.dp, accentColor.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 196.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("tenant_grid_card_${spec.tenantId}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Top Row: Icon + Mode Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = accentColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = when (spec.mode) {
                                EngineConfig.Mode.HYBRID -> Icons.Default.PointOfSale
                                EngineConfig.Mode.ONLINE -> Icons.Default.Storefront
                                EngineConfig.Mode.OFFLINE -> Icons.Default.LocalPharmacy
                            },
                            contentDescription = spec.displayName,
                            tint = accentColor,
                            modifier = Modifier.padding(8.dp).size(20.dp)
                        )
                    }

                    Surface(
                        color = colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = spec.mode.name,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = accentColor
                            )
                        }
                    }
                }

                // Tenant Title & Terminal Code
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = spec.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${spec.terminalCode} • id: ${spec.tenantId}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Physical SQLite DB & Schema Tag
                Surface(
                    color = colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "DB: workspace_${spec.tenantId}.db",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Grants: ${spec.capabilities.joinToString(", ")}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Footer CTA: Open Dedicated SDUI Workspace
            Surface(
                color = accentColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Open Workspace",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Launch Workspace",
                        tint = accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ProvisionTenantDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, EngineConfig.Mode, Set<String>) -> Unit
) {
    var tenantId by remember { mutableStateOf("tokyo_ramen_06") }
    var displayName by remember { mutableStateOf("Tokyo Ramen Kiosk") }
    var terminalCode by remember { mutableStateOf("KIOSK-06") }
    var selectedMode by remember { mutableStateOf(EngineConfig.Mode.HYBRID) }
    var capScanner by remember { mutableStateOf(true) }
    var capPrinter by remember { mutableStateOf(true) }
    var capNfc by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect New Tenant Workspace", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Provisions an isolated SQLite file (workspace_<id>.db), signs a new EngineConfig, and generates a DivKit SDUI schema on the fly.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = tenantId,
                    onValueChange = { tenantId = it },
                    label = { Text("tenant_id ([a-zA-Z0-9_-]{1,64})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_new_tenant_id")
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Tenant Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = terminalCode,
                    onValueChange = { terminalCode = it },
                    label = { Text("POS Terminal ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EngineConfig.Mode.entries.forEach { m ->
                        FilterChip(
                            selected = selectedMode == m,
                            onClick = { selectedMode = m },
                            label = { Text(m.name, fontSize = 11.sp) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = capScanner,
                        onClick = { capScanner = !capScanner },
                        label = { Text("scanner", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = capPrinter,
                        onClick = { capPrinter = !capPrinter },
                        label = { Text("printer", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = capNfc,
                        onClick = { capNfc = !capNfc },
                        label = { Text("nfc", fontSize = 11.sp) }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val caps = buildSet {
                        if (capScanner) add("scanner")
                        if (capPrinter) add("printer")
                        if (capNfc) add("nfc")
                    }
                    onCreate(tenantId, displayName, terminalCode, selectedMode, caps)
                },
                modifier = Modifier.testTag("confirm_create_workspace_button")
            ) {
                Text("Connect & Open Workspace")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun parseLauncherHexColor(hexStr: String): Color {
    val clean = hexStr.trim().removePrefix("#")
    return try {
        when (clean.length) {
            6 -> Color(("FF$clean").toLong(16))
            8 -> Color(clean.toLong(16))
            else -> Color(0xFF38BDF8)
        }
    } catch (_: Exception) {
        Color(0xFF38BDF8)
    }
}
