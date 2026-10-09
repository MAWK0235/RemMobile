package com.example.ui.session

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.data.model.InputControlMode
import com.example.data.model.RemoteProtocol
import com.example.protocol.ActiveRemoteSession
import com.example.protocol.ResolutionMode
import com.example.protocol.ScalingMode
import com.example.protocol.SessionConnectionPhase
import com.example.protocol.StandardResolutionPresets

@Composable
fun ActiveSessionsScreen(
    sessions: List<ActiveRemoteSession>,
    currentSessionId: String?,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onRetryConnection: (String) -> Unit,
    onSubmitSessionCredentials: (String, String, String, String, String, Boolean) -> Unit,
    onOpenNewConnectionTab: () -> Unit,
    onToggleFullScreen: (String) -> Unit,
    onToggleFloatingToolbar: (String) -> Unit,
    onUpdateResolutionAndScaling: (String, ResolutionMode, Int, Int, ScalingMode, Int, Int, Int) -> Unit,
    onDynamicViewportResize: (String, Int, Int) -> Unit,
    onCycleScalingMode: (String) -> Unit,
    onToggleInputMode: (String) -> Unit,
    onTapNormalized: (String, Float, Float) -> Unit,
    onSecondaryClick: (String, Float?, Float?) -> Unit,
    onMiddleClickNormalized: (String, Float?, Float?) -> Unit = { _, _, _ -> },
    onScrollWheelNormalized: (String, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    onHoverMoveNormalized: (String, Float, Float) -> Unit = { _, _, _ -> },
    onDragNormalized: (String, Float, Float) -> Unit,
    onDragStartNormalized: (String, Float, Float) -> Unit = { _, _, _ -> },
    onDragMoveNormalized: (String, Float, Float, Float, Float) -> Unit = { id, _, _, dx, dy -> onDragNormalized(id, dx, dy) },
    onDragEndNormalized: (String, Float, Float) -> Unit = { _, _, _ -> },
    onZoomAndPan: (String, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onZoomChange: (String, Float) -> Unit,
    onResetZoom: (String) -> Unit,
    onDismissContextMenu: (String) -> Unit,
    onToggleStickyModifier: (String, String) -> Unit,
    onDispatchKeyEvent: (String, Int, Int, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onDispatchHardwareKeyDownOrUp: (String, Int, Int, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit = { id, code, uni, isUp, c, a, s, m ->
        if (!isUp) onDispatchKeyEvent(id, code, uni, c, a, s, m)
    },
    onDispatchTypedText: (String, String) -> Unit = { _, _ -> },
    onFitDeviceViewport: (String) -> Unit = {},
    onSendSshCommand: (String, String) -> Unit,
    onSendChordMacro: (String, String) -> Unit,
    onToggleSftpPanel: (String) -> Unit
) {
    val activeSession = sessions.firstOrNull { it.sessionId == currentSessionId } ?: sessions.firstOrNull()
    val focusRequester = remember { FocusRequester() }
    val imeFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var imeFieldValue by remember { mutableStateOf(TextFieldValue(text = " ", selection = TextRange(1))) }
    var showKeyboardDock by remember { mutableStateOf(true) }
    var showFunctionKeysRow by remember { mutableStateOf(false) }
    var showQuickTextInputRow by remember { mutableStateOf(false) }
    var showResolutionDialog by remember { mutableStateOf(false) }
    var showManualAuthDialog by remember { mutableStateOf(false) }

    val openAndroidSoftKeyboard: () -> Unit = {
        try {
            imeFieldValue = TextFieldValue(text = " ", selection = TextRange(1))
            imeFocusRequester.requestFocus()
            keyboardController?.show()
        } catch (_: Exception) {
        }
    }

    if (activeSession == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Keyboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Text("No Active Connections", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Connect to a saved RDP, SSH, VNC, or SPICE profile or enter a server address in the Connections tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onOpenNewConnectionTab,
                        modifier = Modifier.testTag("browse_profiles_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Connections List")
                    }
                }
            }
        }
        return
    }

    LaunchedEffect(activeSession.sessionId, activeSession.phase) {
        if (activeSession.phase == SessionConnectionPhase.STREAMING_FRAMEBUFFER) {
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusTarget()
            .onPreviewKeyEvent { event ->
                if (activeSession.phase == SessionConnectionPhase.STREAMING_FRAMEBUFFER &&
                    (event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp)
                ) {
                    val native = event.nativeKeyEvent
                    // Allow Android system volume & back buttons unless inside session keys
                    if (native.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                        native.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                        native.keyCode == KeyEvent.KEYCODE_BACK
                    ) {
                        false
                    } else {
                        onDispatchHardwareKeyDownOrUp(
                            activeSession.sessionId,
                            native.keyCode,
                            native.unicodeChar,
                            event.type == KeyEventType.KeyUp,
                            event.isCtrlPressed,
                            event.isAltPressed,
                            event.isShiftPressed,
                            event.isMetaPressed
                        )
                        true
                    }
                } else {
                    false
                }
            }
    ) {
        // 0. Hidden 1dp IME-connected BasicTextField so Android Soft Keyboard (Gboard / Samsung)
        // delivers live letters, numbers, symbols, Backspace, and Enter directly to the remote session!
        BasicTextField(
            value = imeFieldValue,
            onValueChange = { newVal ->
                val text = newVal.text
                if (text.isEmpty()) {
                    // User pressed Backspace on the leading sentinel space
                    onDispatchKeyEvent(activeSession.sessionId, KeyEvent.KEYCODE_DEL, 0, false, false, false, false)
                } else if (text.startsWith(" ") && text.length > 1) {
                    val added = text.substring(1)
                    for (ch in added) {
                        if (ch == '\n' || ch == '\r') {
                            onDispatchKeyEvent(activeSession.sessionId, KeyEvent.KEYCODE_ENTER, 0, false, false, false, false)
                        } else {
                            onDispatchTypedText(activeSession.sessionId, ch.toString())
                        }
                    }
                } else if (!text.startsWith(" ")) {
                    for (ch in text) {
                        if (ch == '\n' || ch == '\r') {
                            onDispatchKeyEvent(activeSession.sessionId, KeyEvent.KEYCODE_ENTER, 0, false, false, false, false)
                        } else {
                            onDispatchTypedText(activeSession.sessionId, ch.toString())
                        }
                    }
                }
                imeFieldValue = TextFieldValue(text = " ", selection = TextRange(1))
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.None,
                autoCorrectEnabled = false
            ),
            modifier = Modifier
                .size(1.dp)
                .focusRequester(imeFocusRequester)
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // When NOT in Fullscreen Mode, show the Remmina Multi-Tab Header & Action Toolbar
            if (!activeSession.isFullScreen) {
                // 1. Remmina GTK Session Tabs Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sessions.forEach { s ->
                        val isSelected = s.sessionId == activeSession.sessionId
                        Surface(
                            color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) s.protocol.badgeColor else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { onSelectSession(s.sessionId) }
                                .testTag("session_tab_${s.profile.protocol.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = s.protocol.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = s.protocol.badgeColor,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${s.profile.name} (${s.profile.server}:${s.profile.port})",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close session tab",
                                    modifier = Modifier
                                        .size(15.dp)
                                        .clickable { onCloseSession(s.sessionId) }
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenNewConnectionTab,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Tab", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // 2. RemMobile Session Control Toolbar (Fullscreen, Resolution Manager, Fit Device, Scaling, Pointer, Soft Keyboard)
                RemminaSessionToolbar(
                    session = activeSession,
                    showKeyboardDock = showKeyboardDock,
                    onToggleFullScreen = { onToggleFullScreen(activeSession.sessionId) },
                    onOpenResolutionDialog = { showResolutionDialog = true },
                    onFitDeviceViewport = { onFitDeviceViewport(activeSession.sessionId) },
                    onCycleScalingMode = { onCycleScalingMode(activeSession.sessionId) },
                    onToggleInputMode = { onToggleInputMode(activeSession.sessionId) },
                    onSecondaryClick = { onSecondaryClick(activeSession.sessionId, null, null) },
                    onOpenSoftKeyboard = openAndroidSoftKeyboard,
                    onToggleKeyboardDock = { showKeyboardDock = !showKeyboardDock },
                    onToggleSftpPanel = { onToggleSftpPanel(activeSession.sessionId) },
                    onDisconnect = { onCloseSession(activeSession.sessionId) }
                )
            }

            // 3. Main Session Content Area (measures device viewport immediately so RDP scales to tablet/phone!)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { sz ->
                        if (sz.width > 200 && sz.height > 200) {
                            onDynamicViewportResize(activeSession.sessionId, sz.width, sz.height)
                        }
                    }
            ) {
                when (activeSession.phase) {
                    SessionConnectionPhase.INITIALIZING_TUNNEL,
                    SessionConnectionPhase.CONNECTING_SOCKET,
                    SessionConnectionPhase.NEGOTIATING_SECURITY -> {
                        RemminaConnectingModal(
                            session = activeSession,
                            onOpenCredentialsModal = { showManualAuthDialog = true },
                            onCancel = { onCloseSession(activeSession.sessionId) }
                        )
                    }

                    SessionConnectionPhase.AUTHENTICATION_REQUIRED -> {
                        RemminaAuthenticationScreen(
                            session = activeSession,
                            onSubmitCredentials = { user, pass, dom, secMode, save ->
                                onSubmitSessionCredentials(activeSession.sessionId, user, pass, dom, secMode, save)
                            },
                            onCancel = { onCloseSession(activeSession.sessionId) }
                        )
                    }

                    SessionConnectionPhase.UNABLE_TO_CONNECT -> {
                        RemminaUnableToConnectScreen(
                            session = activeSession,
                            onRetry = { onRetryConnection(activeSession.sessionId) },
                            onOpenCredentialsModal = { showManualAuthDialog = true },
                            onOpenResolutionManager = { showResolutionDialog = true },
                            onToggleFullScreen = { onToggleFullScreen(activeSession.sessionId) },
                            onCloseSession = { onCloseSession(activeSession.sessionId) }
                        )
                    }

                    SessionConnectionPhase.STREAMING_FRAMEBUFFER -> {
                        if (activeSession.protocol == RemoteProtocol.SSH) {
                            SshTerminalWorkspace(
                                session = activeSession,
                                onSendSshCommand = { cmd ->
                                    onSendSshCommand(activeSession.sessionId, cmd)
                                }
                            )
                        } else {
                            RemoteFramebufferCanvas(
                                session = activeSession,
                                onDynamicViewportResize = { w, h ->
                                    onDynamicViewportResize(activeSession.sessionId, w, h)
                                },
                                onTapNormalized = { nx, ny -> onTapNormalized(activeSession.sessionId, nx, ny) },
                                onLongPressNormalized = { nx, ny -> onSecondaryClick(activeSession.sessionId, nx, ny) },
                                onMiddleClickNormalized = { nx, ny -> onMiddleClickNormalized(activeSession.sessionId, nx, ny) },
                                onScrollWheelNormalized = { nx, ny, vDelta, hDelta ->
                                    onScrollWheelNormalized(activeSession.sessionId, nx, ny, vDelta, hDelta)
                                },
                                onHoverMoveNormalized = { nx, ny ->
                                    onHoverMoveNormalized(activeSession.sessionId, nx, ny)
                                },
                                onDragNormalized = { dx, dy -> onDragNormalized(activeSession.sessionId, dx, dy) },
                                onDragStartNormalized = { nx, ny ->
                                    onDragStartNormalized(activeSession.sessionId, nx, ny)
                                },
                                onDragMoveNormalized = { absX, absY, dx, dy ->
                                    onDragMoveNormalized(activeSession.sessionId, absX, absY, dx, dy)
                                },
                                onDragEndNormalized = { nx, ny ->
                                    onDragEndNormalized(activeSession.sessionId, nx, ny)
                                },
                                onZoomAndPan = { zoom, panX, panY ->
                                    onZoomAndPan(activeSession.sessionId, zoom, panX, panY)
                                },
                                onSendChordMacro = { macro -> onSendChordMacro(activeSession.sessionId, macro) },
                                onOpenResolutionDialog = { showResolutionDialog = true },
                                onToggleFullScreen = { onToggleFullScreen(activeSession.sessionId) },
                                onDismissContextMenu = { onDismissContextMenu(activeSession.sessionId) },
                                onRequestKeyboardFocus = {
                                    try {
                                        focusRequester.requestFocus()
                                    } catch (_: Exception) {
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // 4. Bottom Hardware Keyboard Scancode, Soft Keyboard (IME) & Quick Text Dock
            if (showKeyboardDock) {
                HardwareKeyboardDock(
                    session = activeSession,
                    showFunctionKeysRow = showFunctionKeysRow,
                    showQuickTextInputRow = showQuickTextInputRow,
                    onToggleFunctionKeysRow = { showFunctionKeysRow = !showFunctionKeysRow },
                    onToggleQuickTextInputRow = { showQuickTextInputRow = !showQuickTextInputRow },
                    onOpenSoftKeyboard = openAndroidSoftKeyboard,
                    onToggleStickyModifier = { mod -> onToggleStickyModifier(activeSession.sessionId, mod) },
                    onSendKeyCode = { keyCode ->
                        onDispatchKeyEvent(activeSession.sessionId, keyCode, 0, false, false, false, false)
                    },
                    onSendTypedText = { text ->
                        onDispatchTypedText(activeSession.sessionId, text)
                    },
                    onSendChordMacro = { macro -> onSendChordMacro(activeSession.sessionId, macro) }
                )
            }
        }

        // 5. Floating Remmina Top-Edge Toolbar in FULLSCREEN MODE (Authentic Desktop Remmina Pull-Tab!)
        if (activeSession.isFullScreen) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = Color(0xEE242424),
                    shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                    modifier = Modifier
                        .border(1.dp, activeSession.protocol.badgeColor, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .clickable { onToggleFloatingToolbar(activeSession.sessionId) }
                        .testTag("fullscreen_floating_tab")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${activeSession.protocol.displayName} • ${activeSession.profile.server}:${activeSession.profile.port}",
                            style = MaterialTheme.typography.labelSmall,
                            color = activeSession.protocol.badgeColor,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${activeSession.remoteWidth}×${activeSession.remoteHeight} (${activeSession.scalingMode.shortLabel})",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                        Icon(
                            imageVector = if (activeSession.showFloatingToolbarExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Toggle floating toolbar",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                if (activeSession.showFloatingToolbarExpanded) {
                    Surface(
                        color = Color(0xF2282828),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .padding(top = 4.dp, start = 12.dp, end = 12.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    ) {
                        RemminaSessionToolbar(
                            session = activeSession,
                            showKeyboardDock = showKeyboardDock,
                            onToggleFullScreen = { onToggleFullScreen(activeSession.sessionId) },
                            onOpenResolutionDialog = { showResolutionDialog = true },
                            onFitDeviceViewport = { onFitDeviceViewport(activeSession.sessionId) },
                            onCycleScalingMode = { onCycleScalingMode(activeSession.sessionId) },
                            onToggleInputMode = { onToggleInputMode(activeSession.sessionId) },
                            onSecondaryClick = { onSecondaryClick(activeSession.sessionId, null, null) },
                            onOpenSoftKeyboard = openAndroidSoftKeyboard,
                            onToggleKeyboardDock = { showKeyboardDock = !showKeyboardDock },
                            onToggleSftpPanel = { onToggleSftpPanel(activeSession.sessionId) },
                            onDisconnect = { onCloseSession(activeSession.sessionId) }
                        )
                    }
                }
            }
        }
    }

    if (showResolutionDialog) {
        SessionResolutionManagerDialog(
            session = activeSession,
            onDismiss = { showResolutionDialog = false },
            onApply = { resMode, w, h, scaleMode, bpp, dpi, monitors ->
                onUpdateResolutionAndScaling(
                    activeSession.sessionId,
                    resMode,
                    w,
                    h,
                    scaleMode,
                    bpp,
                    dpi,
                    monitors
                )
                showResolutionDialog = false
            }
        )
    }

    if (showManualAuthDialog) {
        Dialog(onDismissRequest = { showManualAuthDialog = false }) {
            RemminaAuthenticationScreen(
                session = activeSession,
                onSubmitCredentials = { user, pass, dom, secMode, save ->
                    showManualAuthDialog = false
                    onSubmitSessionCredentials(activeSession.sessionId, user, pass, dom, secMode, save)
                },
                onCancel = { showManualAuthDialog = false }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemminaAuthenticationScreen(
    session: ActiveRemoteSession,
    onSubmitCredentials: (String, String, String, String, Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var username by remember(session) { mutableStateOf(session.profile.username) }
    var password by remember(session) { mutableStateOf(session.profile.password) }
    var domain by remember(session) { mutableStateOf(session.profile.domain) }
    var securityMode by remember(session) { mutableStateOf(session.profile.securityMode.ifBlank { "Negotiate" }) }
    var showPassword by remember { mutableStateOf(false) }
    var saveCredentials by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141414))
            .padding(16.dp)
            .testTag("authentication_required_screen"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .border(1.dp, session.protocol.badgeColor, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = session.protocol.badgeColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = "${session.protocol.displayName} Authentication — ${session.profile.server}:${session.profile.port}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = session.authPromptReason ?: "Enter credentials for ${session.profile.server}:${session.profile.port}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (session.tlsCertSubject != null || session.tlsCertFingerprint != null) {
                    Surface(
                        color = Color(0xFF101820),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Server TLS Certificate Verified:",
                                style = MaterialTheme.typography.labelSmall,
                                color = session.protocol.badgeColor
                            )
                            session.tlsCertSubject?.let {
                                Text(text = "Subject: $it", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                            session.tlsCertFingerprint?.let {
                                Text(text = it, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A9996))
                            }
                        }
                    }
                }

                if (session.protocol != RemoteProtocol.VNC) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_username_input")
                    )
                }

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle password visibility"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auth_password_input")
                )

                if (session.protocol == RemoteProtocol.RDP) {
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = { Text("Domain (Optional, leave blank for local account)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_domain_input")
                    )

                    Text("RDP Security Mode", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Negotiate" to "Negotiate (Auto)",
                            "NLA" to "NLA (CredSSP)",
                            "TLS" to "TLS Security",
                            "RDP" to "Standard RDP"
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = securityMode.equals(key, ignoreCase = true),
                                onClick = { securityMode = key },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = saveCredentials,
                        onCheckedChange = { saveCredentials = it }
                    )
                    Text(
                        "Save credentials to encrypted vault (AES-256-GCM)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSubmitCredentials(username, password, domain, securityMode, saveCredentials)
                        },
                        modifier = Modifier.testTag("auth_connect_button")
                    ) {
                        Text("Authenticate & Connect")
                    }
                }
            }
        }
    }
}

@Composable
private fun RemminaSessionToolbar(
    session: ActiveRemoteSession,
    showKeyboardDock: Boolean,
    onToggleFullScreen: () -> Unit,
    onOpenResolutionDialog: () -> Unit,
    onFitDeviceViewport: () -> Unit = {},
    onCycleScalingMode: () -> Unit,
    onToggleInputMode: () -> Unit,
    onSecondaryClick: () -> Unit,
    onOpenSoftKeyboard: () -> Unit = {},
    onToggleKeyboardDock: () -> Unit,
    onToggleSftpPanel: () -> Unit,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = session.isFullScreen,
            onClick = onToggleFullScreen,
            leadingIcon = {
                Icon(
                    imageVector = if (session.isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = "Toggle Fullscreen",
                    modifier = Modifier.size(16.dp)
                )
            },
            label = { Text(if (session.isFullScreen) "Exit Fullscreen" else "Fullscreen") },
            modifier = Modifier.testTag("toggle_fullscreen_button")
        )

        FilterChip(
            selected = session.resolutionMode == ResolutionMode.DYNAMIC_CLIENT,
            onClick = onFitDeviceViewport,
            leadingIcon = {
                Icon(Icons.Default.FitScreen, contentDescription = "Fit Device Screen", modifier = Modifier.size(16.dp))
            },
            label = { Text("Fit Device") },
            modifier = Modifier.testTag("fit_device_resolution_button")
        )

        FilterChip(
            selected = false,
            onClick = onOpenResolutionDialog,
            leadingIcon = {
                Icon(Icons.Default.AspectRatio, contentDescription = "Resolution Manager", modifier = Modifier.size(16.dp))
            },
            label = {
                Text("${session.remoteWidth}×${session.remoteHeight} (${session.colorDepthBpp}bpp)")
            },
            modifier = Modifier.testTag("open_resolution_manager_button")
        )

        FilterChip(
            selected = session.scalingMode == ScalingMode.FIT_WINDOW,
            onClick = onCycleScalingMode,
            leadingIcon = {
                Icon(Icons.Default.FitScreen, contentDescription = "Toggle Scaled Mode", modifier = Modifier.size(16.dp))
            },
            label = { Text("Scale: ${session.scalingMode.shortLabel}") },
            modifier = Modifier.testTag("cycle_scaling_mode_button")
        )

        if (session.protocol != RemoteProtocol.SSH) {
            FilterChip(
                selected = false,
                onClick = onOpenSoftKeyboard,
                leadingIcon = {
                    Icon(Icons.Default.Keyboard, contentDescription = "Open Soft Keyboard", modifier = Modifier.size(16.dp))
                },
                label = { Text("Keyboard") },
                modifier = Modifier.testTag("open_soft_keyboard_chip")
            )

            FilterChip(
                selected = session.inputMode == InputControlMode.TRACKPAD,
                onClick = onToggleInputMode,
                leadingIcon = {
                    Icon(
                        imageVector = if (session.inputMode == InputControlMode.DIRECT_TOUCH) Icons.Default.TouchApp else Icons.Default.Mouse,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = { Text(session.inputMode.label) },
                modifier = Modifier.testTag("toggle_input_mode_chip")
            )

            FilterChip(
                selected = session.showContextMenuAt != null,
                onClick = onSecondaryClick,
                label = { Text("Right Click") },
                modifier = Modifier.testTag("right_click_chip")
            )
        } else {
            FilterChip(
                selected = session.showSftpPanel,
                onClick = onToggleSftpPanel,
                leadingIcon = {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = { Text("SFTP") },
                modifier = Modifier.testTag("toggle_sftp_chip")
            )
        }

        FilterChip(
            selected = showKeyboardDock,
            onClick = onToggleKeyboardDock,
            leadingIcon = {
                Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            label = { Text("Keybar") }
        )

        OutlinedButton(
            onClick = onDisconnect,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Disconnect", modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Disconnect", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RemminaConnectingModal(
    session: ActiveRemoteSession,
    onOpenCredentialsModal: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF161616)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = session.protocol.badgeColor)
                Text(
                    text = session.serverBanner.ifBlank { session.phase.label },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${session.protocol.displayName}://${session.profile.server}:${session.profile.port} • Security: ${session.profile.securityMode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (session.wireProtocolTrace.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF101216), RoundedCornerShape(6.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        session.wireProtocolTrace.takeLast(4).forEach { traceLine ->
                            Text(
                                text = traceLine,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF9A9996)
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onOpenCredentialsModal) {
                        Text("Credentials / Security Mode")
                    }
                    TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemminaUnableToConnectScreen(
    session: ActiveRemoteSession,
    onRetry: () -> Unit,
    onOpenCredentialsModal: () -> Unit,
    onOpenResolutionManager: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onCloseSession: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF181818))
            .padding(16.dp)
            .testTag("unable_to_connect_screen"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = "Connection Failed",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text(
                            text = session.errorTitle ?: "Unable to connect to the ${session.protocol.displayName} server ${session.profile.server}:${session.profile.port}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = session.errorDetails ?: "Host unreachable or connection timed out",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                Text(
                    text = "Profile: ${session.profile.name} • Security: ${session.profile.securityMode} • Resolution: ${session.remoteWidth}×${session.remoteHeight} (${session.colorDepthBpp} bpp)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = Color(0xFF121212),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "RemMobile Connection Log (${session.protocol.displayName} Plugin):",
                            style = MaterialTheme.typography.labelSmall,
                            color = session.protocol.badgeColor
                        )
                        session.wireProtocolTrace.forEach { logLine ->
                            Text(
                                text = logLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (logLine.startsWith("[error]")) MaterialTheme.colorScheme.error else Color(0xFFCCCCCC)
                            )
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("retry_connection_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reconnect")
                    }

                    OutlinedButton(
                        onClick = onOpenCredentialsModal,
                        modifier = Modifier.testTag("error_credentials_button")
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Credentials / Security Mode")
                    }

                    OutlinedButton(
                        onClick = onOpenResolutionManager,
                        modifier = Modifier.testTag("error_manage_resolution_button")
                    ) {
                        Icon(Icons.Default.AspectRatio, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Resolution (${session.remoteWidth}×${session.remoteHeight})")
                    }

                    OutlinedButton(onClick = onToggleFullScreen) {
                        Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Fullscreen")
                    }

                    OutlinedButton(onClick = onCloseSession) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionResolutionManagerDialog(
    session: ActiveRemoteSession,
    onDismiss: () -> Unit,
    onApply: (ResolutionMode, Int, Int, ScalingMode, Int, Int, Int) -> Unit
) {
    var resolutionMode by remember(session) { mutableStateOf(session.resolutionMode) }
    var widthText by remember(session) { mutableStateOf(session.remoteWidth.toString()) }
    var heightText by remember(session) { mutableStateOf(session.remoteHeight.toString()) }
    var scalingMode by remember(session) { mutableStateOf(session.scalingMode) }
    var colorDepth by remember(session) { mutableIntStateOf(session.colorDepthBpp) }
    var dpiScale by remember(session) { mutableIntStateOf(session.remoteDpiScalePercent) }
    var monitorCount by remember(session) { mutableIntStateOf(session.monitorCount) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "${session.protocol.displayName} Resolution & Scaling",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = when (session.protocol) {
                                RemoteProtocol.RDP -> "Dynamic Channel: MS-RDPEGFX DisplayControl PDU"
                                RemoteProtocol.VNC -> "Dynamic Channel: RFB ExtendedDesktopSize"
                                RemoteProtocol.SPICE -> "Dynamic Channel: spice-vdagent MonitorsConfig"
                                RemoteProtocol.SSH -> "Dynamic Channel: PTY SIGWINCH window-change"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = session.protocol.badgeColor
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider()

                Text("Resolution Mode", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ResolutionMode.entries.forEach { mode ->
                        FilterChip(
                            selected = resolutionMode == mode,
                            onClick = { resolutionMode = mode },
                            label = { Text(mode.label) }
                        )
                    }
                }

                Text("Remote Desktop Resolution (Width × Height)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StandardResolutionPresets.forEach { preset ->
                        val isSelected = widthText == preset.width.toString() && heightText == preset.height.toString()
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                widthText = preset.width.toString()
                                heightText = preset.height.toString()
                                if (resolutionMode == ResolutionMode.DYNAMIC_CLIENT) {
                                    resolutionMode = ResolutionMode.FIXED_PRESET
                                }
                            },
                            label = { Text("${preset.display} (${preset.aspectLabel})") },
                            modifier = Modifier.testTag("res_preset_${preset.display}")
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = {
                            widthText = it.filter { c -> c.isDigit() }
                            resolutionMode = ResolutionMode.CUSTOM
                        },
                        label = { Text("Width (px)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = {
                            heightText = it.filter { c -> c.isDigit() }
                            resolutionMode = ResolutionMode.CUSTOM
                        },
                        label = { Text("Height (px)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text("Viewport Scaling Mode", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScalingMode.entries.forEach { sm ->
                        FilterChip(
                            selected = scalingMode == sm,
                            onClick = { scalingMode = sm },
                            label = { Text(sm.label) }
                        )
                    }
                }

                Text("Color Depth (BPP)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(32, 24, 16, 8).forEach { bpp ->
                        FilterChip(
                            selected = colorDepth == bpp,
                            onClick = { colorDepth = bpp },
                            label = { Text("$bpp bpp") }
                        )
                    }
                }

                Text("Remote Desktop DPI Scale & Monitors", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 125, 150, 200).forEach { dpi ->
                        FilterChip(
                            selected = dpiScale == dpi,
                            onClick = { dpiScale = dpi },
                            label = { Text("$dpi%") }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3).forEach { count ->
                        FilterChip(
                            selected = monitorCount == count,
                            onClick = { monitorCount = count },
                            label = { Text(if (count == 1) "Single Monitor" else "$count Monitors") }
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val w = widthText.toIntOrNull() ?: 1280
                            val h = heightText.toIntOrNull() ?: 720
                            onApply(resolutionMode, w, h, scalingMode, colorDepth, dpiScale, monitorCount)
                        },
                        modifier = Modifier.testTag("apply_resolution_button")
                    ) {
                        Text("Apply Resolution")
                    }
                }
            }
        }
    }
}

@Composable
private fun SshTerminalWorkspace(
    session: ActiveRemoteSession,
    onSendSshCommand: (String) -> Unit
) {
    var manualCommandText by remember { mutableStateOf("") }

    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            modifier = Modifier
                .weight(if (session.showSftpPanel) 0.62f else 1f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101010))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(session.terminalLines) { line ->
                        val color = when {
                            line.isError -> Color(0xFFE01B24)
                            line.isAccent -> Color(0xFF3584E4)
                            line.isCommandPrompt -> Color(0xFF2EC27E)
                            else -> Color(0xFFEEEEEC)
                        }
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = color
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = manualCommandText,
                        onValueChange = { manualCommandText = it },
                        placeholder = { Text("SSH command (${session.remoteWidth / 10}x${session.remoteHeight / 20} PTY)...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (manualCommandText.isNotBlank()) {
                                    onSendSshCommand(manualCommandText)
                                    manualCommandText = ""
                                }
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ssh_command_input")
                    )
                    Button(
                        onClick = {
                            onSendSshCommand(manualCommandText)
                            manualCommandText = ""
                        },
                        modifier = Modifier.testTag("ssh_send_button")
                    ) {
                        Text("Send")
                    }
                }
            }
        }

        if (session.showSftpPanel) {
            Card(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "SFTP • ${session.sftpCurrentPath}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    HorizontalDivider()
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(session.sftpFiles) { file ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = if (file.isDirectory) "[DIR] ${file.name}" else file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${file.permissions} • ${file.sizeBytes} B",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HardwareKeyboardDock(
    session: ActiveRemoteSession,
    showFunctionKeysRow: Boolean,
    showQuickTextInputRow: Boolean,
    onToggleFunctionKeysRow: () -> Unit,
    onToggleQuickTextInputRow: () -> Unit,
    onOpenSoftKeyboard: () -> Unit,
    onToggleStickyModifier: (String) -> Unit,
    onSendKeyCode: (Int) -> Unit,
    onSendTypedText: (String) -> Unit,
    onSendChordMacro: (String) -> Unit
) {
    var quickTextBuffer by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                QuickKeyButton("KB", isAccent = true) { onOpenSoftKeyboard() }
                QuickKeyButton("TYPE", isAccent = showQuickTextInputRow) { onToggleQuickTextInputRow() }

                StickyModifierKey("CTRL", session.stickyCtrl) { onToggleStickyModifier("CTRL") }
                StickyModifierKey("ALT", session.stickyAlt) { onToggleStickyModifier("ALT") }
                StickyModifierKey("SHIFT", session.stickyShift) { onToggleStickyModifier("SHIFT") }
                StickyModifierKey("SUPER", session.stickySuper) { onToggleStickyModifier("SUPER") }

                QuickKeyButton("ESC") { onSendKeyCode(KeyEvent.KEYCODE_ESCAPE) }
                QuickKeyButton("TAB") { onSendKeyCode(KeyEvent.KEYCODE_TAB) }
                QuickKeyButton("ENTER", isAccent = true) { onSendKeyCode(KeyEvent.KEYCODE_ENTER) }
                QuickKeyButton("BKSP") { onSendKeyCode(KeyEvent.KEYCODE_DEL) }
                QuickKeyButton("CTRL+ALT+DEL", isAccent = true) { onSendChordMacro("CTRL_ALT_DEL") }
                QuickKeyButton("ALT+TAB", isAccent = true) { onSendChordMacro("ALT_TAB") }
                QuickKeyButton("↑") { onSendKeyCode(KeyEvent.KEYCODE_DPAD_UP) }
                QuickKeyButton("↓") { onSendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
                QuickKeyButton("←") { onSendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
                QuickKeyButton("→") { onSendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
                QuickKeyButton("DEL") { onSendKeyCode(KeyEvent.KEYCODE_FORWARD_DEL) }
                QuickKeyButton("F1-F12") { onToggleFunctionKeysRow() }
            }

            if (showQuickTextInputRow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = quickTextBuffer,
                        onValueChange = { quickTextBuffer = it },
                        placeholder = {
                            Text(
                                "Type or paste text/command to send to remote host...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (quickTextBuffer.isNotEmpty()) {
                                    onSendTypedText(quickTextBuffer)
                                    onSendKeyCode(KeyEvent.KEYCODE_ENTER)
                                    quickTextBuffer = ""
                                } else {
                                    onSendKeyCode(KeyEvent.KEYCODE_ENTER)
                                }
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_text_input_field")
                    )
                    OutlinedButton(
                        onClick = {
                            if (quickTextBuffer.isNotEmpty()) {
                                onSendTypedText(quickTextBuffer)
                                quickTextBuffer = ""
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("quick_text_send_button")
                    ) {
                        Text("Send", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = {
                            if (quickTextBuffer.isNotEmpty()) {
                                onSendTypedText(quickTextBuffer)
                                quickTextBuffer = ""
                            }
                            onSendKeyCode(KeyEvent.KEYCODE_ENTER)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("quick_text_send_enter_button")
                    ) {
                        Text("Send+↵", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (showFunctionKeysRow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    QuickKeyButton("CAPS") { onSendKeyCode(KeyEvent.KEYCODE_CAPS_LOCK) }
                    val fnCodes = listOf(
                        "F1" to KeyEvent.KEYCODE_F1,
                        "F2" to KeyEvent.KEYCODE_F2,
                        "F3" to KeyEvent.KEYCODE_F3,
                        "F4" to KeyEvent.KEYCODE_F4,
                        "F5" to KeyEvent.KEYCODE_F5,
                        "F6" to KeyEvent.KEYCODE_F6,
                        "F7" to KeyEvent.KEYCODE_F7,
                        "F8" to KeyEvent.KEYCODE_F8,
                        "F9" to KeyEvent.KEYCODE_F9,
                        "F10" to KeyEvent.KEYCODE_F10,
                        "F11" to KeyEvent.KEYCODE_F11,
                        "F12" to KeyEvent.KEYCODE_F12
                    )
                    fnCodes.forEach { (label, code) ->
                        QuickKeyButton(label) { onSendKeyCode(code) }
                    }
                    QuickKeyButton("HOME") { onSendKeyCode(KeyEvent.KEYCODE_MOVE_HOME) }
                    QuickKeyButton("END") { onSendKeyCode(KeyEvent.KEYCODE_MOVE_END) }
                    QuickKeyButton("PGUP") { onSendKeyCode(KeyEvent.KEYCODE_PAGE_UP) }
                    QuickKeyButton("PGDN") { onSendKeyCode(KeyEvent.KEYCODE_PAGE_DOWN) }
                    QuickKeyButton("SYSRQ") { onSendKeyCode(KeyEvent.KEYCODE_SYSRQ) }
                }
            }

            val latestKey = session.lastTranslatedKeys.firstOrNull()
            if (latestKey != null) {
                Text(
                    text = "Key: ${latestKey.keyLabel} • RDP:${latestKey.rdpScancodeHex} • VNC:${latestKey.vncKeySymName}(${latestKey.vncKeySymHex}) • SSH:${latestKey.sshAnsiEscape}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StickyModifierKey(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .testTag("sticky_key_${label.lowercase()}")
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun QuickKeyButton(
    label: String,
    isAccent: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        color = if (isAccent) MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (isAccent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .testTag("quick_key_${label.lowercase().replace('+', '_')}")
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isAccent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}
