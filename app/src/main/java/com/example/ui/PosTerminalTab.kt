package com.example.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiEngineViewModel
import engine.db.CachedSchemaVersion
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.ui.EngineScreen
import engine.ui.rememberDivKitDependencies
import kotlin.math.abs

private enum class ActiveHostOverlay {
    NONE,
    WORKSPACE_SETTINGS,
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
 * - Top-center **Standout Pill** (zero shadow, high-contrast on both Dark and Light themes)
 *   opens the AppBar when clicked.
 * - Once opened, the **AppBar remains open unless the active workspace screen is clicked
 *   or the Exit (`< Hub`) button is clicked**.
 * - Includes the Expanded AppBar (Back/Exit, QR, Refresh, Bell, 3-Dot Menu with Refresh Page,
 *   Workspace Settings, Clear Offline Cache, Schema Cache & Rollback, Return to Hub),
 *   Origin-Scoped Public Client ID & cURL Dispatch card, Route Path & URL Queries (Preserved on Refresh),
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        // 1. Active Workspace Screen (Full-Screen Self-Contained Tenant SDUI Content + Native Bridge Cards)
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
                    bottom = 24.dp
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // A. Dynamic DivKit SDUI Schema Card (Rendered on the fly from cached SQLite schema)
            if (uiState.verifiedDivKitJson.isNotBlank()) {
                key(
                    uiState.activeSchemaVersion?.versionId ?: 0L,
                    uiState.verifiedDivKitJson.hashCode()
                ) {
                    EngineScreen(
                        divKitDependencies = divKitDeps,
                        initialJson = uiState.verifiedDivKitJson,
                        cardId = "pos_card_${spec.tenantId}_${activeSchemaTag}",
                        onAction = { url, payload ->
                            viewModel.dispatchDivKitAction(url, payload)
                        }
                    )
                }
            }

