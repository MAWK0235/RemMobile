# Remmina for Android

A modern, full-featured remote desktop and systems administration client for Android built with **Kotlin** and **Jetpack Compose**, inspired by the Linux [Remmina](https://remmina.org/) remote desktop client.

Remmina for Android supports concurrent tabbed sessions across **RDP (Remote Desktop Protocol)**, **VNC (RFB 3.8)**, **SSH2 Interactive Shell**, **SFTP Dual-Pane File Transfer**, and **HTTP/HTTPS Web Management Consoles**, complete with SSH Jump Host (Bastion) TCP port forwarding, `.remmina` profile import/export, and a hardware-accelerated double-buffered display compositor.

---

## Key Features

### 1. Multi-Protocol Remote Connectivity
- **RDP (`remmina-plugin-rdp`)**
  - Full X.224 / MCS (T.125) / GCC handshake with TLS (`PROTOCOL_SSL`), NLA / CredSSP v2–v6 (`NTLMv2` + SPNEGO + `pubKeyAuth`), and Standard RDP Security (`PROTOCOL_RDP`).
  - Supports **15-bit, 16-bit, 24-bit TrueColor, and 32-bit** remote desktop sessions with both **Interleaved RLE** (`MS-RDPBCGR 3.1.9`) and **RDP 6.0 Planar** (`MS-RDPEGDI 2.2.2.5.1`) bitmap decompression.
  - Real-time **Fast-Path & Slow-Path** input injection: pointer move, left/right/middle click, click-and-drag window movement, vertical/horizontal scroll wheel, hardware scancodes, and Unicode keystrokes.
  - Dynamic display resolution alignment to the Android viewport or explicit desktop presets (`1920×1080`, `1280×720`, `2560×1440`, etc.).
- **VNC / RFB 3.8 (`remmina-plugin-vnc`)**
  - Standard RFB 3.3 / 3.7 / 3.8 negotiation with VNC Challenge-Response DES authentication and None security.
  - Supports **Raw**, **CopyRect**, **RRE**, **Hextile**, and **ZRLE (Zlib Run-Length)** encodings plus dynamic `DesktopSize` pseudo-encoding.
- **SSH2 Interactive Terminal (`remmina-plugin-ssh`)**
  - Built on **JSch (`com.github.mwiede:jsch`)** with curve25519-sha256, diffie-hellman-group14-sha256, RSA-SHA2, and Ed25519 support.
  - Full ANSI/VT100 terminal emulation with 16-color palette, cursor positioning, scrollback history, and quick-action system modifier keys (`ESC`, `TAB`, `CTRL`, `ALT`, arrow keys).
- **SFTP Dual-Pane File Manager (`remmina-plugin-sftp`)**
  - Side-by-side or tabbed Local Android storage and Remote SFTP directory browser.
  - Live file upload, download, directory creation, deletion, and permission inspection over SSH2.
- **WWW / Web Console (`remmina-plugin-www`)**
  - Embedded WebView for server management interfaces (Proxmox, Cockpit, iDRAC,portainer, router admin panels) with custom User-Agent and self-signed TLS tolerance options.

---

### 2. Hardware-Accelerated Framebuffer & Display Pipeline
- **Zero-Allocation Double-Buffered Compositor (`HardwareAcceleratedFrameCompositor`)**
  - Maintains two persistent `ARGB_8888` `Bitmap` + `ImageBitmap` surfaces (`Buffer A` and `Buffer B`), eliminating per-frame heap allocations and GC stutter during 60–120 FPS desktop streaming.
- **Dirty-Rectangle Union & Burst Coalescing**
  - Coalesces multi-tile RDP/VNC bitmap bursts (`TS_UPDATE_BITMAP_DATA`, `TS_SURFCMD_SET_SURFACE_BITS`, `TS_FRAME_MARKER`) into a single bounding dirty rectangle and uploads only the changed scanlines (`Bitmap.setPixels` with stride offset).
- **GPU RenderNode Offscreen Layer**
  - Renders the remote desktop viewport inside a hardware-accelerated `CompositingStrategy.Offscreen` layer with adaptive texture filtering (`FilterQuality.Medium` hardware bilinear mipmapping when downscaling high-DPI desktops; `FilterQuality.High` bicubic sampling when zoomed in).
- **Precision Coordinate Normalizer (`RemoteCoordinateNormalizer`)**
  - Exact 1:1 sub-pixel mapping between Android touch/trackpad gestures and remote desktop coordinates across `Fit Window`, `Fill Stretch`, and `1:1 Original` aspect scaling modes.

---

### 3. SSH Tunnel / Bastion Jump Host (`SshTunnelBridge`)
- Every RDP, VNC, or Web profile can optionally route traffic through an **SSH Jump Host / Bastion** using local TCP port forwarding (`ssh -L 127.0.0.1:<ephemeralPort>:<remoteHost>:<remotePort>`).
- Supports password or private-key authentication and custom loopback bind addresses.

---

### 4. Profile Persistence, Quick Connect & `.remmina` Import/Export
- **Room SQLite Database (`RemminaDatabase`)** for saved connection profiles, groups, search tags, and favorite pinning.
- **Quick Connect Bar**: Type `rdp://user@10.0.0.5:3389`, `vnc://192.168.1.20:5901`, `ssh://root@server:22`, or `sftp://host` to launch an instant session.
- **Native `.remmina` INI Serializer (`RemminaFileSerializer`)**: Import and export desktop-compatible `.remmina` configuration files.
- **Built-in Diagnostics**: Integrated **Port Scanner**, **Ping / TCP Latency Probe**, and **Live Protocol Trace Drawer**.

---

## Project Architecture

```text
app/src/main/java/com/example/
├── MainActivity.kt                        # Edge-to-edge Compose host & lifecycle entry point
├── data/
│   ├── ConnectionProfileEntity.kt         # Room entity for saved connection profiles
│   ├── ConnectionProfileDao.kt            # Room DAO with reactive Flow queries
│   ├── RemminaDatabase.kt                 # Room database configuration & seed profiles
│   ├── RemminaRepository.kt               # Repository mediating profile CRUD & preferences
│   └── RemminaFileSerializer.kt           # Desktop-compatible .remmina INI parser & exporter
├── model/
│   └── RemminaModels.kt                   # Domain models, protocol enums, session state, scaling modes
├── protocol/
│   ├── RdpLiveSessionClient.kt            # Live RDP client (X.224, MCS, TLS, NLA/CredSSP, Fast-Path)
│   ├── RdpCryptoAndBitmapEngine.kt        # NTLMv2, SPNEGO, RC4, Interleaved RLE & Planar decoders
│   ├── HardwareAcceleratedFrameCompositor.kt # Double-buffered dirty-rect GPU frame compositor
│   ├── LibFreeRdpAndroidWrapper.kt        # Native libfreerdp-android JNI bridge & fallback state machine
│   ├── VncAndSshLiveClients.kt            # Live RFB 3.8 VNC client, JSch SSH2 shell, SFTP & SSH tunnel bridge
│   └── RemoteCoordinateNormalizer.kt      # Viewport-to-desktop coordinate & aspect ratio math
└── ui/
    ├── RemminaApp.kt                      # Main navigation scaffold, Quick Connect bar, profile list
    ├── RemminaViewModel.kt                # Multi-session state holder & coroutine lifecycle manager
    ├── components/                        # Reusable cards, dialogs, port scanner & profile editors
    ├── session/
    │   ├── ActiveSessionWorkspace.kt      # Multi-tabbed remote session container & floating toolbar
    │   └── RemoteFramebufferCanvas.kt     # Hardware-accelerated RDP/VNC canvas, trackpad & keyboard bar
    └── theme/                             # Material 3 Dark/Light color schemes & typography
```
Buy me coffee!: buymeacoffee.com/mawk
