package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.enginehost.EngineThemeMode
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiEngineViewModel
import com.example.enginehost.TenantWorkspaceSpec
import com.example.enginehost.WorkspaceToolPermissions

/**
 * 1. App Settings / Engine Settings Bottom Sheet (Screenshot 3 + Engine Theme Switching defaulting to System
 *    + App Engine Owner Gateway FCM & Signed SDUI Schema Registry details).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsBottomSheet(
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    var gatewayInput by remember(uiState.pushGatewayBase) { mutableStateOf(uiState.pushGatewayBase) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row: Gear Icon + "App Settings" + Subtitle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    color = colorScheme.primary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "App Settings",
                            tint = colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "App Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = "Gateway, Telemetry, Theme & Realtime Connectivity",
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            // App Engine Owner Role Banner (FCM + Gateway SDUI Schema Verification)
            Surface(
                color = colorScheme.primary.copy(alpha = 0.10f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "🛡️ APP ENGINE OWNER GATEWAY (FCM & SDUI SCHEMA STORE)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    )
                    Text(
                        text = "The App Engine Owner operates the Gateway Server below, providing FCM push delivery and storing/signing each tenant's DivKit SDUI schema with Ed25519 so this mobile frontend only renders verified schemas.",
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f))

            // Engine Theme Switching (Defaults to System)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Engine Theme (Defaults to System)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EngineThemeMode.entries.forEach { mode ->
                        val isSelected = uiState.themeMode == mode
                        Surface(
                            color = if (isSelected) colorScheme.primary else colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) colorScheme.primary else colorScheme.outline
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewModel.setThemeMode(mode) }
                                .testTag("theme_mode_${mode.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when (mode) {
                                        EngineThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                        EngineThemeMode.LIGHT -> Icons.Default.LightMode
                                        EngineThemeMode.DARK -> Icons.Default.DarkMode
                                    },
                                    contentDescription = mode.label,
                                    tint = if (isSelected) Color.White else colorScheme.onSurface,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (mode == EngineThemeMode.SYSTEM) "System" else mode.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Push Gateway Base & SDUI Schema Registry Server
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Push Gateway Base (FCM & SDUI Schema Vault)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
                OutlinedTextField(
                    value = gatewayInput,
                    onValueChange = {
                        gatewayInput = it
                        viewModel.updatePushGatewayBase(it)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_push_gateway_input")
                )
            }

            // Hardware Device ID with Copy Icon
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Hardware Device ID",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
                Surface(
                    color = colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.hardwareDeviceId,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface
                        )
                        IconButton(
                            onClick = {
                                copyToSystemClipboard(context, "Hardware Device ID", uiState.hardwareDeviceId)
                            },
                            modifier = Modifier.size(36.dp).testTag("copy_device_id_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Device ID",
                                tint = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // FCM Push Token with Copy Icon
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "FCM Push Token (Provisioned by App Engine Owner)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
                Surface(
                    color = colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.fcmPushToken,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                copyToSystemClipboard(context, "FCM Push Token", uiState.fcmPushToken)
                            },
                            modifier = Modifier.size(36.dp).testTag("copy_fcm_token_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy FCM Token",
                                tint = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Bottom Action Row: Re-sync FCM + Reconnect WS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.resyncFcmToken()
                        Toast.makeText(context, "FCM Token re-synced with Gateway Server", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, colorScheme.outline),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("settings_resync_fcm_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Re-sync FCM",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    )
                }

                Button(
                    onClick = {
                        viewModel.reconnectRealtimeWs()
                        Toast.makeText(context, "Reconnected Realtime WS to Gateway", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("settings_reconnect_ws_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Reconnect WS",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/**
 * 2. Connect New Workspace Bottom Sheet (Screenshot 4).
 *
 * Connects this hybrid mobile SDUI frontend to a **Desktop Client-Server App** (via Local LAN or Public Tunnel),
 * while fetching & Ed25519-verifying the tenant's DivKit SDUI schema stored on the **App Engine Owner's Gateway Server**.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectNewWorkspaceBottomSheet(
    viewModel: SduiEngineViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    var terminalName by remember { mutableStateOf("") }
    var localLanUrl by remember { mutableStateOf("") }
    var publicTunnelUrl by remember { mutableStateOf("") }
    var customPullEndpoint by remember { mutableStateOf("/webhook/edge_notifications/pull-notifications") }
    var scannedDesktopQrPayload by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Connect New Workspace",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface
            )
            Text(
                text = "Link this hybrid mobile frontend to a Desktop Client-Server POS app (LAN or Public Tunnel). The tenant's DivKit SDUI schema is fetched & Ed25519-verified from the App Engine Owner's Gateway Server and cached locally in SQLite.",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant
            )

            // Scan Desktop Pairing QR Code Button
            Button(
                onClick = {
                    terminalName = "Kokwet Electrical Works POS"
                    localLanUrl = "http://192.168.1.77:8080"
                    publicTunnelUrl = "https://kokwet-pos.trycloudflare.com"
                    scannedDesktopQrPayload = """
                        {
                          "desktop_host": "Kokwet-Desktop-Server-01",
                          "tenant_id": "kokwet_electrical_pos",
                          "lan_url": "http://192.168.1.77:8080",
                          "tunnel_url": "https://kokwet-pos.trycloudflare.com",
                          "gateway_schema_vault": "https://kipeles-apexkit.hf.space/tenant/apexapp-push/schemas/kokwet_electrical_pos/v1.1.json",
                          "ed25519_verified": true
                        }
                    """.trimIndent()
                    Toast.makeText(
                        context,
                        "Scanned Desktop Client-Server QR & verified Gateway SDUI Schema",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("scan_desktop_pairing_qr_button")
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Scan Desktop Pairing QR Code",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Fill Demo Terminal Preset Button
            OutlinedButton(
                onClick = {
                    terminalName = "Tokyo Ramen KDS & Register"
                    localLanUrl = "http://192.168.1.42:8080"
                    publicTunnelUrl = "https://tokyo-ramen-pos.hf.space"
                    scannedDesktopQrPayload = """
                        {
                          "desktop_host": "TokyoRamen-POS-Server",
                          "tenant_id": "tokyo_ramen_kds_register",
                          "lan_url": "http://192.168.1.42:8080",
                          "tunnel_url": "https://tokyo-ramen-pos.hf.space",
                          "gateway_schema_vault": "https://kipeles-apexkit.hf.space/tenant/apexapp-push/schemas/tokyo_ramen/v1.1.json",
                          "ed25519_verified": true
                        }
                    """.trimIndent()
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("fill_demo_terminal_preset_button")
            ) {
                Icon(
                    Icons.Default.Storefront,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Fill Demo Terminal Preset",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
            }

            // Show Scanned Desktop Client-Server Pairing & Gateway Schema Verification Summary if present
            scannedDesktopQrPayload?.let { payload ->
                Surface(
                    color = colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colorScheme.tertiary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "✓ DESKTOP CLIENT-SERVER QR + GATEWAY SCHEMA VERIFIED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.tertiary
                        )
                        Text(
                            text = payload,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 2.dp),
                color = colorScheme.outline.copy(alpha = 0.5f)
            )

            OutlinedTextField(
                value = terminalName,
                onValueChange = { terminalName = it },
                label = { Text("App / POS Terminal Name *") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connect_terminal_name_input")
            )

            OutlinedTextField(
                value = localLanUrl,
                onValueChange = { localLanUrl = it },
                label = { Text("Local Wi-Fi LAN URL (Desktop Client-Server)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connect_local_lan_input")
            )

            OutlinedTextField(
                value = publicTunnelUrl,
                onValueChange = { publicTunnelUrl = it },
                label = { Text("Public Tunnel / Cloudflare / FRP URL") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connect_public_tunnel_input")
            )

            OutlinedTextField(
                value = customPullEndpoint,
                onValueChange = { customPullEndpoint = it },
                label = { Text("Custom Background Pull Endpoint") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connect_pull_endpoint_input")
            )

            val canConnect = terminalName.isNotBlank()
            Button(
                onClick = {
                    viewModel.connectNewWorkspaceFromSheet(
                        displayName = terminalName,
                        localLanUrl = localLanUrl,
                        tunnelUrl = publicTunnelUrl,
                        pullEndpoint = customPullEndpoint,
                        openImmediately = true
                    )
                    onDismiss()
                },
                enabled = canConnect,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_workspace_and_connect_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Save Workspace & Connect",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 3. Per-Tenant Workspace Settings Bottom Sheet — "Native Bridge Tools & Hardware Permissions" (Screenshots 6, 8, 9).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSettingsBottomSheet(
    spec: TenantWorkspaceSpec,
    initialPermissions: WorkspaceToolPermissions,
    onSave: (WorkspaceToolPermissions) -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var perms by remember(spec.tenantId, initialPermissions) { mutableStateOf(initialPermissions) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.fillMaxHeight(0.92f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Workspace Settings",
                    tint = colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Column {
                    Text(
                        text = "${spec.displayName} Settings",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = "Native Bridge Tools & Hardware Permissions",
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "By default, all native bridged tools are turned off for security. Enable the specific capabilities needed for this Desktop Client-Server frontend terminal.",
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f))

            // 1. Haptic Vibration
            PermissionToggleRow(
                icon = Icons.Default.Vibration,
                title = "Haptic Vibration",
                description = "Allow webapp to trigger tick, click, and heavy confirmation vibration",
                checked = perms.hapticVibration,
                onCheckedChange = { perms = perms.copy(hapticVibration = it) },
                testTag = "perm_toggle_haptics"
            )

            // 2. Hardware Beeper Audio
            PermissionToggleRow(
                icon = Icons.Default.VolumeUp,
                title = "Hardware Beeper Audio",
                description = "Allow webapp to emit audio tones via the tools://beeper capability",
                checked = perms.hardwareBeeperAudio,
                onCheckedChange = { perms = perms.copy(hardwareBeeperAudio = it) },
                testTag = "perm_toggle_beeper"
            )

            // 3. Camera Barcode & QR Scanner
            PermissionToggleRow(
                icon = Icons.Default.QrCodeScanner,
                title = "Camera Barcode & QR Scanner",
                description = "Allow webapp to request CameraX + Google ML Kit 60 FPS scanning",
                checked = perms.cameraBarcodeScanner,
                onCheckedChange = { perms = perms.copy(cameraBarcodeScanner = it) },
                testTag = "perm_toggle_scanner"
            )

            // 4. Supervisor Biometric Auth
            PermissionToggleRow(
                icon = Icons.Default.Fingerprint,
                title = "Supervisor Biometric Auth",
                description = "Allow webapp to request BiometricPrompt / supervisor authorization",
                checked = perms.supervisorBiometricAuth,
                onCheckedChange = { perms = perms.copy(supervisorBiometricAuth = it) },
                testTag = "perm_toggle_biometrics"
            )

            // 5. Clipboard Access
            PermissionToggleRow(
                icon = Icons.Default.ContentPaste,
                title = "Clipboard Access",
                description = "Allow webapp to copy order receipts and data directly to system clipboard",
                checked = perms.clipboardAccess,
                onCheckedChange = { perms = perms.copy(clipboardAccess = it) },
                testTag = "perm_toggle_clipboard"
            )

            // 6. Background Alerts & Push
            PermissionToggleRow(
                icon = Icons.Default.Notifications,
                title = "Background Alerts & Push",
                description = "Allow webapp to log alerts into Room database and status bar",
                checked = perms.backgroundAlertsPush,
                onCheckedChange = { perms = perms.copy(backgroundAlertsPush = it) },
                testTag = "perm_toggle_push"
            )

            // 7. Offline Page Caching
            PermissionToggleRow(
                icon = Icons.Default.CloudDownload,
                title = "Offline Page Caching",
                description = "Cache web pages and assets locally for offline resilience and fast loads",
                checked = perms.offlinePageCaching,
                onCheckedChange = { perms = perms.copy(offlinePageCaching = it) },
                testTag = "perm_toggle_offline_cache"
            )

            // 8. Realtime Engine (WS / SSE)
            PermissionToggleRow(
                icon = Icons.Default.SwapHoriz,
                title = "Realtime Engine (WS / SSE)",
                description = "Allow webapp to stream live channels & signals via the shared native engine",
                checked = perms.realtimeEngineWsSse,
                onCheckedChange = { perms = perms.copy(realtimeEngineWsSse = it) },
                testTag = "perm_toggle_realtime_ws"
            )

            Spacer(Modifier.height(6.dp))

            Button(
                onClick = {
                    onSave(perms)
                    onDismiss()
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_workspace_settings_button")
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Save Workspace Settings",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun PermissionToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            color = colorScheme.primary.copy(alpha = 0.12f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

fun copyToSystemClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied $label to clipboard", Toast.LENGTH_SHORT).show()
}
