package com.example

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainNavTab
import com.example.ui.RemminaViewModel
import com.example.ui.connections.ConnectionsScreen
import com.example.ui.keyboard.KeyboardAndVaultScreen
import com.example.ui.security.SystemAuthGateDialog
import com.example.ui.security.VaultAppLockOverlay
import com.example.ui.session.ActiveSessionsScreen
import com.example.ui.sync.LocalSyncAndDiagScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var isDarkTheme by remember { mutableStateOf(true) }
            MyApplicationTheme(darkTheme = isDarkTheme) {
                RemminaAppRoot(
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { isDarkTheme = !isDarkTheme }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemminaAppRoot(
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    viewModel: RemminaViewModel = viewModel()
) {
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val profiles by viewModel.filteredProfiles.collectAsStateWithLifecycle()
    val allProfiles by viewModel.allProfiles.collectAsStateWithLifecycle()
    val protocolDefaultPorts by viewModel.protocolDefaultPorts.collectAsStateWithLifecycle()
    val quickConnectProtocol by viewModel.quickConnectProtocol.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedProtocol by viewModel.selectedProtocolFilter.collectAsStateWithLifecycle()
    val quickConnectText by viewModel.quickConnectText.collectAsStateWithLifecycle()
    val selectedProfile by viewModel.selectedProfileForDetail.collectAsStateWithLifecycle()
    val activeSessions by viewModel.activeSessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val isFullScreenSession by viewModel.isCurrentSessionFullScreen.collectAsStateWithLifecycle()
    val sshKeys by viewModel.allSshKeys.collectAsStateWithLifecycle()
    val keyMappings by viewModel.allKeyMappings.collectAsStateWithLifecycle()
    val lastTestedKeyPacket by viewModel.lastTestedKeyPacket.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBannerMessage.collectAsStateWithLifecycle()
    val securityPolicy by viewModel.securityPolicy.collectAsStateWithLifecycle()
    val biometricStatus by viewModel.biometricStatus.collectAsStateWithLifecycle()
    val isVaultLocked by viewModel.isVaultLocked.collectAsStateWithLifecycle()
    val pendingSecurityChallenge by viewModel.pendingSecurityChallenge.collectAsStateWithLifecycle()
    val editingDecryptedProfile by viewModel.editingDecryptedProfile.collectAsStateWithLifecycle()
    val securityAuditChecklist by viewModel.securityAuditChecklist.collectAsStateWithLifecycle()
    val lastSecuritySelfTest by viewModel.lastSecuritySelfTest.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentViewedSession = activeSessions.firstOrNull { it.sessionId == currentSessionId }
        ?: activeSessions.firstOrNull()
    val allowScreenshotsForCurrentDisplay = currentTab == MainNavTab.ACTIVE_SESSIONS &&
        !isVaultLocked &&
        pendingSecurityChallenge == null &&
        currentViewedSession?.allowScreenshots == true

    // Enforce WindowManager.LayoutParams.FLAG_SECURE unless screenshots are explicitly allowed on the currently viewed display
    LaunchedEffect(
        securityPolicy.flagSecureEnabled,
        allowScreenshotsForCurrentDisplay,
        currentViewedSession?.sessionId,
        currentViewedSession?.allowScreenshots
    ) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val shouldProtectWindow = securityPolicy.flagSecureEnabled && !allowScreenshotsForCurrentDisplay
        if (shouldProtectWindow) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Track App Background & Resume for automatic inactivity Vault Lock
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_RESUME -> viewModel.onAppResumed()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Toggle Android immersive system bars when Fullscreen Mode is active on a remote session
    LaunchedEffect(isFullScreenSession) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (isFullScreenSession) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (isFullScreenSession) {
        BackHandler {
            currentSessionId?.let { viewModel.toggleSessionFullScreen(it) }
        }
    } else if (currentTab != MainNavTab.CONNECTIONS) {
        BackHandler {
            viewModel.selectTab(MainNavTab.CONNECTIONS)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 640.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (!isFullScreenSession) {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    color = Color(0xFF57E389).copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.clickable {
                                        viewModel.selectTab(MainNavTab.KEYBOARD_AND_VAULT)
                                    }
                                ) {
                                    Text(
                                        text = "AES-256-GCM",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF57E389),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { viewModel.lockVaultNow() },
                                modifier = Modifier.testTag("lock_vault_top_button")
                            ) {
                                Icon(
                                    imageVector = if (securityPolicy.isPinConfigured) Icons.Default.Lock else Icons.Default.Shield,
                                    contentDescription = "Lock Security Vault",
                                    tint = if (securityPolicy.isPinConfigured) Color(0xFF57E389) else MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = onToggleTheme,
                                modifier = Modifier.testTag("theme_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = "Toggle theme"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            },
            bottomBar = {
                if (!useNavigationRail && !isFullScreenSession) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        NavigationBarItem(
                            selected = currentTab == MainNavTab.CONNECTIONS,
                            onClick = { viewModel.selectTab(MainNavTab.CONNECTIONS) },
                            icon = { Icon(Icons.Default.Computer, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_connections)) },
                            modifier = Modifier.testTag("nav_tab_connections")
                        )
                        NavigationBarItem(
                            selected = currentTab == MainNavTab.ACTIVE_SESSIONS,
                            onClick = { viewModel.selectTab(MainNavTab.ACTIVE_SESSIONS) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (activeSessions.isNotEmpty()) {
                                            Badge { Text(activeSessions.size.toString()) }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.DesktopWindows, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(R.string.nav_sessions)) },
                            modifier = Modifier.testTag("nav_tab_sessions")
                        )
                        NavigationBarItem(
                            selected = currentTab == MainNavTab.KEYBOARD_AND_VAULT,
                            onClick = { viewModel.selectTab(MainNavTab.KEYBOARD_AND_VAULT) },
                            icon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_vault)) },
                            modifier = Modifier.testTag("nav_tab_keyboard")
                        )
                        NavigationBarItem(
                            selected = currentTab == MainNavTab.PREFERENCES_AND_IMPORT,
                            onClick = { viewModel.selectTab(MainNavTab.PREFERENCES_AND_IMPORT) },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("Preferences") },
                            modifier = Modifier.testTag("nav_tab_sync")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail && !isFullScreenSession) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        NavigationRailItem(
                            selected = currentTab == MainNavTab.CONNECTIONS,
                            onClick = { viewModel.selectTab(MainNavTab.CONNECTIONS) },
                            icon = { Icon(Icons.Default.Computer, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_connections)) },
                            modifier = Modifier.testTag("nav_rail_connections")
                        )
                        NavigationRailItem(
                            selected = currentTab == MainNavTab.ACTIVE_SESSIONS,
                            onClick = { viewModel.selectTab(MainNavTab.ACTIVE_SESSIONS) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (activeSessions.isNotEmpty()) {
                                            Badge { Text(activeSessions.size.toString()) }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.DesktopWindows, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(R.string.nav_sessions)) },
                            modifier = Modifier.testTag("nav_rail_sessions")
                        )
                        NavigationRailItem(
                            selected = currentTab == MainNavTab.KEYBOARD_AND_VAULT,
                            onClick = { viewModel.selectTab(MainNavTab.KEYBOARD_AND_VAULT) },
                            icon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_vault)) },
                            modifier = Modifier.testTag("nav_rail_keyboard")
                        )
                        NavigationRailItem(
                            selected = currentTab == MainNavTab.PREFERENCES_AND_IMPORT,
                            onClick = { viewModel.selectTab(MainNavTab.PREFERENCES_AND_IMPORT) },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("Preferences") },
                            modifier = Modifier.testTag("nav_rail_sync")
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    AnimatedVisibility(visible = statusBanner != null && !isFullScreenSession) {
                        statusBanner?.let { msg ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f))
                                    .clickable { viewModel.clearStatusBanner() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "$msg (tap to dismiss)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (currentTab) {
                            MainNavTab.CONNECTIONS -> {
                                ConnectionsScreen(
                                    profiles = profiles,
                                    protocolDefaultPorts = protocolDefaultPorts,
                                    quickConnectProtocol = quickConnectProtocol,
                                    searchQuery = searchQuery,
                                    selectedProtocol = selectedProtocol,
                                    quickConnectText = quickConnectText,
                                    selectedProfile = selectedProfile,
                                    editingProfile = editingDecryptedProfile,
                                    onQuickConnectProtocolChange = viewModel::setQuickConnectProtocol,
                                    onSearchQueryChange = viewModel::updateSearchQuery,
                                    onProtocolFilterChange = viewModel::selectProtocolFilter,
                                    onQuickConnectTextChange = viewModel::updateQuickConnectText,
                                    onQuickConnectLaunch = viewModel::launchQuickConnect,
                                    onSelectProfile = viewModel::selectProfileForDetail,
                                    onConnectProfile = viewModel::connectToProfile,
                                    onRequestEditProfile = viewModel::requestEditProfile,
                                    onDismissEditProfile = viewModel::dismissEditProfile,
                                    onSaveProfile = viewModel::saveProfile,
                                    onDeleteProfile = viewModel::deleteProfile,
                                    onToggleFavorite = viewModel::toggleFavorite,
                                    onExportRemminaIni = viewModel.repository::exportProfileToRemminaIni,
                                    onImportRemminaFilesFromUris = { uris ->
                                        viewModel.importRemminaConfigFilesFromUris(context, uris)
                                    },
                                    onStatusMessage = viewModel::showStatusMessage
                                )
                            }

                            MainNavTab.ACTIVE_SESSIONS -> {
                                ActiveSessionsScreen(
                                    sessions = activeSessions,
                                    currentSessionId = currentSessionId,
                                    onSelectSession = viewModel::selectActiveSession,
                                    onCloseSession = viewModel::closeSession,
                                    onRetryConnection = viewModel::retrySessionConnection,
                                    onSubmitSessionCredentials = viewModel::submitSessionCredentials,
                                    onOpenNewConnectionTab = { viewModel.selectTab(MainNavTab.CONNECTIONS) },
                                    onToggleFullScreen = viewModel::toggleSessionFullScreen,
                                    onToggleFloatingToolbar = viewModel::toggleFloatingToolbarExpanded,
                                    onUpdateResolutionAndScaling = viewModel::updateSessionResolutionAndScaling,
                                    onDynamicViewportResize = viewModel::onDynamicViewportResize,
                                    onFitDeviceViewport = viewModel::fitSessionToDeviceViewport,
                                    onCycleScalingMode = viewModel::cycleSessionScalingMode,
                                    onToggleInputMode = viewModel::toggleSessionInputMode,
                                    onTapNormalized = viewModel::onSessionTap,
                                    onSecondaryClick = viewModel::onSessionSecondaryClick,
                                    onMiddleClickNormalized = viewModel::onSessionMiddleClick,
                                    onScrollWheelNormalized = viewModel::onSessionScrollWheel,
                                    onHoverMoveNormalized = viewModel::onSessionMouseHover,
                                    onDragNormalized = viewModel::onSessionDragOrTrackpadMove,
                                    onDragStartNormalized = viewModel::onSessionDragStart,
                                    onDragMoveNormalized = viewModel::onSessionDragMove,
                                    onDragEndNormalized = viewModel::onSessionDragEnd,
                                    onZoomAndPan = viewModel::onSessionZoomAndPan,
                                    onZoomChange = { id, z -> viewModel.onSessionZoomAndPan(id, z, 0f, 0f) },
                                    onResetZoom = viewModel::resetSessionZoom,
                                    onDismissContextMenu = viewModel::dismissContextMenu,
                                    onToggleStickyModifier = viewModel::toggleStickyModifier,
                                    onDispatchKeyEvent = viewModel::dispatchKeyEventToSession,
                                    onDispatchHardwareKeyDownOrUp = viewModel::dispatchHardwareKeyDownOrUp,
                                    onDispatchTypedText = viewModel::dispatchTypedTextToSession,
                                    onSendSshCommand = viewModel::sendSshCommandString,
                                    onSendChordMacro = viewModel::sendSpecialChordMacro,
                                    onToggleSftpPanel = viewModel::toggleSftpPanel,
                                    onToggleAllowScreenshots = viewModel::toggleSessionAllowScreenshots
                                )
                            }

                            MainNavTab.KEYBOARD_AND_VAULT -> {
                                KeyboardAndVaultScreen(
                                    keyMappings = keyMappings,
                                    sshKeys = sshKeys,
                                    lastTestedKeyPacket = lastTestedKeyPacket,
                                    securityPolicy = securityPolicy,
                                    biometricStatus = biometricStatus,
                                    securityAuditChecklist = securityAuditChecklist,
                                    lastSecuritySelfTest = lastSecuritySelfTest,
                                    onTestHardwareKey = { code, unicode, ctrl, alt, shift, meta ->
                                        viewModel.testHardwareKeyEvent(code, unicode, ctrl, alt, shift, meta)
                                    },
                                    onSaveKeyMapping = viewModel::saveKeyMapping,
                                    onToggleKeyMapping = viewModel::toggleKeyMapping,
                                    onDeleteKeyMapping = viewModel::deleteKeyMapping,
                                    onGenerateSshKey = viewModel::generateNewSshKey,
                                    onDeleteSshKey = viewModel::deleteSshKey,
                                    onVerifyVaultPin = viewModel::verifyVaultPin,
                                    onSaveVaultPin = viewModel::setupOrUpdateVaultPin,
                                    onRemoveVaultPin = viewModel::removeVaultPin,
                                    onLockAppNow = viewModel::lockVaultNow,
                                    onRunSecuritySelfTest = viewModel::runSecuritySelfTest,
                                    onUpdateSecurityPolicy = viewModel::updateSecurityPolicy,
                                    onStatusMessage = viewModel::showStatusMessage
                                )
                            }

                            MainNavTab.PREFERENCES_AND_IMPORT -> {
                                LocalSyncAndDiagScreen(
                                    profilesCount = allProfiles.size,
                                    keyMappingsCount = keyMappings.size,
                                    protocolDefaultPorts = protocolDefaultPorts,
                                    onUpdateDefaultPort = viewModel::updateProtocolDefaultPort,
                                    onResetDefaultPorts = viewModel::resetAllDefaultPorts,
                                    onExportPortableBundle = viewModel.repository::exportLocalPortableBundle,
                                    onImportPortableBundle = viewModel::importPortableBundle,
                                    onImportRemminaIni = viewModel::importRemminaIniProfile,
                                    onImportRemminaFilesFromUris = { uris ->
                                        viewModel.importRemminaConfigFilesFromUris(context, uris)
                                    },
                                    onStatusMessage = viewModel::showStatusMessage
                                )
                            }
                        }
                    }
                }
            }
        }

        // Per-System Connection & Credential Biometric / PIN Gate Modal
        pendingSecurityChallenge?.let { challenge ->
            SystemAuthGateDialog(
                challenge = challenge,
                policy = securityPolicy,
                biometricStatus = biometricStatus,
                onAuthenticated = viewModel::completePendingSecurityChallenge,
                onVerifyPin = viewModel::verifyVaultPin,
                onSetupQuickPinAndProceed = viewModel::setupOrUpdateVaultPin,
                onTriggerBiometric = { activity, onErr ->
                    viewModel.triggerHardwareBiometricForChallenge(activity, onErr)
                },
                onDismiss = viewModel::dismissPendingSecurityChallenge
            )
        }

        // Full-Screen App Lock Overlay (Biometric + Cryptographic PIN Pad)
        if (isVaultLocked) {
            VaultAppLockOverlay(
                policy = securityPolicy,
                biometricStatus = biometricStatus,
                onVerifyPin = viewModel::verifyVaultPinForAppUnlock,
                onTriggerBiometric = { activity, onErr ->
                    viewModel.triggerHardwareBiometricForAppUnlock(activity, onErr)
                }
            )
        }
    }
}
