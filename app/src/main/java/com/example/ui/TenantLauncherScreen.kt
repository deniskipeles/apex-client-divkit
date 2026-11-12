package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * - Connected tenant cards with notification badge, cached schema version badge and tools status
 *   pill ("🛠️ 6 tools active" vs "🔒 Tools off (default)")
 * - Context menu (Workspace Settings, Open Terminal, Disconnect Workspace) opened by
 *   **long-pressing** a tenant card (the former 3-dot overflow button was removed)
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
            if (filteredWorkspaces.isEmpty()) {
                EmptyLauncherState(
                    hasSearch = uiState.searchQuery.isNotBlank(),
                    onConnectWorkspace = { onShowConnectWorkspaceSheetChange(true) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .testTag("tenant_launcher_empty_state")
                )
            } else {
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

/** Shown when no tenant matches the current search (or none are connected). */
@Composable
private fun EmptyLauncherState(
    hasSearch: Boolean,
    onConnectWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (hasSearch) Icons.Default.SearchOff else Icons.Default.Storefront,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = if (hasSearch) "No terminals match your search" else "No workspaces connected yet",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface
            )
            Text(
                text = if (hasSearch) {
                    "Try a different name, register code or URL."
                } else {
                    "Connect a Desktop Client-Server workspace to render its DivKit SDUI surface."
                },
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (!hasSearch) {
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(onClick = onConnectWorkspace) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Connect New Workspace")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    val accent = remember(spec.accentHex) { parseAccentColor(spec.accentHex) }

    Surface(
        color = colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            // Square card: the icon claims the top third, the copy sits at the bottom.
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showCardMenu = true }
            )
            .testTag("tenant_grid_card_${spec.tenantId}")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Soft accent wash behind the icon zone, fading into the card surface.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.22f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Column(modifier = Modifier.fillMaxSize()) {
                // Icon zone — the icon occupies exactly the top third of the square,
                // horizontally centered and flush with the top edge.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Icon(
                        imageVector = if (spec.isBuiltInDemo) Icons.Default.Terminal
                        else Icons.Default.PointOfSale,
                        contentDescription = spec.displayName,
                        tint = accent,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                    )
                }

                // Copy zone — bottom two thirds, content pinned to the bottom.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(2f)
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        text = spec.displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(2.dp))

                    Text(
                        text = if (spec.isBuiltInDemo) "⚡ DivKit SDUI Showcase"
                        else "🌍 ${spec.tunnelUrl.removePrefix("https://").removePrefix("http://")}",
                        fontSize = 12.sp,
                        fontWeight = if (spec.isBuiltInDemo) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (spec.isBuiltInDemo) accent else colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = cachedBadge,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(8.dp))

                    ToolsStatusPill(activeTools = activeTools)
                }
            }

            // Unread badge (top-end). Long-press opens the tenant menu instead of a 3-dot button.
            if (spec.unreadCount > 0) {
                Surface(
                    color = Color(0xFFEF4444),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
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

            Box(modifier = Modifier.align(Alignment.BottomEnd)) {
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

@OptIn(ExperimentalFoundationApi::class)
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
    val accent = remember(spec.accentHex) { parseAccentColor(spec.accentHex) }

    Surface(
        color = colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showCardMenu = true }
            )
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
                color = accent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(50.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (spec.isBuiltInDemo) Icons.Default.Terminal
                        else Icons.Default.PointOfSale,
                        contentDescription = spec.displayName,
                        tint = accent,
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
                    text = if (spec.isBuiltInDemo) "⚡ DivKit SDUI Showcase • $cachedBadge"
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

/** Parses a `#RRGGBB` accent string into a Compose [Color], falling back to the theme primary. */
private fun parseAccentColor(hex: String, fallback: Color = Color(0xFF38BDF8)): Color {
    val cleaned = hex.trim().removePrefix("#")
    return runCatching {
        when (cleaned.length) {
            6 -> Color(("FF$cleaned").toLong(16))
            8 -> Color(cleaned.toLong(16))
            else -> fallback
        }
    }.getOrDefault(fallback)
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
