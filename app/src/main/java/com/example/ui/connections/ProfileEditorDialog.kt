package com.example.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.ConnectionProfileEntity
import com.example.data.model.RemoteProtocol
import com.example.protocol.ResolutionMode
import com.example.protocol.ScalingMode
import com.example.protocol.StandardResolutionPresets

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorDialog(
    initialProfile: ConnectionProfileEntity?,
    protocolDefaultPorts: Map<String, Int>,
    onDismiss: () -> Unit,
    onSave: (ConnectionProfileEntity) -> Unit
) {
    val defaultRdpPort = protocolDefaultPorts["RDP"] ?: 3389
    val defaultSshTunnelPort = protocolDefaultPorts["SSH_TUNNEL"] ?: 22
    val defaultRdGwPort = protocolDefaultPorts["RD_GATEWAY"] ?: 443

    val base = initialProfile ?: ConnectionProfileEntity(
        name = "",
        groupName = "Workstations",
        protocol = "RDP",
        server = "",
        port = defaultRdpPort,
        sshTunnelPort = defaultSshTunnelPort,
        rdGatewayPort = defaultRdGwPort,
        username = ""
    )

    var name by remember(base) { mutableStateOf(base.name) }
    var groupName by remember(base) { mutableStateOf(base.groupName) }
    var protocol by remember(base) { mutableStateOf(RemoteProtocol.fromString(base.protocol)) }
    var server by remember(base) { mutableStateOf(base.server) }
    var portText by remember(base) { mutableStateOf(base.port.toString()) }
    var username by remember(base) { mutableStateOf(base.username) }
    var password by remember(base) { mutableStateOf(base.password) }
    var showPassword by remember { mutableStateOf(false) }
    var domain by remember(base) { mutableStateOf(base.domain) }

    // Display, Resolution & Fullscreen
    var resolutionMode by remember(base) {
        mutableStateOf(
            try {
                ResolutionMode.valueOf(base.resolutionMode)
            } catch (_: Exception) {
                ResolutionMode.DYNAMIC_CLIENT
            }
        )
    }
    var resolution by remember(base) { mutableStateOf(base.resolution) }
    var customWidthText by remember(base) { mutableStateOf(base.resolution.substringBefore("x", "1280")) }
    var customHeightText by remember(base) { mutableStateOf(base.resolution.substringAfter("x", "720")) }
    var scalingMode by remember(base) {
        mutableStateOf(
            try {
                ScalingMode.valueOf(base.scalingMode)
            } catch (_: Exception) {
                ScalingMode.FIT_WINDOW
            }
        )
    }
    var startFullScreen by remember(base) { mutableStateOf(base.startFullScreen) }
    var colorDepth by remember(base) { mutableIntStateOf(base.colorDepth) }
    var dpiScalePercent by remember(base) { mutableIntStateOf(base.dpiScalePercent) }
    var codec by remember(base) { mutableStateOf(base.codec) }
    var securityMode by remember(base) { mutableStateOf(base.securityMode.ifBlank { "Negotiate" }) }
    var requireBiometricForConnection by remember(base) { mutableStateOf(base.requireBiometricForConnection) }
    var ignoreCertWarnings by remember(base) { mutableStateOf(base.ignoreCertWarnings) }
    var pinnedCertSha256 by remember(base) { mutableStateOf(base.pinnedCertSha256) }
    var dynamicResolution by remember(base) { mutableStateOf(base.dynamicResolutionUpdate) }
    var audioRedir by remember(base) { mutableStateOf(base.enableAudioRedirection) }
    var clipboardSync by remember(base) { mutableStateOf(base.enableClipboardSync) }
    var usbRedir by remember(base) { mutableStateOf(base.enableUsbRedirection) }
    var allowScreenshots by remember(base) { mutableStateOf(base.allowScreenshots) }

    // SSH Tunnel
    var sshTunnelEnabled by remember(base) { mutableStateOf(base.sshTunnelEnabled) }
    var sshTunnelHost by remember(base) { mutableStateOf(base.sshTunnelHost) }
    var sshTunnelPortText by remember(base) { mutableStateOf(base.sshTunnelPort.toString()) }
    var sshTunnelUsername by remember(base) { mutableStateOf(base.sshTunnelUsername) }
    var sshTunnelPassword by remember(base) { mutableStateOf(base.sshTunnelPassword) }
    var sshTunnelAuthMethod by remember(base) { mutableStateOf(base.sshTunnelAuthMethod) }
    var sshTunnelLoopback by remember(base) { mutableStateOf(base.sshTunnelLoopback) }

    // RD Gateway
    var rdGatewayEnabled by remember(base) { mutableStateOf(base.rdGatewayEnabled) }
    var rdGatewayServer by remember(base) { mutableStateOf(base.rdGatewayServer) }
    var rdGatewayPortText by remember(base) { mutableStateOf(base.rdGatewayPort.toString()) }
    var rdGatewayUsername by remember(base) { mutableStateOf(base.rdGatewayUsername) }
    var rdGatewayDomain by remember(base) { mutableStateOf(base.rdGatewayDomain) }

    // Input & Keyboard
    var defaultInputMode by remember(base) { mutableStateOf(base.defaultInputMode) }
    var keyboardLayoutCode by remember(base) { mutableStateOf(base.keyboardLayoutCode) }
    var preExecCommand by remember(base) { mutableStateOf(base.preExecCommand) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Basic", "Resolution & Display", "SSH Tunnel", "RD Gateway", "Keyboard")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .padding(vertical = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // GTK-style Dialog HeaderBar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (initialProfile == null) "Remote Connection Profile" else "Edit Profile — ${base.name}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${protocol.wireProtocolName} • Default Port :${protocolDefaultPorts[protocol.name] ?: protocol.defaultPort}",
                            style = MaterialTheme.typography.labelSmall,
                            color = protocol.badgeColor
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close profile editor")
                    }
                }

                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 12.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    tabs.forEachIndexed { idx, title ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            text = { Text(title) }
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (selectedTab) {
                        0 -> {
                            Text("Protocol Plugin", style = MaterialTheme.typography.labelLarge)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RemoteProtocol.entries.forEach { proto ->
                                    val configuredPort = protocolDefaultPorts[proto.name] ?: proto.defaultPort
                                    FilterChip(
                                        selected = protocol == proto,
                                        onClick = {
                                            protocol = proto
                                            portText = configuredPort.toString()
                                            codec = proto.defaultCodec
                                        },
                                        label = { Text("${proto.displayName} (:$configuredPort)") }
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = { Text("Name") },
                                    placeholder = { Text("Workstation or Server Name") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(0.6f)
                                        .testTag("profile_name_input")
                                )
                                OutlinedTextField(
                                    value = groupName,
                                    onValueChange = { groupName = it },
                                    label = { Text("Group") },
                                    placeholder = { Text("Workstations") },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.4f)
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = server,
                                    onValueChange = { server = it },
                                    label = { Text("Server (Host / IP)") },
                                    placeholder = { Text("192.168.1.100") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(0.68f)
                                        .testTag("profile_server_input")
                                )
                                OutlinedTextField(
                                    value = portText,
                                    onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("Port") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(0.32f)
                                        .testTag("profile_port_input")
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    label = { Text("Username") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(0.55f)
                                        .testTag("profile_username_input")
                                )
                                OutlinedTextField(
                                    value = domain,
                                    onValueChange = { domain = it },
                                    label = { Text("Domain (Optional)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.45f)
                                )
                            }

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password (Encrypted at Rest with AES-256-GCM)") },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.EnhancedEncryption,
                                        contentDescription = "AES-256-GCM Encrypted",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
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
                                    .testTag("profile_password_input")
                            )

                            SettingToggleRow(
                                title = "Require Biometric / PIN Before Connecting",
                                subtitle = "Prompt for Fingerprint, Face Unlock, or Master Vault PIN before launching or editing this system",
                                checked = requireBiometricForConnection,
                                onCheckedChange = { requireBiometricForConnection = it }
                            )

                            SettingToggleRow(
                                title = "Strict TLS SHA-256 Certificate Pinning (TOFU)",
                                subtitle = if (pinnedCertSha256.isNotBlank()) {
                                    "Pinned: $pinnedCertSha256 (Blocks MITM certificate changes)"
                                } else {
                                    "Pin server certificate SHA-256 fingerprint on first TLS connection and block unexpected changes"
                                },
                                checked = !ignoreCertWarnings,
                                onCheckedChange = { strict -> ignoreCertWarnings = !strict }
                            )

                            if (pinnedCertSha256.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { pinnedCertSha256 = "" },
                                    modifier = Modifier.testTag("reset_pinned_cert_button")
                                ) {
                                    Text("Clear Pinned Certificate ($pinnedCertSha256)")
                                }
                            }

                            if (protocol == RemoteProtocol.RDP) {
                                Text("RDP Security Mode", style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        "Negotiate" to "Negotiate (Auto)",
                                        "NLA" to "NLA (CredSSP / NTLMv2)",
                                        "TLS" to "TLS Security",
                                        "RDP" to "Standard RDP"
                                    ).forEach { (modeKey, modeLabel) ->
                                        FilterChip(
                                            selected = securityMode.equals(modeKey, ignoreCase = true),
                                            onClick = { securityMode = modeKey },
                                            label = { Text(modeLabel) }
                                        )
                                    }
                                }
                            }

                            if (protocol == RemoteProtocol.HTTPS) {
                                Text("Web Management Console Preset & Scheme", style = MaterialTheme.typography.labelLarge)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        Triple("Proxmox VE (:8006)", "8006", "HTTPS"),
                                        Triple("Cockpit (:9090)", "9090", "HTTPS"),
                                        Triple("HTTPS (:443)", "443", "HTTPS"),
                                        Triple("HTTP (:80)", "80", "HTTP"),
                                        Triple("Portainer (:9443)", "9443", "HTTPS"),
                                        Triple("HTTP Alt (:8080)", "8080", "HTTP")
                                    ).forEach { (presetLabel, presetPort, presetScheme) ->
                                        val isPresetSelected = portText == presetPort && securityMode.equals(presetScheme, ignoreCase = true)
                                        FilterChip(
                                            selected = isPresetSelected,
                                            onClick = {
                                                portText = presetPort
                                                securityMode = presetScheme
                                            },
                                            label = { Text(presetLabel) }
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = preExecCommand,
                                    onValueChange = { preExecCommand = it },
                                    label = { Text("Initial Console Path (Optional, e.g. / or /ui)") },
                                    placeholder = { Text("/") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("profile_web_path_input")
                                )
                            }
                        }

                        1 -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.AspectRatio, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Resolution & Fullscreen Management", style = MaterialTheme.typography.titleMedium)
                            }

                            SettingToggleRow(
                                title = "Launch Directly in Fullscreen Mode",
                                subtitle = "Hide Android status/navigation bars and RemMobile window chrome on connect",
                                checked = startFullScreen,
                                onCheckedChange = { startFullScreen = it }
                            )

                            SettingToggleRow(
                                title = "Allow Screenshots & Screen Recording on This Display",
                                subtitle = "Temporarily lift Android FLAG_SECURE while viewing this display session so you can capture screenshots",
                                checked = allowScreenshots,
                                onCheckedChange = { allowScreenshots = it }
                            )

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

                            if (resolutionMode == ResolutionMode.FIXED_PRESET) {
                                Text("Preset Resolution", style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StandardResolutionPresets.forEach { preset ->
                                        FilterChip(
                                            selected = resolution == preset.display,
                                            onClick = {
                                                resolution = preset.display
                                                customWidthText = preset.width.toString()
                                                customHeightText = preset.height.toString()
                                            },
                                            label = { Text("${preset.display} (${preset.aspectLabel})") }
                                        )
                                    }
                                }
                            } else if (resolutionMode == ResolutionMode.CUSTOM) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = customWidthText,
                                        onValueChange = {
                                            customWidthText = it.filter { c -> c.isDigit() }
                                            resolution = "${customWidthText}x${customHeightText}"
                                        },
                                        label = { Text("Width (px)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = customHeightText,
                                        onValueChange = {
                                            customHeightText = it.filter { c -> c.isDigit() }
                                            resolution = "${customWidthText}x${customHeightText}"
                                        },
                                        label = { Text("Height (px)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Text("Viewport Scaling Mode", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ScalingMode.entries.forEach { sm ->
                                    FilterChip(
                                        selected = scalingMode == sm,
                                        onClick = { scalingMode = sm },
                                        label = { Text(sm.label) }
                                    )
                                }
                            }

                            Text("Color Depth & Desktop DPI Scale", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(32, 24, 16, 8).forEach { depth ->
                                    FilterChip(
                                        selected = colorDepth == depth,
                                        onClick = { colorDepth = depth },
                                        label = { Text("${depth} bpp") }
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(100, 125, 150, 200).forEach { dpi ->
                                    FilterChip(
                                        selected = dpiScalePercent == dpi,
                                        onClick = { dpiScalePercent = dpi },
                                        label = { Text("${dpi}% DPI") }
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = codec,
                                onValueChange = { codec = it },
                                label = { Text("Encoding / Codec") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            SettingToggleRow(
                                title = "Dynamic Resolution Update (RDPEGFX / xrandr / vdagent)",
                                subtitle = "Automatically resize remote session when rotating device or toggling Fullscreen",
                                checked = dynamicResolution,
                                onCheckedChange = { dynamicResolution = it }
                            )
                            SettingToggleRow(
                                title = "Clipboard Synchronization",
                                subtitle = "Sync copy/paste buffer between Android and remote server",
                                checked = clipboardSync,
                                onCheckedChange = { clipboardSync = it }
                            )
                            SettingToggleRow(
                                title = "Audio Redirection",
                                subtitle = "Stream audio from remote session",
                                checked = audioRedir,
                                onCheckedChange = { audioRedir = it }
                            )
                        }

                        2 -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                Text("SSH Tunnel", style = MaterialTheme.typography.titleMedium)
                            }
                            SettingToggleRow(
                                title = "Enable SSH Tunnel",
                                subtitle = "Tunnel ${protocol.displayName} traffic via an SSH jump/bastion host",
                                checked = sshTunnelEnabled,
                                onCheckedChange = { sshTunnelEnabled = it }
                            )

                            if (sshTunnelEnabled) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = sshTunnelHost,
                                        onValueChange = { sshTunnelHost = it },
                                        label = { Text("SSH Tunnel Host") },
                                        placeholder = { Text("bastion.example.com") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.7f)
                                    )
                                    OutlinedTextField(
                                        value = sshTunnelPortText,
                                        onValueChange = { sshTunnelPortText = it.filter { c -> c.isDigit() } },
                                        label = { Text("SSH Port") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.3f)
                                    )
                                }
                                OutlinedTextField(
                                    value = sshTunnelUsername,
                                    onValueChange = { sshTunnelUsername = it },
                                    label = { Text("SSH Username") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = sshTunnelPassword,
                                    onValueChange = { sshTunnelPassword = it },
                                    label = { Text("SSH Password") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                SettingToggleRow(
                                    title = "Tunnel via Loopback (127.0.0.1)",
                                    subtitle = "Bind local forwarded socket on 127.0.0.1",
                                    checked = sshTunnelLoopback,
                                    onCheckedChange = { sshTunnelLoopback = it }
                                )
                            }
                        }

                        3 -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("RD Gateway (HTTPS / RPC)", style = MaterialTheme.typography.titleMedium)
                            }
                            SettingToggleRow(
                                title = "Enable RD Gateway",
                                subtitle = "Connect through an enterprise Remote Desktop Gateway",
                                checked = rdGatewayEnabled,
                                onCheckedChange = { rdGatewayEnabled = it }
                            )
                            if (rdGatewayEnabled) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = rdGatewayServer,
                                        onValueChange = { rdGatewayServer = it },
                                        label = { Text("RD Gateway Server") },
                                        placeholder = { Text("rdgw.example.com") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.7f)
                                    )
                                    OutlinedTextField(
                                        value = rdGatewayPortText,
                                        onValueChange = { rdGatewayPortText = it.filter { c -> c.isDigit() } },
                                        label = { Text("Port") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.3f)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = rdGatewayUsername,
                                        onValueChange = { rdGatewayUsername = it },
                                        label = { Text("Gateway Username") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.55f)
                                    )
                                    OutlinedTextField(
                                        value = rdGatewayDomain,
                                        onValueChange = { rdGatewayDomain = it },
                                        label = { Text("Gateway Domain") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.45f)
                                    )
                                }
                            }
                        }

                        4 -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Hardware Keyboard & Pointer", style = MaterialTheme.typography.titleMedium)
                            }
                            Text("Default Pointer Mode", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = defaultInputMode == "DIRECT_TOUCH",
                                    onClick = { defaultInputMode = "DIRECT_TOUCH" },
                                    label = { Text("Direct Touch") }
                                )
                                FilterChip(
                                    selected = defaultInputMode == "TRACKPAD",
                                    onClick = { defaultInputMode = "TRACKPAD" },
                                    label = { Text("Virtual Trackpad") }
                                )
                            }

                            Text("Hardware Keyboard Scancode Layout", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("en-US (PC105)", "de-DE (QWERTZ)", "fr-FR (AZERTY)", "uk-GB (ISO)", "jp-JP (JIS106)").forEach { layout ->
                                    FilterChip(
                                        selected = keyboardLayoutCode == layout,
                                        onClick = { keyboardLayoutCode = layout },
                                        label = { Text(layout) }
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = preExecCommand,
                                onValueChange = { preExecCommand = it },
                                label = { Text("Pre-connecting command") },
                                placeholder = { Text("Optional shell startup command") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                    Button(
                        onClick = {
                            val finalServer = server.trim().ifBlank { "127.0.0.1" }
                            val finalName = name.trim().ifBlank { "${protocol.displayName} • $finalServer" }
                            val fallbackPort = protocolDefaultPorts[protocol.name] ?: protocol.defaultPort
                            val finalRes = if (resolutionMode == ResolutionMode.CUSTOM) {
                                "${customWidthText.ifBlank { "1280" }}x${customHeightText.ifBlank { "720" }}"
                            } else {
                                resolution
                            }
                            val updated = base.copy(
                                name = finalName,
                                groupName = groupName.trim().ifBlank { "Default" },
                                protocol = protocol.name,
                                server = finalServer,
                                port = portText.toIntOrNull() ?: fallbackPort,
                                username = username.trim(),
                                password = password,
                                domain = domain.trim(),
                                resolutionMode = resolutionMode.name,
                                resolution = finalRes,
                                scalingMode = scalingMode.name,
                                startFullScreen = startFullScreen,
                                colorDepth = colorDepth,
                                dpiScalePercent = dpiScalePercent,
                                codec = codec.trim().ifBlank { protocol.defaultCodec },
                                securityMode = securityMode.trim().ifBlank { "Negotiate" },
                                ignoreCertWarnings = ignoreCertWarnings,
                                requireBiometricForConnection = requireBiometricForConnection,
                                pinnedCertSha256 = pinnedCertSha256,
                                dynamicResolutionUpdate = dynamicResolution,
                                enableAudioRedirection = audioRedir,
                                enableClipboardSync = clipboardSync,
                                enableUsbRedirection = usbRedir,
                                allowScreenshots = allowScreenshots,
                                sshTunnelEnabled = sshTunnelEnabled,
                                sshTunnelHost = sshTunnelHost.trim(),
                                sshTunnelPort = sshTunnelPortText.toIntOrNull() ?: defaultSshTunnelPort,
                                sshTunnelUsername = sshTunnelUsername.trim(),
                                sshTunnelPassword = sshTunnelPassword,
                                sshTunnelAuthMethod = sshTunnelAuthMethod,
                                sshTunnelLoopback = sshTunnelLoopback,
                                rdGatewayEnabled = rdGatewayEnabled,
                                rdGatewayServer = rdGatewayServer.trim(),
                                rdGatewayPort = rdGatewayPortText.toIntOrNull() ?: defaultRdGwPort,
                                rdGatewayUsername = rdGatewayUsername.trim(),
                                rdGatewayDomain = rdGatewayDomain.trim(),
                                defaultInputMode = defaultInputMode,
                                keyboardLayoutCode = keyboardLayoutCode,
                                preExecCommand = preExecCommand.trim()
                            )
                            onSave(updated)
                        },
                        modifier = Modifier.testTag("save_profile_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
