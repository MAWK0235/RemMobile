package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connection_profiles")
data class ConnectionProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val groupName: String = "Workstations",
    val protocol: String = "RDP", // RDP, SSH, VNC, SPICE
    val server: String,
    val port: Int = 3389,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val passwordHint: String = "",
    // Display, Resolution & Scaling
    val resolutionMode: String = "DYNAMIC_CLIENT", // DYNAMIC_CLIENT, FIXED_PRESET, CUSTOM
    val resolution: String = "1280x720",
    val scalingMode: String = "FIT_WINDOW", // FIT_WINDOW, ONE_TO_ONE, STRETCH, CUSTOM_ZOOM
    val startFullScreen: Boolean = false,
    val colorDepth: Int = 16, // 8, 16, 24, 32
    val dpiScalePercent: Int = 100, // 100, 125, 150, 200
    val qualityPreset: String = "Best (Ultra Low-Latency)",
    val codec: String = "RemoteFX / RLE Bitmap",
    val dynamicResolutionUpdate: Boolean = true,
    val multiMonitorMode: Boolean = false,
    // Channels & Redirection
    val enableAudioRedirection: Boolean = true,
    val enableClipboardSync: Boolean = true,
    val enableFolderSharing: Boolean = false,
    val sharedFolderPath: String = "/storage/emulated/0/Download",
    val enableUsbRedirection: Boolean = false,
    // Security & Biometric / TLS Pinning
    val securityMode: String = "Negotiate", // Negotiate, NLA, TLS, RDP
    val ignoreCertWarnings: Boolean = true,
    val requireBiometricForConnection: Boolean = true,
    val pinnedCertSha256: String = "",
    // Advanced SSH Tunnel / Bastion Gateway
    val sshTunnelEnabled: Boolean = false,
    val sshTunnelHost: String = "",
    val sshTunnelPort: Int = 22,
    val sshTunnelUsername: String = "",
    val sshTunnelPassword: String = "",
    val sshTunnelAuthMethod: String = "PASSWORD",
    val sshTunnelKeyId: Long? = null,
    val sshTunnelLoopback: Boolean = false,
    // RD Gateway (for RDP over HTTPS)
    val rdGatewayEnabled: Boolean = false,
    val rdGatewayServer: String = "",
    val rdGatewayPort: Int = 443,
    val rdGatewayUsername: String = "",
    val rdGatewayDomain: String = "",
    // Hardware Keyboard & Touch Defaults for this profile
    val defaultInputMode: String = "DIRECT_TOUCH",
    val keyboardLayoutCode: String = "en-US (PC105)",
    val forwardSystemShortcuts: Boolean = true,
    val sshColorScheme: String = "RemMobile Dark",
    val preExecCommand: String = "",
    // Telemetry & Status
    val isFavorite: Boolean = false,
    val lastConnectedEpochMs: Long = 0L,
    val lastPingLatencyMs: Int = -1
)

@Entity(tableName = "protocol_port_defaults")
data class ProtocolPortDefaultEntity(
    @PrimaryKey val protocolName: String, // "RDP", "SSH", "VNC", "SPICE", "SSH_TUNNEL", "RD_GATEWAY"
    val defaultPort: Int
)

@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    val algorithm: String = "Ed25519",
    val publicKeyOpenSsh: String,
    val encryptedPrivateKeyPem: String = "",
    val fingerprintSha256: String,
    val passphraseProtected: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "key_mappings")
data class KeyMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetProtocol: String = "ALL",
    val sourceKeyLabel: String,
    val sourceAndroidKeyCode: Int,
    val requireCtrl: Boolean = false,
    val requireAlt: Boolean = false,
    val requireShift: Boolean = false,
    val requireMeta: Boolean = false,
    val mappedActionType: String = "KEY_REMAP",
    val targetKeyLabel: String,
    val targetAndroidKeyCode: Int = 0,
    val targetModifiersMask: Int = 0,
    val customSequence: String = "",
    val isEnabled: Boolean = true
)
