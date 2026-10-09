package com.example.ui.connections

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ConnectionProfileEntity
import com.example.data.model.RemoteProtocol

@Composable
fun ConnectionsScreen(
    profiles: List<ConnectionProfileEntity>,
    protocolDefaultPorts: Map<String, Int>,
    quickConnectProtocol: RemoteProtocol,
    searchQuery: String,
    selectedProtocol: RemoteProtocol?,
    quickConnectText: String,
    selectedProfile: ConnectionProfileEntity?,
    editingProfile: ConnectionProfileEntity?,
    onQuickConnectProtocolChange: (RemoteProtocol) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onProtocolFilterChange: (RemoteProtocol?) -> Unit,
    onQuickConnectTextChange: (String) -> Unit,
    onQuickConnectLaunch: () -> Unit,
    onSelectProfile: (ConnectionProfileEntity) -> Unit,
    onConnectProfile: (ConnectionProfileEntity) -> Unit,
    onRequestEditProfile: (ConnectionProfileEntity) -> Unit,
    onDismissEditProfile: () -> Unit,
    onSaveProfile: (ConnectionProfileEntity) -> Unit,
    onDeleteProfile: (ConnectionProfileEntity) -> Unit,
    onToggleFavorite: (ConnectionProfileEntity) -> Unit,
    onExportRemminaIni: (ConnectionProfileEntity) -> String,
    onImportRemminaFilesFromUris: (List<Uri>) -> Unit,
    onStatusMessage: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportRemminaFilesFromUris(uris)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedTablet = maxWidth >= 700.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Authentic Desktop Remmina GTK Toolbar (New Profile + Import .remmina + Protocol Selector + Quick Connect Bar)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Top Row: [+] New, [Import .remmina], Quick Connect Protocol Selector + Address Field
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { showCreateDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("new_profile_fab")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "New connection profile", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New", style = MaterialTheme.typography.labelLarge)
                        }

                        OutlinedButton(
                            onClick = {
                                importFileLauncher.launch(arrayOf("*/*"))
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("import_remmina_file_button")
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = "Import .remmina file", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Import .remmina", style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedTextField(
                            value = quickConnectText,
                            onValueChange = onQuickConnectTextChange,
                            placeholder = {
                                val defPort = protocolDefaultPorts[quickConnectProtocol.name] ?: quickConnectProtocol.defaultPort
                                Text(
                                    text = "${quickConnectProtocol.displayName} server (default :$defPort)...",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_connect_input")
                        )

                        Button(
                            onClick = onQuickConnectLaunch,
                            enabled = quickConnectText.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("quick_connect_button")
                        ) {
                            Text("Connect")
                        }
                    }

                    // Second Row: Protocol Selector for Quick Connect & Filter + Search Filter
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedProtocol == null,
                            onClick = { onProtocolFilterChange(null) },
                            label = { Text("All (${profiles.size})") },
                            modifier = Modifier.testTag("filter_all")
                        )
                        RemoteProtocol.entries.forEach { proto ->
                            val portNum = protocolDefaultPorts[proto.name] ?: proto.defaultPort
                            FilterChip(
                                selected = selectedProtocol == proto,
                                onClick = {
                                    onQuickConnectProtocolChange(proto)
                                    onProtocolFilterChange(if (selectedProtocol == proto) null else proto)
                                },
                                label = { Text("${proto.displayName} :$portNum") },
                                modifier = Modifier.testTag("filter_${proto.name.lowercase()}")
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text("Filter saved profiles...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            singleLine = true,
                            modifier = Modifier
                                .width(220.dp)
                                .testTag("search_profiles_input")
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // 2. Main Remmina GTK Tree/Table List + Optional Tablet Inspector Split View
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(if (isExpandedTablet) 0.58f else 1f)
                        .fillMaxHeight()
                ) {
                    // GTK Column Headers Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Proto",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(56.dp)
                        )
                        Text(
                            text = "Name / Group",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.38f)
                        )
                        Text(
                            text = "Server : Port",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.34f)
                        )
                        Text(
                            text = "Actions",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(124.dp)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    if (profiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Computer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text("No Saved Connection Profiles", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Click '+ New' or 'Import .remmina' in the top bar to add a connection.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(profiles, key = { it.id }) { profile ->
                                RemminaGtkProfileRow(
                                    profile = profile,
                                    isSelected = selectedProfile?.id == profile.id,
                                    onRowClick = {
                                        onSelectProfile(profile)
                                        if (!isExpandedTablet) {
                                            onConnectProfile(profile)
                                        }
                                    },
                                    onConnectClick = { onConnectProfile(profile) },
                                    onEditClick = { onRequestEditProfile(profile) },
                                    onFavoriteClick = { onToggleFavorite(profile) },
                                    onExportIniClick = {
                                        val ini = onExportRemminaIni(profile)
                                        clipboardManager.setText(AnnotatedString(ini))
                                        onStatusMessage("Copied '${profile.name}.remmina' config to clipboard")
                                    }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                            }
                        }
                    }
                }

                // Right Column on Tablet / Expanded Displays
                if (isExpandedTablet) {
                    val detailTarget = selectedProfile ?: profiles.firstOrNull()
                    Surface(
                        modifier = Modifier
                            .weight(0.42f)
                            .fillMaxHeight()
                            .border(1.dp, MaterialTheme.colorScheme.outline),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        if (detailTarget != null) {
                            TabletProfileDetailPane(
                                profile = detailTarget,
                                onConnect = { onConnectProfile(detailTarget) },
                                onEdit = { onRequestEditProfile(detailTarget) },
                                onDelete = { onDeleteProfile(detailTarget) },
                                onExportIni = {
                                    val ini = onExportRemminaIni(detailTarget)
                                    clipboardManager.setText(AnnotatedString(ini))
                                    onStatusMessage("Copied '${detailTarget.name}.remmina' to clipboard")
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        ProfileEditorDialog(
            initialProfile = null,
            protocolDefaultPorts = protocolDefaultPorts,
            onDismiss = { showCreateDialog = false },
            onSave = { created ->
                onSaveProfile(created)
                showCreateDialog = false
            }
        )
    }

    editingProfile?.let { target ->
        ProfileEditorDialog(
            initialProfile = target,
            protocolDefaultPorts = protocolDefaultPorts,
            onDismiss = onDismissEditProfile,
            onSave = { updated ->
                onSaveProfile(updated)
                onDismissEditProfile()
            }
        )
    }
}

@Composable
private fun RemminaGtkProfileRow(
    profile: ConnectionProfileEntity,
    isSelected: Boolean,
    onRowClick: () -> Unit,
    onConnectClick: () -> Unit,
    onEditClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onExportIniClick: () -> Unit
) {
    val proto = RemoteProtocol.fromString(profile.protocol)
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.background
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onRowClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("profile_card_${profile.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Protocol Pill
        Box(modifier = Modifier.width(56.dp)) {
            Surface(
                color = proto.badgeColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.border(1.dp, proto.badgeColor, RoundedCornerShape(4.dp))
            ) {
                Text(
                    text = proto.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = proto.badgeColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        // Name & Group
        Column(modifier = Modifier.weight(0.38f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (profile.password.isNotEmpty()) {
                    Icon(
                        Icons.Default.EnhancedEncryption,
                        contentDescription = "AES-256-GCM Encrypted Password",
                        tint = Color(0xFF57E389),
                        modifier = Modifier.size(13.dp)
                    )
                }
                if (profile.requireBiometricForConnection) {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = "Biometric / PIN Protected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                }
                if (profile.sshTunnelEnabled) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "SSH Tunnel Enabled",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            Text(
                text = "${profile.groupName} • ${profile.resolution}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Server:Port & User
        Column(modifier = Modifier.weight(0.34f).padding(end = 8.dp)) {
            Text(
                text = "${profile.server}:${profile.port}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (profile.sshTunnelEnabled) "via ${profile.sshTunnelHost}:${profile.sshTunnelPort}" else profile.username.ifBlank { "default user" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Action Buttons (Connect, Edit, Copy .remmina, Star)
        Row(
            modifier = Modifier.width(124.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = onConnectClick,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("connect_profile_${profile.id}")
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Connect",
                    tint = proto.badgeColor
                )
            }
            IconButton(
                onClick = onEditClick,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("edit_profile_${profile.id}")
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Edit profile", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = onExportIniClick,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("export_ini_${profile.id}")
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy .remmina config", modifier = Modifier.size(17.dp))
            }
            IconButton(
                onClick = onFavoriteClick,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = if (profile.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Favorite",
                    tint = if (profile.isFavorite) Color(0xFFF5C211) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun TabletProfileDetailPane(
    profile: ConnectionProfileEntity,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExportIni: () -> Unit
) {
    val proto = RemoteProtocol.fromString(profile.protocol)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${proto.displayName} PLUGIN (${proto.wireProtocolName})",
                    style = MaterialTheme.typography.labelSmall,
                    color = proto.badgeColor
                )
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = proto.badgeColor)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Connect")
            }
        }

        HorizontalDivider()

        DetailSpecRow("Server Endpoint", "${profile.server}:${profile.port}")
        DetailSpecRow("Username / Domain", "${profile.username.ifBlank { "none" }} / ${profile.domain.ifBlank { "none" }}")
        DetailSpecRow(
            "Credential Security",
            buildString {
                append(if (profile.password.isNotEmpty()) "AES-256-GCM Encrypted" else "Prompt on Connect")
                if (profile.requireBiometricForConnection) append(" • Biometric/PIN Gate")
            }
        )
        if (profile.pinnedCertSha256.isNotBlank()) {
            DetailSpecRow("Pinned TLS Cert", profile.pinnedCertSha256)
        }
        DetailSpecRow("Resolution & Mode", "${profile.resolution} (${profile.resolutionMode})")
        DetailSpecRow("Scaling & Color", "${profile.scalingMode} • ${profile.colorDepth} bpp • ${profile.dpiScalePercent}% DPI")
        DetailSpecRow("Start Fullscreen", if (profile.startFullScreen) "Yes (Host+F to toggle)" else "Windowed")
        DetailSpecRow("SSH Bastion Tunnel", if (profile.sshTunnelEnabled) "${profile.sshTunnelUsername}@${profile.sshTunnelHost}:${profile.sshTunnelPort}" else "Disabled")
        DetailSpecRow("RD Gateway", if (profile.rdGatewayEnabled) "${profile.rdGatewayServer}:${profile.rdGatewayPort}" else "Disabled")

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Edit")
            }
            OutlinedButton(onClick = onExportIni, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(".remmina")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete profile", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DetailSpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f)
        )
    }
}
