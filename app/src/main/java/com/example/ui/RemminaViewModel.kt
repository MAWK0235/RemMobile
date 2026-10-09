package com.example.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ConnectionProfileEntity
import com.example.data.local.KeyMappingEntity
import com.example.data.local.RemminaDatabase
import com.example.data.local.SshKeyEntity
import com.example.data.model.InputControlMode
import com.example.data.model.RemoteProtocol
import com.example.data.repository.RemminaRepository
import com.example.keyboard.HardwareKeyboardEngine
import com.example.keyboard.TranslatedKeyPacket
import com.example.protocol.ActiveRemoteSession
import com.example.protocol.NetworkProbeEngine
import com.example.protocol.RdpLiveSessionClient
import com.example.protocol.RdpSessionCallbacks
import com.example.protocol.ResolutionMode
import com.example.protocol.ScalingMode
import com.example.protocol.SessionConnectionPhase
import com.example.protocol.SftpRemoteFile
import com.example.protocol.SshBastionTunnel
import com.example.protocol.SshLiveSessionClient
import com.example.protocol.SshSessionCallbacks
import com.example.protocol.TerminalLine
import com.example.protocol.VncLiveSessionClient
import com.example.security.BiometricCapabilityStatus
import com.example.security.CredentialVaultManager
import com.example.security.PinVerificationResult
import com.example.security.SecurityAuditCheckItem
import com.example.security.SecuritySelfTestResult
import com.example.security.SecurityVaultPolicy
import com.example.ui.security.PendingSecurityChallenge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class MainNavTab {
    CONNECTIONS,
    ACTIVE_SESSIONS,
    KEYBOARD_AND_VAULT,
    PREFERENCES_AND_IMPORT
}

class RemminaViewModel(application: Application) : AndroidViewModel(application) {
    private val database = RemminaDatabase.getInstance(application)
    val vaultManager = CredentialVaultManager(application)
    val repository = RemminaRepository(database.remminaDao(), vaultManager)

    // Security Vault, Biometric & PIN State
    private val _securityPolicy = MutableStateFlow(vaultManager.getSecurityPolicy())
    val securityPolicy: StateFlow<SecurityVaultPolicy> = _securityPolicy.asStateFlow()

    private val _biometricStatus = MutableStateFlow(vaultManager.queryBiometricCapability())
    val biometricStatus: StateFlow<BiometricCapabilityStatus> = _biometricStatus.asStateFlow()

    private val _isVaultLocked = MutableStateFlow(
        vaultManager.getSecurityPolicy().let { it.requireAuthOnAppLaunch && it.isPinConfigured }
    )
    val isVaultLocked: StateFlow<Boolean> = _isVaultLocked.asStateFlow()

    private val _pendingSecurityChallenge = MutableStateFlow<PendingSecurityChallenge?>(null)
    val pendingSecurityChallenge: StateFlow<PendingSecurityChallenge?> = _pendingSecurityChallenge.asStateFlow()

    private val _editingDecryptedProfile = MutableStateFlow<ConnectionProfileEntity?>(null)
    val editingDecryptedProfile: StateFlow<ConnectionProfileEntity?> = _editingDecryptedProfile.asStateFlow()

    private val _lastSecuritySelfTest = MutableStateFlow<SecuritySelfTestResult?>(null)
    val lastSecuritySelfTest: StateFlow<SecuritySelfTestResult?> = _lastSecuritySelfTest.asStateFlow()

