package com.example.data.model

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.ProtocolRdpColor
import com.example.ui.theme.ProtocolSpiceColor
import com.example.ui.theme.ProtocolSshColor
import com.example.ui.theme.ProtocolVncColor
import com.example.ui.theme.ProtocolWebColor

enum class RemoteProtocol(
    val displayName: String,
    val defaultPort: Int,
    val badgeColor: Color,
    val wireProtocolName: String,
    val defaultCodec: String,
    val description: String
) {
    RDP(
        displayName = "RDP",
        defaultPort = 3389,
        badgeColor = ProtocolRdpColor,
        wireProtocolName = "FreeRDP / MS-RDPEGFX",
        defaultCodec = "RemoteFX / H.264 AVC444",
        description = "Windows & Linux XRDP Remote Desktop with dynamic channel resolution & NLA"
    ),
    SSH(
        displayName = "SSH",
        defaultPort = 22,
        badgeColor = ProtocolSshColor,
        wireProtocolName = "SSH-2.0 / OpenSSH VT100",
        defaultCodec = "ChaCha20-Poly1305 / Ed25519",
        description = "Encrypted terminal emulator, SFTP browser & dynamic port forwarding"
    ),
    VNC(
        displayName = "VNC",
        defaultPort = 5900,
        badgeColor = ProtocolVncColor,
        wireProtocolName = "RFB 003.008 (TightVNC)",
        defaultCodec = "Tight + ZRLE + CopyRect",
        description = "Cross-platform frame buffer sharing with low-latency delta tile compression"
    ),
    SPICE(
        displayName = "SPICE",
        defaultPort = 5900,
        badgeColor = ProtocolSpiceColor,
        wireProtocolName = "SPICE KVM / QXL VirtIO",
        defaultCodec = "LZ4 / GLZ + VirtIO-GPU",
        description = "High-performance KVM/QEMU virtual machine display, USB & audio channels"
    ),
    HTTPS(
        displayName = "HTTP/S",
        defaultPort = 443,
        badgeColor = ProtocolWebColor,
        wireProtocolName = "HTTP/1.1 & HTTP/2 TLS Web Console",
        defaultCodec = "HTML5 / WebSockets / noVNC",
        description = "Proxmox VE, Cockpit, iDRAC, TrueNAS, Portainer & Router Web Management Consoles"
    );

    companion object {
        fun fromString(value: String): RemoteProtocol {
            val clean = value.trim().uppercase()
            if (
                clean == "HTTP" ||
                clean == "HTTPS" ||
                clean == "WWW" ||
                clean == "WEB" ||
                clean == "HTTP/S" ||
                clean == "PROXMOX" ||
                clean == "COCKPIT" ||
                clean == "IDRAC" ||
                clean == "TRUENAS" ||
                clean == "PORTAINER"
            ) {
                return HTTPS
            }
            return entries.firstOrNull { it.name.equals(clean, ignoreCase = true) } ?: RDP
        }
    }
}

enum class RdpSecurityMode(val label: String) {
    NLA("NLA (Network Level Auth - Recommended)"),
    TLS("TLS 1.3 Encryption"),
    RDP("Standard RDP Security"),
    NEGO("Auto-Negotiate (NLA / TLS / Ext)")
}

enum class InputControlMode(val label: String, val summary: String) {
    DIRECT_TOUCH("Direct Touch", "Tap directly on remote UI elements; pinch to zoom"),
    TRACKPAD("Virtual Trackpad", "Relative mouse pointer movement with inertia & edge scroll")
}

enum class SshAuthMethod(val label: String) {
    PASSWORD("Password"),
    PUBLIC_KEY("SSH Key Vault (Ed25519 / RSA)"),
    SSH_AGENT("SSH Agent Forwarding"),
    KEYBOARD_INTERACTIVE("Keyboard-Interactive (2FA/PAM)")
}
