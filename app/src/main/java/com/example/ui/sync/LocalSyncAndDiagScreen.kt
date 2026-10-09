package com.example.ui.sync

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.ConnectionProfileEntity
import com.example.protocol.NetworkProbeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LocalSyncAndDiagScreen(
    profilesCount: Int,
    keyMappingsCount: Int,
    protocolDefaultPorts: Map<String, Int>,
    onUpdateDefaultPort: (String, Int) -> Unit,
    onResetDefaultPorts: () -> Unit,
    onExportPortableBundle: suspend () -> String,
    onImportPortableBundle: (String) -> Unit,
    onImportRemminaIni: (String) -> Unit,
    onImportRemminaFilesFromUris: (List<Uri>) -> Unit,
    onStatusMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Default Port Input States
    var rdpPortText by remember { mutableStateOf((protocolDefaultPorts["RDP"] ?: 3389).toString()) }
    var sshPortText by remember { mutableStateOf((protocolDefaultPorts["SSH"] ?: 22).toString()) }
    var vncPortText by remember { mutableStateOf((protocolDefaultPorts["VNC"] ?: 5900).toString()) }
    var spicePortText by remember { mutableStateOf((protocolDefaultPorts["SPICE"] ?: 5900).toString()) }
    var sshTunnelPortText by remember { mutableStateOf((protocolDefaultPorts["SSH_TUNNEL"] ?: 22).toString()) }
    var rdGwPortText by remember { mutableStateOf((protocolDefaultPorts["RD_GATEWAY"] ?: 443).toString()) }

    LaunchedEffect(protocolDefaultPorts) {
        rdpPortText = (protocolDefaultPorts["RDP"] ?: 3389).toString()
        sshPortText = (protocolDefaultPorts["SSH"] ?: 22).toString()
        vncPortText = (protocolDefaultPorts["VNC"] ?: 5900).toString()
        spicePortText = (protocolDefaultPorts["SPICE"] ?: 5900).toString()
        sshTunnelPortText = (protocolDefaultPorts["SSH_TUNNEL"] ?: 22).toString()
        rdGwPortText = (protocolDefaultPorts["RD_GATEWAY"] ?: 443).toString()
    }

    var payloadText by remember {
        mutableStateOf("")
    }

    val openFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportRemminaFilesFromUris(uris)
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write(payloadText)
                    }
                    withContext(Dispatchers.Main) {
                        onStatusMessage("Saved configuration file to device storage")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        onStatusMessage("Failed to save file: ${e.localizedMessage}")
                    }
                }
            }
        }
    }

    var diagHost by remember { mutableStateOf("") }
    var diagPort by remember { mutableStateOf("3389") }
    var diagResultLines by remember { mutableStateOf<List<String>>(emptyList()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Remmina Protocol Default Ports Preferences Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SettingsEthernet, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text(
                                    text = "Protocol Default Port Values",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Customize default ports used for new profiles, Quick Connect, and .remmina imports",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onResetDefaultPorts,
                            modifier = Modifier.testTag("reset_default_ports_button")
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset")
                        }
                    }

                    HorizontalDivider()

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DefaultPortField(
                            label = "RDP Port",
                            value = rdpPortText,
                            onValueChange = { rdpPortText = it },
                            onSave = { rdpPortText.toIntOrNull()?.let { p -> onUpdateDefaultPort("RDP", p) } },
                            testTag = "default_port_rdp_input",
                            modifier = Modifier.weight(1f)
                        )
                        DefaultPortField(
                            label = "SSH Port",
                            value = sshPortText,
                            onValueChange = { sshPortText = it },
                            onSave = { sshPortText.toIntOrNull()?.let { p -> onUpdateDefaultPort("SSH", p) } },
                            testTag = "default_port_ssh_input",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DefaultPortField(
                            label = "VNC Port",
                            value = vncPortText,
                            onValueChange = { vncPortText = it },
                            onSave = { vncPortText.toIntOrNull()?.let { p -> onUpdateDefaultPort("VNC", p) } },
                            testTag = "default_port_vnc_input",
                            modifier = Modifier.weight(1f)
                        )
                        DefaultPortField(
                            label = "SPICE Port",
                            value = spicePortText,
                            onValueChange = { spicePortText = it },
                            onSave = { spicePortText.toIntOrNull()?.let { p -> onUpdateDefaultPort("SPICE", p) } },
                            testTag = "default_port_spice_input",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DefaultPortField(
                            label = "SSH Tunnel Port",
                            value = sshTunnelPortText,
                            onValueChange = { sshTunnelPortText = it },
                            onSave = { sshTunnelPortText.toIntOrNull()?.let { p -> onUpdateDefaultPort("SSH_TUNNEL", p) } },
                            testTag = "default_port_ssh_tunnel_input",
                            modifier = Modifier.weight(1f)
                        )
                        DefaultPortField(
                            label = "RD Gateway Port",
                            value = rdGwPortText,
                            onValueChange = { rdGwPortText = it },
                            onSave = { rdGwPortText.toIntOrNull()?.let { p -> onUpdateDefaultPort("RD_GATEWAY", p) } },
                            testTag = "default_port_rdgw_input",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Button(
                        onClick = {
                            rdpPortText.toIntOrNull()?.let { onUpdateDefaultPort("RDP", it) }
                            sshPortText.toIntOrNull()?.let { onUpdateDefaultPort("SSH", it) }
                            vncPortText.toIntOrNull()?.let { onUpdateDefaultPort("VNC", it) }
                            spicePortText.toIntOrNull()?.let { onUpdateDefaultPort("SPICE", it) }
                            sshTunnelPortText.toIntOrNull()?.let { onUpdateDefaultPort("SSH_TUNNEL", it) }
                            rdGwPortText.toIntOrNull()?.let { onUpdateDefaultPort("RD_GATEWAY", it) }
                            onStatusMessage("Saved all custom protocol default port values")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("save_all_default_ports_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apply Default Port Values")
                    }
                }
            }
        }

        // 2. Direct .remmina Config File Import & Export Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.SyncAlt, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Column {
                            Text(
                                text = "Import & Export RemMobile / Remmina Config Files (.remmina)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Import .remmina files directly from your device filesystem or export all $profilesCount profiles and $keyMappingsCount keymaps locally.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // File System Picker Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                openFilesLauncher.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pick_remmina_files_button")
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import .remmina File(s)")
                        }

                        OutlinedButton(
                            onClick = {
                                saveFileLauncher.launch("remmobile-profile.remmina")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_remmina_file_button")
                        ) {
                            Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save to File")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val bundle = onExportPortableBundle()
                                    payloadText = bundle
                                    clipboardManager.setText(AnnotatedString(bundle))
                                    onStatusMessage("Exported all profiles & port defaults to JSON")
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_bundle_button")
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export All (JSON)")
                        }

                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(payloadText))
                                onStatusMessage("Copied config buffer to clipboard")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Buffer")
                        }
                    }

                    OutlinedTextField(
                        value = payloadText,
                        onValueChange = { payloadText = it },
                        label = { Text("Direct .remmina INI Config or JSON Bundle Content") },
                        minLines = 6,
                        maxLines = 10,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_payload_input")
                    )

                    Button(
                        onClick = {
                            val trimmed = payloadText.trim()
                            if (trimmed.startsWith("{")) {
                                onImportPortableBundle(trimmed)
                            } else {
                                onImportRemminaIni(trimmed)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_payload_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import Pasted .remmina Config")
                    }
                }
            }
        }

        // 3. Direct TCP Socket & Protocol Wire Handshake Probe
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(
                                text = "Direct TCP Socket & Protocol Wire Probe",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Test real TCP reachability and X.224 / RFB / SSH-2.0 / SPICE REDQ handshakes",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = diagHost,
                            onValueChange = { diagHost = it },
                            label = { Text("Target Host / IP") },
                            singleLine = true,
                            modifier = Modifier.weight(0.68f)
                        )
                        OutlinedTextField(
                            value = diagPort,
                            onValueChange = { diagPort = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.32f)
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                val pNum = diagPort.toIntOrNull() ?: 3389
                                val proto = when (pNum) {
                                    protocolDefaultPorts["SSH"] ?: 22 -> "SSH"
                                    protocolDefaultPorts["VNC"] ?: 5900, 5901 -> "VNC"
                                    else -> "RDP"
                                }
                                val probe = NetworkProbeEngine.probeProfileEndpoint(
                                    ConnectionProfileEntity(
                                        name = "Socket Probe",
                                        protocol = proto,
                                        server = diagHost.trim(),
                                        port = pNum
                                    )
                                )
                                diagResultLines = probe.wireTrace + listOf(
                                    if (probe.reachableSocket) {
                                        "Result: CONNECTED (${probe.latencyMs} ms) — ${probe.discoveredBanner}"
                                    } else {
                                        "Result: ${probe.errorTitle} (${probe.errorDetails})"
                                    }
                                )
                            }
                        },
                        modifier = Modifier.testTag("run_socket_probe_button")
                    ) {
                        Text("Run Socket Probe")
                    }

                    if (diagResultLines.isNotEmpty()) {
                        HorizontalDivider()
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                diagResultLines.forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (line.contains("error", ignoreCase = true) || line.contains("Unable", ignoreCase = true)) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
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
private fun DefaultPortField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(5)
            onValueChange(filtered)
            filtered.toIntOrNull()?.let { if (it in 1..65535) onSave() }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier.testTag(testTag)
    )
}
