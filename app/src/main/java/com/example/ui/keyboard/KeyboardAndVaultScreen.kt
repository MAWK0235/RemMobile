package com.example.ui.keyboard

import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.data.local.KeyMappingEntity
import com.example.data.local.SshKeyEntity
import com.example.data.model.RemoteProtocol
import com.example.keyboard.HardwareKeyboardEngine
import com.example.keyboard.TranslatedKeyPacket
import com.example.security.BiometricCapabilityStatus
import com.example.security.PinVerificationResult
import com.example.security.SecurityAuditCheckItem
import com.example.security.SecuritySelfTestResult
import com.example.security.SecurityVaultPolicy
import com.example.ui.security.SecurityAuditAndVaultSection
import com.example.ui.security.SetupOrChangePinDialog

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardAndVaultScreen(
    keyMappings: List<KeyMappingEntity>,
    sshKeys: List<SshKeyEntity>,
    lastTestedKeyPacket: TranslatedKeyPacket?,
    securityPolicy: SecurityVaultPolicy,
    biometricStatus: BiometricCapabilityStatus,
    securityAuditChecklist: List<SecurityAuditCheckItem>,
    lastSecuritySelfTest: SecuritySelfTestResult?,
    onTestHardwareKey: (Int, Int, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onSaveKeyMapping: (KeyMappingEntity) -> Unit,
    onToggleKeyMapping: (KeyMappingEntity) -> Unit,
    onDeleteKeyMapping: (KeyMappingEntity) -> Unit,
    onGenerateSshKey: (String, String, Boolean) -> Unit,
    onDeleteSshKey: (SshKeyEntity) -> Unit,
    onVerifyVaultPin: (String) -> PinVerificationResult,
    onSaveVaultPin: (String) -> Boolean,
    onRemoveVaultPin: () -> Unit,
    onLockAppNow: () -> Unit,
    onRunSecuritySelfTest: () -> Unit,
    onUpdateSecurityPolicy: (Boolean?, Boolean?, Boolean?, Int?, Boolean?, Boolean?) -> Unit,
    onStatusMessage: (String) -> Unit
) {
    var selectedSection by remember { mutableIntStateOf(0) }
    var showAddMappingDialog by remember { mutableStateOf(false) }
    var showGenerateKeyDialog by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    val native = event.nativeKeyEvent
                    onTestHardwareKey(
                        native.keyCode,
                        native.unicodeChar,
                        event.isCtrlPressed,
                        event.isAltPressed,
                        event.isShiftPressed,
                        event.isMetaPressed
                    )
                    true
                } else {
                    false
                }
            }
    ) {
        ScrollableTabRow(
            selectedTabIndex = selectedSection,
            edgePadding = 12.dp
        ) {
            Tab(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                text = { Text("Security & Biometrics") },
                icon = { Icon(Icons.Default.Shield, contentDescription = null) },
                modifier = Modifier.testTag("subtab_security_vault")
            )
            Tab(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                text = { Text("SSH Key Vault (${sshKeys.size})") },
                icon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                modifier = Modifier.testTag("subtab_ssh_vault")
            )
            Tab(
                selected = selectedSection == 2,
                onClick = { selectedSection = 2 },
                text = { Text("Hardware Key Mapper (${keyMappings.size})") },
                icon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                modifier = Modifier.testTag("subtab_keyboard_mapper")
            )
        }

        when (selectedSection) {
            0 -> {
                SecurityAuditAndVaultSection(
                    policy = securityPolicy,
                    biometricStatus = biometricStatus,
                    auditChecklist = securityAuditChecklist,
                    lastSelfTestResult = lastSecuritySelfTest,
                    onOpenPinSetupDialog = { showPinSetupDialog = true },
                    onLockAppNow = onLockAppNow,
                    onRunSecuritySelfTest = onRunSecuritySelfTest,
                    onUpdatePolicy = onUpdateSecurityPolicy
                )
            }

            2 -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Live Hardware Key Scancode Tester Card
                    item {
                        HardwareScancodeTesterCard(
                            packet = lastTestedKeyPacket,
                            onSimulateKeyPress = { code, ctrl, alt, shift ->
                                onTestHardwareKey(code, 0, ctrl, alt, shift, false)
                            }
                        )
                    }

                    // 2. Header + Add Mapping Button
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Customizable Key Mapping & Chord Rules",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Remap physical keys, modifier keys, and function keys across RDP, SSH, VNC & SPICE",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { showAddMappingDialog = true },
                                modifier = Modifier.testTag("add_key_mapping_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Rule")
                            }
                        }
                    }

                    items(keyMappings, key = { it.id }) { mapping ->
                        KeyMappingRuleCard(
                            mapping = mapping,
                            onToggle = { onToggleKeyMapping(mapping) },
                            onTest = {
                                onTestHardwareKey(
                                    mapping.sourceAndroidKeyCode,
                                    0,
                                    mapping.requireCtrl,
                                    mapping.requireAlt,
                                    mapping.requireShift,
                                    mapping.requireMeta
                                )
                            },
                            onDelete = { onDeleteKeyMapping(mapping) }
                        )
                    }
                }
            }

            1 -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Local SSH Key & Bastion Identity Vault",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "100% client-side Ed25519 & RSA-4096 keys with AES-256-GCM encrypted private keys",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { showGenerateKeyDialog = true },
                                modifier = Modifier.testTag("generate_ssh_key_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New Key")
                            }
                        }
                    }

                    items(sshKeys, key = { it.id }) { sshKey ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = sshKey.algorithm,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        Text(sshKey.alias, style = MaterialTheme.typography.titleMedium)
                                        Surface(
                                            color = Color(0xFF57E389).copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.EnhancedEncryption,
                                                    contentDescription = null,
                                                    tint = Color(0xFF57E389),
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = "AES-256-GCM",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF57E389)
                                                )
                                            }
                                        }
                                    }

                                    Row {
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(sshKey.publicKeyOpenSsh))
                                                onStatusMessage("Copied public key '${sshKey.alias}' to clipboard")
                                            }
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy public key")
                                        }
                                        IconButton(onClick = { onDeleteSshKey(sshKey) }) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Delete SSH key",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = sshKey.fingerprintSha256,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = sshKey.publicKeyOpenSsh,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPinSetupDialog) {
        SetupOrChangePinDialog(
            isPinAlreadyConfigured = securityPolicy.isPinConfigured,
            onVerifyExistingPin = onVerifyVaultPin,
            onSaveNewPin = { pin ->
                val ok = onSaveVaultPin(pin)
                if (ok) {
                    onStatusMessage("Master Vault PIN updated & secured with PBKDF2-HMAC-SHA256 (210,000 iterations)")
                }
                ok
            },
            onRemovePin = {
                onRemoveVaultPin()
                onStatusMessage("Master Vault PIN removed")
            },
            onDismiss = { showPinSetupDialog = false }
        )
    }

    if (showAddMappingDialog) {
        AddKeyMappingDialog(
            onDismiss = { showAddMappingDialog = false },
            onSave = { created ->
                onSaveKeyMapping(created)
                showAddMappingDialog = false
            }
        )
    }

    if (showGenerateKeyDialog) {
        GenerateSshKeyDialog(
            onDismiss = { showGenerateKeyDialog = false },
            onGenerate = { alias, alg, passphrase ->
                onGenerateSshKey(alias, alg, passphrase)
                showGenerateKeyDialog = false
            }
        )
    }
}

