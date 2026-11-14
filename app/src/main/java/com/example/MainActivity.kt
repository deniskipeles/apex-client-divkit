package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.enginehost.EngineThemeMode
import com.example.enginehost.SduiEngineViewModel
import com.example.ui.AppSettingsBottomSheet
import com.example.ui.FileManagerTab
import com.example.ui.PosTerminalTab
import com.example.ui.SecurityAndKmpTab
import com.example.ui.TenantLauncherScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SduiPosEngineRoot()
        }
    }
}

private enum class LauncherSection {
    TENANT_GRID,
    FILES,
    SECURITY_KMP
}

@Composable
fun SduiPosEngineRoot() {
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

    // Engine theme defaults to System (EngineThemeMode.SYSTEM), and can be switched in App Settings
    val isDark = when (uiState.themeMode) {
        EngineThemeMode.SYSTEM -> isSystemInDarkTheme()
        EngineThemeMode.LIGHT -> false
        EngineThemeMode.DARK -> true
    }

    MyApplicationTheme(darkTheme = isDark) {
        SduiPosEngineApp(viewModel = viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SduiPosEngineApp(viewModel: SduiEngineViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var launcherSection by remember { mutableStateOf(LauncherSection.TENANT_GRID) }
    var showAppSettingsSheet by remember { mutableStateOf(false) }
    var showConnectWorkspaceSheet by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme

    // System Back handling.
    // - Inside an opened tenant, the tenant screen owns back for in-tenant popups; once those
    //   are exhausted we deliberately do NOT intercept, so the system exits the app (the workspace
    //   stays remembered and resumes on next launch).
    // - On the launcher, back returns from a secondary section to the Tenants Hub, then exits.
    if (!uiState.isWorkspaceOpen && launcherSection != LauncherSection.TENANT_GRID) {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                color = colorScheme.primary,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Terminal,
                                        contentDescription = "Engine Logo",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "ApexClient Terminal",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = uiState.statusBannerMessage
                                        ?: "Enterprise Workspaces & POS Terminals",
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        // 1. Grid / List View Toggle (Defaults to 2-column Grid View)
                        IconButton(
                            onClick = { viewModel.toggleLauncherLayoutMode() },
                            modifier = Modifier.testTag("top_bar_toggle_grid_list_button")
                        ) {
                            Icon(
                                imageVector = if (uiState.isGridView) Icons.Default.ViewList
                                else Icons.Default.GridView,
                                contentDescription = if (uiState.isGridView) "Switch to List View"
                                else "Switch to 2-Column Grid View",
                                tint = colorScheme.onSurface
                            )
                        }

                        // 2. QR Code Pairing / Connect Workspace Button
                        IconButton(
                            onClick = { showConnectWorkspaceSheet = true },
                            modifier = Modifier.testTag("top_bar_qr_pair_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Scan Desktop Pairing QR Code",
                                tint = colorScheme.onSurface
                            )
                        }

                        // 3. App / Engine Settings Gear Button (Opens App Settings with Theme Switching defaulting to System)
                        IconButton(
                            onClick = { showAppSettingsSheet = true },
                            modifier = Modifier.testTag("top_bar_app_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Engine & App Settings",
                                tint = colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
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
                        selected = launcherSection == LauncherSection.FILES,
                        onClick = {
                            viewModel.refreshWorkspaceFolders()
                            launcherSection = LauncherSection.FILES
                        },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Files") },
                        label = { Text("Files", fontSize = 11.sp) },
                        modifier = Modifier.testTag("nav_files")
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
                PosTerminalTab(
                    uiState = uiState,
                    viewModel = viewModel,
                    onExitWorkspace = { viewModel.returnToLauncher() }
                )
            } else {
                when (launcherSection) {
                    LauncherSection.TENANT_GRID -> TenantLauncherScreen(
                        uiState = uiState,
                        viewModel = viewModel,
                        showConnectWorkspaceSheet = showConnectWorkspaceSheet,
                        onShowConnectWorkspaceSheetChange = { showConnectWorkspaceSheet = it }
                    )
                    LauncherSection.FILES -> FileManagerTab(
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

    // Engine / App Settings Bottom Sheet (with Theme Switching defaulting to System)
    if (showAppSettingsSheet) {
        AppSettingsBottomSheet(
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = { showAppSettingsSheet = false }
        )
    }
}
