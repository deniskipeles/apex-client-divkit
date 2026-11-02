package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiEngineViewModel
import com.example.enginehost.TenantWorkspaceSpec
import com.example.enginehost.WorkspaceToolPermissions

/**
 * Home Page Multi-Tenant Launcher (Defaults to 2-Column Grid View, toggleable to 1-Column List View).
 *
 * Includes:
 * - Search bar ("Search terminals, registers, URLs...")
 * - Connected tenant cards with notification badge, 3-dot context menu
 *   (Workspace Settings, Open Terminal, Disconnect Workspace), cached schema version badge,
 *   and tools status pill ("🛠️ 6 tools active" vs "🔒 Tools off (default)")
 * - Floating `+` FAB to open Connect New Workspace bottom sheet.
 */
@Composable
fun TenantLauncherScreen(
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel,
    showConnectWorkspaceSheet: Boolean,
    onShowConnectWorkspaceSheetChange: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var settingsTargetTenant by remember { mutableStateOf<TenantWorkspaceSpec?>(null) }

    val filteredWorkspaces = remember(uiState.workspaces, uiState.searchQuery) {
        val q = uiState.searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            uiState.workspaces
        } else {
            uiState.workspaces.filter { spec ->
                spec.displayName.lowercase().contains(q) ||
                    spec.tenantId.lowercase().contains(q) ||
                    spec.terminalCode.lowercase().contains(q) ||
                    spec.tunnelUrl.lowercase().contains(q) ||
                    spec.subtitle.lowercase().contains(q)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search Bar ("Search terminals, registers, URLs...")
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = {
                    Text(
                        text = "Search terminals, registers, URLs...",
                        fontSize = 15.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search terminals",
                        tint = colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colorScheme.surface,
                    unfocusedContainerColor = colorScheme.surface,
                    focusedBorderColor = colorScheme.primary,
                    unfocusedBorderColor = colorScheme.outline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("launcher_search_input")
            )

            // 2-Column Grid (Default) or 1-Column List
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (uiState.isGridView) 2 else 1),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 6.dp,
                    bottom = 96.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("tenant_launcher_grid")
            ) {
                items(
                    items = filteredWorkspaces,
                    key = { it.tenantId }
                ) { spec ->
                    val perms = uiState.tenantToolsMap[spec.tenantId] ?: spec.defaultTools
                    val cachedBadge = uiState.tenantCachedBadgeMap[spec.tenantId] ?: "Schema v1.1 (2 cached)"

                    if (uiState.isGridView) {
                        ConnectedTenantGridCard(
                            spec = spec,
                            permissions = perms,
                            cachedBadge = cachedBadge,
                            onClick = { viewModel.openTenantWorkspace(spec) },
                            onOpenWorkspaceSettings = { settingsTargetTenant = spec },
                            onDisconnectWorkspace = { viewModel.disconnectWorkspace(spec.tenantId) }
                        )
                    } else {
                        ConnectedTenantListCard(
                            spec = spec,
                            permissions = perms,
                            cachedBadge = cachedBadge,
                            onClick = { viewModel.openTenantWorkspace(spec) },
                            onOpenWorkspaceSettings = { settingsTargetTenant = spec },
                            onDisconnectWorkspace = { viewModel.disconnectWorkspace(spec.tenantId) }
                        )
                    }
                }
            }
        }

        // Bottom-Right Floating `+` Button (Screenshot 1 & 2)
        FloatingActionButton(
            onClick = { onShowConnectWorkspaceSheetChange(true) },
            containerColor = colorScheme.primary,
            contentColor = Color.White,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(60.dp)
                .testTag("launcher_add_tenant_button")
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Connect New Workspace",
                modifier = Modifier.size(28.dp)
            )
        }
    }

    // Connect New Workspace Bottom Sheet (Screenshot 4)
    if (showConnectWorkspaceSheet) {
        ConnectNewWorkspaceBottomSheet(
            viewModel = viewModel,
            onDismiss = { onShowConnectWorkspaceSheetChange(false) }
        )
    }

    // Per-Tenant Workspace Settings Bottom Sheet (Screenshots 6, 8, 9)
    settingsTargetTenant?.let { target ->
        val currentPerms = uiState.tenantToolsMap[target.tenantId] ?: target.defaultTools
        WorkspaceSettingsBottomSheet(
            spec = target,
            initialPermissions = currentPerms,
            onSave = { updatedPerms ->
                viewModel.saveWorkspaceToolPermissions(target.tenantId, updatedPerms)
            },
            onDismiss = { settingsTargetTenant = null }
        )
    }
}

