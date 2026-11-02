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
import androidx.compose.ui.graphics.Color
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
    var customBtnLabel by remember { mutableStateOf("Seasonal Flash Special") }
    var customBtnSku by remember { mutableStateOf("FLS-777") }
    var customBtnPrice by remember { mutableStateOf("14.99") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Card
        Surface(
            color = Color(0xFF1E293B),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFF38BDF8)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "ON-THE-FLY DIVKIT SDUI BUILDER",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        "Target: ${spec.tenantId}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF34D399)
                    )
                }
                Text(
                    "Dynamically assemble DivKit components (buttons, user.* reactive banners, hardware:// triggers, or json_list blocks), sign the canonical envelope with Ed25519, and hot-render on the POS terminal.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        // 1. One-Tap Component Palette (Injects directly into the DivKit JSON AST)
        Surface(
            color = Color(0xFF152033),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "1. INJECT DYNAMIC DIVKIT COMPONENT ON THE FLY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
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
                    Text("Inject POS Button → Sign & Render in Terminal", fontWeight = FontWeight.Bold)
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
            }
        }

        // 2. Preset Blueprint Switcher
        Surface(
            color = Color(0xFF152033),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "2. SWITCH TERMINAL SDUI BLUEPRINT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
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
                        Text("POS Register Card", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val json = SduiOnFlyBuilder.buildInventoryTerminalBlueprint(spec)
                            viewModel.applySduiBlueprint(json, "Stock & SKU Replenishment Card")
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Stock Controller Card", fontSize = 12.sp)
                    }
                }
            }
        }

        // 3. Live DivKit JSON Payload Editor + Ed25519 Envelope Sign & Verify
        Surface(
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
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
                        color = Color(0xFF38BDF8)
                    )
                    Button(
                        onClick = {
                            viewModel.signVerifyAndDeployDraft()
                            onSwitchToTerminalPreview()
                        },
                        modifier = Modifier.testTag("sign_and_deploy_json_button")
                    ) {
                        Icon(Icons.Default.Verified, contentDescription = "Sign & Render", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sign, Verify & Render", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedTextField(
                    value = uiState.draftDivKitJson,
                    onValueChange = { viewModel.updateDraftDivKitJson(it) },
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFE2E8F0)
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