@Composable
private fun HardwareScancodeTesterCard(
    packet: TranslatedKeyPacket?,
    onSimulateKeyPress: (Int, Boolean, Boolean, Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Keyboard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(
                        text = "Live Hardware Keyboard Scancode Matrix",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Press any physical key on your keyboard or tap a test key below to verify protocol translation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Interactive Test Key Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val sampleKeys = listOf(
                    Triple("CAPS_LOCK", KeyEvent.KEYCODE_CAPS_LOCK, Triple(false, false, false)),
                    Triple("CTRL+SHIFT+DEL", KeyEvent.KEYCODE_DEL, Triple(true, false, true)),
                    Triple("ALT_RIGHT", KeyEvent.KEYCODE_ALT_RIGHT, Triple(false, false, false)),
                    Triple("CTRL+ALT+T", KeyEvent.KEYCODE_T, Triple(true, true, false)),
                    Triple("ESC", KeyEvent.KEYCODE_ESCAPE, Triple(false, false, false)),
                    Triple("TAB", KeyEvent.KEYCODE_TAB, Triple(false, false, false)),
                    Triple("SUPER/WIN", KeyEvent.KEYCODE_META_LEFT, Triple(false, false, false)),
                    Triple("F1", KeyEvent.KEYCODE_F1, Triple(false, false, false)),
                    Triple("F5", KeyEvent.KEYCODE_F5, Triple(false, false, false)),
                    Triple("F11", KeyEvent.KEYCODE_F11, Triple(false, false, false)),
                    Triple("F12", KeyEvent.KEYCODE_F12, Triple(false, false, false)),
                    Triple("SYSRQ", KeyEvent.KEYCODE_SYSRQ, Triple(false, false, false))
                )
                sampleKeys.forEach { (label, code, mods) ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                            .clickable { onSimulateKeyPress(code, mods.first, mods.second, mods.third) }
                            .testTag("test_key_${label.lowercase()}")
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            val displayPacket = packet ?: HardwareKeyboardEngine.translateKeyEvent(
                keyCode = KeyEvent.KEYCODE_CAPS_LOCK,
                unicodeChar = 0,
                protocol = RemoteProtocol.RDP,
                stickyCtrl = false,
                stickyAlt = false,
                stickyShift = false,
                stickySuper = false,
                customMappings = emptyList()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Effective Output: ${displayPacket.keyLabel}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                if (displayPacket.appliedMappingName != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Remapped by: ${displayPacket.appliedMappingName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProtocolCodeBox(
                    title = "RDP (FreeRDP XT)",
                    value = displayPacket.rdpScancodeHex + if (displayPacket.rdpExtended) " (EXT)" else "",
                    accent = RemoteProtocol.RDP.badgeColor,
                    modifier = Modifier.weight(1f)
                )
                ProtocolCodeBox(
                    title = "VNC (X11 KeySym)",
                    value = "${displayPacket.vncKeySymName} (${displayPacket.vncKeySymHex})",
                    accent = RemoteProtocol.VNC.badgeColor,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProtocolCodeBox(
                    title = "SSH (VT100 / ANSI)",
                    value = displayPacket.sshAnsiEscape,
                    accent = RemoteProtocol.SSH.badgeColor,
                    modifier = Modifier.weight(1f)
                )
                ProtocolCodeBox(
                    title = "SPICE (QEMU Set 1)",
                    value = displayPacket.spiceScancodeHex,
                    accent = RemoteProtocol.SPICE.badgeColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ProtocolCodeBox(
    title: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = accent)
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun KeyMappingRuleCard(
    mapping: KeyMappingEntity,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTest),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = mapping.targetProtocol,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Text(
                        text = mapping.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = "Source [${mapping.sourceKeyLabel}] ➔ Target [${mapping.targetKeyLabel}] (${mapping.mappedActionType})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = mapping.isEnabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.testTag("toggle_mapping_${mapping.id}")
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete mapping",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddKeyMappingDialog(
    onDismiss: () -> Unit,
    onSave: (KeyMappingEntity) -> Unit
) {
    val catalog = HardwareKeyboardEngine.standardKeysCatalog
    var ruleName by remember { mutableStateOf("") }
    var targetProtocol by remember { mutableStateOf("ALL") }
    var selectedSourceIdx by remember { mutableIntStateOf(2) } // CAPS_LOCK
    var selectedTargetIdx by remember { mutableIntStateOf(0) } // ESC
    var requireCtrl by remember { mutableStateOf(false) }
    var requireAlt by remember { mutableStateOf(false) }
    var requireShift by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("New Hardware Key Mapping", style = MaterialTheme.typography.titleLarge)

                OutlinedTextField(
                    value = ruleName,
                    onValueChange = { ruleName = it },
                    label = { Text("Mapping Description") },
                    placeholder = { Text("e.g. Swap CapsLock to Left Ctrl") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mapping_name_input")
                )

                Text("Target Protocol Scope", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ALL", "RDP", "SSH", "VNC", "SPICE").forEach { proto ->
                        FilterChip(
                            selected = targetProtocol == proto,
                            onClick = { targetProtocol = proto },
                            label = { Text(proto) }
                        )
                    }
                }

                Text("Source Physical Key", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    catalog.take(14).forEachIndexed { idx, meta ->
                        FilterChip(
                            selected = selectedSourceIdx == idx,
                            onClick = { selectedSourceIdx = idx },
                            label = { Text(meta.label) }
                        )
                    }
                }

                Text("Required Modifier Chord", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = requireCtrl,
                        onClick = { requireCtrl = !requireCtrl },
                        label = { Text("+Ctrl") }
                    )
                    FilterChip(
                        selected = requireAlt,
                        onClick = { requireAlt = !requireAlt },
                        label = { Text("+Alt") }
                    )
                    FilterChip(
                        selected = requireShift,
                        onClick = { requireShift = !requireShift },
                        label = { Text("+Shift") }
                    )
                }

                Text("Remap To Target Remote Key", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    catalog.take(14).forEachIndexed { idx, meta ->
                        FilterChip(
                            selected = selectedTargetIdx == idx,
                            onClick = { selectedTargetIdx = idx },
                            label = { Text(meta.label) }
                        )
                    }
                }

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
                            val src = catalog[selectedSourceIdx]
                            val dst = catalog[selectedTargetIdx]
                            val finalName = ruleName.trim().ifBlank { "${src.label} -> ${dst.label}" }
                            onSave(
                                KeyMappingEntity(
                                    name = finalName,
                                    targetProtocol = targetProtocol,
                                    sourceKeyLabel = src.label,
                                    sourceAndroidKeyCode = src.keyCode,
                                    requireCtrl = requireCtrl,
                                    requireAlt = requireAlt,
                                    requireShift = requireShift,
                                    mappedActionType = "KEY_REMAP",
                                    targetKeyLabel = dst.label,
                                    targetAndroidKeyCode = dst.keyCode,
                                    isEnabled = true
                                )
                            )
                        },
                        modifier = Modifier.testTag("confirm_save_mapping_button")
                    ) {
                        Text("Save Mapping")
                    }
                }
            }
        }
    }
}

@Composable
private fun GenerateSshKeyDialog(
    onDismiss: () -> Unit,
    onGenerate: (String, String, Boolean) -> Unit
) {
    var alias by remember { mutableStateOf("") }
    var algorithm by remember { mutableStateOf("Ed25519") }
    var passphraseProtected by remember { mutableStateOf(true) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Generate Local SSH Keypair", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Key Alias / Comment") },
                    placeholder = { Text("prod-bastion-ed25519") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Ed25519", "RSA-4096", "ECDSA-P256").forEach { alg ->
                        FilterChip(
                            selected = algorithm == alg,
                            onClick = { algorithm = alg },
                            label = { Text(alg) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Encrypt private key in Android Keystore")
                    Switch(checked = passphraseProtected, onCheckedChange = { passphraseProtected = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onGenerate(alias, algorithm, passphraseProtected) }) {
                        Text("Generate")
                    }
                }
            }
        }
    }
}