@Composable
private fun ConnectedTenantGridCard(
    spec: TenantWorkspaceSpec,
    permissions: WorkspaceToolPermissions,
    cachedBadge: String,
    onClick: () -> Unit,
    onOpenWorkspaceSettings: () -> Unit,
    onDisconnectWorkspace: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var showCardMenu by remember { mutableStateOf(false) }
    val activeTools = permissions.activeCount

    Surface(
        color = colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 198.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag("tenant_grid_card_${spec.tenantId}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Top Row: Rounded Icon Box + Notification Count Badge + 3-Dot Menu
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (spec.isBuiltInDemo) colorScheme.primary
                        else colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (spec.isBuiltInDemo) Icons.Default.Terminal
                                else Icons.Default.PointOfSale,
                                contentDescription = spec.displayName,
                                tint = if (spec.isBuiltInDemo) Color.White else colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (spec.unreadCount > 0) {
                            Surface(
                                color = Color(0xFFEF4444),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = spec.unreadCount.toString(),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Box {
                            IconButton(
                                onClick = { showCardMenu = true },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("tenant_card_menu_${spec.tenantId}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Tenant Options",
                                    tint = colorScheme.onSurfaceVariant
                                )
                            }

                            TenantCardOverflowMenu(
                                expanded = showCardMenu,
                                onDismiss = { showCardMenu = false },
                                onWorkspaceSettings = {
                                    showCardMenu = false
                                    onOpenWorkspaceSettings()
                                },
                                onOpenTerminal = {
                                    showCardMenu = false
                                    onClick()
                                },
                                onDisconnectWorkspace = {
                                    showCardMenu = false
                                    onDisconnectWorkspace()
                                }
                            )
                        }
                    }
                }

                // Tenant Title + Built-in Demo / Tunnel Subtitle + Cached Schema Version
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = spec.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (spec.isBuiltInDemo) "⚡ Built-in Demo"
                            else "🌍 Tunnel: ${spec.tunnelUrl.removePrefix("https://").removePrefix("http://")}",
                            fontSize = 12.sp,
                            fontWeight = if (spec.isBuiltInDemo) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (spec.isBuiltInDemo) colorScheme.primary else colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = cachedBadge,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Bottom Tools Pill: "🛠️ 6 tools active" vs "🔒 Tools off (default)"
            ToolsStatusPill(activeTools = activeTools)
        }
    }
}

@Composable
private fun ConnectedTenantListCard(
    spec: TenantWorkspaceSpec,
    permissions: WorkspaceToolPermissions,
    cachedBadge: String,
    onClick: () -> Unit,
    onOpenWorkspaceSettings: () -> Unit,
    onDisconnectWorkspace: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var showCardMenu by remember { mutableStateOf(false) }
    val activeTools = permissions.activeCount

    Surface(
        color = colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag("tenant_list_card_${spec.tenantId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                color = if (spec.isBuiltInDemo) colorScheme.primary
                else colorScheme.primary.copy(alpha = 0.14f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(50.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (spec.isBuiltInDemo) Icons.Default.Terminal
                        else Icons.Default.PointOfSale,
                        contentDescription = spec.displayName,
                        tint = if (spec.isBuiltInDemo) Color.White else colorScheme.primary,
                        modifier = Modifier.size(25.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = spec.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (spec.isBuiltInDemo) "⚡ Built-in Terminal Demo • $cachedBadge"
                    else "🌍 Public Tunnel: ${spec.tunnelUrl} • $cachedBadge",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ToolsStatusPill(activeTools = activeTools)
            }

            if (spec.unreadCount > 0) {
                Surface(
                    color = Color(0xFFEF4444),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = spec.unreadCount.toString(),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { showCardMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Tenant Options",
                        tint = colorScheme.onSurfaceVariant
                    )
                }

                TenantCardOverflowMenu(
                    expanded = showCardMenu,
                    onDismiss = { showCardMenu = false },
                    onWorkspaceSettings = {
                        showCardMenu = false
                        onOpenWorkspaceSettings()
                    },
                    onOpenTerminal = {
                        showCardMenu = false
                        onClick()
                    },
                    onDisconnectWorkspace = {
                        showCardMenu = false
                        onDisconnectWorkspace()
                    }
                )
            }
        }
    }
}

@Composable
private fun ToolsStatusPill(activeTools: Int) {
    val colorScheme = MaterialTheme.colorScheme
    if (activeTools > 0) {
        Surface(
            color = colorScheme.primary.copy(alpha = 0.12f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "🛠️ $activeTools tools active",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
            )
        }
    } else {
        Surface(
            color = colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "🔒 Tools off (default)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
            )
        }
    }
}

/**
 * 3-Dot Popup Menu on each Tenant Card (Screenshot 10):
 * - Workspace Settings
 * - Open Terminal
 * - Disconnect Workspace
 */
@Composable
private fun TenantCardOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onWorkspaceSettings: () -> Unit,
    onOpenTerminal: () -> Unit,
    onDisconnectWorkspace: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Workspace Settings", fontWeight = FontWeight.Medium) },
            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
            onClick = onWorkspaceSettings
        )
        DropdownMenuItem(
            text = { Text("Open Terminal", fontWeight = FontWeight.Medium) },
            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
            onClick = onOpenTerminal
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = {
                Text(
                    text = "Disconnect Workspace",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            onClick = onDisconnectWorkspace
        )
    }
}
