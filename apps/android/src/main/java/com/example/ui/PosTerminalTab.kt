package com.example.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiEngineViewModel
import engine.db.CachedSchemaVersion
import engine.navigation.CardRegistry
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.ui.EngineScreen
import engine.ui.rememberDivKitDependencies
import kotlin.math.abs

private enum class ActiveHostOverlay {
    NONE,
    WORKSPACE_SETTINGS,
    SCHEMA_ROLLBACK,
    DEVELOPER_DIAGNOSTICS,
    FILES,
    NOTIFICATIONS
}

/**
 * Self-Contained Full-Screen Tenant Workspace View.
 *
 * - Renders the tenant's active cached DivKit JSON schema (`uiState.verifiedDivKitJson`)
 *   across 100% of the screen space.
 * - Top-center **Standout Pill** (zero shadow, high-contrast on both Dark and Light themes)
 *   opens the AppBar when clicked. The whole pill — fill, border and handle — renders at 65%
 *   opacity, and the 48 dp minimum touch target (not just the 56x20 dp bar) is the hit area.
 * - Once opened, the **AppBar remains open unless the active workspace screen is clicked
 *   or the Exit (`< Hub`) button is clicked**.
 * - Includes the Expanded AppBar (Back/Exit, QR, Bell, 3-Dot Menu with Workspace Settings,
 *   Clear Offline Cache, Schema Cache & Rollback, Developer diagnostics, Return to Hub).
 *   and Hardware Audio & Haptics tester.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosTerminalTab(
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel,
    onExitWorkspace: () -> Unit = { viewModel.returnToLauncher() }
) {
    val context = LocalContext.current
    val divKitDeps = rememberDivKitDependencies()
    val bridgeVars by EngineStateBridge.state.collectAsState()
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.luminance() < 0.5f

    // Controls whether the top-center Standout Pill has been clicked to open the AppBar.
    // Remains open unless the active workspace screen is clicked or the Exit button is clicked.
    var isAppBarExpanded by remember(uiState.activeWorkspace.tenantId) { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var activeOverlay by remember { mutableStateOf(ActiveHostOverlay.NONE) }

    val spec = uiState.activeWorkspace
    val permissions = uiState.tenantToolsMap[spec.tenantId] ?: spec.defaultTools
    val activeSchemaTag = uiState.activeSchemaVersion?.let { "v${it.schemaVersion}" } ?: "v1.1"

    // Device back button: consume it to dismiss in-tenant popups first (overlay sheets,
    // overflow menu, expanded app bar). When nothing is left to pop, we do NOT handle it —
    // the event falls through to the Activity so it exits the app rather than the tenant.
    val hasOpenTenantPopup =
        activeOverlay != ActiveHostOverlay.NONE || showOverflowMenu || isAppBarExpanded
    BackHandler(enabled = hasOpenTenantPopup) {
        when {
            activeOverlay != ActiveHostOverlay.NONE -> activeOverlay = ActiveHostOverlay.NONE
            showOverflowMenu -> showOverflowMenu = false
            else -> isAppBarExpanded = false
        }
    }

    // A pushed card is a different document, so it must open at the top rather than
    // inheriting the scroll offset of the card the user navigated away from.
    val workspaceScrollState = rememberScrollState()
    LaunchedEffect(uiState.activeCardId) { workspaceScrollState.scrollTo(0) }

    // A pushed card is one level of history: back pops to the hub before leaving the tenant.
    val canPopCard = uiState.cardStack.size > 1
    BackHandler(enabled = !hasOpenTenantPopup && canPopCard) { viewModel.navigateBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        // 1. Active Workspace Screen (Full-Screen Self-Contained Tenant SDUI Content + Native
        //    Bridge Cards). The breadcrumb is pinned outside the scroll container so a long
        //    card can never push the only way back to the hub off-screen.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("workspace_active_screen_area")
        ) {
            if (uiState.cardStack.size > 1) {
                // The collapsed app-bar pill sits at TopCenter, so the breadcrumb clears it.
                Box(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 30.dp)) {
                    CardStackBreadcrumb(
                        stack = uiState.cardStack,
                        onBack = { viewModel.navigateBack() }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(isAppBarExpanded) {
                        if (!isAppBarExpanded) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            val downPos = down.position
                            val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                            if (up != null) {
                                val dx = abs(up.position.x - downPos.x)
                                val dy = abs(up.position.y - downPos.y)
                                if (dx < viewConfiguration.touchSlop && dy < viewConfiguration.touchSlop) {
                                    isAppBarExpanded = false
                                }
                            }
                        }
                    }
                    .verticalScroll(workspaceScrollState)
                    .padding(
                        start = 14.dp,
                        end = 14.dp,
                        // The collapsed state floats the 48dp standout pill over the scroll area,
                        // so the landing card has to start below it or the pill lands on the
                        // title. A pushed card already sits under the breadcrumb, which clears
                        // the pill, so it keeps the tighter inset.
                        top = when {
                            isAppBarExpanded -> 68.dp
                            uiState.cardStack.size > 1 -> 12.dp
                            else -> 44.dp
                        },
                        bottom = 24.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // A2. Dynamic DivKit SDUI card. Route index 0 is the entry hub; tool cards are
                //    pushed on top of it through nav://push, so only the visible card renders here.
                val visibleCardJson = uiState.activeCardJson.ifBlank { uiState.verifiedDivKitJson }
                if (visibleCardJson.isNotBlank()) {
                    key(
                        uiState.activeSchemaVersion?.versionId ?: 0L,
                        uiState.activeCardId,
                        visibleCardJson.hashCode()
                    ) {
                        EngineScreen(
                            divKitDependencies = divKitDeps,
                            initialJson = visibleCardJson,
                            cardId = uiState.activeCardId.ifBlank {
                                "pos_card_${spec.tenantId}_$activeSchemaTag"
                            },
                            onAction = { url, payload ->
                                viewModel.dispatchDivKitActionAwait(url, payload)
                            }
                        )
                    }
                }

            }
        }

        // 1b. `sheet://` card presented over the workspace. The sheet renders the same DivKit
        //     document the stack would, and dismissing it leaves the stack untouched.
        val sheetCardId = uiState.presentedSheetCardId
        val sheetCardJson = sheetCardId?.let { CardRegistry.card(it)?.toString() }
        if (sheetCardId != null && sheetCardJson != null) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.dismissSheet() },
                containerColor = colorScheme.background,
                modifier = Modifier.fillMaxHeight(0.85f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("workspace_sheet_host")
                ) {
                    EngineScreen(
                        divKitDependencies = divKitDeps,
                        initialJson = sheetCardJson,
                        cardId = sheetCardId,
                        onAction = { url, payload ->
                            viewModel.dispatchDivKitActionAwait(url, payload)
                        }
                    )
                }
            }
        }

        // 2. Top-Center Standout Pill (Collapsed State — Zero Shadow, High Contrast on Both Themes)
        if (!isAppBarExpanded) {
            WorkspaceStandoutPill(
                isDark = isDark,
                onOpen = { isAppBarExpanded = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
            )
        }

        // 3. Expanded Top AppBar — Remains open unless the active workspace screen is clicked or Exit is clicked
        AnimatedVisibility(
            visible = isAppBarExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = colorScheme.surface.copy(alpha = 0.98f),
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
                    .testTag("workspace_expanded_appbar")
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left: Back / `< Hub` Exit Button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    isAppBarExpanded = false
                                    onExitWorkspace()
                                }
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                                .testTag("appbar_exit_hub_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Return to Hub",
                                tint = colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Hub",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.primary
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // Center: Tenant Name + Subtitle (Built-in Demo or Public Tunnel + Cached Schema Version)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = spec.displayName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when (spec.origin) {
                                    "builtin" -> "⚡ Built-in Demo • $activeSchemaTag"
                                    "update" -> "⬇ Gateway update • $activeSchemaTag"
                                    else -> "🌍 Imported • $activeSchemaTag"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Right Actions (Screenshots 7 & 11): QR Scanner, Refresh, Notification Bell with Red Dot, 3-Dot Menu
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // QR Scanner Button
                            IconButton(
                                onClick = {
                                    viewModel.dispatchDivKitAction(
                                        "hardware://scanner?cmd=scan",
                                        null
                                    )
                                },
                                modifier = Modifier.size(38.dp).testTag("appbar_qr_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = "Scan Barcode / QR",
                                    tint = colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Notification Bell Icon with Red Dot Badge
                            Box {
                                IconButton(
                                    onClick = { activeOverlay = ActiveHostOverlay.NOTIFICATIONS },
                                    modifier = Modifier.size(38.dp).testTag("appbar_notifications_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Notifications,
                                        contentDescription = "Workspace Alerts",
                                        tint = colorScheme.onSurface,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 6.dp, end = 6.dp)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFEF4444))
                                )
                            }

                            // 3-Dot Overflow Menu (`⋮`) (Matching Screenshot 7 + Schema Rollback & Builder)
                            Box {
                                IconButton(
                                    onClick = { showOverflowMenu = true },
                                    modifier = Modifier.size(38.dp).testTag("appbar_more_menu_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Workspace Options",
                                        tint = colorScheme.onSurface,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Workspace Settings", fontWeight = FontWeight.Medium) },
                                        leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            activeOverlay = ActiveHostOverlay.WORKSPACE_SETTINGS
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear Offline Cache", fontWeight = FontWeight.Medium) },
                                        leadingIcon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.clearActiveWorkspace()
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        enabled = !uiState.isWorkspaceUpdating,
                                        text = {
                                            Text(
                                                if (uiState.isWorkspaceUpdating) "Updating workspace…"
                                                else "Update workspace",
                                                fontWeight = FontWeight.Medium
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.SystemUpdateAlt,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.updateWorkspaceFromGateway()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Schema Cache & Rollback ($activeSchemaTag)",
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            activeOverlay = ActiveHostOverlay.SCHEMA_ROLLBACK
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Developer diagnostics") },
                                        leadingIcon = { Icon(Icons.Default.Build, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            activeOverlay = ActiveHostOverlay.DEVELOPER_DIAGNOSTICS
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Workspace Files") },
                                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.refreshWorkspaceFolders()
                                            activeOverlay = ActiveHostOverlay.FILES
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Notifications") },
                                        leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            activeOverlay = ActiveHostOverlay.NOTIFICATIONS
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Return to Hub", fontWeight = FontWeight.SemiBold) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            isAppBarExpanded = false
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

    // Per-Tenant Workspace Settings Bottom Sheet (8 Native Bridge Tools & Hardware Permissions)
    if (activeOverlay == ActiveHostOverlay.WORKSPACE_SETTINGS) {
        WorkspaceSettingsBottomSheet(
            spec = spec,
            initialPermissions = permissions,
            onSave = { updated ->
                viewModel.saveWorkspaceToolPermissions(spec.tenantId, updated)
            },
            onDismiss = { activeOverlay = ActiveHostOverlay.NONE }
        )
    }

    // Other Host Engine Overlay Modal Sheets (Schema Rollback, SDUI Builder, SQLite & Sync, Notifications)
    if (activeOverlay != ActiveHostOverlay.NONE && activeOverlay != ActiveHostOverlay.WORKSPACE_SETTINGS) {
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
                            ActiveHostOverlay.SCHEMA_ROLLBACK ->
                                "Cached DivKit Schemas & Version Rollback (${spec.tenantId})"
                            ActiveHostOverlay.DEVELOPER_DIAGNOSTICS ->
                                "Developer diagnostics (${spec.tenantId})"
                            ActiveHostOverlay.FILES ->
                                "Workspace Files (${spec.tenantId})"
                            ActiveHostOverlay.NOTIFICATIONS ->
                                "Workspace Notifications"
                            else -> ""
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { activeOverlay = ActiveHostOverlay.NONE }) {
                        Text("Done")
                    }
                }
                HorizontalDivider(color = colorScheme.outline)
                when (activeOverlay) {
                    ActiveHostOverlay.SCHEMA_ROLLBACK -> SchemaVersionRollbackSheet(
                        uiState = uiState,
                        onRollback = viewModel::rollbackToSchemaVersion,
                        onDismiss = { activeOverlay = ActiveHostOverlay.NONE }
                    )
                    ActiveHostOverlay.DEVELOPER_DIAGNOSTICS -> DeveloperDiagnosticsSheet(
                        uiState = uiState,
                        spec = spec,
                        permissions = permissions,
                        onOpenWorkspaceSettings = {
                            activeOverlay = ActiveHostOverlay.WORKSPACE_SETTINGS
                        },
                        onOpenSchemaRollback = {
                            activeOverlay = ActiveHostOverlay.SCHEMA_ROLLBACK
                        }
                    )
                    ActiveHostOverlay.FILES -> FileManagerTab(
                        uiState = uiState,
                        viewModel = viewModel,
                        initialFolderId = spec.tenantId
                    )
                    ActiveHostOverlay.NOTIFICATIONS -> NotificationsTab(
                        uiState = uiState,
                        viewModel = viewModel,
                        showHeader = false
                    )
                    else -> Unit
                }
            }
        }
    }
}

/** Human label for a card id: the stack must never show `tool_kiosk_customer` to a user. */
private fun cardStackLabel(id: String): String {
    KIOSK_CARD_LABELS[id]?.let { return it }
    val words = id.removePrefix("hub_").removePrefix("tool_").split('_').filter { it.isNotBlank() }
    return words.joinToString(" ") { word ->
        if (word.length <= 2) word.uppercase() else word.replaceFirstChar { it.uppercase() }
    }
}

