package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
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
import com.example.enginehost.KmpArchitectureCatalog
import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiEngineViewModel

@Composable
fun SecurityAndKmpTab(
    uiState: SduiEngineUiState,
    viewModel: SduiEngineViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Live Threat Model & Security Invariant Verification Suite
        Surface(
            color = Color(0xFF1E293B),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFF34D399)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = "Security Suite",
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "ZERO-TRUST SDUI THREAT VERIFICATION LAB",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF34D399)
                    )
                }
                Text(
                    "Key Algorithm: ${uiState.keyPair?.algorithmMode ?: "Ed25519"}\nPublic Key (DER): ${uiState.keyPair?.publicKeyDerBase64?.take(44)}...",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { viewModel.runSecurityVerificationScenario("tamper_signature") },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("sec_test_tamper")
                    ) {
                        Text("MITM Tamper", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.runSecurityVerificationScenario("replay_jti") },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("sec_test_replay")
                    ) {
                        Text("Replay jti", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.runSecurityVerificationScenario("expired_iat") },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("sec_test_expired")
                    ) {
                        Text("Expired iat", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { viewModel.runSecurityVerificationScenario("cross_tenant") },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("sec_test_cross_tenant")
                    ) {
                        Text("Tenant Spoof", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.runSecurityVerificationScenario("sql_injection") },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("sec_test_sqli")
                    ) {
                        Text("SQLi Table", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.runSecurityVerificationScenario("unfiltered_delete") },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("sec_test_truncate")
                    ) {
                        Text("Blind DELETE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (uiState.securityEvents.isNotEmpty()) {
                    HorizontalDivider(color = Color(0xFF334155))
                    uiState.securityEvents.take(5).forEach { ev ->
                        Surface(
                            color = Color(0xFF0F172A),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "✔ ${ev.title}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF34D399)
                                )
                                Text(
                                    text = ev.detail,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFE2E8F0)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Engine Convention Enforcement Table
        Surface(
            color = Color(0xFF152033),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "ENGINE SECURITY CONVENTIONS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8)
                )
                KmpArchitectureCatalog.conventions.forEach { (rule, where) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(rule, fontSize = 11.sp, color = Color(0xFFE2E8F0), modifier = Modifier.weight(1f))
                        Text(
                            where,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 3. KMP & iOS Architecture Source Browser
        Surface(
            color = Color(0xFF152033),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "KMP (commonMain / androidMain / iosMain) + SWIFT CODEBASE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF818CF8)
                )
                KmpArchitectureCatalog.files.forEach { file ->
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    file.path,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF38BDF8),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    file.moduleLayer,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF34D399)
                                )
                            }
                            Text(file.summary, fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Text(
                                file.snippet,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                }
            }
        }
    }
}
