package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.enginehost.SduiEngineViewModel
import com.example.ui.DatabaseAndSyncTab
import com.example.ui.PosTerminalTab
import com.example.ui.SecurityAndKmpTab
import com.example.ui.TenantLauncherScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Defaults to system theme (Light or Dark)
            MyApplicationTheme {
                SduiPosEngineApp()
            }
        }
    }
}

private enum class LauncherSection {
    TENANT_GRID,
    DB_INSPECTOR,
    SECURITY_KMP
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SduiPosEngineApp() {
    val context = LocalContext.current
    val viewModel: SduiEngineViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SduiEngineViewModel(context.applicationContext) as T
            }
        }
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var launcherSection by remember { mutableStateOf(LauncherSection.TENANT_GRID) }
    val colorScheme = MaterialTheme.colorScheme

    // Handle system Back button inside opened tenant workspace or secondary launcher sections
    if (uiState.isWorkspaceOpen) {
        BackHandler {
            viewModel.returnToLauncher()
        }
    } else if (launcherSection != LauncherSection.TENANT_GRID) {
        BackHandler {
            launcherSection = LauncherSection.TENANT_GRID
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            // Only show the host TopAppBar when on the Home Launcher!
            // When a tenant workspace is opened (`uiState.isWorkspaceOpen == true`), the tenant gets 100%
            // of the screen space with only the non-shadowing Standout Pill at the top-center.
            if (!uiState.isWorkspaceOpen) {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "DivKit SDUI Hub",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = colorScheme.onSurface
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = colorScheme.primary.copy(alpha = 0.15f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = "${uiState.workspaces.size} tenants",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            uiState.statusBannerMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            // Only show the host NavigationBar on the Home Launcher!
            // Opened tenant workspaces run full-screen like a self-contained app.
            if (!uiState.isWorkspaceOpen) {
                NavigationBar(
                    containerColor = colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    NavigationBarItem(
                        selected = launcherSection == LauncherSection.TENANT_GRID,
                        onClick = { launcherSection = LauncherSection.TENANT_GRID },
                        icon = { Icon(Icons.Default.GridView, contentDescription = "Tenants Hub") },
                        label = { Text("Tenants Hub", fontSize = 11.sp) },
                        modifier = Modifier.testTag("nav_tenants_hub")
                    )
                    NavigationBarItem(
                        selected = launcherSection == LauncherSection.DB_INSPECTOR,
                        onClick = {
                            viewModel.refreshDatabaseTelemetry()
                            launcherSection = LauncherSection.DB_INSPECTOR
                        },
                        icon = { Icon(Icons.Default.Storage, contentDescription = "DB & Sync") },
                        label = { Text("DB & Sync", fontSize = 11.sp) },
                        modifier = Modifier.testTag("nav_db_sync")
                    )
                    NavigationBarItem(
                        selected = launcherSection == LauncherSection.SECURITY_KMP,
                        onClick = { launcherSection = LauncherSection.SECURITY_KMP },
                        icon = { Icon(Icons.Default.Security, contentDescription = "Security & KMP") },
                        label = { Text("Security & KMP", fontSize = 11.sp) },
                        modifier = Modifier.testTag("nav_security_kmp")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.isWorkspaceOpen) {
                // Full-screen self-contained tenant workspace with top-center Standout Pill -> `< Hub` AppBar
                PosTerminalTab(
                    uiState = uiState,
                    viewModel = viewModel,
                    onExitWorkspace = { viewModel.returnToLauncher() }
                )
            } else {
                when (launcherSection) {
                    LauncherSection.TENANT_GRID -> TenantLauncherScreen(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                    LauncherSection.DB_INSPECTOR -> DatabaseAndSyncTab(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                    LauncherSection.SECURITY_KMP -> SecurityAndKmpTab(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}