private val KIOSK_CARD_LABELS = mapOf(
    "hub_apex_kiosk" to "APEX KIOSK",
    "tool_kiosk_customer" to "Customer order",
    "tool_kiosk_owner" to "Owner receipt",
    "tool_kiosk_transfer" to "QR transfer",
    "tool_kiosk_lan" to "LAN share"
)

/** Shows how deep the card stack is and offers one-tap return to the hub. */
@Composable
private fun CardStackBreadcrumb(
    stack: List<String>,
    onBack: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        color = colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_stack_breadcrumb")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = colorScheme.primary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack)
                    .testTag("card_stack_back_button")
            ) {
                Text(
                    text = "← Hub",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Text(
                text = stack.joinToString(" › ") { cardStackLabel(it) },
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${stack.size}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary
            )
        }
    }
}

/**
 * Every cached DivKit schema version for the active tenant, with a confirmed rollback.
 *
 * Rollback activates the chosen `sdui_schema_versions` row, re-renders the restored payload and
 * keeps it active across relaunches: [com.example.enginehost.SduiEngineViewModel.needsDemoCardRefresh]
 * refuses to overwrite a rolled-back row, so the choice survives a cold start.
 */
@Composable
fun SchemaVersionRollbackSheet(
    uiState: SduiEngineUiState,
    onRollback: (CachedSchemaVersion) -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var pendingRollback by remember { mutableStateOf<CachedSchemaVersion?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            color = colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "SCHEMA VERSION LEDGER",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
                Text(
                    text = "Every verified DivKit schema for this workspace is kept in its database. " +
                        "Rolling back activates an older version immediately and keeps it active after a relaunch.",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${uiState.schemaVersions.size} cached version(s) • active " +
                        (uiState.activeSchemaVersion?.let { "v${it.schemaVersion}" } ?: "none"),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.tertiary
                )
            }
        }

        if (uiState.isRollingBack) {
            Surface(
                color = colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("schema_rollback_progress")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Activating cached schema…",
                        fontSize = 12.sp,
                        color = colorScheme.onSurface
                    )
                }
            }
        }

        if (uiState.rollbackError.isNotBlank()) {
            Surface(
                color = colorScheme.error.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, colorScheme.error),
                modifier = Modifier.fillMaxWidth().testTag("schema_rollback_error")
            ) {
                Text(
                    text = uiState.rollbackError,
                    fontSize = 12.sp,
                    color = colorScheme.error,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (uiState.schemaVersions.isEmpty()) {
            Surface(
                color = colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colorScheme.outline),
                modifier = Modifier.fillMaxWidth().testTag("schema_rollback_empty_state")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "No cached versions yet",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = "This workspace is still running its bundled schema. Verified schemas " +
                            "appear here as soon as the workspace is provisioned or a new one is received.",
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        uiState.schemaVersions.forEach { ver ->
            val borderColor = if (ver.isActive) colorScheme.tertiary else colorScheme.outline
            Surface(
                color = colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(if (ver.isActive) 1.5.dp else 1.dp, borderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("schema_version_card_${ver.schemaVersion}")
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = if (ver.isActive) colorScheme.tertiary.copy(alpha = 0.16f)
                                else colorScheme.primary.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "v${ver.schemaVersion}",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (ver.isActive) colorScheme.tertiary else colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = ver.changeSummary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.onSurface
                                )
                                Text(
                                    text = "jti=${ver.envelopeJti}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (ver.isActive) {
                            Surface(
                                color = colorScheme.tertiary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.tertiary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        } else {
                            Button(
                                onClick = { pendingRollback = ver },
                                enabled = !uiState.isRollingBack,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 44.dp)
                                    .testTag("rollback_to_v_${ver.schemaVersion}_button")
                            ) {
                                Icon(
                                    Icons.Default.Restore,
                                    contentDescription = "Roll back to v${ver.schemaVersion}",
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Roll back",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Text(
                        text = "${ver.divkitJson.length} bytes • cached ${relativeCacheAge(ver.receivedAt)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Close", fontWeight = FontWeight.Bold)
        }
    }

    pendingRollback?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRollback = null },
            title = { Text("Roll back to v${target.schemaVersion}?") },
            text = {
                Text(
                    "The workspace will switch to the schema cached at v${target.schemaVersion} " +
                        "(${target.changeSummary}). The current version stays cached, so you can roll forward again."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRollback = null
                        onRollback(target)
                    },
                    modifier = Modifier.testTag("confirm_schema_rollback_button")
                ) { Text("Roll back") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRollback = null }) { Text("Cancel") }
            }
        )
    }
}

/** "12 min ago" style age for the cached row, or "just now" for a fresh seed. */
private fun relativeCacheAge(receivedAt: Long): String {
    val delta = System.currentTimeMillis() - receivedAt
    if (delta < 60_000) return "just now"
    val minutes = delta / 60_000
    if (minutes < 60) return "$minutes min ago"
    val hours = minutes / 60
    if (hours < 24) return "$hours h ago"
    return "${hours / 24} d ago"
}

/**
 * The collapsed workspace pill.
 *
 * The visible bar is 56x20 dp and renders at 65% opacity — fill, border and handle together.
 * `clickable` is applied *outside* `minimumInteractiveComponentSize()`, so the whole 48 dp
 * minimum touch target opens the app bar instead of only the 20 dp-tall strip.
 */
@Composable
internal fun WorkspaceStandoutPill(
    isDark: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillFill = if (isDark) Color(0xFF64748B) else Color(0xFF8E95A2)
    val pillBorder = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)

    Box(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onOpen() }
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = "Workspace menu and Hub navigation" }
            .testTag("workspace_standout_pill"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(20.dp)
                .alpha(0.65f)
                .clip(RoundedCornerShape(50))
                .background(pillFill)
                .border(1.dp, pillBorder, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
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
