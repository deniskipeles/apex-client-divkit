package com.example.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiEngineViewModel
import com.example.enginehost.SduiOnFlyBuilder
import com.example.enginehost.WorkspaceProvisioner

@Composable
fun SduiBuilderTab(
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel,
    onSwitchToTerminalPreview: () -> Unit
) {
    val spec = uiState.activeWorkspace
    val colorScheme = MaterialTheme.colorScheme
    var customBtnLabel by remember { mutableStateOf("Seasonal Flash Special") }
    var customBtnSku by remember { mutableStateOf("FLS-777") }
    var customBtnPrice by remember { mutableStateOf("14.99") }

    val activeVerTag = uiState.activeSchemaVersion?.let { "v${it.schemaVersion}" } ?: "v1.1"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Card
        Surface(
            color = colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "ON-THE-FLY DIVKIT SDUI BUILDER & SCHEMA CACHE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    )
                    Text(
                        "Active: $activeVerTag (${uiState.schemaVersions.size} cached)",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.tertiary
                    )
                }
                Text(
                    "Whenever a new DivKit JSON schema is received and Ed25519-verified, it is cached in workspace_${spec.tenantId}.db (sdui_schema_versions) as a new version, allowing instant rollback to any prior schema.",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }

        // Cached Schema History & Instant Version Rollback Card
        Surface(
            color = colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "CACHED SCHEMA VERSIONS (ROLLBACK READY)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
                uiState.schemaVersions.forEach { ver ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "v${ver.schemaVersion} — ${ver.changeSummary}",
                                fontSize = 12.sp,
                                fontWeight = if (ver.isActive) FontWeight.Bold else FontWeight.Medium,
                                color = if (ver.isActive) colorScheme.tertiary else colorScheme.onSurface
                            )
                            Text(
                                text = "jti: ${ver.envelopeJti} • ${ver.divkitJson.length} bytes",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        if (ver.isActive) {
                            Text(
                                text = "ACTIVE",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.tertiary
                            )
                        } else {
                            OutlinedButton(
                                onClick = {
                                    viewModel.rollbackToSchemaVersion(ver)
                                    onSwitchToTerminalPreview()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 34.dp)
                            ) {
                                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Rollback to v${ver.schemaVersion}", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // 1. One-Tap Component Palette (Injects directly into the DivKit JSON AST & caches new version)
        Surface(
            color = colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "1. INJECT DYNAMIC DIVKIT COMPONENT (STORES NEW VERSION)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = customBtnLabel,
                        onValueChange = { customBtnLabel = it },
                        label = { Text("Button Label", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f).testTag("builder_btn_label_input")
                    )
                    OutlinedTextField(
                        value = customBtnSku,
                        onValueChange = { customBtnSku = it },
                        label = { Text("SKU", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                    OutlinedTextField(
                        value = customBtnPrice,
                        onValueChange = { customBtnPrice = it },
                        label = { Text("Price", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                }

                Button(
                    onClick = {
                        val block = SduiOnFlyBuilder.buildQuickSaleButtonBlock(
                            spec = spec,
                            productName = customBtnLabel.ifBlank { "Flash Item" },
                            sku = customBtnSku.ifBlank { "SKU-99" },
                            price = customBtnPrice.toDoubleOrNull() ?: 9.99,
                            colorHex = "#7C3AED"
                        )
                        viewModel.injectOnFlyComponent(
                            block = block,
                            description = "Quick-Sale Button ($customBtnLabel)",
                            prepend = true
                        )
                        onSwitchToTerminalPreview()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("inject_quicksale_button")
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = "Inject POS Button")
                    Spacer(Modifier.width(8.dp))
                    Text("Inject POS Button → Cache New Version & Render", fontWeight = FontWeight.Bold)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildInventoryJsonListBlock(spec)
                            viewModel.injectOnFlyComponent(block, "Inventory json_list Block")
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_inventory_json_list_button")
                    ) {
                        Text("+ json_list (Inventory)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildHardwareTriggerBlock(
                                label = "Open Cash Drawer",
                                capability = "cash_drawer",
                                command = "open_solenoid",
                                terminalCode = spec.terminalCode
                            )
                            viewModel.injectOnFlyComponent(block, "hardware://cash_drawer Button", prepend = true)
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_hardware_drawer_button")
                    ) {
                        Text("+ hardware://drawer", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f))

                Text(
                    "CUSTOM DIVKIT CHART BLOCKS (KMP KOALAPLOT • ANDROID VICO • IOS SWIFT CHARTS / DGCHARTS)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildSduiChartBlock(
                                spec = spec,
                                chartType = "bar",
                                dataSource = "orders"
                            )
                            viewModel.injectOnFlyComponent(
                                block = block,
                                description = "sdui_chart Bar (Revenue by SKU)",
                                prepend = true
                            )
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_bar_chart_button"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text("+ Bar Chart", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildHourlyTrendChartBlock(
                                spec = spec,
                                chartType = "area"
                            )
                            viewModel.injectOnFlyComponent(
                                block = block,
                                description = "sdui_chart Area (Hourly Throughput)",
                                prepend = true
                            )
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_area_chart_button"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text("+ Area Trend", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildSduiChartBlock(
                                spec = spec,
                                chartType = "donut",
                                dataSource = "inventory"
                            )
                            viewModel.injectOnFlyComponent(
                                block = block,
                                description = "sdui_chart Donut (Inventory Stock)",
                                prepend = true
                            )
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_donut_chart_button"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text("+ Donut Stock", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f))

                Text(
                    "NATIVE TOOLS & ON-DEVICE AI BLOCKS (tools://{libsignal, scan+OCR, code-generate, camera, u-2-Net, MobileNetV4})",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildMobileNetV4ClassifyToolBlock(spec)
                            viewModel.injectOnFlyComponent(block, "tools://MobileNetV4-small AI Classifier", prepend = true)
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_mobilenetv4_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("+ MobileNetV4 AI", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildU2NetToolBlock(spec)
                            viewModel.injectOnFlyComponent(block, "tools://u-2-Net.tflite Saliency Matte", prepend = true)
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_u2net_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("+ U-2-Net Matte", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildScannerToolBlock(spec)
                            viewModel.injectOnFlyComponent(block, "tools://scan Optical Suite (Barcode/QR/OCR)", prepend = true)
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_scanner_suite_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("+ Optical Scan/OCR", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildLibSignalToolBlock(spec)
                            viewModel.injectOnFlyComponent(block, "tools://libsignal E2EE Ratchet", prepend = true)
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_libsignal_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("+ LibSignal E2EE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildCodeGenerateToolBlock(spec)
                            viewModel.injectOnFlyComponent(block, "tools://code-generate Barcode/QR Generator", prepend = true)
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_code_generate_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("+ Barcode/QR Gen", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildHapticsToolBlock(spec)
                            viewModel.injectOnFlyComponent(block, "device.haptics@1 Tactile Suite", prepend = true)
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_haptics_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("+ Haptics (device.haptics)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildBeeperToolBlock(spec)
                            viewModel.injectOnFlyComponent(block, "device.beeper@1 Audio Suite", prepend = true)
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_beeper_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("+ Beeper Audio (1200Hz)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildBiometricsToolBlock(spec)
                            viewModel.injectOnFlyComponent(block, "auth.biometrics@1 Supervisor Auth", prepend = true)
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_biometrics_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("+ Supervisor Biometrics", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        onClick = {
                            val block = SduiOnFlyBuilder.buildClipboardAndPushToolBlock(spec)
                            viewModel.injectOnFlyComponent(block, "device.clipboard@1 & network.push@1", prepend = true)
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inject_clipboard_push_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("+ Clipboard & Push Alerts", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 2. Receive New Full Schema Blueprint (Caches as New Version)
        Surface(
            color = colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "2. RECEIVE & CACHE NEW DIVKIT SCHEMA BLUEPRINT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val json = WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)
                            viewModel.applySduiBlueprint(json, "Full POS Register & Order Ledger")
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("POS Register Schema", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val json = SduiOnFlyBuilder.buildInventoryTerminalBlueprint(spec)
                            viewModel.applySduiBlueprint(json, "Stock & SKU Replenishment Schema")
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Stock Controller Schema", fontSize = 12.sp)
                    }
                }
            }
        }

        // 3. Live DivKit JSON Payload Editor + Ed25519 Envelope Sign, Cache & Verify
        Surface(
            color = colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "3. RAW DIVKIT SDUI PAYLOAD (JSON AST)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    )
                    Button(
                        onClick = {
                            viewModel.signVerifyAndDeployDraft()
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier.testTag("sign_and_deploy_json_button")
                    ) {
                        Icon(Icons.Default.Verified, contentDescription = "Sign & Cache", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sign, Cache & Render", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedTextField(
                    value = uiState.draftDivKitJson,
                    onValueChange = { viewModel.updateDraftDivKitJson(it) },
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = colorScheme.onSurface
                    ),
                    minLines = 10,
                    maxLines = 16,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("raw_divkit_json_editor")
                )
            }
        }
    }
}
