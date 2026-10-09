package com.example.protocol

import androidx.compose.ui.graphics.ImageBitmap
import com.example.data.local.ConnectionProfileEntity
import com.example.data.model.InputControlMode
import com.example.data.model.RemoteProtocol
import com.example.keyboard.TranslatedKeyPacket

enum class SessionConnectionPhase(val label: String) {
    INITIALIZING_TUNNEL("Opening SSH Bastion Tunnel..."),
    CONNECTING_SOCKET("Connecting TCP Socket..."),
    NEGOTIATING_SECURITY("Negotiating TLS & Protocol Handshake..."),
    AUTHENTICATION_REQUIRED("Authentication Required"),
    UNABLE_TO_CONNECT("Unable to connect"),
    STREAMING_FRAMEBUFFER("Connected")
}

enum class ResolutionMode(val label: String, val description: String) {
    DYNAMIC_CLIENT("Use client resolution (Dynamic Resize)", "Automatically send RDPEGFX / xrandr / vdagent resize events to match viewport"),
    FIXED_PRESET("Use profile / preset resolution", "Lock remote desktop framebuffer to the selected Width × Height"),
    CUSTOM("Custom resolution", "Specify exact horizontal and vertical pixel dimensions")
}

enum class ScalingMode(val label: String, val shortLabel: String) {
    FIT_WINDOW("Scale to fit window (Preserve Aspect Ratio)", "Fit"),
    ONE_TO_ONE("1:1 Original Pixel Mapping (Scroll/Pan)", "1:1"),
    STRETCH("Stretch to fill entire screen", "Stretch"),
    CUSTOM_ZOOM("Manual Pinch / Custom Zoom", "Zoom")
}

data class ResolutionPreset(
    val width: Int,
    val height: Int,
    val aspectLabel: String
) {
    val display: String get() = "${width}x${height}"
}

val StandardResolutionPresets = listOf(
    ResolutionPreset(3840, 2160, "16:9 4K UHD"),
    ResolutionPreset(2560, 1600, "16:10 WQXGA"),
    ResolutionPreset(2560, 1440, "16:9 QHD"),
    ResolutionPreset(1920, 1200, "16:10 WUXGA"),
    ResolutionPreset(1920, 1080, "16:9 Full HD"),
    ResolutionPreset(1600, 900, "16:9 HD+"),
    ResolutionPreset(1440, 900, "16:10 WXGA+"),
    ResolutionPreset(1366, 768, "16:9 WXGA"),
    ResolutionPreset(1280, 800, "16:10 Tablet"),
    ResolutionPreset(1280, 720, "16:9 HD"),
    ResolutionPreset(1024, 768, "4:3 XGA"),
    ResolutionPreset(800, 600, "4:3 SVGA")
)

data class TerminalLine(
    val text: String,
    val isCommandPrompt: Boolean = false,
    val isAccent: Boolean = false,
    val isError: Boolean = false
)

data class SftpRemoteFile(
    val name: String,
    val permissions: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val modifiedDate: String
)

data class ActiveRemoteSession(
    val sessionId: String,
    val profile: ConnectionProfileEntity,
    val protocol: RemoteProtocol,
    val phase: SessionConnectionPhase = SessionConnectionPhase.CONNECTING_SOCKET,
    val serverBanner: String,
    val tunnelCommandPreview: String?,
    val rttLatencyMs: Int = -1,
    val currentFps: Int = 0,
    val bitrateMbps: Float = 0f,
    // Connection Failure, Auth Prompt & TLS Certificate Metadata
    val errorTitle: String? = null,
    val errorDetails: String? = null,
    val authPromptReason: String? = null,
    val tlsCertSubject: String? = null,
    val tlsCertFingerprint: String? = null,
    val wireProtocolTrace: List<String> = emptyList(),
    // Live Decoded Remote Framebuffer Bitmap (from real RDP / VNC / SPICE server)
    val framebufferBitmap: ImageBitmap? = null,
    val framebufferFrameCount: Int = 0,
    // Fullscreen, Resolution & Scaling State
    val isFullScreen: Boolean = false,
    val showFloatingToolbarExpanded: Boolean = true,
    val resolutionMode: ResolutionMode = ResolutionMode.FIXED_PRESET,
    val scalingMode: ScalingMode = ScalingMode.FIT_WINDOW,
    val remoteWidth: Int = 1280,
    val remoteHeight: Int = 720,
    val colorDepthBpp: Int = 16,
    val remoteDpiScalePercent: Int = 100,
    val activeMonitorIndex: Int = 0,
    val monitorCount: Int = 1,
    // Pointer & Viewport State
    val inputMode: InputControlMode = InputControlMode.DIRECT_TOUCH,
    val cursorXNorm: Float = 0.50f,
    val cursorYNorm: Float = 0.50f,
    val isLeftButtonDragging: Boolean = false,
    val dragStartXNorm: Float? = null,
    val dragStartYNorm: Float? = null,
    val zoomScale: Float = 1.0f,
    val panOffsetX: Float = 0f,
    val panOffsetY: Float = 0f,
    val lastPointerAction: String = "Ready",
    val showContextMenuAt: Pair<Float, Float>? = null,
    val remoteClipboardText: String = "",
    // Sticky Modifiers & Hardware Keyboard State
    val stickyCtrl: Boolean = false,
    val stickyAlt: Boolean = false,
    val stickyShift: Boolean = false,
    val stickySuper: Boolean = false,
    val keyboardGrabEnabled: Boolean = true,
    val lastTranslatedKeys: List<TranslatedKeyPacket> = emptyList(),
    // Live SSH Terminal & SFTP State
    val terminalLines: List<TerminalLine> = emptyList(),
    val currentTerminalInput: String = "",
    val commandHistory: List<String> = emptyList(),
    val historyIndex: Int = -1,
    val sftpCurrentPath: String = ".",
    val sftpFiles: List<SftpRemoteFile> = emptyList(),
    val showSftpPanel: Boolean = false
)
