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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiEngineViewModel
import engine.db.CachedSchemaVersion
import engine.state.EngineStateBridge
import engine.ui.EngineScreen
import engine.ui.rememberDivKitDependencies
import kotlin.math.abs

private enum class ActiveHostOverlay {
    NONE,
    SCHEMA_ROLLBACK,
    SDUI_BUILDER,
    DB_AND_SYNC,
    SECURITY_KMP
}

/**
 * Self-Contained Full-Screen Tenant Workspace View.
 *
 * - Renders the tenant's active cached DivKit JSON schema (`uiState.verifiedDivKitJson`)
 *   across 100% of the screen space.
 * - Top-center **Standout Pill** (zero shadow, high-contrast on both Dark and Light system themes)
 *   opens the `< Hub` AppBar when clicked.
 * - Once opened, the **AppBar remains open unless the active workspace screen is clicked
 *   or the `< Hub` exit button is clicked**.
 * - Supports full **Schema Caching & Version Rollback** (`sdui_schema_versions` in SQLite).
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

    // Controls whether the top-center Standout Pill has been clicked to open the AppBar.
    // Remains open unless the active workspace screen is clicked or the Exit (`< Hub`) button is clicked.
    var isAppBarExpanded by remember(uiState.activeWorkspace.tenantId) { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var activeOverlay by remember { mutableStateOf(ActiveHostOverlay.NONE) }

    val activeSchemaTag = uiState.activeSchemaVersion?.let { "v${it.schemaVersion}" } ?: "v1.1"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        // 1. Active Workspace Screen (Full-Screen Self-Contained Tenant SDUI Content)
        // Clicking anywhere on the active workspace screen while the AppBar is open dismisses the AppBar.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("workspace_active_screen_area")
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
                            // Treat as a click/tap on the active workspace screen (not a drag/scroll)
                            if (dx < viewConfiguration.touchSlop && dy < viewConfiguration.touchSlop) {
                                isAppBarExpanded = false
                            }
                        }
                    }
                }
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
                // Keyed by versionId + cardHash so rolling back or receiving a new schema immediately re-renders
                key(
                    uiState.activeSchemaVersion?.versionId ?: 0L,
                    uiState.verifiedDivKitJson.hashCode()
                ) {
                    EngineScreen(
                        divKitDependencies = divKitDeps,
                        initialJson = uiState.verifiedDivKitJson,
                        cardId = "pos_card_${uiState.activeWorkspace.tenantId}_${activeSchemaTag}",
                        onAction = { url, payload ->
                            viewModel.dispatchDivKitAction(url, payload)
                        }
                    )
                }
            }

            // Cached Schema Status & Version Rollback Quick Bar inside the workspace footer
            Surface(
                color = colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CACHED DIVKIT SCHEMA • ${activeSchemaTag.uppercase()} ACTIVE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.primary
                            )
                            Text(
                                text = uiState.activeSchemaVersion?.changeSummary
                                    ?: "Cached in SQLite (sdui_schema_versions)",
                                fontSize = 11.sp,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { activeOverlay = ActiveHostOverlay.SCHEMA_ROLLBACK },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 36.dp)
                                .testTag("open_schema_rollback_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "Schema Rollback",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Rollback (${uiState.schemaVersions.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f))

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

        // 3. Expanded Top AppBar — Remains open unless the active workspace screen is clicked or `< Hub` Exit is clicked
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
                    .clickable(enabled = false) { /* Consumes nothing; stays open while interacting with AppBar */ }
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
                        // Left: `< Hub` Exit Button (closes workspace and returns to 2-column Hub launcher)
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

                        // Center: Tenant Icon + Title + Tunnel & Cached Schema Version Subtitle
                        // (Does NOT close the AppBar when clicked — AppBar remains open until workspace screen or Exit is clicked)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
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
                                        text = "${uiState.activeWorkspace.mode.name} Tunnel • Schema $activeSchemaTag (${uiState.schemaVersions.size} cached)",
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
                            // Refresh Button (`↻`) — reloads cached active schema & re-runs SQLite queries
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
                                    contentDescription = "Refresh Cached Workspace Schema",
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
                                        text = {
                                            Text(
                                                "Schema Cache & Rollback ($activeSchemaTag)",
                                                fontWeight = FontWeight.Bold
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            activeOverlay = ActiveHostOverlay.SCHEMA_ROLLBACK
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Receive / Build New SDUI Schema", fontWeight = FontWeight.SemiBold) },
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

    // Host Engine Overlay Modal Sheets (launched from the 3-dot menu or footer rollback button)
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
                            ActiveHostOverlay.SCHEMA_ROLLBACK ->
                                "Cached DivKit Schemas & Version Rollback (${uiState.activeWorkspace.tenantId})"
                            ActiveHostOverlay.SDUI_BUILDER ->
                                "Receive / Build New DivKit SDUI Schema"
                            ActiveHostOverlay.DB_AND_SYNC ->
                                "Workspace SQLite & Sync (${uiState.activeWorkspace.tenantId})"
                            ActiveHostOverlay.SECURITY_KMP ->
                                "Zero-Trust Security & KMP Architecture"
                            ActiveHostOverlay.NONE -> ""
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
                        onRollback = { targetVersion ->
                            viewModel.rollbackToSchemaVersion(targetVersion)
                            activeOverlay = ActiveHostOverlay.NONE
                        },
                        onOpenBuilder = {
                            activeOverlay = ActiveHostOverlay.SDUI_BUILDER
                        }
                    )
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

/**
 * Displays all cached DivKit JSON schema versions stored in `sdui_schema_versions`
 * for the active tenant and allows one-click version rollback.
 */
@Composable
fun SchemaVersionRollbackSheet(
    uiState: SduiEngineUiState,
    onRollback: (CachedSchemaVersion) -> Unit,
    onOpenBuilder: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

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
                    text = "PERSISTENT SCHEMA CACHE & ROLLBACK LEDGER",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
                Text(
                    text = "A tenant is initiated when its signed DivKit JSON schema is received. Every verified schema is stored in SQLite (workspace_${uiState.activeWorkspace.tenantId}.db → sdui_schema_versions). When a new schema arrives, it is cached as a new version so you can roll back at any time.",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${uiState.schemaVersions.size} Cached Versions in SQLite",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.tertiary
                    )
                    FilledTonalButton(
                        onClick = onOpenBuilder,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Receive New Schema Version", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
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
                                    text = "version_id=#${ver.versionId} • jti=${ver.envelopeJti}",
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
                                onClick = { onRollback(ver) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 38.dp)
                                    .testTag("rollback_to_v_${ver.schemaVersion}_button")
                            ) {
                                Icon(
                                    Icons.Default.Restore,
                                    contentDescription = "Rollback to v${ver.schemaVersion}",
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Rollback to v${ver.schemaVersion}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Text(
                        text = "Payload size: ${ver.divkitJson.length} bytes • Cached in workspace_${uiState.activeWorkspace.tenantId}.db",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
