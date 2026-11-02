package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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
import engine.state.EngineStateBridge
import engine.ui.EngineScreen
import engine.ui.rememberDivKitDependencies

private enum class ActiveHostOverlay {
    NONE,
    SDUI_BUILDER,
    DB_AND_SYNC,
    SECURITY_KMP
}

/**
 * Self-Contained Full-Screen Tenant Workspace View.
 *
 * Gives 100% of the screen space to the tenant's dynamically rendered DivKit UI
 * ([EngineScreen] -> [com.yandex.divkit.multiplatform.DivView]).
 *
 * Features a top-center non-shadowing **Standout Pill** (visible on both Light and
 * Dark system themes) that expands on click into the **Hub AppBar** (`< Hub` exit
 * button, tenant title & tunnel status, Refresh button, and 3-dot Menu).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosTerminalTab(
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel,
    onExitWorkspace: () -> Unit = { viewModel.returnToLauncher() }
) {
    val divKitDeps = rememberDivKitDependencies()
    val bridgeVars by EngineStateBridge.state.collectAsState()
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.luminance() < 0.5f

    // Controls whether the top-center Standout Pill has been clicked to reveal the `< Hub` AppBar
    var isAppBarExpanded by remember(uiState.activeWorkspace.tenantId) { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var activeOverlay by remember { mutableStateOf(ActiveHostOverlay.NONE) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        // 1. Full-Screen Self-Contained Tenant SDUI Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = if (isAppBarExpanded) 68.dp else 18.dp,
                    bottom = 20.dp
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.verifiedDivKitJson.isNotBlank()) {
                EngineScreen(
                    divKitDependencies = divKitDeps,
                    initialJson = uiState.verifiedDivKitJson,
                    cardId = "pos_card_${uiState.activeWorkspace.tenantId}",
                    onAction = { url, payload ->
                        viewModel.dispatchDivKitAction(url, payload)
                    }
                )
            }

            // Compact reactive variable footer inside the tenant scroll view
            Surface(
                color = colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "ENGINESTATEBRIDGE (user.* StateFlow)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.primary
                        )
                        Text(
                            "Ed25519 Verified • ${bridgeVars.size} vars",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.tertiary
                        )
                    }
                    bridgeVars.entries
                        .filter { it.key != "user.orders_json" && it.key != "user.inventory_json" }
                        .forEach { (k, v) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = k,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = v.asDisplayString(),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.onSurface
                                )
                            }
                        }
                }
            }
        }

        // 2. Top-Center Standout Pill (Collapsed State — Zero Shadow, High Contrast on Both Themes)
        if (!isAppBarExpanded) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(50))
                    .clickable { isAppBarExpanded = true }
                    .testTag("workspace_standout_pill"),
                contentAlignment = Alignment.Center
            ) {
                // High-contrast pill body that stands out on both Dark (#0B1120) and Light (#F1F5F9 / #FFFFFF) themes
                // Zero shadow (no shadowElevation / no tonalElevation) so nothing in the workspace is shadowed
                val pillFill = if (isDark) Color(0xFF64748B) else Color(0xFF8E95A2)
                val pillBorder = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)

                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(50))
                        .background(pillFill)
                        .border(1.dp, pillBorder, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    // Crisp inner white handle bar (matching Screenshot 2)
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White)
                    )
                }
            }
        }

        // 3. Expanded Top AppBar with `< Hub` Exit Button, Refresh, and 3-Dot Menu (Screenshot 3)
        AnimatedVisibility(
            visible = isAppBarExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = colorScheme.surface.copy(alpha = 0.97f),
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("workspace_expanded_appbar")
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left: `< Hub` Exit Button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onExitWorkspace() }
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                                .testTag("appbar_exit_hub_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Exit to Hub",
                                tint = colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Hub",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.primary
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // Center: Tenant Icon + Title + Tunnel/Mode Subtitle (tap to collapse back to pill)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { isAppBarExpanded = false }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Surface(
                                color = colorScheme.tertiary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, colorScheme.tertiary.copy(alpha = 0.4f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ElectricBolt,
                                    contentDescription = null,
                                    tint = colorScheme.tertiary,
                                    modifier = Modifier.padding(5.dp).size(16.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = uiState.activeWorkspace.displayName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Public,
                                        contentDescription = null,
                                        tint = colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "${uiState.activeWorkspace.mode.name} Tunnel • ${uiState.activeWorkspace.terminalCode}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(6.dp))

                        // Right: Outlined Refresh Button + Outlined 3-Dot Menu Button with Status Dot
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Refresh Button (`↻`)
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp))
                                    .clickable {
                                        viewModel.reseedActiveWorkspace()
                                    }
                                    .testTag("appbar_refresh_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Workspace Schema",
                                    tint = colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 3-Dot Menu Button (`⋮`) with status indicator dot
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp))
                                        .clickable { showOverflowMenu = true }
                                        .testTag("appbar_more_menu_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Workspace Options",
                                        tint = colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    // Red status dot in top-right corner of the 3-dot button (matching Screenshot 3)
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 6.dp, end = 6.dp)
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFEF4444))
                                    )
                                }

                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("On-the-Fly SDUI Builder", fontWeight = FontWeight.SemiBold) },
                                        leadingIcon = { Icon(Icons.Default.DynamicForm, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            activeOverlay = ActiveHostOverlay.SDUI_BUILDER
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Workspace SQLite & Sync") },
                                        leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.refreshDatabaseTelemetry()
                                            activeOverlay = ActiveHostOverlay.DB_AND_SYNC
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Security & KMP Inspector") },
                                        leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            activeOverlay = ActiveHostOverlay.SECURITY_KMP
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Hide AppBar (Standout Pill Only)") },
                                        leadingIcon = { Icon(Icons.Default.Fullscreen, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            isAppBarExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Exit to Tenant Hub", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            onExitWorkspace()
                                        }
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = colorScheme.outline)
                }
            }
        }
    }

    // Host Engine Overlay Modal Sheets (launched from the 3-dot menu so the workspace stays 100% full-screen)
    if (activeOverlay != ActiveHostOverlay.NONE) {
        ModalBottomSheet(
            onDismissRequest = { activeOverlay = ActiveHostOverlay.NONE },
            containerColor = colorScheme.background,
            modifier = Modifier.fillMaxHeight(0.92f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (activeOverlay) {
                            ActiveHostOverlay.SDUI_BUILDER -> "On-the-Fly SDUI Schema Builder"
                            ActiveHostOverlay.DB_AND_SYNC -> "Workspace SQLite & Sync (${uiState.activeWorkspace.tenantId})"
                            ActiveHostOverlay.SECURITY_KMP -> "Zero-Trust Security & KMP Architecture"
                            ActiveHostOverlay.NONE -> ""
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = colorScheme.onBackground
                    )
                    TextButton(onClick = { activeOverlay = ActiveHostOverlay.NONE }) {
                        Text("Done")
                    }
                }
                HorizontalDivider(color = colorScheme.outline)
                when (activeOverlay) {
                    ActiveHostOverlay.SDUI_BUILDER -> SduiBuilderTab(
                        uiState = uiState,
                        viewModel = viewModel,
                        onSwitchToTerminalPreview = { activeOverlay = ActiveHostOverlay.NONE }
                    )
                    ActiveHostOverlay.DB_AND_SYNC -> DatabaseAndSyncTab(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                    ActiveHostOverlay.SECURITY_KMP -> SecurityAndKmpTab(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                    ActiveHostOverlay.NONE -> Unit
                }
            }
        }
    }
}