            // B. Public Client ID (Origin-Scoped) & cURL Dispatch Card (Screenshots 7 & 11)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔑 PUBLIC CLIENT ID (ORIGIN-SCOPED)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "SYNCED TO GATEWAY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.primary
                        )
                    }

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
                        onClick = {
                            if (!permissions.clipboardAccess) {
                                Toast.makeText(
                                    context,
                                    "Clipboard Access is OFF in Workspace Settings — enabling copy via host",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            copyToSystemClipboard(context, "Public Client ID", spec.publicClientId)
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("copy_public_client_id_button")
                    ) {
                        Text(
                            text = "📋 Copy Client ID",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Text(
                        text = "Test dispatching to this terminal with cURL:",
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariant
                    )

                    val curlSnippet = remember(spec.publicClientId, uiState.pushGatewayBase) {
                        """
                        curl -X POST ${uiState.pushGatewayBase}/api/v1/webhook/fcm/dispatch \
                          -H "Authorization: Bearer gwk_live_4a8f9c1b2e3d5a7e9f012345" \
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

            // C. Native Android Bridge Security Notice Banner (Screenshots 7 & 11)
            Surface(
                color = colorScheme.primary.copy(alpha = 0.10f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { activeOverlay = ActiveHostOverlay.WORKSPACE_SETTINGS }
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "⚡ Native Android Bridge (${permissions.activeCount}/8 tools active): By default, native bridged tools (Haptics, Beep, Scanner, Biometrics, Clipboard, Push, Realtime) are turned OFF for security. Tap here or open the ⋮ (3-dot menu) at the top and select Workspace Settings.",
                        fontSize = 12.sp,
                        color = colorScheme.onSurface
                    )
                }
            }

            // D. Active Route Path & URL Queries — Preserved on Refresh (Screenshot 1 from earlier turn)
            Surface(
                color = colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🌐 ACTIVE ROUTE PATH & URL QUERIES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "PRESERVED ON REFRESH",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.tertiary
                        )
                    }

                    val fullUrl = buildString {
                        append(spec.tunnelUrl.trimEnd('/'))
                        append(uiState.activeRoutePath)
                        if (uiState.activeRouteQueries != "(none)") {
                            append("?")
                            append(uiState.activeRouteQueries)
                        }
                    }

                    Surface(
                        color = colorScheme.background,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Full URL: $fullUrl",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "Path: ${uiState.activeRoutePath}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.primary
                            )
                            Text(
                                text = "Queries: ${uiState.activeRouteQueries}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFF59E0B)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RoutePresetButton(
                            title = "🛒 POS Register 01",
                            queries = "station=1&cashier=john",
                            onClick = { viewModel.switchActiveRoute("/register", "station=1&cashier=john") },
                            modifier = Modifier.weight(1f)
                        )
                        RoutePresetButton(
                            title = "🍳 Kitchen KDS Grill",
                            queries = "station=grill&priority=urgent",
                            onClick = { viewModel.switchActiveRoute("/kds", "station=grill&priority=urgent") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RoutePresetButton(
                            title = "📦 Inventory Stock",
                            queries = "category=beverage",
                            onClick = { viewModel.switchActiveRoute("/inventory", "category=beverage") },
                            modifier = Modifier.weight(1f)
                        )
                        RoutePresetButton(
                            title = "📊 Sales Report",
                            queries = "date=today&format=summary",
                            onClick = { viewModel.switchActiveRoute("/reports", "date=today&format=summary") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // E. Hardware Audio & Haptics Tester (ToneGenerator / VibratorManager)
            Surface(
                color = colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔊 HARDWARE AUDIO & HAPTICS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "TONEGENERATOR / VIBRATORMANAGER",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HardwareTestButton(
                            title = "🔔 POS Ring",
                            subtitle = "1200 Hz",
                            onClick = {
                                playPosTone(
                                    context = context,
                                    enabled = permissions.hardwareBeeperAudio,
                                    toneType = ToneGenerator.TONE_PROP_BEEP,
                                    label = "POS Ring (1200 Hz)",
                                    onBlocked = { activeOverlay = ActiveHostOverlay.WORKSPACE_SETTINGS }
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        HardwareTestButton(
                            title = "⚠️ Alert Beep",
                            subtitle = "2400 Hz",
                            onClick = {
                                playPosTone(
                                    context = context,
                                    enabled = permissions.hardwareBeeperAudio,
                                    toneType = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
                                    label = "Alert Beep (2400 Hz)",
                                    onBlocked = { activeOverlay = ActiveHostOverlay.WORKSPACE_SETTINGS }
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        HardwareTestButton(
                            title = "❌ Error Buzz",
                            subtitle = "600 Hz",
                            onClick = {
                                playPosTone(
                                    context = context,
                                    enabled = permissions.hardwareBeeperAudio,
                                    toneType = ToneGenerator.TONE_SUP_ERROR,
                                    label = "Error Buzz (600 Hz)",
                                    onBlocked = { activeOverlay = ActiveHostOverlay.WORKSPACE_SETTINGS }
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HardwareTestButton(
                            title = "📳 Light",
                            subtitle = "15ms Tick",
                            onClick = {
                                triggerPosHaptic(
                                    context = context,
                                    enabled = permissions.hapticVibration,
                                    durationMs = 15L,
                                    label = "Light Haptic (15ms)",
                                    onBlocked = { activeOverlay = ActiveHostOverlay.WORKSPACE_SETTINGS }
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        HardwareTestButton(
                            title = "📳 Click",
                            subtitle = "35ms Click",
                            onClick = {
                                triggerPosHaptic(
                                    context = context,
                                    enabled = permissions.hapticVibration,
                                    durationMs = 35L,
                                    label = "Click Haptic (35ms)",
                                    onBlocked = { activeOverlay = ActiveHostOverlay.WORKSPACE_SETTINGS }
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        HardwareTestButton(
                            title = "📳 Heavy",
                            subtitle = "80ms Heavy",
                            onClick = {
                                triggerPosHaptic(
                                    context = context,
                                    enabled = permissions.hapticVibration,
                                    durationMs = 80L,
                                    label = "Heavy Haptic (80ms)",
                                    onBlocked = { activeOverlay = ActiveHostOverlay.WORKSPACE_SETTINGS }
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // F. Cached Schema Status & Version Rollback Quick Bar + EngineStateBridge
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
                                text = if (spec.isBuiltInDemo) "⚡ Built-in Demo • $activeSchemaTag"
                                else "🌍 Public Tunnel • $activeSchemaTag",
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

                            // Refresh Page Button (`↻`)
                            IconButton(
                                onClick = { viewModel.reseedActiveWorkspace() },
                                modifier = Modifier.size(38.dp).testTag("appbar_refresh_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Page",
                                    tint = colorScheme.onSurface,
                                    modifier = Modifier.size(21.dp)
                                )
                            }

                            // Notification Bell Icon with Red Dot Badge
                            Box {
                                IconButton(
                                    onClick = {
                                        Toast.makeText(
                                            context,
                                            "${spec.unreadCount} kitchen/POS alerts logged for ${spec.displayName}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
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
                                        text = { Text("Refresh Page", fontWeight = FontWeight.Medium) },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.reseedActiveWorkspace()
                                        }
                                    )
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
                                        text = { Text("On-the-Fly SDUI Builder") },
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

    // Other Host Engine Overlay Modal Sheets (Schema Rollback, SDUI Builder, SQLite & Sync, Security & KMP)
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
                            ActiveHostOverlay.SDUI_BUILDER ->
                                "Receive / Build New DivKit SDUI Schema"
                            ActiveHostOverlay.DB_AND_SYNC ->
                                "Workspace SQLite & Sync (${spec.tenantId})"
                            ActiveHostOverlay.SECURITY_KMP ->
                                "Zero-Trust Security & KMP Architecture"
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
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun RoutePresetButton(
    title: String,
    queries: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        color = colorScheme.background,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colorScheme.outline),
        modifier = modifier
            .defaultMinSize(minHeight = 68.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "?$queries",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HardwareTestButton(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        color = colorScheme.background,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colorScheme.outline),
        modifier = modifier
            .defaultMinSize(minHeight = 62.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun playPosTone(
    context: Context,
    enabled: Boolean,
    toneType: Int,
    label: String,
    onBlocked: () -> Unit
) {
    if (!enabled) {
        EngineStateBridge.pushUpdate(
            "user.last_hardware_event",
            DivVariableValue.Str("DENIED: Enable 'Hardware Beeper Audio' in Workspace Settings")
        )
        Toast.makeText(
            context,
            "Hardware Beeper Audio is OFF. Enable it in Workspace Settings.",
            Toast.LENGTH_SHORT
        ).show()
        onBlocked()
        return
    }
    try {
        val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        toneGen.startTone(toneType, 180)
    } catch (_: Exception) {
    }
    EngineStateBridge.pushUpdate(
        "user.last_hardware_event",
        DivVariableValue.Str("TONEGENERATOR OK: Played $label")
    )
}

private fun triggerPosHaptic(
    context: Context,
    enabled: Boolean,
    durationMs: Long,
    label: String,
    onBlocked: () -> Unit
) {
    if (!enabled) {
        EngineStateBridge.pushUpdate(
            "user.last_hardware_event",
            DivVariableValue.Str("DENIED: Enable 'Haptic Vibration' in Workspace Settings")
        )
        Toast.makeText(
            context,
            "Haptic Vibration is OFF. Enable it in Workspace Settings.",
            Toast.LENGTH_SHORT
        ).show()
        onBlocked()
        return
    }
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    } catch (_: Exception) {
    }
    EngineStateBridge.pushUpdate(
        "user.last_hardware_event",
        DivVariableValue.Str("VIBRATORMANAGER OK: Triggered $label")
    )
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