    private var lastBackgroundedEpochMs: Long = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.migratePlaintextPasswordsInDatabase()
        }
    }

    private val _currentTab = MutableStateFlow(MainNavTab.CONNECTIONS)
    val currentTab: StateFlow<MainNavTab> = _currentTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedProtocolFilter = MutableStateFlow<RemoteProtocol?>(null)
    val selectedProtocolFilter: StateFlow<RemoteProtocol?> = _selectedProtocolFilter.asStateFlow()

    private val _quickConnectProtocol = MutableStateFlow(RemoteProtocol.RDP)
    val quickConnectProtocol: StateFlow<RemoteProtocol> = _quickConnectProtocol.asStateFlow()

    private val _quickConnectText = MutableStateFlow("")
    val quickConnectText: StateFlow<String> = _quickConnectText.asStateFlow()

    private val _selectedProfileForDetail = MutableStateFlow<ConnectionProfileEntity?>(null)
    val selectedProfileForDetail: StateFlow<ConnectionProfileEntity?> = _selectedProfileForDetail.asStateFlow()

    val protocolDefaultPorts: StateFlow<Map<String, Int>> = repository.protocolDefaultPorts
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            mapOf(
                "RDP" to 3389,
                "SSH" to 22,
                "VNC" to 5900,
                "SPICE" to 5900,
                "SSH_TUNNEL" to 22,
                "RD_GATEWAY" to 443
            )
        )

    val allProfiles: StateFlow<List<ConnectionProfileEntity>> = repository.allProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredProfiles: StateFlow<List<ConnectionProfileEntity>> = combine(
        allProfiles,
        _searchQuery,
        _selectedProtocolFilter
    ) { profiles, query, protoFilter ->
        profiles.filter { p ->
            val matchesProto = protoFilter == null || p.protocol.equals(protoFilter.name, ignoreCase = true)
            val matchesQuery = query.isBlank() ||
                p.name.contains(query, ignoreCase = true) ||
                p.server.contains(query, ignoreCase = true) ||
                p.groupName.contains(query, ignoreCase = true) ||
                p.username.contains(query, ignoreCase = true)
            matchesProto && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSshKeys: StateFlow<List<SshKeyEntity>> = repository.allSshKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allKeyMappings: StateFlow<List<KeyMappingEntity>> = repository.allKeyMappings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val securityAuditChecklist: StateFlow<List<SecurityAuditCheckItem>> = combine(
        allProfiles,
        _securityPolicy,
        _biometricStatus
    ) { profiles, _, _ ->
        vaultManager.buildSecurityAuditChecklist(profiles)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        vaultManager.buildSecurityAuditChecklist(emptyList())
    )

    // Active Concurrent Remote Sessions & Live Protocol Engines
    private val _activeSessions = MutableStateFlow<List<ActiveRemoteSession>>(emptyList())
    val activeSessions: StateFlow<List<ActiveRemoteSession>> = _activeSessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val activeRdpClients = ConcurrentHashMap<String, RdpLiveSessionClient>()
    private val activeVncClients = ConcurrentHashMap<String, VncLiveSessionClient>()
    private val activeSshClients = ConcurrentHashMap<String, SshLiveSessionClient>()
    private val activeSshTunnels = ConcurrentHashMap<String, SshBastionTunnel>()
    private val lastDynamicResizeEpochMs = ConcurrentHashMap<String, Long>()
    private val lastTapEpochMs = ConcurrentHashMap<String, Long>()
    private val lastTapNormCoords = ConcurrentHashMap<String, Pair<Float, Float>>()

    @Volatile
    private var lastViewportWidthPx: Int = application.resources.displayMetrics.widthPixels.coerceAtLeast(800)

    @Volatile
    private var lastViewportHeightPx: Int = (application.resources.displayMetrics.heightPixels - (140 * application.resources.displayMetrics.density).toInt()).coerceAtLeast(600)

    /**
     * Computes an RDP-aligned desktop resolution (`width` multiple of 4, `height` multiple of 2)
     * that matches the exact aspect ratio of the phone or tablet session viewport `(viewportW, viewportH)`.
     */
    fun computeDeviceMatchedResolution(
        viewportW: Int = lastViewportWidthPx,
        viewportH: Int = lastViewportHeightPx,
        dpiScalePercent: Int = 100
    ): Pair<Int, Int> {
        val safeW = viewportW.coerceAtLeast(360)
        val safeH = viewportH.coerceAtLeast(360)
        val aspect = safeW.toFloat() / safeH.toFloat()
        val scaleFactor = (dpiScalePercent.coerceIn(75, 250) / 100f)
        // Target a crisp logical desktop (~1280px on the longest edge at 100% scale) with the exact device aspect ratio
        val targetLongEdge = (1280f / scaleFactor).coerceIn(720f, 1920f)
        val rawW: Float
        val rawH: Float
        if (safeW >= safeH) {
            rawW = minOf(safeW.toFloat(), targetLongEdge)
            rawH = rawW / aspect
        } else {
            rawH = minOf(safeH.toFloat(), targetLongEdge)
            rawW = rawH * aspect
        }
        val alignedW = (((rawW.toInt().coerceIn(360, 4096)) + 2) / 4) * 4
        val alignedH = (((rawH.toInt().coerceIn(360, 4096)) + 1) / 2) * 2
        return Pair(alignedW, alignedH)
    }

    val isCurrentSessionFullScreen: StateFlow<Boolean> = combine(
        _currentTab,
        _activeSessions,
        _currentSessionId
    ) { tab, sessions, activeId ->
        if (tab != MainNavTab.ACTIVE_SESSIONS) {
            false
        } else {
            val active = sessions.firstOrNull { it.sessionId == activeId } ?: sessions.firstOrNull()
            active?.isFullScreen == true
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Hardware Key Tester State
    private val _lastTestedKeyPacket = MutableStateFlow<TranslatedKeyPacket?>(null)
    val lastTestedKeyPacket: StateFlow<TranslatedKeyPacket?> = _lastTestedKeyPacket.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    fun selectTab(tab: MainNavTab) {
        _currentTab.value = tab
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectProtocolFilter(protocol: RemoteProtocol?) {
        _selectedProtocolFilter.value = protocol
    }

    fun setQuickConnectProtocol(protocol: RemoteProtocol) {
        _quickConnectProtocol.value = protocol
    }

    fun updateQuickConnectText(text: String) {
        _quickConnectText.value = text
    }

    fun selectProfileForDetail(profile: ConnectionProfileEntity?) {
        _selectedProfileForDetail.value = profile
    }

    fun clearStatusBanner() {
        _statusBannerMessage.value = null
    }

    fun showStatusMessage(message: String) {
        _statusBannerMessage.value = message
    }

    fun updateProtocolDefaultPort(protocolName: String, newPort: Int) {
        viewModelScope.launch {
            repository.updateProtocolDefaultPort(protocolName, newPort)
            _statusBannerMessage.value = "Updated default port for $protocolName to $newPort"
        }
    }

    fun resetAllDefaultPorts() {
        viewModelScope.launch {
            repository.resetAllDefaultPortsToStandard()
            _statusBannerMessage.value = "Reset all protocol default ports to standard values"
        }
    }

    fun saveProfile(profile: ConnectionProfileEntity) {
        viewModelScope.launch {
            val savedId = repository.saveProfile(profile)
            val encryptedSaved = vaultManager.encryptProfileSecrets(profile.copy(id = savedId))
            _selectedProfileForDetail.value = encryptedSaved
            _statusBannerMessage.value = "Saved & AES-256-GCM encrypted '${profile.name}' (${profile.protocol} :${profile.port})"
        }
    }

    // =========================================================================
    // Biometric & Cryptographic Vault PIN Management
    // =========================================================================

    fun refreshSecurityState() {
        _securityPolicy.value = vaultManager.getSecurityPolicy()
        _biometricStatus.value = vaultManager.queryBiometricCapability()
    }

    fun onAppBackgrounded() {
        lastBackgroundedEpochMs = System.currentTimeMillis()
        val policy = vaultManager.getSecurityPolicy()
        if (policy.requireAuthOnAppLaunch && policy.isPinConfigured && policy.autoLockTimeoutSeconds == 0) {
            _isVaultLocked.value = true
        }
    }

    fun onAppResumed() {
        refreshSecurityState()
        val policy = _securityPolicy.value
        if (policy.requireAuthOnAppLaunch && policy.isPinConfigured && lastBackgroundedEpochMs > 0L) {
            val elapsedMs = System.currentTimeMillis() - lastBackgroundedEpochMs
            if (elapsedMs >= policy.autoLockTimeoutSeconds * 1000L) {
                _isVaultLocked.value = true
            }
        }
    }

    fun lockVaultNow() {
        refreshSecurityState()
        val policy = _securityPolicy.value
        if (policy.isPinConfigured || _biometricStatus.value.isAvailable) {
            _isVaultLocked.value = true
        } else {
            _currentTab.value = MainNavTab.KEYBOARD_AND_VAULT
            _statusBannerMessage.value = "Set a 4–8 digit Master Vault PIN in Security & Biometrics to enable App Lock"
        }
    }

    fun verifyVaultPinForAppUnlock(pin: String): PinVerificationResult {
        val result = vaultManager.verifyPin(pin)
        refreshSecurityState()
        if (result is PinVerificationResult.Success) {
            _isVaultLocked.value = false
            lastBackgroundedEpochMs = 0L
        }
        return result
    }

    fun verifyVaultPin(pin: String): PinVerificationResult {
        val result = vaultManager.verifyPin(pin)
        refreshSecurityState()
        return result
    }

    fun setupOrUpdateVaultPin(newPin: String): Boolean {
        val ok = vaultManager.setupOrUpdatePin(newPin)
        refreshSecurityState()
        return ok
    }

    fun removeVaultPin() {
        vaultManager.removePin()
        _isVaultLocked.value = false
        refreshSecurityState()
    }

    fun updateSecurityPolicy(
        biometricEnabled: Boolean? = null,
        requireAuthOnAppLaunch: Boolean? = null,
        requireAuthBeforeSystemConnect: Boolean? = null,
        autoLockTimeoutSeconds: Int? = null,
        flagSecureEnabled: Boolean? = null,
        strictTlsTofuPinning: Boolean? = null
    ) {
        _securityPolicy.value = vaultManager.updateSecurityPolicy(
            biometricEnabled = biometricEnabled,
            requireAuthOnAppLaunch = requireAuthOnAppLaunch,
            requireAuthBeforeSystemConnect = requireAuthBeforeSystemConnect,
            autoLockTimeoutSeconds = autoLockTimeoutSeconds,
            flagSecureEnabled = flagSecureEnabled,
            strictTlsTofuPinning = strictTlsTofuPinning
        )
    }

    fun triggerHardwareBiometricForAppUnlock(activity: Activity, onError: (String) -> Unit) {
        vaultManager.triggerHardwareBiometricPrompt(
            activity = activity,
            title = "Unlock RemMobile Security Vault",
            subtitle = "Biometric Authentication",
            description = "Verify your fingerprint or face to unlock encrypted remote sessions and credentials.",
            onAuthenticated = {
                _isVaultLocked.value = false
                lastBackgroundedEpochMs = 0L
            },
            onFallbackToPin = {},
            onErrorMessage = onError
        )
    }

    fun triggerHardwareBiometricForChallenge(activity: Activity, onError: (String) -> Unit) {
        val currentChallenge = _pendingSecurityChallenge.value ?: return
        val targetName = when (currentChallenge) {
            is PendingSecurityChallenge.ConnectToSystem -> currentChallenge.profile.name
            is PendingSecurityChallenge.EditProtectedProfile -> currentChallenge.profile.name
        }
        vaultManager.triggerHardwareBiometricPrompt(
            activity = activity,
            title = "Authorize System Access",
            subtitle = targetName,
            description = "Authenticate with Biometrics to decrypt AES-256-GCM credentials and continue.",
            onAuthenticated = {
                completePendingSecurityChallenge()
            },
            onFallbackToPin = {},
            onErrorMessage = onError
        )
    }

    fun dismissPendingSecurityChallenge() {
        _pendingSecurityChallenge.value = null
    }

    fun completePendingSecurityChallenge() {
        val challenge = _pendingSecurityChallenge.value ?: return
        _pendingSecurityChallenge.value = null
        when (challenge) {
            is PendingSecurityChallenge.ConnectToSystem -> {
                connectToProfileInternal(challenge.profile)
            }
            is PendingSecurityChallenge.EditProtectedProfile -> {
                _editingDecryptedProfile.value = repository.decryptProfileSecrets(challenge.profile)
            }
        }
    }

    fun requestEditProfile(profile: ConnectionProfileEntity) {
        refreshSecurityState()
        val policy = _securityPolicy.value
        val hasSavedSecrets = profile.password.isNotEmpty() || profile.sshTunnelPassword.isNotEmpty()
        val shouldGate = (policy.requireAuthBeforeSystemConnect || profile.requireBiometricForConnection) &&
            (hasSavedSecrets || policy.isPinConfigured || _biometricStatus.value.isAvailable)

        if (shouldGate) {
            _pendingSecurityChallenge.value = PendingSecurityChallenge.EditProtectedProfile(profile)
        } else {
            _editingDecryptedProfile.value = repository.decryptProfileSecrets(profile)
        }
    }

    fun dismissEditProfile() {
        _editingDecryptedProfile.value = null
    }

    fun runSecuritySelfTest() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.migratePlaintextPasswordsInDatabase()
            val rawProfiles = repository.getRawDatabaseProfilesSnapshot()
            val result = vaultManager.runCryptographicSelfTest(rawProfiles)
            withContext(Dispatchers.Main) {
                _lastSecuritySelfTest.value = result
                _statusBannerMessage.value = result.summary
            }
        }
    }

    fun deleteProfile(profile: ConnectionProfileEntity) {
        viewModelScope.launch {
            repository.deleteProfile(profile)
            if (_selectedProfileForDetail.value?.id == profile.id) {
                _selectedProfileForDetail.value = null
            }
            _statusBannerMessage.value = "Deleted profile '${profile.name}'"
        }
    }

    fun toggleFavorite(profile: ConnectionProfileEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(profile)
        }
    }

    fun launchQuickConnect() {
        val raw = _quickConnectText.value.trim()
        if (raw.isBlank()) return
        val tempProfile = repository.parseQuickConnectUri(
            rawInput = raw,
            fallbackProtocol = _quickConnectProtocol.value,
            customDefaultPorts = protocolDefaultPorts.value
        )
        viewModelScope.launch {
            val id = repository.saveProfile(tempProfile)
            val saved = tempProfile.copy(id = id)
            _quickConnectText.value = ""
            connectToProfile(saved)
        }
    }

    fun connectToProfile(profile: ConnectionProfileEntity) {
        val existing = _activeSessions.value.firstOrNull { it.profile.id == profile.id && profile.id != 0L }
        if (existing != null) {
            _currentSessionId.value = existing.sessionId
            _currentTab.value = MainNavTab.ACTIVE_SESSIONS
            return
        }

        refreshSecurityState()
        val policy = _securityPolicy.value
        val hasSavedSecrets = profile.password.isNotEmpty() || profile.sshTunnelPassword.isNotEmpty()
        val shouldChallenge = (policy.requireAuthBeforeSystemConnect || profile.requireBiometricForConnection) &&
            (policy.isPinConfigured || _biometricStatus.value.isAvailable || hasSavedSecrets)

        if (shouldChallenge) {
            _pendingSecurityChallenge.value = PendingSecurityChallenge.ConnectToSystem(profile)
            return
        }

        connectToProfileInternal(profile)
    }

    private fun connectToProfileInternal(profile: ConnectionProfileEntity) {
        val existing = _activeSessions.value.firstOrNull { it.profile.id == profile.id && profile.id != 0L }
        if (existing != null) {
            _currentSessionId.value = existing.sessionId
            _currentTab.value = MainNavTab.ACTIVE_SESSIONS
            return
        }

        val protocol = RemoteProtocol.fromString(profile.protocol)
        val newSessionId = UUID.randomUUID().toString()
        val resMode = try {
            ResolutionMode.valueOf(profile.resolutionMode)
        } catch (_: Exception) {
            ResolutionMode.DYNAMIC_CLIENT
        }
        val scaleMode = try {
            ScalingMode.valueOf(profile.scalingMode)
        } catch (_: Exception) {
            ScalingMode.FIT_WINDOW
        }
        val (initW, initH) = if (resMode == ResolutionMode.DYNAMIC_CLIENT) {
            computeDeviceMatchedResolution(lastViewportWidthPx, lastViewportHeightPx, profile.dpiScalePercent)
        } else {
            NetworkProbeEngine.parseResolutionDimensions(profile.resolution)
        }

        val initialPhase = if (profile.sshTunnelEnabled && profile.sshTunnelHost.isNotBlank()) {
            SessionConnectionPhase.INITIALIZING_TUNNEL
        } else {
            SessionConnectionPhase.CONNECTING_SOCKET
        }

        val newSession = ActiveRemoteSession(
            sessionId = newSessionId,
            profile = profile,
            protocol = protocol,
            phase = initialPhase,
            serverBanner = "Connecting to ${profile.server}:${profile.port}...",
            tunnelCommandPreview = if (profile.sshTunnelEnabled && profile.sshTunnelHost.isNotBlank()) {
                "ssh -N -L 127.0.0.1:0:${profile.server}:${profile.port} ${profile.sshTunnelUsername}@${profile.sshTunnelHost} -p ${profile.sshTunnelPort}"
            } else null,
            isFullScreen = profile.startFullScreen,
            resolutionMode = resMode,
            scalingMode = scaleMode,
            remoteWidth = initW,
            remoteHeight = initH,
            colorDepthBpp = profile.colorDepth,
            remoteDpiScalePercent = profile.dpiScalePercent,
            inputMode = if (profile.defaultInputMode == "TRACKPAD") InputControlMode.TRACKPAD else InputControlMode.DIRECT_TOUCH
        )

        _activeSessions.update { it + newSession }
        _currentSessionId.value = newSessionId
        _currentTab.value = MainNavTab.ACTIVE_SESSIONS

        startLiveSessionEngine(newSessionId, profile)
    }

    fun retrySessionConnection(sessionId: String) {
        val target = _activeSessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        cleanupSessionEngine(sessionId)
        updateSession(sessionId) {
            it.copy(
                phase = if (it.profile.sshTunnelEnabled) SessionConnectionPhase.INITIALIZING_TUNNEL else SessionConnectionPhase.CONNECTING_SOCKET,
                errorTitle = null,
                errorDetails = null,
                authPromptReason = null,
                framebufferBitmap = null,
                wireProtocolTrace = listOf("[remmina] Reconnecting to ${it.profile.server}:${it.profile.port}...")
            )
        }
        startLiveSessionEngine(sessionId, target.profile)
    }

    /**
     * Submits credentials (Username, Password, Domain, and RDP Security Mode) from the live Remmina Authentication Modal
     * and immediately reconnects the session.
     */
    fun submitSessionCredentials(
        sessionId: String,
        username: String,
        password: String,
        domain: String,
        securityMode: String,
        saveToProfile: Boolean
    ) {
        val current = _activeSessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        val updatedProfile = current.profile.copy(
            username = username.trim(),
            password = password,
            domain = domain.trim(),
            securityMode = securityMode
        )

        if (saveToProfile && updatedProfile.id != 0L) {
            viewModelScope.launch {
                repository.updateProfileCredentials(
                    profileId = updatedProfile.id,
                    username = updatedProfile.username,
                    password = updatedProfile.password,
                    domain = updatedProfile.domain,
                    securityMode = updatedProfile.securityMode
                )
            }
        }

        cleanupSessionEngine(sessionId)
        updateSession(sessionId) {
            it.copy(
                profile = updatedProfile,
                phase = SessionConnectionPhase.CONNECTING_SOCKET,
                errorTitle = null,
                errorDetails = null,
                authPromptReason = null,
                wireProtocolTrace = it.wireProtocolTrace + "[auth] Retrying connection to ${updatedProfile.server}:${updatedProfile.port} with supplied credentials..."
            )
        }
        startLiveSessionEngine(sessionId, updatedProfile)
    }

    private fun startLiveSessionEngine(sessionId: String, profile: ConnectionProfileEntity) {
        // Decrypt AES-256-GCM credentials transiently in memory for the wire protocol engine only
        val decryptedProfile = repository.decryptProfileSecrets(profile)
        val protocol = RemoteProtocol.fromString(decryptedProfile.protocol)
        val activeSessionSnapshot = _activeSessions.value.firstOrNull { it.sessionId == sessionId }
        val (reqW, reqH) = if (activeSessionSnapshot != null && activeSessionSnapshot.resolutionMode == ResolutionMode.DYNAMIC_CLIENT) {
            computeDeviceMatchedResolution(
                lastViewportWidthPx,
                lastViewportHeightPx,
                activeSessionSnapshot.remoteDpiScalePercent
            ).also { (dw, dh) ->
                updateSession(sessionId) { s -> s.copy(remoteWidth = dw, remoteHeight = dh) }
            }
        } else if (activeSessionSnapshot != null) {
            Pair(activeSessionSnapshot.remoteWidth, activeSessionSnapshot.remoteHeight)
        } else {
            NetworkProbeEngine.parseResolutionDimensions(decryptedProfile.resolution)
        }

        viewModelScope.launch(Dispatchers.IO) {
            var targetHost = decryptedProfile.server.trim()
            var targetPort = decryptedProfile.port

            // Establish real SSH local port forwarding tunnel if enabled
            if (decryptedProfile.sshTunnelEnabled && decryptedProfile.sshTunnelHost.isNotBlank()) {
                try {
                    val tunnel = SshBastionTunnel(decryptedProfile)
                    val localPort = tunnel.establishTunnel { traceLine ->
                        appendSessionTrace(sessionId, traceLine)
                    }
                    activeSshTunnels[sessionId] = tunnel
                    targetHost = "127.0.0.1"
                    targetPort = localPort
                } catch (e: SshBastionTunnel.NeedSshTunnelAuthException) {
                    updateSession(sessionId) {
                        it.copy(
                            phase = SessionConnectionPhase.AUTHENTICATION_REQUIRED,
                            authPromptReason = e.message
                        )
                    }
                    return@launch
                } catch (e: Exception) {
                    val errDetail = "${e.javaClass.simpleName}: ${e.localizedMessage ?: "SSH Bastion unreachable"}"
                    updateSession(sessionId) {
                        it.copy(
                            phase = SessionConnectionPhase.UNABLE_TO_CONNECT,
                            serverBanner = "Unable to connect",
                            errorTitle = "Unable to establish SSH tunnel via ${decryptedProfile.sshTunnelHost}:${decryptedProfile.sshTunnelPort}",
                            errorDetails = errDetail,
                            wireProtocolTrace = it.wireProtocolTrace + "[error] $errDetail"
                        )
                    }
                    return@launch
                }
            }

            when (protocol) {
                RemoteProtocol.RDP -> {
                    val rdpClient = RdpLiveSessionClient(
                        scope = viewModelScope,
                        connectHost = targetHost,
                        connectPort = targetPort,
                        profile = decryptedProfile,
                        requestedWidth = reqW,
                        requestedHeight = reqH,
                        callbacks = createRdpOrVncCallbacks(sessionId, decryptedProfile)
                    )
                    activeRdpClients[sessionId] = rdpClient
                    rdpClient.start()
                }

                RemoteProtocol.VNC -> {
                    val vncClient = VncLiveSessionClient(
                        scope = viewModelScope,
                        connectHost = targetHost,
                        connectPort = targetPort,
                        profile = decryptedProfile,
                        callbacks = createRdpOrVncCallbacks(sessionId, decryptedProfile)
                    )
                    activeVncClients[sessionId] = vncClient
                    vncClient.start()
                }

                RemoteProtocol.SSH -> {
                    val sshClient = SshLiveSessionClient(
                        scope = viewModelScope,
                        connectHost = targetHost,
                        connectPort = targetPort,
                        profile = decryptedProfile,
                        callbacks = object : SshSessionCallbacks {
                            override fun onTraceLog(line: String) {
                                appendSessionTrace(sessionId, line)
                            }

                            override fun onPhaseChanged(phase: SessionConnectionPhase, banner: String, latencyMs: Int) {
                                updateSession(sessionId) {
                                    it.copy(
                                        phase = phase,
                                        serverBanner = banner,
                                        rttLatencyMs = if (latencyMs > 0) latencyMs else it.rttLatencyMs
                                    )
                                }
                                if (phase == SessionConnectionPhase.STREAMING_FRAMEBUFFER && profile.id != 0L && latencyMs > 0) {
                                    viewModelScope.launch { repository.recordConnection(profile.id, latencyMs) }
                                }
                            }

                            override fun onAuthenticationRequired(reason: String) {
                                updateSession(sessionId) {
                                    it.copy(
                                        phase = SessionConnectionPhase.AUTHENTICATION_REQUIRED,
                                        authPromptReason = reason
                                    )
                                }
                            }

                            override fun onTerminalOutputReceived(lines: List<String>) {
                                val termLines = lines.map { TerminalLine(it) }
                                updateSession(sessionId) {
                                    it.copy(
                                        terminalLines = (it.terminalLines + termLines).takeLast(200)
                                    )
                                }
                            }

                            override fun onSftpFilesListed(currentPath: String, files: List<SftpRemoteFile>) {
                                updateSession(sessionId) {
                                    it.copy(
                                        sftpCurrentPath = currentPath,
                                        sftpFiles = files
                                    )
                                }
                            }

                            override fun onDisconnected(errorTitle: String, errorDetails: String) {
                                updateSession(sessionId) {
                                    it.copy(
                                        phase = SessionConnectionPhase.UNABLE_TO_CONNECT,
                                        serverBanner = "Unable to connect",
                                        errorTitle = errorTitle,
                                        errorDetails = errorDetails
                                    )
                                }
                            }
                        }
                    )
                    activeSshClients[sessionId] = sshClient
                    sshClient.start()
                }

                RemoteProtocol.SPICE -> {
                    val probe = NetworkProbeEngine.probeProfileEndpoint(profile)
                    updateSession(sessionId) {
                        it.copy(
                            phase = SessionConnectionPhase.UNABLE_TO_CONNECT,
                            serverBanner = if (probe.reachableSocket) probe.discoveredBanner else "Unable to connect",
                            errorTitle = probe.errorTitle ?: "SPICE channel negotiation requires SPICE ticket / TLS cert from ${profile.server}:${profile.port}",
                            errorDetails = probe.errorDetails ?: "Handshake response: ${probe.discoveredBanner}",
                            wireProtocolTrace = probe.wireTrace
                        )
                    }
                }
            }
        }
    }

    private fun createRdpOrVncCallbacks(sessionId: String, profile: ConnectionProfileEntity): RdpSessionCallbacks {
        return object : RdpSessionCallbacks {
            override fun onTraceLog(line: String) {
                appendSessionTrace(sessionId, line)
            }

            override fun onPhaseChanged(phase: SessionConnectionPhase, banner: String) {
                updateSession(sessionId) {
                    it.copy(
                        phase = phase,
                        serverBanner = banner,
                        errorTitle = null,
                        errorDetails = null
                    )
                }
                if (phase == SessionConnectionPhase.STREAMING_FRAMEBUFFER && profile.id != 0L) {
                    viewModelScope.launch { repository.recordConnection(profile.id, 10) }
                }
            }

            override fun onTlsCertificateDiscovered(subject: String, sha256Fingerprint: String) {
                updateSession(sessionId) {
                    it.copy(
                        tlsCertSubject = subject,
                        tlsCertFingerprint = sha256Fingerprint
                    )
                }
                if (_securityPolicy.value.strictTlsTofuPinning && profile.id != 0L && profile.pinnedCertSha256.isBlank()) {
                    viewModelScope.launch {
                        repository.updatePinnedCertificate(profile.id, sha256Fingerprint)
                    }
                }
            }

            override fun onAuthenticationRequired(reason: String, certSubject: String?, certFingerprint: String?) {
                updateSession(sessionId) {
                    it.copy(
                        phase = SessionConnectionPhase.AUTHENTICATION_REQUIRED,
                        authPromptReason = reason,
                        tlsCertSubject = certSubject ?: it.tlsCertSubject,
                        tlsCertFingerprint = certFingerprint ?: it.tlsCertFingerprint
                    )
                }
            }

            override fun onResolutionNegotiated(width: Int, height: Int, bpp: Int) {
                updateSession(sessionId) {
                    it.copy(
                        remoteWidth = width,
                        remoteHeight = height,
                        colorDepthBpp = bpp
                    )
                }
            }

            override fun onFramebufferUpdated(bitmap: ImageBitmap, frameCount: Int, bitrateMbps: Float) {
                onFramebufferUpdatedWithMetrics(bitmap, frameCount, bitrateMbps, 60, 1)
            }

            override fun onFramebufferUpdatedWithMetrics(
                bitmap: ImageBitmap,
                frameCount: Int,
                bitrateMbps: Float,
                measuredFps: Int,
                coalescedTiles: Int
            ) {
                updateSession(sessionId) {
                    it.copy(
                        phase = SessionConnectionPhase.STREAMING_FRAMEBUFFER,
                        framebufferBitmap = bitmap,
                        remoteWidth = bitmap.width.coerceAtLeast(1),
                        remoteHeight = bitmap.height.coerceAtLeast(1),
                        framebufferFrameCount = frameCount,
                        bitrateMbps = bitrateMbps,
                        currentFps = measuredFps.coerceIn(1, 120)
                    )
                }
            }

            override fun onDisconnected(errorTitle: String, errorDetails: String) {
                updateSession(sessionId) {
                    it.copy(
                        phase = SessionConnectionPhase.UNABLE_TO_CONNECT,
                        serverBanner = "Unable to connect",
                        errorTitle = errorTitle,
                        errorDetails = errorDetails
                    )
                }
            }
        }
    }

    private fun appendSessionTrace(sessionId: String, line: String) {
        updateSession(sessionId) {
            it.copy(wireProtocolTrace = (it.wireProtocolTrace + line).takeLast(35))
        }
    }

    private fun cleanupSessionEngine(sessionId: String) {
        activeRdpClients.remove(sessionId)?.disconnect()
        activeVncClients.remove(sessionId)?.disconnect()
        activeSshClients.remove(sessionId)?.disconnect()
        activeSshTunnels.remove(sessionId)?.close()
    }

    fun selectActiveSession(sessionId: String) {
        _currentSessionId.value = sessionId
    }

    fun closeSession(sessionId: String) {
        cleanupSessionEngine(sessionId)
        _activeSessions.update { list -> list.filterNot { it.sessionId == sessionId } }
        val remaining = _activeSessions.value
        if (_currentSessionId.value == sessionId) {
            _currentSessionId.value = remaining.lastOrNull()?.sessionId
        }
        if (remaining.isEmpty() && _currentTab.value == MainNavTab.ACTIVE_SESSIONS) {
            _currentTab.value = MainNavTab.CONNECTIONS
        }
    }

    // Fullscreen & Resolution Management
    fun toggleSessionFullScreen(sessionId: String) {
        updateSession(sessionId) { s ->
            val nextFs = !s.isFullScreen
            s.copy(
                isFullScreen = nextFs,
                lastPointerAction = if (nextFs) "Entered Fullscreen Mode (Host+F)" else "Exited Fullscreen Mode"
            )
        }
    }

    fun toggleFloatingToolbarExpanded(sessionId: String) {
        updateSession(sessionId) { s ->
            s.copy(showFloatingToolbarExpanded = !s.showFloatingToolbarExpanded)
        }
    }

    fun updateSessionResolutionAndScaling(
        sessionId: String,
        resolutionMode: ResolutionMode,
        width: Int,
        height: Int,
        scalingMode: ScalingMode,
        colorDepthBpp: Int,
        dpiScalePercent: Int,
        monitorCount: Int = 1
    ) {
        val validW = width.coerceIn(320, 7680)
        val validH = height.coerceIn(240, 4320)
        val resString = "${validW}x${validH}"

        var targetProfileId = 0L
        var resolutionChanged = false
        var updatedProfileSnapshot: ConnectionProfileEntity? = null

        updateSession(sessionId) { s ->
            targetProfileId = s.profile.id
            resolutionChanged = (s.remoteWidth != validW || s.remoteHeight != validH || s.colorDepthBpp != colorDepthBpp)
            val newProfile = s.profile.copy(
                resolution = resString,
                resolutionMode = resolutionMode.name,
                scalingMode = scalingMode.name,
                colorDepth = colorDepthBpp,
                dpiScalePercent = dpiScalePercent
            )
            updatedProfileSnapshot = newProfile
            s.copy(
                resolutionMode = resolutionMode,
                scalingMode = scalingMode,
                remoteWidth = validW,
                remoteHeight = validH,
                colorDepthBpp = colorDepthBpp,
                remoteDpiScalePercent = dpiScalePercent,
                monitorCount = monitorCount,
                profile = newProfile,
                lastPointerAction = "Resolution -> ${validW}×${validH} (${scalingMode.shortLabel})"
            )
        }

        if (targetProfileId != 0L) {
            viewModelScope.launch {
                repository.updateProfileDisplaySettings(
                    profileId = targetProfileId,
                    resolution = resString,
                    resolutionMode = resolutionMode.name,
                    scalingMode = scalingMode.name,
                    colorDepth = colorDepthBpp,
                    dpiScale = dpiScalePercent
                )
            }
        }

        activeSshClients[sessionId]?.resizePty(validW / 10, validH / 20, validW, validH)

        // If RDP resolution or color depth changed while connected, reconnect with new GCC dimensions
        val prof = updatedProfileSnapshot
        if (resolutionChanged && prof != null && activeRdpClients.containsKey(sessionId)) {
            retrySessionConnection(sessionId)
        }
    }

    fun onDynamicViewportResize(sessionId: String, viewportWidthPx: Int, viewportHeightPx: Int) {
        if (viewportWidthPx <= 200 || viewportHeightPx <= 200) return
        lastViewportWidthPx = viewportWidthPx
        lastViewportHeightPx = viewportHeightPx

        // While a session is already streaming its remote framebuffer, keep `remoteWidth` and `remoteHeight`
        // locked to the authoritative server framebuffer dimensions (`framebufferBitmap.width` x `height`)
        // so that opening/closing the keyboard dock or toolbars never distorts pointer coordinate normalization.
        updateSession(sessionId) { s ->
            if (s.resolutionMode == ResolutionMode.DYNAMIC_CLIENT &&
                s.phase != SessionConnectionPhase.STREAMING_FRAMEBUFFER &&
                s.framebufferBitmap == null
            ) {
                val (targetW, targetH) = computeDeviceMatchedResolution(
                    viewportWidthPx,
                    viewportHeightPx,
                    s.remoteDpiScalePercent
                )
                s.copy(
                    remoteWidth = targetW,
                    remoteHeight = targetH,
                    profile = s.profile.copy(
                        resolution = "${targetW}x${targetH}",
                        resolutionMode = ResolutionMode.DYNAMIC_CLIENT.name
                    )
                )
            } else {
                s
            }
        }
    }

    /**
     * One-tap helper to snap any active session's resolution and aspect ratio to the tablet/phone viewport.
     */
    fun fitSessionToDeviceViewport(sessionId: String) {
        val current = _activeSessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        val (targetW, targetH) = computeDeviceMatchedResolution(
            lastViewportWidthPx,
            lastViewportHeightPx,
            current.remoteDpiScalePercent
        )
        updateSessionResolutionAndScaling(
            sessionId = sessionId,
            resolutionMode = ResolutionMode.DYNAMIC_CLIENT,
            width = targetW,
            height = targetH,
            scalingMode = ScalingMode.FIT_WINDOW,
            colorDepthBpp = current.colorDepthBpp,
            dpiScalePercent = current.remoteDpiScalePercent,
            monitorCount = current.monitorCount
        )
    }

    fun cycleSessionScalingMode(sessionId: String) {
        updateSession(sessionId) { s ->
            val modes = ScalingMode.entries
            val next = modes[(modes.indexOf(s.scalingMode) + 1) % modes.size]
            s.copy(
                scalingMode = next,
                zoomScale = if (next == ScalingMode.FIT_WINDOW || next == ScalingMode.STRETCH) 1.0f else s.zoomScale,
                panOffsetX = if (next == ScalingMode.FIT_WINDOW || next == ScalingMode.STRETCH) 0f else s.panOffsetX,
                panOffsetY = if (next == ScalingMode.FIT_WINDOW || next == ScalingMode.STRETCH) 0f else s.panOffsetY,
                lastPointerAction = "Scaling: ${next.label}"
            )
        }
    }

    // Touch Gesture & Live Pointer Handlers
    fun toggleSessionInputMode(sessionId: String) {
        updateSession(sessionId) { s ->
            val next = if (s.inputMode == InputControlMode.DIRECT_TOUCH) {
                InputControlMode.TRACKPAD
            } else {
                InputControlMode.DIRECT_TOUCH
            }
            s.copy(inputMode = next, lastPointerAction = "Pointer: ${next.label}")
        }
    }

    fun onSessionTap(sessionId: String, normX: Float, normY: Float) {
        val now = System.currentTimeMillis()
        var targetX = normX.coerceIn(0f, 1f)
        var targetY = normY.coerceIn(0f, 1f)
        var isDoubleTap = false

        updateSession(sessionId) { s ->
            val rawX = if (s.inputMode == InputControlMode.DIRECT_TOUCH) normX.coerceIn(0f, 1f) else s.cursorXNorm
            val rawY = if (s.inputMode == InputControlMode.DIRECT_TOUCH) normY.coerceIn(0f, 1f) else s.cursorYNorm

            // Stabilize rapid finger double-taps: if second tap is within 400ms and 3.2% screen distance,
            // snap to the exact pixel of the first tap so Windows/X11 SM_CXDOUBLECLK registers a double-click!
            val prevTime = lastTapEpochMs[sessionId] ?: 0L
            val prevCoords = lastTapNormCoords[sessionId]
            if (prevCoords != null && (now - prevTime) in 35L..400L) {
                val dist = hypot(rawX - prevCoords.first, rawY - prevCoords.second)
                if (dist <= 0.032f) {
                    targetX = prevCoords.first
                    targetY = prevCoords.second
                    isDoubleTap = true
                } else {
                    targetX = rawX
                    targetY = rawY
                }
            } else {
                targetX = rawX
                targetY = rawY
            }
            lastTapEpochMs[sessionId] = now
            lastTapNormCoords[sessionId] = Pair(targetX, targetY)

            val srvW = (s.framebufferBitmap?.width ?: s.remoteWidth).coerceAtLeast(1)
            val srvH = (s.framebufferBitmap?.height ?: s.remoteHeight).coerceAtLeast(1)
            val px = (targetX * (srvW - 1).coerceAtLeast(1)).roundToInt()
            val py = (targetY * (srvH - 1).coerceAtLeast(1)).roundToInt()
            val actionLabel = if (isDoubleTap) "DblClick ($px, $py)" else "LClick ($px, $py)"

            s.copy(
                cursorXNorm = targetX,
                cursorYNorm = targetY,
                isLeftButtonDragging = false,
                dragStartXNorm = null,
                dragStartYNorm = null,
                showContextMenuAt = null,
                lastPointerAction = actionLabel
            )
        }

        // Send ordered Left Button Move + Down + Up to live RDP or VNC server
        activeRdpClients[sessionId]?.sendMouseClick(targetX, targetY, isRightClick = false)
        activeVncClients[sessionId]?.sendMouseClick(targetX, targetY, isRightClick = false)
    }

    fun onSessionSecondaryClick(sessionId: String, normX: Float? = null, normY: Float? = null) {
        var cx = 0.5f
        var cy = 0.5f
        updateSession(sessionId) { s ->
            cx = (normX ?: s.cursorXNorm).coerceIn(0f, 1f)
            cy = (normY ?: s.cursorYNorm).coerceIn(0f, 1f)
            val srvW = (s.framebufferBitmap?.width ?: s.remoteWidth).coerceAtLeast(1)
            val srvH = (s.framebufferBitmap?.height ?: s.remoteHeight).coerceAtLeast(1)
            val px = (cx * (srvW - 1).coerceAtLeast(1)).roundToInt()
            val py = (cy * (srvH - 1).coerceAtLeast(1)).roundToInt()
            s.copy(
                cursorXNorm = cx,
                cursorYNorm = cy,
                isLeftButtonDragging = false,
                dragStartXNorm = null,
                dragStartYNorm = null,
                showContextMenuAt = null,
                lastPointerAction = "RClick ($px, $py)"
            )
        }

        // Send ordered Right Button Move + Down + Up directly to live RDP or VNC server
        activeRdpClients[sessionId]?.sendMouseClick(cx, cy, isRightClick = true)
        activeVncClients[sessionId]?.sendMouseClick(cx, cy, isRightClick = true)
    }

    fun onSessionMiddleClick(sessionId: String, normX: Float? = null, normY: Float? = null) {
        var cx = 0.5f
        var cy = 0.5f
        updateSession(sessionId) { s ->
            cx = (normX ?: s.cursorXNorm).coerceIn(0f, 1f)
            cy = (normY ?: s.cursorYNorm).coerceIn(0f, 1f)
            val srvW = (s.framebufferBitmap?.width ?: s.remoteWidth).coerceAtLeast(1)
            val srvH = (s.framebufferBitmap?.height ?: s.remoteHeight).coerceAtLeast(1)
            val px = (cx * (srvW - 1).coerceAtLeast(1)).roundToInt()
            val py = (cy * (srvH - 1).coerceAtLeast(1)).roundToInt()
            s.copy(
                cursorXNorm = cx,
                cursorYNorm = cy,
                isLeftButtonDragging = false,
                dragStartXNorm = null,
                dragStartYNorm = null,
                showContextMenuAt = null,
                lastPointerAction = "MClick ($px, $py)"
            )
        }

        activeRdpClients[sessionId]?.sendMouseClick(cx, cy, isRightClick = false, isMiddleClick = true)
        activeVncClients[sessionId]?.sendMouseClick(cx, cy, isRightClick = false, isMiddleClick = true)
    }

    fun onSessionScrollWheel(
        sessionId: String,
        normX: Float,
        normY: Float,
        verticalDelta: Float,
        horizontalDelta: Float = 0f
    ) {
        val cx = normX.coerceIn(0f, 1f)
        val cy = normY.coerceIn(0f, 1f)
        updateSession(sessionId) { s ->
            val srvW = (s.framebufferBitmap?.width ?: s.remoteWidth).coerceAtLeast(1)
            val srvH = (s.framebufferBitmap?.height ?: s.remoteHeight).coerceAtLeast(1)
            val px = (cx * (srvW - 1).coerceAtLeast(1)).roundToInt()
            val py = (cy * (srvH - 1).coerceAtLeast(1)).roundToInt()
            val dir = when {
                verticalDelta > 0.01f -> "Wheel Down"
                verticalDelta < -0.01f -> "Wheel Up"
                horizontalDelta > 0.01f -> "Wheel Right"
                else -> "Wheel Left"
            }
            s.copy(
                cursorXNorm = cx,
                cursorYNorm = cy,
                showContextMenuAt = null,
                lastPointerAction = "$dir ($px, $py)"
            )
        }

        activeRdpClients[sessionId]?.sendMouseWheelScroll(cx, cy, verticalDelta, horizontalDelta)
        activeVncClients[sessionId]?.sendMouseWheelScroll(cx, cy, verticalDelta, horizontalDelta)
    }

    fun onSessionMouseHover(sessionId: String, normX: Float, normY: Float) {
        val cx = normX.coerceIn(0f, 1f)
        val cy = normY.coerceIn(0f, 1f)
        updateSession(sessionId) { s ->
            if (kotlin.math.abs(s.cursorXNorm - cx) < 0.0015f && kotlin.math.abs(s.cursorYNorm - cy) < 0.0015f) {
                return@updateSession s
            }
            val srvW = (s.framebufferBitmap?.width ?: s.remoteWidth).coerceAtLeast(1)
            val srvH = (s.framebufferBitmap?.height ?: s.remoteHeight).coerceAtLeast(1)
            val px = (cx * (srvW - 1).coerceAtLeast(1)).roundToInt()
            val py = (cy * (srvH - 1).coerceAtLeast(1)).roundToInt()
            s.copy(
                cursorXNorm = cx,
                cursorYNorm = cy,
                lastPointerAction = "Move ($px, $py)"
            )
        }
        activeRdpClients[sessionId]?.sendMouseInput(cx, cy, 0x0800) // PTRFLAGS_MOVE
        activeVncClients[sessionId]?.sendPointerEvent(cx, cy, 0)
    }

    fun dismissContextMenu(sessionId: String) {
        updateSession(sessionId) { it.copy(showContextMenuAt = null) }
    }

    /**
     * Initiates a touch-drag operation on the remote desktop (e.g. grabbing a terminal title bar to move it,
     * resizing a window border, or selecting text).
     * In `DIRECT_TOUCH` mode, presses Left Mouse Button DOWN at `(startNormX, startNormY)`.
     * In `TRACKPAD` mode, if started within 450ms of a tap (tap-and-drag gesture), presses Left Mouse Button DOWN
     * at the current virtual cursor position; otherwise moves the virtual cursor relatively.
     */
    fun onSessionDragStart(sessionId: String, startNormX: Float, startNormY: Float) {
        val now = System.currentTimeMillis()
        var holdLeftButton = true
        var targetX = startNormX.coerceIn(0f, 1f)
        var targetY = startNormY.coerceIn(0f, 1f)

        updateSession(sessionId) { s ->
            if (s.inputMode == InputControlMode.DIRECT_TOUCH) {
                holdLeftButton = true
                targetX = startNormX.coerceIn(0f, 1f)
                targetY = startNormY.coerceIn(0f, 1f)
            } else {
                // In TRACKPAD mode, tap-then-drag (within 450ms of a tap) grabs and drags windows
                val prevTap = lastTapEpochMs[sessionId] ?: 0L
                holdLeftButton = (now - prevTap) <= 450L
                targetX = s.cursorXNorm
                targetY = s.cursorYNorm
            }
            val srvW = (s.framebufferBitmap?.width ?: s.remoteWidth).coerceAtLeast(1)
            val srvH = (s.framebufferBitmap?.height ?: s.remoteHeight).coerceAtLeast(1)
            val px = (targetX * (srvW - 1).coerceAtLeast(1)).roundToInt()
            val py = (targetY * (srvH - 1).coerceAtLeast(1)).roundToInt()

            s.copy(
                cursorXNorm = targetX,
                cursorYNorm = targetY,
                isLeftButtonDragging = holdLeftButton,
                dragStartXNorm = if (holdLeftButton) targetX else null,
                dragStartYNorm = if (holdLeftButton) targetY else null,
                showContextMenuAt = null,
                lastPointerAction = if (holdLeftButton) "Drag Hold ($px, $py)" else "Trackpad ($px, $py)"
            )
        }

        if (holdLeftButton) {
            activeRdpClients[sessionId]?.sendMouseDragStart(targetX, targetY)
            activeVncClients[sessionId]?.sendMouseDragStart(targetX, targetY)
        }
    }

    /**
     * Streams pointer movement during a drag gesture.
     * In `DIRECT_TOUCH` mode, maps directly to `(absNormX, absNormY)` while holding Left Mouse Button down.
     * In `TRACKPAD` mode, applies relative `(deltaXNorm, deltaYNorm)` while either holding Left Mouse Button
     * (if tap-and-drag is active) or moving the cursor freely.
     */
    fun onSessionDragMove(
        sessionId: String,
        absNormX: Float,
        absNormY: Float,
        deltaXNorm: Float,
        deltaYNorm: Float
    ) {
        var nx = absNormX.coerceIn(0f, 1f)
        var ny = absNormY.coerceIn(0f, 1f)
        var isDraggingWithLeftButton = true

        updateSession(sessionId) { s ->
            isDraggingWithLeftButton = s.isLeftButtonDragging
            if (s.inputMode == InputControlMode.DIRECT_TOUCH) {
                nx = absNormX.coerceIn(0f, 1f)
                ny = absNormY.coerceIn(0f, 1f)
            } else {
                val scale = 1.25f
                nx = (s.cursorXNorm + deltaXNorm * scale).coerceIn(0f, 1f)
                ny = (s.cursorYNorm + deltaYNorm * scale).coerceIn(0f, 1f)
            }
            val srvW = (s.framebufferBitmap?.width ?: s.remoteWidth).coerceAtLeast(1)
            val srvH = (s.framebufferBitmap?.height ?: s.remoteHeight).coerceAtLeast(1)
            val px = (nx * (srvW - 1).coerceAtLeast(1)).roundToInt()
            val py = (ny * (srvH - 1).coerceAtLeast(1)).roundToInt()

            s.copy(
                cursorXNorm = nx,
                cursorYNorm = ny,
                lastPointerAction = if (isDraggingWithLeftButton) "Dragging ($px, $py)" else "Pointer ($px, $py)"
            )
        }

        if (isDraggingWithLeftButton) {
            activeRdpClients[sessionId]?.sendMouseDragMove(nx, ny)
            activeVncClients[sessionId]?.sendMouseDragMove(nx, ny)
        } else {
            activeRdpClients[sessionId]?.sendMouseInput(nx, ny, 0x0800) // PTRFLAGS_MOVE
            activeVncClients[sessionId]?.sendPointerEvent(nx, ny, 0)
        }
    }

    /**
     * Ends a drag gesture and releases the Left Mouse Button on the remote RDP/VNC server if it was held.
     */
    fun onSessionDragEnd(sessionId: String, endNormX: Float? = null, endNormY: Float? = null) {
        var finalX = 0.5f
        var finalY = 0.5f
        var wasDraggingWithLeftButton = false

        updateSession(sessionId) { s ->
            wasDraggingWithLeftButton = s.isLeftButtonDragging
            finalX = if (s.inputMode == InputControlMode.DIRECT_TOUCH && endNormX != null) {
                endNormX.coerceIn(0f, 1f)
            } else {
                s.cursorXNorm
            }
            finalY = if (s.inputMode == InputControlMode.DIRECT_TOUCH && endNormY != null) {
                endNormY.coerceIn(0f, 1f)
            } else {
                s.cursorYNorm
            }
            val srvW = (s.framebufferBitmap?.width ?: s.remoteWidth).coerceAtLeast(1)
            val srvH = (s.framebufferBitmap?.height ?: s.remoteHeight).coerceAtLeast(1)
            val px = (finalX * (srvW - 1).coerceAtLeast(1)).roundToInt()
            val py = (finalY * (srvH - 1).coerceAtLeast(1)).roundToInt()

            s.copy(
                cursorXNorm = finalX,
                cursorYNorm = finalY,
                isLeftButtonDragging = false,
                dragStartXNorm = null,
                dragStartYNorm = null,
                lastPointerAction = if (wasDraggingWithLeftButton) "Drop ($px, $py)" else "Pointer ($px, $py)"
            )
        }

        if (wasDraggingWithLeftButton) {
            activeRdpClients[sessionId]?.sendMouseDragEnd(finalX, finalY)
            activeVncClients[sessionId]?.sendMouseDragEnd(finalX, finalY)
        }
    }

    fun onSessionDragOrTrackpadMove(sessionId: String, deltaXNorm: Float, deltaYNorm: Float) {
        val current = _activeSessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        val nextX = (current.cursorXNorm + deltaXNorm).coerceIn(0f, 1f)
        val nextY = (current.cursorYNorm + deltaYNorm).coerceIn(0f, 1f)
        onSessionDragMove(sessionId, nextX, nextY, deltaXNorm, deltaYNorm)
    }

    fun onSessionZoomAndPan(sessionId: String, zoomMultiplier: Float, panDeltaX: Float, panDeltaY: Float) {
        updateSession(sessionId) { s ->
            val newZoom = (s.zoomScale * zoomMultiplier).coerceIn(0.5f, 3.5f)
            val newPanX = if (newZoom <= 1.02f) 0f else (s.panOffsetX + panDeltaX).coerceIn(-800f, 800f)
            val newPanY = if (newZoom <= 1.02f) 0f else (s.panOffsetY + panDeltaY).coerceIn(-600f, 600f)
            s.copy(
                scalingMode = ScalingMode.CUSTOM_ZOOM,
                zoomScale = newZoom,
                panOffsetX = newPanX,
                panOffsetY = newPanY,
                lastPointerAction = "Zoom ${(newZoom * 100).toInt()}%"
            )
        }
    }

    fun resetSessionZoom(sessionId: String) {
        updateSession(sessionId) {
            it.copy(
                scalingMode = ScalingMode.FIT_WINDOW,
                zoomScale = 1.0f,
                panOffsetX = 0f,
                panOffsetY = 0f,
                lastPointerAction = "Fit to Window (100%)"
            )
        }
    }

    fun toggleStickyModifier(sessionId: String, modifierName: String) {
        updateSession(sessionId) { s ->
            when (modifierName) {
                "CTRL" -> s.copy(stickyCtrl = !s.stickyCtrl)
                "ALT" -> s.copy(stickyAlt = !s.stickyAlt)
                "SHIFT" -> s.copy(stickyShift = !s.stickyShift)
                "SUPER" -> s.copy(stickySuper = !s.stickySuper)
                else -> s
            }
        }
    }

    fun toggleSftpPanel(sessionId: String) {
        var shouldFetch = false
        updateSession(sessionId) {
            val next = !it.showSftpPanel
            shouldFetch = next
            it.copy(showSftpPanel = next)
        }
        if (shouldFetch) {
            activeSshClients[sessionId]?.requestSftpDirectoryListing(".")
        }
    }

    /**
     * Handles physical hardware keyboard KeyDown and KeyUp events directly so holding Shift, Ctrl,
     * Alt, Super/Windows, or repeating keys on a real keyboard works seamlessly on the remote VM.
     */
    fun dispatchHardwareKeyDownOrUp(
        sessionId: String,
        keyCode: Int,
        unicodeChar: Int = 0,
        isKeyUp: Boolean = false,
        hwCtrl: Boolean = false,
        hwAlt: Boolean = false,
        hwShift: Boolean = false,
        hwSuper: Boolean = false
    ) {
        val session = _activeSessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        val currentMappings = allKeyMappings.value

        val isModifierKey = keyCode in listOf(
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT
        )

        if (session.protocol == RemoteProtocol.RDP) {
            val rdp = activeRdpClients[sessionId] ?: return
            // Remmina Host Key Shortcut on KeyDown: Ctrl+Alt+F or F11 toggles Fullscreen Mode
            if (!isKeyUp && ((hwCtrl && hwAlt && keyCode == KeyEvent.KEYCODE_F) || keyCode == KeyEvent.KEYCODE_F11)) {
                toggleSessionFullScreen(sessionId)
                return
            }

            // If sticky on-screen modifiers are armed on KeyDown, include them via atomic chord
            if (!isKeyUp && !isModifierKey && (session.stickyCtrl || session.stickyAlt || session.stickyShift || session.stickySuper)) {
                dispatchKeyEventToSession(sessionId, keyCode, unicodeChar, hwCtrl, hwAlt, hwShift, hwSuper)
                return
            }

            val packet = HardwareKeyboardEngine.translateKeyEvent(
                keyCode = keyCode,
                unicodeChar = unicodeChar,
                protocol = RemoteProtocol.RDP,
                stickyCtrl = hwCtrl,
                stickyAlt = hwAlt,
                stickyShift = hwShift,
                stickySuper = hwSuper,
                customMappings = currentMappings
            )
            val scancodeInt = packet.rdpScancodeHex.removePrefix("0x").toIntOrNull(16) ?: 0
            if (scancodeInt != 0) {
                rdp.sendKeyboardScancode(
                    scancode = scancodeInt and 0xFF,
                    isExtended = packet.rdpExtended,
                    isRelease = isKeyUp
                )
                if (!isKeyUp) {
                    _lastTestedKeyPacket.value = packet
                    updateSession(sessionId) { s ->
                        val updatedKeyLog = (listOf(packet) + s.lastTranslatedKeys).take(8)
                        s.copy(
                            lastTranslatedKeys = updatedKeyLog,
                            lastPointerAction = "Key ${packet.keyLabel} (${packet.rdpScancodeHex})"
                        )
                    }
                }
            } else if (!isKeyUp && unicodeChar >= 32) {
                // Unmapped international/symbol character on hardware keyboard -> send via Unicode
                rdp.sendTextString(unicodeChar.toChar().toString())
            }
            return
        }

        // For VNC and SSH, dispatch on KeyDown
        if (!isKeyUp) {
            dispatchKeyEventToSession(sessionId, keyCode, unicodeChar, hwCtrl, hwAlt, hwShift, hwSuper)
        }
    }
    fun dispatchKeyEventToSession(
        sessionId: String,
        keyCode: Int,
        unicodeChar: Int = 0,
        hwCtrl: Boolean = false,
        hwAlt: Boolean = false,
        hwShift: Boolean = false,
        hwSuper: Boolean = false
    ) {
        val currentMappings = allKeyMappings.value
        var packetToSend: TranslatedKeyPacket? = null
        var sessionProtocol: RemoteProtocol = RemoteProtocol.RDP

        updateSession(sessionId) { s ->
            sessionProtocol = s.protocol
            val effectiveCtrl = s.stickyCtrl || hwCtrl
            val effectiveAlt = s.stickyAlt || hwAlt
            val effectiveShift = s.stickyShift || hwShift
            val effectiveSuper = s.stickySuper || hwSuper

            // Remmina Host Key Shortcut: Ctrl+Alt+F or F11 toggles Fullscreen Mode
            if ((effectiveCtrl && effectiveAlt && keyCode == KeyEvent.KEYCODE_F) || keyCode == KeyEvent.KEYCODE_F11) {
                val nextFs = !s.isFullScreen
                return@updateSession s.copy(
                    isFullScreen = nextFs,
                    stickyCtrl = false,
                    stickyAlt = false,
                    lastPointerAction = if (nextFs) "Fullscreen (F11 / Ctrl+Alt+F)" else "Windowed Mode"
                )
            }

            val packet = HardwareKeyboardEngine.translateKeyEvent(
                keyCode = keyCode,
                unicodeChar = unicodeChar,
                protocol = s.protocol,
                stickyCtrl = effectiveCtrl,
                stickyAlt = effectiveAlt,
                stickyShift = effectiveShift,
                stickySuper = effectiveSuper,
                customMappings = currentMappings
            )
            packetToSend = packet

            _lastTestedKeyPacket.value = packet
            val updatedKeyLog = (listOf(packet) + s.lastTranslatedKeys).take(8)

            val isModifierOnly = keyCode in listOf(
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
                KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
                KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT
            )

            if (!isModifierOnly) {
                s.copy(
                    stickyCtrl = false,
                    stickyAlt = false,
                    stickyShift = false,
                    stickySuper = false,
                    lastTranslatedKeys = updatedKeyLog,
                    lastPointerAction = "Key ${packet.keyLabel} (${packet.rdpScancodeHex})"
                )
            } else {
                s.copy(lastTranslatedKeys = updatedKeyLog)
            }
        }

        val pkt = packetToSend ?: return
        when (sessionProtocol) {
            RemoteProtocol.RDP -> {
                val rdp = activeRdpClients[sessionId] ?: return
                val scancodeInt = pkt.rdpScancodeHex.removePrefix("0x").toIntOrNull(16) ?: return
                rdp.sendKeyChord(
                    scancode = scancodeInt and 0xFF,
                    isExtended = pkt.rdpExtended,
                    ctrl = pkt.ctrlActive,
                    alt = pkt.altActive,
                    shift = pkt.shiftActive,
                    meta = pkt.superActive
                )
            }

            RemoteProtocol.VNC -> {
                val vnc = activeVncClients[sessionId] ?: return
                val keySym = pkt.vncKeySymHex.removePrefix("0x").toIntOrNull(16) ?: return
                if (pkt.ctrlActive) vnc.sendKeySymEvent(0xFFE3, true)
                if (pkt.altActive) vnc.sendKeySymEvent(0xFFE9, true)
                if (pkt.shiftActive) vnc.sendKeySymEvent(0xFFE1, true)
                vnc.sendKeySymEvent(keySym, true)
                vnc.sendKeySymEvent(keySym, false)
                if (pkt.shiftActive) vnc.sendKeySymEvent(0xFFE1, false)
                if (pkt.altActive) vnc.sendKeySymEvent(0xFFE9, false)
                if (pkt.ctrlActive) vnc.sendKeySymEvent(0xFFE3, false)
            }

            RemoteProtocol.SSH -> {
                activeSshClients[sessionId]?.sendRawText(pkt.sshRawPayload)
            }

            RemoteProtocol.SPICE -> {}
        }
    }

    /**
     * Dispatches characters or text strings typed via the Android Soft Keyboard (IME) or Quick Text bar
     * directly to the active RDP, VNC, or SSH session.
     */
    fun dispatchTypedTextToSession(sessionId: String, text: String) {
        if (text.isEmpty()) return
        val session = _activeSessions.value.firstOrNull { it.sessionId == sessionId } ?: return

        // If sticky modifiers (Ctrl/Alt/Super) are armed and a single char was typed, route through keychord dispatcher
        if (text.length == 1 && (session.stickyCtrl || session.stickyAlt || session.stickySuper)) {
            val ch = text[0]
            val keyCode = when (ch.lowercaseChar()) {
                in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch.lowercaseChar() - 'a')
                in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
                else -> KeyEvent.KEYCODE_UNKNOWN
            }
            dispatchKeyEventToSession(
                sessionId = sessionId,
                keyCode = keyCode,
                unicodeChar = ch.code,
                hwShift = ch.isUpperCase()
            )
            return
        }

        var stickyShiftWasOn = false
        updateSession(sessionId) { s ->
            stickyShiftWasOn = s.stickyShift
            val effectiveText = if (s.stickyShift) text.uppercase() else text
            val preview = effectiveText.replace("\n", "↵").take(14)
            s.copy(
                stickyShift = false,
                lastPointerAction = "Typed \"$preview\""
            )
        }
        val finalText = if (stickyShiftWasOn) text.uppercase() else text

        when (session.protocol) {
            RemoteProtocol.RDP -> {
                activeRdpClients[sessionId]?.sendTextString(finalText)
            }
            RemoteProtocol.VNC -> {
                val vnc = activeVncClients[sessionId] ?: return
                for (ch in finalText) {
                    val sym = when (ch) {
                        '\n', '\r' -> 0xFF0D
                        '\b' -> 0xFF08
                        '\t' -> 0xFF09
                        else -> ch.code
                    }
                    vnc.sendKeySymEvent(sym, true)
                    vnc.sendKeySymEvent(sym, false)
                }
            }
            RemoteProtocol.SSH -> {
                activeSshClients[sessionId]?.sendRawText(finalText)
            }
            RemoteProtocol.SPICE -> {}
        }
    }

    fun sendSshCommandString(sessionId: String, command: String) {
        activeSshClients[sessionId]?.sendRawText(command + "\n")
    }

    fun sendSpecialChordMacro(sessionId: String, macroName: String) {
        when (macroName) {
            "CTRL_ALT_DEL" -> {
                dispatchKeyEventToSession(
                    sessionId = sessionId,
                    keyCode = KeyEvent.KEYCODE_FORWARD_DEL,
                    hwCtrl = true,
                    hwAlt = true
                )
            }
            "ALT_TAB" -> {
                dispatchKeyEventToSession(
                    sessionId = sessionId,
                    keyCode = KeyEvent.KEYCODE_TAB,
                    hwAlt = true
                )
            }
            "SUPER" -> {
                dispatchKeyEventToSession(
                    sessionId = sessionId,
                    keyCode = KeyEvent.KEYCODE_META_LEFT,
                    hwSuper = true
                )
            }
            "CTRL_C" -> {
                dispatchKeyEventToSession(
                    sessionId = sessionId,
                    keyCode = KeyEvent.KEYCODE_C,
                    hwCtrl = true
                )
            }
        }
    }

    fun testHardwareKeyEvent(
        keyCode: Int,
        unicodeChar: Int,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        meta: Boolean,
        protocol: RemoteProtocol = RemoteProtocol.RDP
    ) {
        val packet = HardwareKeyboardEngine.translateKeyEvent(
            keyCode = keyCode,
            unicodeChar = unicodeChar,
            protocol = protocol,
            stickyCtrl = ctrl,
            stickyAlt = alt,
            stickyShift = shift,
            stickySuper = meta,
            customMappings = allKeyMappings.value
        )
        _lastTestedKeyPacket.value = packet
    }

    fun generateNewSshKey(alias: String, algorithm: String, passphraseProtected: Boolean) {
        viewModelScope.launch {
            val created = repository.generateSshKey(alias, algorithm, passphraseProtected)
            _statusBannerMessage.value = "Generated ${created.algorithm} key '${created.alias}'"
        }
    }

    fun deleteSshKey(key: SshKeyEntity) {
        viewModelScope.launch {
            repository.deleteSshKey(key)
            _statusBannerMessage.value = "Removed SSH key '${key.alias}'"
        }
    }

    fun saveKeyMapping(mapping: KeyMappingEntity) {
        viewModelScope.launch {
            repository.saveKeyMapping(mapping)
            _statusBannerMessage.value = "Saved hardware key mapping '${mapping.name}'"
        }
    }

    fun toggleKeyMapping(mapping: KeyMappingEntity) {
        viewModelScope.launch {
            repository.toggleKeyMapping(mapping)
        }
    }

    fun deleteKeyMapping(mapping: KeyMappingEntity) {
        viewModelScope.launch {
            repository.deleteKeyMapping(mapping)
            _statusBannerMessage.value = "Deleted key mapping '${mapping.name}'"
        }
    }

    fun importRemminaIniProfile(iniContent: String) {
        viewModelScope.launch {
            val imported = repository.importFromRemminaIni(iniContent)
            if (imported.isNotEmpty()) {
                _statusBannerMessage.value = "Imported ${imported.size} .remmina profile(s): ${imported.joinToString { it.name }}"
            } else {
                _statusBannerMessage.value = "Invalid .remmina config (missing server= field)"
            }
        }
    }

    fun importRemminaConfigFilesFromUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var totalImported = 0
            val importedNames = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    try {
                        val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        if (!content.isNullOrBlank()) {
                            val trimmed = content.trim()
                            if (trimmed.startsWith("{")) {
                                totalImported += repository.importLocalPortableBundle(trimmed)
                            } else {
                                val list = repository.importFromRemminaIni(trimmed)
                                totalImported += list.size
                                list.forEach { importedNames.add(it.name) }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            if (totalImported > 0) {
                val suffix = if (importedNames.isNotEmpty()) " (${importedNames.take(3).joinToString()})" else ""
                _statusBannerMessage.value = "Imported $totalImported Remmina configuration file(s)$suffix"
            } else {
                _statusBannerMessage.value = "Could not parse any valid .remmina profiles from selected file(s)"
            }
        }
    }

    fun importPortableBundle(jsonString: String) {
        viewModelScope.launch {
            try {
                val count = repository.importLocalPortableBundle(jsonString)
                _statusBannerMessage.value = "Imported $count profiles into local database"
            } catch (e: Exception) {
                _statusBannerMessage.value = "Bundle import error: ${e.message}"
            }
        }
    }

    private fun updateSession(sessionId: String, transform: (ActiveRemoteSession) -> ActiveRemoteSession) {
        _activeSessions.update { list ->
            list.map { if (it.sessionId == sessionId) transform(it) else it }
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeRdpClients.values.forEach { it.disconnect() }
        activeVncClients.values.forEach { it.disconnect() }
        activeSshClients.values.forEach { it.disconnect() }
        activeSshTunnels.values.forEach { it.close() }
    }
}
