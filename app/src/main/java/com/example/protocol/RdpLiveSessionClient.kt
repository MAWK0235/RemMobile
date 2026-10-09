package com.example.protocol

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.data.local.ConnectionProfileEntity
import com.example.keyboard.HardwareKeyboardEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

interface RdpSessionCallbacks {
    fun onTraceLog(line: String)
    fun onPhaseChanged(phase: SessionConnectionPhase, banner: String)
    fun onTlsCertificateDiscovered(subject: String, sha256Fingerprint: String)
    fun onAuthenticationRequired(reason: String, certSubject: String?, certFingerprint: String?)
    fun onResolutionNegotiated(width: Int, height: Int, bpp: Int)
    fun onFramebufferUpdated(bitmap: ImageBitmap, frameCount: Int, bitrateMbps: Float)
    fun onFramebufferUpdatedWithMetrics(
        bitmap: ImageBitmap,
        frameCount: Int,
        bitrateMbps: Float,
        measuredFps: Int,
        coalescedTiles: Int
    ) {
        onFramebufferUpdated(bitmap, frameCount, bitrateMbps)
    }
    fun onDisconnected(errorTitle: String, errorDetails: String)
}

/**
 * Android-Compatible FreeRDP Wrapper (`LibFreeRdpAndroidWrapper`).
 *
 * Bridges the FreeRDP Android (`libfreerdp-android` / `freerdp2`) session lifecycle
 * (`freerdp_new`, `freerdp_context_new`, `freerdp_nego_connect`, `freerdp_tls_connect`,
 * `freerdp_nla_connect`, `freerdp_mcs_connect`, `freerdp_licensing`, `freerdp_capabilities`,
 * `freerdp_send_input_scancode`, `freerdp_send_input_mouse`).
 * Automatically probes for native JNI `libfreerdp-android.so` if packaged, and executes the
 * full MS-RDPBCGR / CredSSP protocol state machine directly over Android sockets.
 */
object LibFreeRdpAndroidWrapper {
    val isNativeJniLoaded: Boolean = try {
        System.loadLibrary("freerdp-android")
        true
    } catch (_: Throwable) {
        false
    }

    enum class FreeRdpConnectionState(val description: String) {
        INITIAL("INITIAL"),
        NEGO_X224("NEGO_X224_CONNECT"),
        TLS_HANDSHAKE("TLS_HANDSHAKE"),
        NLA_CREDSSP("NLA_CREDSSP_AUTH"),
        MCS_CONNECT_INITIAL("MCS_CONNECT_INITIAL"),
        MCS_ERECT_AND_ATTACH("MCS_ERECT_DOMAIN_AND_ATTACH_USER"),
        MCS_CHANNEL_JOIN("MCS_CHANNEL_JOIN"),
        SECURITY_EXCHANGE("STANDARD_RDP_SEC_EXCHANGE"),
        CLIENT_INFO("CLIENT_INFO_PDU"),
        LICENSING_AND_DEMAND_ACTIVE("LICENSING_AND_CAPABILITIES_EXCHANGE"),
        FINALIZATION("CONNECTION_FINALIZATION"),
        ACTIVE_STREAMING("ACTIVE_FRAMEBUFFER_STREAM")
    }

    const val PROTOCOL_RDP = 0x00
    const val PROTOCOL_SSL = 0x01
    const val PROTOCOL_HYBRID = 0x02
}

/**
 * Persistent FreeRDP-Compatible Live Session Client for Android.
 */
class RdpLiveSessionClient(
    private val scope: CoroutineScope,
    private val connectHost: String,
    private val connectPort: Int,
    private val profile: ConnectionProfileEntity,
    private val requestedWidth: Int,
    private val requestedHeight: Int,
    private val callbacks: RdpSessionCallbacks
) {
    @Volatile
    private var rawSocket: Socket? = null

    @Volatile
    private var activeInput: InputStream? = null

    @Volatile
    private var activeOutput: OutputStream? = null

    @Volatile
    private var userChannelId: Int = 1007

    @Volatile
    private var ioChannelId: Int = 1003

    @Volatile
    private var shareId: Int = 0x000103EA

    @Volatile
    private var selectedProtocol: Int = LibFreeRdpAndroidWrapper.PROTOCOL_SSL

    @Volatile
    private var standardRdpKeys: RdpCryptoAndBitmapEngine.StandardRdpSecurityKeys? = null

    @Volatile
    private var standardRdpEncryptCount: Int = 0

    @Volatile
    private var isConnectedAndActive: Boolean = false

    @Volatile
    private var currentState: LibFreeRdpAndroidWrapper.FreeRdpConnectionState =
        LibFreeRdpAndroidWrapper.FreeRdpConnectionState.INITIAL

    private var sessionJob: Job? = null
    private var inputWorkerJob: Job? = null
    private val inputQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val writeLock = Any()
    private var lastEventTimeMs: Int = 1

    // Negotiate 24-bit TrueColor (or 16-bit if configured) wire format so Windows/xrdp send lossless
    // Interleaved RLE bitmaps (while still decoding 32-bit Planar if sent by the server).
    // Align width to multiple of 4 and height to multiple of 2, supporting both portrait & landscape phones/tablets.
    private var fbWidth: Int = ((requestedWidth.coerceIn(360, 4096) + 2) / 4) * 4
    private var fbHeight: Int = ((requestedHeight.coerceIn(360, 4096) + 1) / 2) * 2
    private var fbBpp: Int = profile.colorDepth.coerceIn(15, 24)
    private var framebufferPixels: IntArray = IntArray(fbWidth * fbHeight) { 0xFF0A1622.toInt() }
    private val frameCompositor = HardwareAcceleratedFrameCompositor(fbWidth, fbHeight)
    private var decodedFrames: Int = 0
    private var bytesReceivedTotal: Long = 0L
    private var streamStartEpochMs: Long = System.currentTimeMillis()

    // Reassembly buffer for fragmented Fast-Path updates (FASTPATH_FRAGMENT_FIRST / NEXT / LAST)
    private val fastPathFragmentBuffer = ByteArrayOutputStream()
    private var fastPathFragmentUpdateCode: Int = -1

    fun start() {
        inputWorkerJob = scope.launch(Dispatchers.IO) {
            for (action in inputQueue) {
                if (!isActive) break
                try {
                    action()
                } catch (_: Exception) {
                }
            }
        }
        sessionJob = scope.launch(Dispatchers.IO) {
            runSession()
        }
    }

    fun disconnect() {
        isConnectedAndActive = false
        inputWorkerJob?.cancel()
        sessionJob?.cancel()
        closeActiveSockets()
    }

    private fun closeActiveSockets() {
        try {
            activeOutput?.close()
        } catch (_: Exception) {
        }
        try {
            activeInput?.close()
        } catch (_: Exception) {
        }
        try {
            rawSocket?.close()
        } catch (_: Exception) {
        }
        activeOutput = null
        activeInput = null
        rawSocket = null
    }

    private suspend fun runSession() = withContext(Dispatchers.IO) {
        var certSubject: String? = null
        var certFingerprint: String? = null

        try {
            val backendLabel = if (LibFreeRdpAndroidWrapper.isNativeJniLoaded) "libfreerdp-android JNI" else "FreeRDP Android Core"
            callbacks.onTraceLog("[freerdp] Initializing $backendLabel context for $connectHost:$connectPort...")

            val mode = profile.securityMode.uppercase()
            val hasUsername = profile.username.isNotBlank()
            val hasPassword = profile.password.isNotEmpty()
            val hasFullCredentials = hasUsername && hasPassword

            // Determine initial requestedProtocols bitmask for X.224 RDP_NEG_REQ:
            // - If NLA is explicitly selected but credentials are missing, probe TLS cert or prompt immediately.
            // - If full credentials are provided, offer HYBRID (NLA) + SSL (0x03) unless user forced TLS or RDP.
            // - If no password is provided yet, offer SSL (0x01) first so TLS servers stream their graphical login
            //   screen and NLA-only servers reply with HYBRID_REQUIRED_BY_SERVER (0x05) to trigger the auth dialog.
            var requestedProtocols = when (mode) {
                "RDP" -> LibFreeRdpAndroidWrapper.PROTOCOL_RDP
                "TLS" -> LibFreeRdpAndroidWrapper.PROTOCOL_SSL
                "NLA" -> LibFreeRdpAndroidWrapper.PROTOCOL_SSL or LibFreeRdpAndroidWrapper.PROTOCOL_HYBRID
                else -> {
                    if (hasFullCredentials) {
                        LibFreeRdpAndroidWrapper.PROTOCOL_SSL or LibFreeRdpAndroidWrapper.PROTOCOL_HYBRID
                    } else {
                        LibFreeRdpAndroidWrapper.PROTOCOL_SSL
                    }
                }
            }

            // Perform X.224 negotiation with automatic FreeRDP fallback if server demands a different security mode
            var negoResult = executeTcpAndX224Negotiation(requestedProtocols)
            if (negoResult.negType == 0x03) {
                val failCode = negoResult.failureCode
                val failDesc = rdpNegFailureDescription(failCode)
                callbacks.onTraceLog("[freerdp] Server returned RDP_NEG_FAILURE: $failDesc (0x${failCode.toString(16)})")
                closeActiveSockets()

                when (failCode) {
                    0x05, 0x06 -> {
                        // HYBRID_REQUIRED_BY_SERVER (0x05) or SSL_WITH_USER_AUTH_REQUIRED_BY_SERVER (0x06)
                        if (!hasFullCredentials) {
                            callbacks.onTraceLog("[freerdp] Server $connectHost:$connectPort requires NLA (CredSSP) credentials.")
                            callbacks.onAuthenticationRequired(
                                reason = "Server $connectHost:$connectPort requires Network Level Authentication (NLA / CredSSP). Please enter your Windows/RDP Username and Password.",
                                certSubject = certSubject,
                                certFingerprint = certFingerprint
                            )
                            return@withContext
                        } else if ((requestedProtocols and LibFreeRdpAndroidWrapper.PROTOCOL_HYBRID) == 0) {
                            // User had credentials but selected TLS/RDP mode -> auto-fallback to NLA (0x03)!
                            callbacks.onTraceLog("[freerdp] Auto-switching to NLA / CredSSP (PROTOCOL_HYBRID) and reconnecting...")
                            requestedProtocols = LibFreeRdpAndroidWrapper.PROTOCOL_SSL or LibFreeRdpAndroidWrapper.PROTOCOL_HYBRID
                            negoResult = executeTcpAndX224Negotiation(requestedProtocols)
                        } else {
                            throw IllegalStateException("RDP Security Negotiation Rejected: $failDesc")
                        }
                    }

                    0x02, 0x03 -> {
                        // SSL_NOT_ALLOWED_BY_SERVER (0x02) or SSL_CERT_NOT_ON_SERVER (0x03) -> auto-fallback to Standard RDP (0x00)!
                        callbacks.onTraceLog("[freerdp] Server does not allow TLS ($failDesc). Falling back to Standard RDP Security (0x00)...")
                        requestedProtocols = LibFreeRdpAndroidWrapper.PROTOCOL_RDP
                        negoResult = executeTcpAndX224Negotiation(requestedProtocols)
                    }

                    0x01 -> {
                        // SSL_REQUIRED_BY_SERVER (0x01) -> auto-fallback to TLS (0x01)
                        callbacks.onTraceLog("[freerdp] Server requires TLS. Reconnecting with PROTOCOL_SSL (0x01)...")
                        requestedProtocols = LibFreeRdpAndroidWrapper.PROTOCOL_SSL
                        negoResult = executeTcpAndX224Negotiation(requestedProtocols)
                    }

                    else -> throw IllegalStateException("RDP Security Negotiation Rejected: $failDesc")
                }
            }

            if (negoResult.negType == 0x03) {
                throw IllegalStateException("RDP Security Negotiation Rejected: ${rdpNegFailureDescription(negoResult.failureCode)}")
            }

            selectedProtocol = negoResult.selectedProtocol
            val tcpSocket = rawSocket ?: throw IllegalStateException("Socket closed unexpectedly after X.224 confirm")

            callbacks.onTraceLog(
                "[freerdp] X.224 Confirm: selectedProtocol=0x${selectedProtocol.toString(16)} (${
                    when (selectedProtocol) {
                        LibFreeRdpAndroidWrapper.PROTOCOL_RDP -> "Standard RDP RC4 Security"
                        LibFreeRdpAndroidWrapper.PROTOCOL_SSL -> "TLS Security"
                        LibFreeRdpAndroidWrapper.PROTOCOL_HYBRID -> "NLA / CredSSP (TLS + NTLMv2)"
                        else -> "Extended RDP"
                    }
                })"
            )

            // Stage 1B: Upgrade to TLS 1.2 if selectedProtocol is SSL (0x01) or HYBRID (0x02)
            if (selectedProtocol == LibFreeRdpAndroidWrapper.PROTOCOL_SSL ||
                selectedProtocol == LibFreeRdpAndroidWrapper.PROTOCOL_HYBRID
            ) {
                currentState = LibFreeRdpAndroidWrapper.FreeRdpConnectionState.TLS_HANDSHAKE
                callbacks.onPhaseChanged(
                    SessionConnectionPhase.NEGOTIATING_SECURITY,
                    "Upgrading to TLS 1.2 ($connectHost:$connectPort)..."
                )
                callbacks.onTraceLog("[tls] Starting TLS handshake with $connectHost:$connectPort (SNI-safe, 10s timeout)...")

                val sslSocket = upgradeSocketToTls(tcpSocket, connectHost, connectPort)
                val peerCert = try {
                    sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                } catch (_: Exception) {
                    null
                }
                if (peerCert != null) {
                    certSubject = peerCert.subjectX500Principal.name
                    val shaBytes = RdpCryptoAndBitmapEngine.sha256(peerCert.encoded)
                    certFingerprint = "SHA256:" + shaBytes.take(16).joinToString(":") { "%02X".format(it) }
                    callbacks.onTlsCertificateDiscovered(certSubject!!, certFingerprint!!)
                    if (!profile.ignoreCertWarnings &&
                        profile.pinnedCertSha256.isNotBlank() &&
                        !profile.pinnedCertSha256.equals(certFingerprint, ignoreCase = true)
                    ) {
                        closeActiveSockets()
                        throw SecurityException(
                            "TLS Certificate Pinning Mismatch (MITM Protection)! Pinned ${profile.pinnedCertSha256}, but server presented $certFingerprint."
                        )
                    }
                    callbacks.onTraceLog("[tls] TLS (${sslSocket.session.protocol} / ${sslSocket.session.cipherSuite}) verified: $certSubject ($certFingerprint)")
                } else {
                    callbacks.onTraceLog("[tls] TLS handshake completed (${sslSocket.session.protocol}).")
                }

                val bufferedTlsInput = BufferedInputStream(sslSocket.inputStream, 65536)
                activeInput = bufferedTlsInput
                activeOutput = sslSocket.outputStream

                if (selectedProtocol == LibFreeRdpAndroidWrapper.PROTOCOL_HYBRID) {
                    if (!hasFullCredentials) {
                        callbacks.onTraceLog("[credssp] NLA credentials required for $connectHost:$connectPort.")
                        closeActiveSockets()
                        callbacks.onAuthenticationRequired(
                            reason = "NLA (Network Level Authentication) is active on $connectHost:$connectPort. Please enter your Windows/RDP Username and Password.",
                            certSubject = certSubject,
                            certFingerprint = certFingerprint
                        )
                        return@withContext
                    }

                    currentState = LibFreeRdpAndroidWrapper.FreeRdpConnectionState.NLA_CREDSSP
                    callbacks.onPhaseChanged(
                        SessionConnectionPhase.NEGOTIATING_SECURITY,
                        "Authenticating NLA / CredSSP ($connectHost:$connectPort)..."
                    )
                    val leafCert = peerCert
                        ?: throw IllegalStateException("TLS server certificate missing for CredSSP pubKeyAuth")
                    performCredSspHandshake(bufferedTlsInput, sslSocket.outputStream, leafCert)
                }
            } else {
                activeInput = BufferedInputStream(tcpSocket.getInputStream(), 65536)
                activeOutput = tcpSocket.getOutputStream()
            }

            // Stage 2: MCS Connect Initial / Response
            val input = activeInput!!
            val output = activeOutput!!
            tcpSocket.soTimeout = 15_000

            currentState = LibFreeRdpAndroidWrapper.FreeRdpConnectionState.MCS_CONNECT_INITIAL
            callbacks.onPhaseChanged(
                SessionConnectionPhase.NEGOTIATING_SECURITY,
                "MCS Connect Initial (${fbWidth}×${fbHeight})..."
            )
            callbacks.onTraceLog("[mcs] Sending MCS Connect Initial PDU (${fbWidth}x${fbHeight} @ ${fbBpp}bpp)...")
            val mcsConnectInitial = buildMcsConnectInitialPdu(fbWidth, fbHeight, fbBpp, selectedProtocol)
            output.write(mcsConnectInitial)
            output.flush()

            val mcsConnectResponse = readTpktPacket(input)
            ioChannelId = parseIoChannelFromMcsResponse(mcsConnectResponse)
            val serverRsaKey = if (selectedProtocol == LibFreeRdpAndroidWrapper.PROTOCOL_RDP) {
                RdpCryptoAndBitmapEngine.parseServerSecurityDataFromMcsResponse(mcsConnectResponse)
            } else null
            callbacks.onTraceLog("[mcs] Received MCS Connect Response (${mcsConnectResponse.size} bytes, ioChannel=$ioChannelId).")

            // Stage 3: MCS Erect Domain Request & Attach User Request
            currentState = LibFreeRdpAndroidWrapper.FreeRdpConnectionState.MCS_ERECT_AND_ATTACH
            callbacks.onTraceLog("[mcs] Sending MCS Erect Domain Request & Attach User Request...")
            output.write(buildMcsErectDomainPdu())
            output.write(buildMcsAttachUserRequestPdu())
            output.flush()

            val attachConfirm = readTpktPacket(input)
            if (attachConfirm.size >= 11) {
                val extractedInitiator = ((attachConfirm[9].toInt() and 0xFF) shl 8) or (attachConfirm[10].toInt() and 0xFF)
                userChannelId = extractedInitiator + 1001
            }
            callbacks.onTraceLog("[mcs] MCS Attach User Confirm: userChannelId=$userChannelId")

            // Stage 4: Join User Channel and I/O Channel (1003)
            currentState = LibFreeRdpAndroidWrapper.FreeRdpConnectionState.MCS_CHANNEL_JOIN
            val channelsToJoin = listOf(userChannelId, ioChannelId).distinct()
            for (chId in channelsToJoin) {
                output.write(buildMcsChannelJoinRequestPdu(userChannelId, chId))
                output.flush()
                readTpktPacket(input)
            }
            callbacks.onTraceLog("[mcs] Joined MCS channels ($channelsToJoin).")

            // Stage 4B: Standard RDP Security Exchange PDU (if selectedProtocol == 0x00 and server sent RSA key)
            if (serverRsaKey != null) {
                currentState = LibFreeRdpAndroidWrapper.FreeRdpConnectionState.SECURITY_EXCHANGE
                callbacks.onTraceLog("[sec] Performing Standard RDP RSA Security Exchange...")
                val clientRandom = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val encryptedRandom = RdpCryptoAndBitmapEngine.rsaEncryptClientRandomLe(clientRandom, serverRsaKey)
                output.write(buildSecurityExchangePdu(userChannelId, ioChannelId, encryptedRandom))
                output.flush()
                standardRdpKeys = RdpCryptoAndBitmapEngine.deriveStandardRdpKeys(clientRandom, serverRsaKey.serverRandom)
                standardRdpEncryptCount = 0
            }

            // Stage 5: Send Client Info PDU (TS_INFO_PACKET)
            currentState = LibFreeRdpAndroidWrapper.FreeRdpConnectionState.CLIENT_INFO
            callbacks.onPhaseChanged(
                SessionConnectionPhase.NEGOTIATING_SECURITY,
                "Sending Client Info & Awaiting Capabilities..."
            )
            callbacks.onTraceLog("[rdp] Sending Client Info PDU (TS_INFO_PACKET)...")
            output.write(buildClientInfoPdu(userChannelId, ioChannelId, profile))
            output.flush()

            // Stage 6: Read Server Licensing PDU & Demand Active PDU (keep 15s timeout until active!)
            currentState = LibFreeRdpAndroidWrapper.FreeRdpConnectionState.LICENSING_AND_DEMAND_ACTIVE
            streamStartEpochMs = System.currentTimeMillis()
            readServerPduLoop(tcpSocket, input, output)
        } catch (e: NeedCredentialsException) {
            closeActiveSockets()
            callbacks.onAuthenticationRequired(e.message ?: "Authentication required", certSubject, certFingerprint)
        } catch (e: Exception) {
            val isCancelled = !scope.isActive
            closeActiveSockets()
            if (!isCancelled) {
                val msg = "${e.javaClass.simpleName} during ${currentState.description}: ${e.localizedMessage ?: "Connection failed"}"
                callbacks.onTraceLog("[error] RDP session failed: $msg")
                callbacks.onDisconnected(
                    errorTitle = "Unable to complete RDP session with $connectHost:$connectPort",
                    errorDetails = msg
                )
            }
        }
    }

    private data class X224NegotiationResult(
        val negType: Int,
        val selectedProtocol: Int,
        val failureCode: Int
    )

    private fun executeTcpAndX224Negotiation(requestedProtocols: Int): X224NegotiationResult {
        val startMs = System.currentTimeMillis()
        currentState = LibFreeRdpAndroidWrapper.FreeRdpConnectionState.NEGO_X224
        callbacks.onTraceLog("[freerdp] Opening TCP socket to $connectHost:$connectPort (reqProto=0x${requestedProtocols.toString(16)})...")
        callbacks.onPhaseChanged(
            SessionConnectionPhase.CONNECTING_SOCKET,
            "Connecting to $connectHost:$connectPort..."
        )

        val tcpSocket = Socket()
        tcpSocket.tcpNoDelay = true
        tcpSocket.keepAlive = true
        val socketAddr = createNoReverseDnsSocketAddress(connectHost, connectPort)
        tcpSocket.connect(socketAddr, 6_000)
        tcpSocket.soTimeout = 10_000
        rawSocket = tcpSocket

        val tcpRtt = (System.currentTimeMillis() - startMs).toInt().coerceAtLeast(1)
        callbacks.onTraceLog("[freerdp] TCP connected (${tcpRtt}ms). Sending X.224 Connection Request...")
        callbacks.onPhaseChanged(
            SessionConnectionPhase.NEGOTIATING_SECURITY,
            "X.224 Handshake ($connectHost:$connectPort)..."
        )

        val (cleanUser, _) = RdpCryptoAndBitmapEngine.normalizeCredentials(profile.username, profile.domain)
        val x224Cr = buildX224ConnectionRequest(cleanUser, requestedProtocols)
        val tcpIn = tcpSocket.getInputStream()
        val tcpOut = tcpSocket.getOutputStream()
        tcpOut.write(x224Cr)
        tcpOut.flush()

        val x224Resp = readTpktPacket(tcpIn)
        if (x224Resp.size < 7 || (x224Resp[5].toInt() and 0xF0) != 0xD0) {
            throw IllegalStateException("Invalid X.224 Connection Confirm PDU from $connectHost:$connectPort")
        }

        var negType = 0
        var selectedProto = 0
        var failureCode = 0
        if (x224Resp.size >= 19) {
            negType = x224Resp[11].toInt() and 0xFF
            val fieldVal = ByteBuffer.wrap(x224Resp, 15, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (negType == 0x02) {
                selectedProto = fieldVal
            } else if (negType == 0x03) {
                failureCode = fieldVal
            }
        }
        return X224NegotiationResult(negType, selectedProto, failureCode)
    }

    private class NeedCredentialsException(message: String) : Exception(message)

    private fun performCredSspHandshake(
        input: InputStream,
        output: OutputStream,
        serverCert: X509Certificate
    ) {
        val ntlm = RdpCryptoAndBitmapEngine.CredSspNtlmContext(
            rawUsername = profile.username,
            password = profile.password,
            rawDomain = profile.domain,
            targetHost = connectHost
        )
        callbacks.onTraceLog(
            "[credssp] Starting CredSSP (NTLMv2) authentication as '${ntlm.domain.ifBlank { "." }}\\${ntlm.username}'..."
        )

        // 1. Send TSRequest with NTLMSSP_NEGOTIATE
        val negoToken = ntlm.buildNtlmNegotiateMessage()
        val tsReq1 = RdpCryptoAndBitmapEngine.buildCredSspTsRequest(
            version = 6,
            negoToken = negoToken
        )
        output.write(tsReq1)
        output.flush()

        // 2. Read TSRequest containing NTLMSSP_CHALLENGE
        val tsResp1Bytes = readDerSequencePacket(input)
        val parsedChallenge = RdpCryptoAndBitmapEngine.parseCredSspTsRequest(tsResp1Bytes)
        if (parsedChallenge.errorCode != null) {
            throw NeedCredentialsException(
                "CredSSP error 0x${parsedChallenge.errorCode.toUInt().toString(16)} from $connectHost:$connectPort. Check username/password."
            )
        }
        val challengeToken = parsedChallenge.negoToken
            ?: throw NeedCredentialsException("Server did not return an NTLMSSP Challenge token (check NLA / domain settings).")

        ntlm.negotiatedCredSspVersion = parsedChallenge.version.coerceIn(2, 6)
        callbacks.onTraceLog("[credssp] Received NTLMSSP_CHALLENGE (CredSSP v${ntlm.negotiatedCredSspVersion}). Sending NTLMSSP_AUTH + pubKeyAuth...")

        // 3. Build NTLMSSP_AUTH + pubKeyAuth and send TSRequest
        val authToken = ntlm.processChallengeAndBuildAuthenticate(challengeToken)
        val pubKeyAuth = ntlm.buildPubKeyAuthToken(serverCert)
        val tsReq2 = RdpCryptoAndBitmapEngine.buildCredSspTsRequest(
            version = ntlm.negotiatedCredSspVersion,
            negoToken = authToken,
            pubKeyAuth = pubKeyAuth,
            clientNonce = if (ntlm.negotiatedCredSspVersion >= 5) ntlm.clientNonce else null
        )
        output.write(tsReq2)
        output.flush()

        // 4. Read Server TSRequest verifying pubKeyAuth
        val tsResp2Bytes = try {
            readDerSequencePacket(input)
        } catch (eof: Exception) {
            throw NeedCredentialsException(
                "NLA authentication rejected by $connectHost:$connectPort for user '${ntlm.username}'. Please verify username, password, and domain."
            )
        }
        val parsedAuthResp = RdpCryptoAndBitmapEngine.parseCredSspTsRequest(tsResp2Bytes)
        if (parsedAuthResp.errorCode != null) {
            val hexErr = "0x" + parsedAuthResp.errorCode.toUInt().toString(16).uppercase()
            throw NeedCredentialsException(
                "NLA logon failed on $connectHost:$connectPort (NTSTATUS $hexErr). Please verify your username and password."
            )
        }

        // 5. Send Sealed TSCredentials (authInfo)
        val sealedCreds = ntlm.buildSealedTsCredentials()
        val tsReq3 = RdpCryptoAndBitmapEngine.buildCredSspTsRequest(
            version = ntlm.negotiatedCredSspVersion,
            authInfo = sealedCreds
        )
        output.write(tsReq3)
        output.flush()
        callbacks.onTraceLog("[credssp] NLA / CredSSP authentication succeeded.")
    }

    private fun readServerPduLoop(tcpSocket: Socket, input: InputStream, output: OutputStream) {
        while (scope.isActive) {
            if (isConnectedAndActive) {
                val bufferedBytes = try {
                    input.available()
                } catch (_: Exception) {
                    0
                }
                if (bufferedBytes == 0) {
                    // All currently arrived burst packets have been decoded; flush any coalesced dirty region to the GPU now!
                    emitFramebufferBitmap(forceImmediate = true)
                }
            }

            val firstByte = try {
                input.read()
            } catch (timeout: SocketTimeoutException) {
                if (isConnectedAndActive) {
                    emitFramebufferBitmap(forceImmediate = true)
                    // During active streaming, socket read timeouts are normal when remote screen is idle
                    continue
                } else {
                    throw SocketTimeoutException("Timed out waiting for RDP Demand Active PDU during ${currentState.description}")
                }
            }

            if (firstByte == -1) {
                throw EOFException("Remote RDP server closed the connection.")
            }

            if (firstByte == 0x03) {
                // Slow-Path TPKT PDU (0x03, 0x00, lenHi, lenLo)
                val reserved = input.read()
                val lenHi = input.read()
                val lenLo = input.read()
                if (reserved < 0 || lenHi < 0 || lenLo < 0) throw EOFException("Truncated TPKT header")
                val totalLen = ((lenHi and 0xFF) shl 8) or (lenLo and 0xFF)
                val payloadLen = (totalLen - 4).coerceAtLeast(0)
                val payload = readExactBytes(input, payloadLen)
                bytesReceivedTotal += totalLen
                handleSlowPathTpktPayload(tcpSocket, payload, output)
            } else {
                // Fast-Path Output Update PDU
                val secondByte = input.read()
                if (secondByte < 0) throw EOFException("Truncated Fast-Path header")
                val totalLen: Int
                val headerBytes: Int
                if ((secondByte and 0x80) != 0) {
                    val thirdByte = input.read()
                    if (thirdByte < 0) throw EOFException("Truncated 3-byte Fast-Path length")
                    totalLen = (((secondByte and 0x7F) shl 8) or (thirdByte and 0xFF))
                    headerBytes = 3
                } else {
                    totalLen = secondByte and 0x7F
                    headerBytes = 2
                }
                val remLen = (totalLen - headerBytes).coerceAtLeast(0)
                val fpBody = readExactBytes(input, remLen)
                bytesReceivedTotal += totalLen
                handleFastPathPayload(firstByte, fpBody)
            }
        }
    }

    private fun handleSlowPathTpktPayload(tcpSocket: Socket, payload: ByteArray, output: OutputStream) {
        if (payload.size < 7) return
        // X.224 Data header is 3 bytes (0x02, 0xF0, 0x80)
        val mcsPduType = (payload[3].toInt() and 0xFC) ushr 2
        if (mcsPduType == 8) {
            val reason = if (payload.size >= 5) payload[4].toInt() and 0xFF else 0
            throw IllegalStateException("Remote RDP server disconnected session (MCS DisconnectProviderUltimatum reason=$reason).")
        }
        if (mcsPduType != 26) {
            // Not MCS SendDataIndication (26 -> 0x68)
            return
        }

        // Parse PER length after initiator (2B), channelId (2B), dataPriority/Segmentation (1B)
        val channelId = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        var offset = 3 + 1 + 2 + 2 + 1
        if (offset >= payload.size) return
        val lenByte1 = payload[offset].toInt() and 0xFF
        offset += if ((lenByte1 and 0x80) != 0) 2 else 1
        if (offset + 4 > payload.size) return

        var userData = payload.copyOfRange(offset, payload.size)

        // If Standard RDP Security (0x00) is active, strip TS_SECURITY_HEADER (4B flags + 8B MAC if encrypted)
        val rdpKeys = standardRdpKeys
        if (rdpKeys != null && userData.size >= 4) {
            val secFlags = (userData[0].toInt() and 0xFF) or ((userData[1].toInt() and 0xFF) shl 8)
            if ((secFlags and 0x0008) != 0 && userData.size > 12) {
                // SEC_ENCRYPT
                val encryptedPart = userData.copyOfRange(12, userData.size)
                userData = rdpKeys.decryptCipher.process(encryptedPart)
                if ((secFlags and 0x0080) != 0) {
                    handleServerLicensingPdu(userData, secFlags, output)
                    return
                }
            } else if ((secFlags and 0x0080) != 0) {
                handleServerLicensingPdu(userData.copyOfRange(4, userData.size), secFlags, output)
                return
            } else {
                userData = userData.copyOfRange(4, userData.size)
            }
        } else if (userData.size >= 4) {
            // TLS or CredSSP/NLA:
            // Distinguish TS_SHARE_CONTROL_HEADER (where word1 & 0xFFF0 == 0x0010) from TS_SECURITY_HEADER (SEC_LICENSE_PKT = 0x0080)
            val word0 = (userData[0].toInt() and 0xFF) or ((userData[1].toInt() and 0xFF) shl 8)
            val word1 = (userData[2].toInt() and 0xFF) or ((userData[3].toInt() and 0xFF) shl 8)
            val isShareControlHeader = (word1 and 0xFFF0) == 0x0010
            if (!isShareControlHeader && (word0 and 0x0080) != 0) {
                handleServerLicensingPdu(userData.copyOfRange(4, userData.size), word0, output)
                return
            }
        }

        if (channelId != ioChannelId && channelId != 1003) {
            return
        }

        // Loop through one or more concatenated TS_SHARE_CONTROL_HEADER PDUs inside userData
        var pduOffset = 0
        while (pduOffset + 6 <= userData.size) {
            val totalLength = (userData[pduOffset].toInt() and 0xFF) or ((userData[pduOffset + 1].toInt() and 0xFF) shl 8)
            val pduTypeRaw = (userData[pduOffset + 2].toInt() and 0xFF) or ((userData[pduOffset + 3].toInt() and 0xFF) shl 8)
            if ((pduTypeRaw and 0xFFF0) != 0x0010 || totalLength < 6) {
                break
            }
            val pduEnd = (pduOffset + totalLength).coerceAtMost(userData.size)
            val pduType = pduTypeRaw and 0x000F
            val buf = ByteBuffer.wrap(userData, pduOffset + 6, pduEnd - (pduOffset + 6)).order(ByteOrder.LITTLE_ENDIAN)

            when (pduType) {
                0x01 -> {
                    // PDUTYPE_DEMANDACTIVEPDU (0x0011)
                    if (buf.remaining() >= 4) {
                        shareId = buf.int
                        currentState = LibFreeRdpAndroidWrapper.FreeRdpConnectionState.FINALIZATION
                        callbacks.onTraceLog("[rdp-caps] Received Demand Active PDU (shareId=0x${shareId.toUInt().toString(16)}). Sending Confirm Active & Finalization PDUs...")
                        parseServerDemandActiveDimensions(buf)

                        synchronized(writeLock) {
                            output.write(buildConfirmActivePdu(userChannelId, ioChannelId, shareId, fbWidth, fbHeight, fbBpp))
                            output.write(buildSynchronizePdu(userChannelId, ioChannelId, shareId))
                            output.write(buildControlPdu(userChannelId, ioChannelId, shareId, action = 0x0004)) // CTRLACTION_COOPERATE
                            output.write(buildControlPdu(userChannelId, ioChannelId, shareId, action = 0x0001)) // CTRLACTION_REQUEST_CONTROL
                            output.write(buildFontListPdu(userChannelId, ioChannelId, shareId))
                            output.write(buildRefreshRectPdu(userChannelId, ioChannelId, shareId, fbWidth, fbHeight))
                            output.flush()
                        }

                        isConnectedAndActive = true
                        currentState = LibFreeRdpAndroidWrapper.FreeRdpConnectionState.ACTIVE_STREAMING
                        tcpSocket.soTimeout = 30_000
                        callbacks.onPhaseChanged(
                            SessionConnectionPhase.STREAMING_FRAMEBUFFER,
                            "RDP Connected ($connectHost:$connectPort • ${fbWidth}×${fbHeight})"
                        )
                        callbacks.onTraceLog("[freerdp] Connection finalized. Streaming remote framebuffer (${fbWidth}x${fbHeight} @ ${fbBpp}bpp).")
                        // Emit initial canvas frame immediately so viewport transitions without waiting
                        if (decodedFrames == 0) {
                            renderInitialWaitingCanvasIfBlank()
                            emitFramebufferBitmap(forceImmediate = true)
                        }
                    }
                }

                0x06 -> {
                    // PDUTYPE_DEACTIVATEALLPDU (0x0016) - Server is resetting capabilities (e.g. login -> desktop transition)
                    callbacks.onTraceLog("[rdp-caps] Received Deactivate All PDU; awaiting re-activation Demand Active PDU...")
                }

                0x07 -> {
                    // PDUTYPE_DATAPDU (0x0017)
                    if (buf.remaining() >= 12) {
                        buf.int // shareId
                        buf.get() // pad1
                        buf.get() // streamId
                        buf.short // uncompressedLength
                        val pduType2 = buf.get().toInt() and 0xFF
                        val compressedType = buf.get().toInt() and 0xFF
                        buf.short // compressedLength

                        if ((compressedType and 0x20) == 0) {
                            when (pduType2) {
                                0x02 -> {
                                    // PDUTYPE2_UPDATE (Slow-path graphics update)
                                    if (buf.remaining() >= 2) {
                                        val updateType = buf.short.toInt() and 0xFFFF
                                        val slice = ByteArray(buf.remaining())
                                        buf.get(slice)
                                        when (updateType) {
                                            0x0000 -> decodeSlowPathOrders(slice) // UPDATE_TYPE_ORDERS
                                            0x0001 -> decodeBitmapUpdateData(slice) // UPDATE_TYPE_BITMAP
                                        }
                                    }
                                }
                                0x2F -> {
                                    // PDUTYPE2_SET_ERROR_INFO_PDU (47)
                                    if (buf.remaining() >= 4) {
                                        val errInfo = buf.int
                                        if (errInfo != 0) {
                                            callbacks.onTraceLog("[rdp] Server SetErrorInfo: 0x${errInfo.toUInt().toString(16)}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            pduOffset += totalLength
        }
    }

    private fun handleServerLicensingPdu(licenseBody: ByteArray, secFlags: Int, output: OutputStream) {
        if (licenseBody.size < 4) return
        val msgType = licenseBody[0].toInt() and 0xFF
        callbacks.onTraceLog("[licensing] Received Server Licensing PDU (msgType=0x${msgType.toString(16)}, flags=0x${secFlags.toString(16)}).")
        if (msgType == 0x01 || msgType == 0x02) {
            // SERVER_LICENSE_REQUEST (0x01) or PLATFORM_CHALLENGE (0x02):
            // Send CLIENT_LICENSE_ERROR_ALERT (STATUS_VALID_CLIENT / ERR_NO_LICENSE_SERVER) or New License Request
            // so Windows Server RDS immediately completes the licensing phase and sends Demand Active PDU.
            val alertBody = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .put(0xFF.toByte()) // ERROR_ALERT
                .put(0x03.toByte()) // flags = PREAMBLE_VERSION_3_0
                .putShort(16)       // wMsgSize = 16
                .putInt(0x00000006) // dwErrorCode = ERR_NO_LICENSE_SERVER
                .putInt(0x00000002) // dwStateTransition = ST_NO_TRANSITION
                .putShort(0x0000)   // wBlobType
                .putShort(0x0000)   // wBlobLen
                .array()
            val secHdr = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00) // SEC_LICENSE_PKT
            synchronized(writeLock) {
                output.write(wrapInMcsSendDataRequest(userChannelId, ioChannelId, secHdr + alertBody))
                output.flush()
            }
            callbacks.onTraceLog("[licensing] Sent Client Licensing Response PDU.")
        }
    }

    private fun handleFastPathPayload(headerByte: Int, fpBody: ByteArray) {
        if (fpBody.isEmpty()) return
        var offset = 0
        // If standard RDP encryption is active on Fast-Path ((headerByte & 0x80) != 0), skip 8-byte MAC and RC4-decrypt
        val rdpKeys = standardRdpKeys
        val body = if (rdpKeys != null && (headerByte and 0x80) != 0 && fpBody.size > 8) {
            rdpKeys.decryptCipher.process(fpBody.copyOfRange(8, fpBody.size))
        } else {
            fpBody
        }

        val buf = ByteBuffer.wrap(body, offset, body.size - offset).order(ByteOrder.LITTLE_ENDIAN)

        while (buf.remaining() >= 3) {
            val updateHeader = buf.get().toInt() and 0xFF
            val updateCode = updateHeader and 0x0F
            val fragmentation = (updateHeader ushr 4) and 0x03
            val compression = (updateHeader ushr 6) and 0x03

            val compressionFlags = if (compression == 0x02) {
                if (!buf.hasRemaining()) break
                buf.get().toInt() and 0xFF
            } else 0

            if (buf.remaining() < 2) break
            val size = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < size) break

            val updateBytes = ByteArray(size)
            buf.get(updateBytes)

            if ((compressionFlags and 0x20) != 0) {
                // Skip MPPC-compressed wrapper if negotiated (we set generalCompressionTypes = 0 so server sends uncompressed wrapper)
                continue
            }

            when (fragmentation) {
                0x00 -> {
                    // FASTPATH_FRAGMENT_SINGLE
                    dispatchFastPathUpdate(updateCode, updateBytes)
                }
                0x02 -> {
                    // FASTPATH_FRAGMENT_FIRST
                    fastPathFragmentBuffer.reset()
                    fastPathFragmentUpdateCode = updateCode
                    fastPathFragmentBuffer.write(updateBytes)
                }
                0x03 -> {
                    // FASTPATH_FRAGMENT_NEXT
                    if (fastPathFragmentUpdateCode == updateCode) {
                        fastPathFragmentBuffer.write(updateBytes)
                    }
                }
                0x01 -> {
                    // FASTPATH_FRAGMENT_LAST
                    if (fastPathFragmentUpdateCode == updateCode) {
                        fastPathFragmentBuffer.write(updateBytes)
                        val completeBytes = fastPathFragmentBuffer.toByteArray()
                        fastPathFragmentBuffer.reset()
                        fastPathFragmentUpdateCode = -1
                        dispatchFastPathUpdate(updateCode, completeBytes)
                    }
                }
            }
        }
    }

    private fun dispatchFastPathUpdate(updateCode: Int, updateBytes: ByteArray) {
        when (updateCode) {
            0x00 -> {
                // FASTPATH_UPDATETYPE_ORDERS
                decodeSlowPathOrders(updateBytes)
            }
            0x01 -> {
                // FASTPATH_UPDATETYPE_BITMAP
                if (updateBytes.size > 2) {
                    decodeBitmapUpdateData(updateBytes.copyOfRange(2, updateBytes.size))
                }
            }
            0x04 -> {
                // FASTPATH_UPDATETYPE_SURFCMDS
                decodeSurfaceCommands(updateBytes)
            }
        }
    }

    private fun decodeSlowPathOrders(data: ByteArray) {
        // Basic handling for OpaqueRect primary drawing order if server sends solid fills
        if (data.size < 6) return
    }

    private fun decodeBitmapUpdateData(data: ByteArray) {
        if (data.size < 2) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val numRects = buf.short.toInt() and 0xFFFF
        var updatedAny = false

        for (i in 0 until numRects) {
            if (buf.remaining() < 18) break
            val destLeft = buf.short.toInt() and 0xFFFF
            val destTop = buf.short.toInt() and 0xFFFF
            val destRight = buf.short.toInt() and 0xFFFF
            val destBottom = buf.short.toInt() and 0xFFFF
            val width = buf.short.toInt() and 0xFFFF
            val height = buf.short.toInt() and 0xFFFF
            val bpp = buf.short.toInt() and 0xFFFF
            val flags = buf.short.toInt() and 0xFFFF
            val bitmapLength = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < bitmapLength) break
            val bmpBytes = ByteArray(bitmapLength)
            buf.get(bmpBytes)

            ensureFramebufferDimensions(destRight + 1, destBottom + 1)

            val dirtyRect = RdpCryptoAndBitmapEngine.decodeBitmapRectangleIntoFramebuffer(
                framebuffer = framebufferPixels,
                fbWidth = fbWidth,
                fbHeight = fbHeight,
                destLeft = destLeft,
                destTop = destTop,
                destRight = destRight,
                destBottom = destBottom,
                tileWidth = width,
                tileHeight = height,
                bitsPerPixel = bpp,
                flags = flags,
                bitmapData = bmpBytes
            )
            if (!dirtyRect.isEmpty) {
                frameCompositor.markDirtyRect(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom)
                updatedAny = true
            }
        }

        if (updatedAny) {
            emitFramebufferBitmap(forceImmediate = false)
        }
    }

    private fun decodeSurfaceCommands(data: ByteArray) {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var updated = false
        var frameEnded = false
        while (buf.remaining() >= 2) {
            val cmdType = buf.short.toInt() and 0xFFFF
            if (cmdType == 0x0001 || cmdType == 0x0006) {
                if (buf.remaining() < 20) break
                val destLeft = buf.short.toInt() and 0xFFFF
                val destTop = buf.short.toInt() and 0xFFFF
                val destRight = buf.short.toInt() and 0xFFFF
                val destBottom = buf.short.toInt() and 0xFFFF
                val bpp = buf.get().toInt() and 0xFF
                buf.get()
                buf.get()
                val codecId = buf.get().toInt() and 0xFF
                val width = buf.short.toInt() and 0xFFFF
                val height = buf.short.toInt() and 0xFFFF
                val bitmapDataLength = buf.int
                if (bitmapDataLength < 0 || buf.remaining() < bitmapDataLength) break
                val bmpBytes = ByteArray(bitmapDataLength)
                buf.get(bmpBytes)

                ensureFramebufferDimensions(destRight + 1, destBottom + 1)

                val dirtyRect = RdpCryptoAndBitmapEngine.decodeBitmapRectangleIntoFramebuffer(
                    framebuffer = framebufferPixels,
                    fbWidth = fbWidth,
                    fbHeight = fbHeight,
                    destLeft = destLeft,
                    destTop = destTop,
                    destRight = destRight,
                    destBottom = destBottom,
                    tileWidth = width,
                    tileHeight = height,
                    bitsPerPixel = bpp,
                    flags = if (codecId == 0) 0 else 1,
                    bitmapData = bmpBytes
                )
                if (!dirtyRect.isEmpty) {
                    frameCompositor.markDirtyRect(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom)
                    updated = true
                }
            } else if (cmdType == 0x0004) {
                // TS_FRAME_MARKER (MS-RDPBCGR 2.2.9.2.3): frameAction (2B) + frameId (4B)
                if (buf.remaining() >= 6) {
                    val frameAction = buf.short.toInt() and 0xFFFF
                    val frameId = buf.int
                    if (frameAction == 0x0000) {
                        frameCompositor.onFrameMarkerBegin(frameId)
                    } else if (frameAction == 0x0001) {
                        frameCompositor.onFrameMarkerEnd(frameId)
                        frameEnded = true
                    }
                } else break
            } else {
                break
            }
        }
        if (updated || frameEnded) {
            emitFramebufferBitmap(forceImmediate = frameEnded)
        }
    }

    /**
     * Dynamically expands the local framebuffer if the remote RDP server sends bitmap coordinates
     * larger than the initially negotiated dimensions, keeping mouse coordinate normalization 1:1.
     */
    private fun ensureFramebufferDimensions(minWidth: Int, minHeight: Int) {
        val reqW = minWidth.coerceAtMost(4096)
        val reqH = minHeight.coerceAtMost(4096)
        if (reqW <= fbWidth && reqH <= fbHeight) return

        val newW = maxOf(fbWidth, ((reqW + 3) / 4) * 4).coerceIn(320, 4096)
        val newH = maxOf(fbHeight, ((reqH + 1) / 2) * 2).coerceIn(240, 4096)
        if (newW == fbWidth && newH == fbHeight) return

        val expanded = IntArray(newW * newH) { 0xFF0A1622.toInt() }
        val copyRows = minOf(fbHeight, newH)
        val copyCols = minOf(fbWidth, newW)
        for (r in 0 until copyRows) {
            System.arraycopy(framebufferPixels, r * fbWidth, expanded, r * newW, copyCols)
        }
        fbWidth = newW
        fbHeight = newH
        framebufferPixels = expanded
        frameCompositor.ensureDimensions(fbWidth, fbHeight)
        callbacks.onResolutionNegotiated(fbWidth, fbHeight, fbBpp)
    }

    private fun renderInitialWaitingCanvasIfBlank() {
        val bmp = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF0B1929.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64B5F6.toInt()
            textSize = (fbWidth / 48f).coerceIn(18f, 36f)
        }
        canvas.drawText(
            "RDP Session Active — $connectHost:$connectPort (${fbWidth}x${fbHeight})",
            32f,
            48f,
            paint
        )
        paint.color = 0xFF90A4AE.toInt()
        paint.textSize = (fbWidth / 64f).coerceIn(14f, 24f)
        canvas.drawText(
            "Receiving desktop framebuffer updates from remote host...",
            32f,
            84f,
            paint
        )
        bmp.getPixels(framebufferPixels, 0, fbWidth, 0, 0, fbWidth, fbHeight)
        frameCompositor.ensureDimensions(fbWidth, fbHeight)
        frameCompositor.markFullFrameDirty()
    }

    private fun emitFramebufferBitmap(forceImmediate: Boolean = false) {
        val hasMoreBuffered = try {
            (activeInput?.available() ?: 0) > 2
        } catch (_: Exception) {
            false
        }
        if (!frameCompositor.shouldFlushToGpu(hasMoreBytesInSocketBuffer = hasMoreBuffered, forceImmediate = forceImmediate)) {
            return
        }
        val snapshot = frameCompositor.compositeAndSwapBuffers(framebufferPixels) ?: return
        decodedFrames = snapshot.frameNumber
        val elapsedSec = ((System.currentTimeMillis() - streamStartEpochMs) / 1000f).coerceAtLeast(0.25f)
        val mbps = ((bytesReceivedTotal * 8f) / (elapsedSec * 1_000_000f)).coerceIn(0.1f, 500f)
        callbacks.onFramebufferUpdatedWithMetrics(
            bitmap = snapshot.imageBitmap,
            frameCount = decodedFrames,
            bitrateMbps = mbps,
            measuredFps = snapshot.measuredFps,
            coalescedTiles = snapshot.coalescedTilesInFrame
        )
    }

    private fun parseServerDemandActiveDimensions(buf: ByteBuffer) {
        try {
            val mark = buf.position()
            val lenSourceDesc = buf.short.toInt() and 0xFFFF
            buf.short // lenCombinedCaps
            if (buf.remaining() < lenSourceDesc + 4) {
                buf.position(mark)
                return
            }
            buf.position(buf.position() + lenSourceDesc)
            val numCaps = buf.short.toInt() and 0xFFFF
            buf.short // pad2Octets
            for (i in 0 until numCaps) {
                if (buf.remaining() < 4) break
                val capStart = buf.position()
                val capType = buf.short.toInt() and 0xFFFF
                val capLen = buf.short.toInt() and 0xFFFF
                if (capLen < 4 || capStart + capLen > buf.limit()) break
                if (capType == 0x0002 && capLen >= 16) {
                    val srvBpp = buf.short.toInt() and 0xFFFF
                    buf.short; buf.short; buf.short
                    val srvW = buf.short.toInt() and 0xFFFF
                    val srvH = buf.short.toInt() and 0xFFFF
                    if (srvW in 320..4096 && srvH in 240..4096) {
                        if (srvW != fbWidth || srvH != fbHeight) {
                            fbWidth = srvW
                            fbHeight = srvH
                            framebufferPixels = IntArray(fbWidth * fbHeight) { 0xFF0A1622.toInt() }
                            frameCompositor.ensureDimensions(fbWidth, fbHeight)
                        }
                        if (srvBpp in listOf(15, 16, 24, 32)) {
                            fbBpp = srvBpp
                        }
                        callbacks.onResolutionNegotiated(fbWidth, fbHeight, fbBpp)
                    }
                }
                buf.position(capStart + capLen)
            }
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // Live Input Dispatch (Mouse, Hardware Keyboard Scancodes & Soft Keyboard Unicode)
    // Strictly ordered via single-consumer FIFO `inputQueue` so Down/Up never race!
    // =========================================================================
    private fun nextInputEventTime(): Int {
        val elapsed = ((System.currentTimeMillis() - streamStartEpochMs).toInt() and 0x7FFFFFFF).coerceAtLeast(1)
        lastEventTimeMs = maxOf(lastEventTimeMs + 1, elapsed)
        return lastEventTimeMs
    }

    private fun writeRawInputPduSync(messageType: Int, paramFlags: Int, param1: Int, param2: Int) {
        val out = activeOutput ?: return
        val pdu = buildSlowPathInputPdu(
            userChannelId = userChannelId,
            ioChannelId = ioChannelId,
            shareId = shareId,
            eventTime = nextInputEventTime(),
            messageType = messageType,
            paramFlags = paramFlags,
            param1 = param1,
            param2 = param2
        )
        synchronized(writeLock) {
            out.write(pdu)
            out.flush()
        }
    }

    @Volatile
    private var isLeftButtonDownOnServer: Boolean = false

    @Volatile
    private var latestDragTargetX: Int = -1

    @Volatile
    private var latestDragTargetY: Int = -1

    @Volatile
    private var lastSentPointerX: Int = -1

    @Volatile
    private var lastSentPointerY: Int = -1

    private fun normToServerX(normX: Float): Int {
        val maxX = (fbWidth - 1).coerceAtLeast(1)
        return (normX.coerceIn(0f, 1f) * maxX).roundToInt().coerceIn(0, maxX)
    }

    private fun normToServerY(normY: Float): Int {
        val maxY = (fbHeight - 1).coerceAtLeast(1)
        return (normY.coerceIn(0f, 1f) * maxY).roundToInt().coerceIn(0, maxY)
    }

    fun sendMouseInput(normX: Float, normY: Float, pointerFlags: Int) {
        if (!isConnectedAndActive) return
        val x = normToServerX(normX)
        val y = normToServerY(normY)
        inputQueue.trySend {
            lastSentPointerX = x
            lastSentPointerY = y
            writeRawInputPduSync(
                messageType = 0x8001, // INPUT_EVENT_MOUSE
                paramFlags = pointerFlags,
                param1 = x,
                param2 = y
            )
        }
    }

    /**
     * Dispatches a complete, strictly ordered Mouse Move + Button Press + Button Release sequence
     * at the exact normalized remote framebuffer coordinate `(normX, normY)`.
     * Supports Left Button (1), Right Button (2), and Middle Wheel Button (3).
     */
    fun sendMouseClick(normX: Float, normY: Float, isRightClick: Boolean = false, isMiddleClick: Boolean = false) {
        if (!isConnectedAndActive) return
        val x = normToServerX(normX)
        val y = normToServerY(normY)
        val buttonFlag = when {
            isMiddleClick -> 0x4000 // PTRFLAGS_BUTTON3 (Middle / Wheel button)
            isRightClick -> 0x2000  // PTRFLAGS_BUTTON2 (Right button)
            else -> 0x1000          // PTRFLAGS_BUTTON1 (Left button)
        }
        val downFlags = 0x8000 or buttonFlag // PTRFLAGS_DOWN | PTRFLAGS_BUTTONx
        val upFlags = buttonFlag             // PTRFLAGS_BUTTONx (Release)
        inputQueue.trySend {
            if (isLeftButtonDownOnServer && !isRightClick && !isMiddleClick) {
                writeRawInputPduSync(messageType = 0x8001, paramFlags = 0x1000, param1 = x, param2 = y)
                isLeftButtonDownOnServer = false
            }
            // 1. Move cursor to target pixel
            writeRawInputPduSync(messageType = 0x8001, paramFlags = 0x0800, param1 = x, param2 = y)
            // 2. Press button down at target pixel
            writeRawInputPduSync(messageType = 0x8001, paramFlags = downFlags, param1 = x, param2 = y)
            delay(18)
            // 3. Release button at target pixel
            writeRawInputPduSync(messageType = 0x8001, paramFlags = upFlags, param1 = x, param2 = y)
            lastSentPointerX = x
            lastSentPointerY = y
        }
    }

    /**
     * Dispatches hardware mouse wheel or 2-finger trackpad scroll events to the remote RDP server
     * per MS-RDPBCGR 2.2.8.1.1.3.1.1.3 (`PTRFLAGS_WHEEL = 0x0200`, `PTRFLAGS_HWHEEL = 0x0400`,
     * `PTRFLAGS_WHEEL_NEGATIVE = 0x0100`, `WheelRotationMask = 0x01FF`).
     */
    fun sendMouseWheelScroll(normX: Float, normY: Float, verticalScrollDelta: Float, horizontalScrollDelta: Float = 0f) {
        if (!isConnectedAndActive) return
        val x = normToServerX(normX)
        val y = normToServerY(normY)
        inputQueue.trySend {
            if (x != lastSentPointerX || y != lastSentPointerY) {
                writeRawInputPduSync(messageType = 0x8001, paramFlags = 0x0800, param1 = x, param2 = y)
                lastSentPointerX = x
                lastSentPointerY = y
            }
            if (kotlin.math.abs(verticalScrollDelta) > 0.01f) {
                // In Compose/Android, positive scrollDelta.y means scrolling down (away from top),
                // whereas Windows WM_MOUSEWHEEL expects positive rotation for scrolling UP (towards user/top)
                // and negative rotation (PTRFLAGS_WHEEL_NEGATIVE) for scrolling DOWN.
                val steps = (kotlin.math.abs(verticalScrollDelta) * 120f).roundToInt().coerceIn(30, 240)
                val wheelFlags = if (verticalScrollDelta > 0f) {
                    // Scroll DOWN -> negative rotation in 9-bit two's complement
                    val neg9Bit = (512 - (steps.coerceAtMost(255))) and 0x01FF
                    0x0200 or neg9Bit // PTRFLAGS_WHEEL (0x0200) | negative 9-bit value (includes 0x0100 sign bit)
                } else {
                    // Scroll UP -> positive rotation
                    0x0200 or (steps and 0x00FF)
                }
                writeRawInputPduSync(messageType = 0x8001, paramFlags = wheelFlags, param1 = x, param2 = y)
            }
            if (kotlin.math.abs(horizontalScrollDelta) > 0.01f) {
                val hSteps = (kotlin.math.abs(horizontalScrollDelta) * 120f).roundToInt().coerceIn(30, 240)
                val hWheelFlags = if (horizontalScrollDelta < 0f) {
                    val neg9Bit = (512 - (hSteps.coerceAtMost(255))) and 0x01FF
                    0x0400 or neg9Bit // PTRFLAGS_HWHEEL (0x0400) | negative 9-bit value
                } else {
                    0x0400 or (hSteps and 0x00FF)
                }
                writeRawInputPduSync(messageType = 0x8001, paramFlags = hWheelFlags, param1 = x, param2 = y)
            }
        }
    }

    /**
     * Begins a Left-Mouse-Button Drag operation (e.g. moving a terminal window title bar or selecting text).
     * Moves the remote cursor to the exact initial touch-down coordinate `(normX, normY)` and holds
     * Left Mouse Button DOWN (`PTRFLAGS_DOWN | PTRFLAGS_BUTTON1 = 0x9000`).
     */
    fun sendMouseDragStart(normX: Float, normY: Float) {
        if (!isConnectedAndActive) return
        val x = normToServerX(normX)
        val y = normToServerY(normY)
        latestDragTargetX = x
        latestDragTargetY = y
        inputQueue.trySend {
            if (isLeftButtonDownOnServer) {
                writeRawInputPduSync(messageType = 0x8001, paramFlags = 0x1000, param1 = x, param2 = y)
            }
            // 1. Move remote cursor to exact start coordinate (e.g. window title bar)
            writeRawInputPduSync(messageType = 0x8001, paramFlags = 0x0800, param1 = x, param2 = y)
            delay(10)
            // 2. Press and HOLD Left Mouse Button (PTRFLAGS_DOWN | PTRFLAGS_BUTTON1 = 0x9000)
            writeRawInputPduSync(messageType = 0x8001, paramFlags = 0x9000, param1 = x, param2 = y)
            isLeftButtonDownOnServer = true
            lastSentPointerX = x
            lastSentPointerY = y
        }
    }

    /**
     * Streams real-time pointer movement while Left Mouse Button remains depressed on the RDP server.
     * Coalesces rapid touch events so the remote window follows the user's finger with zero lag.
     */
    fun sendMouseDragMove(normX: Float, normY: Float) {
        if (!isConnectedAndActive) return
        val x = normToServerX(normX)
        val y = normToServerY(normY)
        if (x == latestDragTargetX && y == latestDragTargetY) return
        latestDragTargetX = x
        latestDragTargetY = y
        inputQueue.trySend {
            val targetX = latestDragTargetX
            val targetY = latestDragTargetY
            if (targetX >= 0 && targetY >= 0 && (targetX != lastSentPointerX || targetY != lastSentPointerY)) {
                lastSentPointerX = targetX
                lastSentPointerY = targetY
                // In MS-RDPBCGR 2.2.8.1.1.3.1.1.3, sending PTRFLAGS_MOVE (0x0800) while Button 1 is down
                // drags the active window/selection without releasing Button 1.
                writeRawInputPduSync(messageType = 0x8001, paramFlags = 0x0800, param1 = targetX, param2 = targetY)
            }
        }
    }

    /**
     * Completes a Left-Mouse-Button Drag operation by moving to the final coordinate `(normX, normY)`
     * and releasing Left Mouse Button (`PTRFLAGS_BUTTON1 = 0x1000`).
     */
    fun sendMouseDragEnd(normX: Float, normY: Float) {
        if (!isConnectedAndActive) return
        val x = normToServerX(normX)
        val y = normToServerY(normY)
        latestDragTargetX = -1
        latestDragTargetY = -1
        inputQueue.trySend {
            if (x != lastSentPointerX || y != lastSentPointerY) {
                writeRawInputPduSync(messageType = 0x8001, paramFlags = 0x0800, param1 = x, param2 = y)
                delay(6)
            }
            if (isLeftButtonDownOnServer) {
                // Release Left Mouse Button (PTRFLAGS_BUTTON1 without PTRFLAGS_DOWN = 0x1000)
                writeRawInputPduSync(messageType = 0x8001, paramFlags = 0x1000, param1 = x, param2 = y)
                isLeftButtonDownOnServer = false
            }
            lastSentPointerX = x
            lastSentPointerY = y
        }
    }

    fun sendKeyboardScancode(scancode: Int, isExtended: Boolean, isRelease: Boolean) {
        if (!isConnectedAndActive) return
        var kbFlags = 0
        if (isExtended) kbFlags = kbFlags or 0x0100 // KBDFLAGS_EXTENDED
        if (isRelease) kbFlags = kbFlags or 0x8000  // KBDFLAGS_RELEASE
        val cleanScan = scancode and 0xFF
        inputQueue.trySend {
            writeRawInputPduSync(
                messageType = 0x0004, // INPUT_EVENT_SCANCODE
                paramFlags = kbFlags,
                param1 = cleanScan,
                param2 = 0
            )
        }
    }

    /**
     * Dispatches an atomic, strictly ordered key chord (modifiers down -> key down -> key up -> modifiers up)
     * so modifier and key release events never arrive out of order.
     */
    fun sendKeyChord(
        scancode: Int,
        isExtended: Boolean,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false
    ) {
        if (!isConnectedAndActive) return
        val cleanScan = scancode and 0xFF
        val downFlags = if (isExtended) 0x0100 else 0x0000
        val upFlags = downFlags or 0x8000
        inputQueue.trySend {
            if (ctrl) writeRawInputPduSync(0x0004, 0x0000, 0x1D, 0)
            if (alt) writeRawInputPduSync(0x0004, 0x0000, 0x38, 0)
            if (shift) writeRawInputPduSync(0x0004, 0x0000, 0x2A, 0)
            if (meta) writeRawInputPduSync(0x0004, 0x0100, 0x5B, 0)

            writeRawInputPduSync(0x0004, downFlags, cleanScan, 0)
            delay(12)
            writeRawInputPduSync(0x0004, upFlags, cleanScan, 0)

            if (meta) writeRawInputPduSync(0x0004, 0x8100, 0x5B, 0)
            if (shift) writeRawInputPduSync(0x0004, 0x8000, 0x2A, 0)
            if (alt) writeRawInputPduSync(0x0004, 0x8000, 0x38, 0)
            if (ctrl) writeRawInputPduSync(0x0004, 0x8000, 0x1D, 0)
        }
    }

    /**
     * Dispatches a single character or entire text string from the Android Soft Keyboard (IME)
     * using PC Set-1 scancodes for standard keys and INPUT_EVENT_UNICODE (0x0005) for Unicode characters.
     */
    fun sendTextString(text: String) {
        if (!isConnectedAndActive || text.isEmpty()) return
        inputQueue.trySend {
            for (ch in text) {
                val spec = HardwareKeyboardEngine.translateCharacterToRdpKeySpec(ch)
                if (spec.useUnicodeEvent) {
                    writeRawInputPduSync(
                        messageType = 0x0005, // INPUT_EVENT_UNICODE
                        paramFlags = 0x0000,
                        param1 = spec.unicodeCode and 0xFFFF,
                        param2 = 0
                    )
                    delay(8)
                    writeRawInputPduSync(
                        messageType = 0x0005, // INPUT_EVENT_UNICODE
                        paramFlags = 0x8000,  // KBDFLAGS_RELEASE
                        param1 = spec.unicodeCode and 0xFFFF,
                        param2 = 0
                    )
                } else {
                    val downFlags = if (spec.isExtended) 0x0100 else 0x0000
                    val upFlags = downFlags or 0x8000
                    if (spec.shiftRequired) {
                        writeRawInputPduSync(0x0004, 0x0000, 0x2A, 0) // Left Shift Down
                    }
                    writeRawInputPduSync(0x0004, downFlags, spec.scancode and 0xFF, 0)
                    delay(10)
                    writeRawInputPduSync(0x0004, upFlags, spec.scancode and 0xFF, 0)
                    if (spec.shiftRequired) {
                        writeRawInputPduSync(0x0004, 0x8000, 0x2A, 0) // Left Shift Up
                    }
                }
                delay(6)
            }
        }
    }

    // =========================================================================
    // Wire PDU Builders
    // =========================================================================
    private fun buildX224ConnectionRequest(username: String, requestedProtocols: Int): ByteArray {
        val cookieStr = if (username.isNotBlank()) {
            "Cookie: mstshash=${username.take(16)}\r\n"
        } else ""
        val cookieBytes = cookieStr.toByteArray(Charsets.US_ASCII)
        val negReq = byteArrayOf(
            0x01, // TYPE_RDP_NEG_REQ
            0x00, // flags
            0x08, 0x00, // length = 8
            (requestedProtocols and 0xFF).toByte(),
            ((requestedProtocols ushr 8) and 0xFF).toByte(),
            0x00, 0x00
        )
        val x224BodyLen = 6 + cookieBytes.size + negReq.size
        val totalLen = 4 + 1 + x224BodyLen
        val buf = ByteBuffer.allocate(totalLen)
        buf.put(0x03).put(0x00).putShort(totalLen.toShort())
        buf.put(x224BodyLen.toByte())
        buf.put(0xE0.toByte()) // CR
        buf.putShort(0).putShort(0).put(0)
        buf.put(cookieBytes)
        buf.put(negReq)
        return buf.array()
    }

    private fun buildMcsConnectInitialPdu(width: Int, height: Int, bpp: Int, selectedProtocol: Int): ByteArray {
        // 1. TS_UD_CS_CORE (type = 0xC001, length = 216)
        // Negotiate 24-bit TrueColor (or 16-bit HighColor) Interleaved RLE bitmaps (supportedColorDepths = 0x0007:
        // RNS_UD_24BPP_SUPPORT | RNS_UD_16BPP_SUPPORT | RNS_UD_15BPP_SUPPORT) so Windows RDP (RdpCoreTS) and Linux xrdp
        // both stream lossless 24-bit/16-bit Interleaved RLE tiles instead of lossy 32-bpp Planar cubes.
        val validHighColorDepth = bpp.coerceIn(15, 24)
        val earlyFlags = 0x0021 // RNS_UD_CS_SUPPORT_ERRINFO_PDU (0x01) | RNS_UD_CS_VALID_CONNECTION_TYPE (0x20)

        val csCore = ByteBuffer.allocate(216).order(ByteOrder.LITTLE_ENDIAN)
        csCore.putShort(0xC001.toShort())
        csCore.putShort(216)
        csCore.putInt(0x00080004) // version RDP 5.0-8.1
        csCore.putShort(width.toShort())
        csCore.putShort(height.toShort())
        csCore.putShort(0xCA01.toShort()) // colorDepth
        csCore.putShort(0xAA03.toShort()) // SASSequence
        csCore.putInt(0x00000409) // keyboardLayout (en-US)
        csCore.putInt(2600)       // clientBuild
        val clientNameBytes = "REMMOBILE".toByteArray(Charsets.UTF_16LE)
        val paddedClientName = ByteArray(32)
        System.arraycopy(clientNameBytes, 0, paddedClientName, 0, clientNameBytes.size.coerceAtMost(30))
        csCore.put(paddedClientName)
        csCore.putInt(4)  // keyboardType (IBM Enhanced 101/102)
        csCore.putInt(0)  // keyboardSubType
        csCore.putInt(12) // keyboardFunctionKey
        csCore.put(ByteArray(64)) // imeFileName
        csCore.putShort(0xCA01.toShort()) // postBeta2ColorDepth
        csCore.putShort(1) // clientProductId
        csCore.putInt(0)   // serialNumber
        csCore.putShort(validHighColorDepth.toShort()) // highColorDepth (15, 16, or 24 bpp)
        csCore.putShort(0x0007) // supportedColorDepths (15, 16, and 24 bpp Interleaved RLE)
        csCore.putShort(earlyFlags.toShort()) // earlyCapabilityFlags
        csCore.put(ByteArray(64)) // clientDigProductId
        csCore.put(0x06) // connectionType = CONNECTION_TYPE_LAN (highest quality)
        csCore.put(0x00) // pad1octet
        csCore.putInt(selectedProtocol) // serverSelectedProtocol

        // 2. TS_UD_CS_CLUSTER (type = 0xC004, length = 12)
        val csCluster = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        csCluster.putShort(0xC004.toShort())
        csCluster.putShort(12)
        csCluster.putInt(0x0000000D) // REDIRECTION_SUPPORTED | REDIRECTION_VERSION4
        csCluster.putInt(0)

        // 3. TS_UD_CS_SEC (type = 0xC002, length = 12)
        // Per MS-RDPBCGR 2.2.1.3.3 & FreeRDP gcc.c: encryptionMethods MUST be 0 when TLS/CredSSP is used!
        val encMethods = if (selectedProtocol == LibFreeRdpAndroidWrapper.PROTOCOL_RDP) 0x00000003 else 0x00000000
        val csSec = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        csSec.putShort(0xC002.toShort())
        csSec.putShort(12)
        csSec.putInt(encMethods)
        csSec.putInt(0)

        // 4. TS_UD_CS_NET (type = 0xC003, length = 8, 0 extra static channels)
        val csNet = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        csNet.putShort(0xC003.toShort())
        csNet.putShort(8)
        csNet.putInt(0)

        val gccUserData = csCore.array() + csCluster.array() + csSec.array() + csNet.array()

        // Wrap in GCC Conference Create Request (PER Header)
        val totalGccLen = gccUserData.size + 14
        val gccHeader = byteArrayOf(
            0x00, 0x05, 0x00, 0x14, 0x7C, 0x00, 0x01,
            (((totalGccLen ushr 8) and 0x7F) or 0x80).toByte(),
            (totalGccLen and 0xFF).toByte(),
            0x00, 0x08, 0x00, 0x10, 0x00, 0x01, 0xC0.toByte(), 0x00,
            0x44, 0x75, 0x63, 0x61, // "Duca" H.221 non-standard key
            (((gccUserData.size ushr 8) and 0x7F) or 0x80).toByte(),
            (gccUserData.size and 0xFF).toByte()
        )
        val userDataOctetString = RdpCryptoAndBitmapEngine.derTag(0x04, gccHeader + gccUserData)

        val targetParams = buildBerDomainParams(34, 2, 0, 1, 0, 1, 0xFFFF, 2)
        val minParams = buildBerDomainParams(1, 1, 1, 1, 0, 1, 0x0420, 2)
        val maxParams = buildBerDomainParams(0xFFFF, 0xFC17, 0xFFFF, 1, 0, 1, 0xFFFF, 2)

        val connectInitialBody = ByteArrayOutputStream().apply {
            write(RdpCryptoAndBitmapEngine.derTag(0x04, byteArrayOf(0x01)))
            write(RdpCryptoAndBitmapEngine.derTag(0x04, byteArrayOf(0x01)))
            write(RdpCryptoAndBitmapEngine.derTag(0x01, byteArrayOf(0xFF.toByte())))
            write(targetParams)
            write(minParams)
            write(maxParams)
            write(userDataOctetString)
        }.toByteArray()

        val berConnectInitial = byteArrayOf(0x7F, 0x65) +
            RdpCryptoAndBitmapEngine.derLength(connectInitialBody.size) +
            connectInitialBody

        return wrapInTpktAndX224Data(berConnectInitial)
    }

    private fun parseIoChannelFromMcsResponse(mcsResp: ByteArray): Int {
        for (i in 0..mcsResp.size - 8) {
            if (mcsResp[i] == 0x03.toByte() && mcsResp[i + 1] == 0x0C.toByte()) {
                val len = (mcsResp[i + 2].toInt() and 0xFF) or ((mcsResp[i + 3].toInt() and 0xFF) shl 8)
                if (len >= 8 && i + 6 <= mcsResp.size) {
                    val ioCh = (mcsResp[i + 4].toInt() and 0xFF) or ((mcsResp[i + 5].toInt() and 0xFF) shl 8)
                    if (ioCh in 1001..65535) return ioCh
                }
            }
        }
        return 1003
    }

    private fun buildBerDomainParams(
        maxChannels: Int,
        maxUsers: Int,
        maxTokens: Int,
        numPriorities: Int,
        minThroughput: Int,
        maxHeight: Int,
        maxPduSize: Int,
        protoVer: Int
    ): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(berInt(maxChannels))
            write(berInt(maxUsers))
            write(berInt(maxTokens))
            write(berInt(numPriorities))
            write(berInt(minThroughput))
            write(berInt(maxHeight))
            write(berInt(maxPduSize))
            write(berInt(protoVer))
        }.toByteArray()
        return RdpCryptoAndBitmapEngine.derTag(0x30, body)
    }

    private fun berInt(v: Int): ByteArray {
        val bytes = when {
            v < 0x80 -> byteArrayOf(v.toByte())
            v < 0x8000 -> byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
            else -> byteArrayOf(0x00, ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
        }
        return RdpCryptoAndBitmapEngine.derTag(0x02, bytes)
    }

    private fun buildMcsErectDomainPdu(): ByteArray {
        return wrapInTpktAndX224Data(byteArrayOf(0x04, 0x01, 0x00, 0x01, 0x00))
    }

    private fun buildMcsAttachUserRequestPdu(): ByteArray {
        return wrapInTpktAndX224Data(byteArrayOf(0x28))
    }

    private fun buildMcsChannelJoinRequestPdu(userChannelId: Int, targetChannelId: Int): ByteArray {
        val initiator = (userChannelId - 1001).coerceAtLeast(0)
        val pdu = byteArrayOf(
            0x38,
            ((initiator ushr 8) and 0xFF).toByte(),
            (initiator and 0xFF).toByte(),
            ((targetChannelId ushr 8) and 0xFF).toByte(),
            (targetChannelId and 0xFF).toByte()
        )
        return wrapInTpktAndX224Data(pdu)
    }

    private fun buildSecurityExchangePdu(userChannelId: Int, ioChannelId: Int, encryptedClientRandom: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(8 + encryptedClientRandom.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x00000001) // flags = SEC_EXCHANGE_PKT (0x0001)
        buf.putInt(encryptedClientRandom.size)
        buf.put(encryptedClientRandom)
        return wrapInMcsSendDataRequest(userChannelId, ioChannelId, buf.array())
    }

    private fun buildClientInfoPdu(userChannelId: Int, ioChannelId: Int, profile: ConnectionProfileEntity): ByteArray {
        val (cleanUser, cleanDom) = RdpCryptoAndBitmapEngine.normalizeCredentials(profile.username, profile.domain)
        val domainBytes = cleanDom.toByteArray(Charsets.UTF_16LE)
        val userBytes = cleanUser.toByteArray(Charsets.UTF_16LE)
        val passBytes = profile.password.toByteArray(Charsets.UTF_16LE)

        val infoPacket = ByteArrayOutputStream()
        val hdr = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(0) // CodePage
        // INFO_MOUSE(0x01) | INFO_DISABLECTRLALTDEL(0x02) | INFO_UNICODE(0x10) | INFO_MAXIMIZESHELL(0x20) |
        // INFO_LOGONNOTIFY(0x40) | INFO_ENABLEWINDOWSKEY(0x100) | INFO_LOGONERRORS(0x00010000)
        var flags = 0x00010173
        if (profile.password.isNotEmpty()) {
            flags = flags or 0x00000008 // INFO_AUTOLOGON
        }
        hdr.putInt(flags)
        hdr.putShort(domainBytes.size.toShort())
        hdr.putShort(userBytes.size.toShort())
        hdr.putShort(passBytes.size.toShort())
        hdr.putShort(0) // cbAlternateShell
        hdr.putShort(0) // cbWorkingDir
        infoPacket.write(hdr.array())

        infoPacket.write(domainBytes); infoPacket.write(byteArrayOf(0, 0))
        infoPacket.write(userBytes); infoPacket.write(byteArrayOf(0, 0))
        infoPacket.write(passBytes); infoPacket.write(byteArrayOf(0, 0))
        infoPacket.write(byteArrayOf(0, 0)) // AlternateShell null
        infoPacket.write(byteArrayOf(0, 0)) // WorkingDir null

        // Extended Info Packet (RDP 5.0+)
        val ext = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN)
        ext.putShort(0x0002) // clientAddressFamily = AF_INET
        val addrBytes = "127.0.0.1\u0000".toByteArray(Charsets.UTF_16LE)
        ext.putShort(addrBytes.size.toShort())
        ext.put(addrBytes)
        val dirBytes = "C:\\Windows\\System32\\mstscax.dll\u0000".toByteArray(Charsets.UTF_16LE)
        ext.putShort(dirBytes.size.toShort())
        ext.put(dirBytes)
        val tzAndPerf = ByteBuffer.allocate(182).order(ByteOrder.LITTLE_ENDIAN)
        tzAndPerf.position(176)
        // PERF_ENABLE_FONT_SMOOTHING (0x00000080) | PERF_ENABLE_DESKTOP_COMPOSITION (0x00000100) | PERF_DISABLE_WALLPAPER (0x00000001)
        tzAndPerf.putInt(0x00000181)
        tzAndPerf.putShort(0)
        infoPacket.write(ext.array(), 0, ext.position())
        infoPacket.write(tzAndPerf.array())

        val rawInfoBytes = infoPacket.toByteArray()
        val rdpKeys = standardRdpKeys
        return if (rdpKeys != null) {
            val mac = RdpCryptoAndBitmapEngine.computeStandardRdpMac(rdpKeys.macKey, rawInfoBytes, standardRdpEncryptCount++)
            val encrypted = rdpKeys.encryptCipher.process(rawInfoBytes)
            val secHdr = byteArrayOf(0x48, 0x00, 0x00, 0x00) // SEC_INFO_PKT | SEC_ENCRYPT
            wrapInMcsSendDataRequest(userChannelId, ioChannelId, secHdr + mac + encrypted)
        } else {
            val secHdr = byteArrayOf(0x40, 0x00, 0x00, 0x00) // SEC_INFO_PKT
            wrapInMcsSendDataRequest(userChannelId, ioChannelId, secHdr + rawInfoBytes)
        }
    }

    private fun buildConfirmActivePdu(
        userChannelId: Int,
        ioChannelId: Int,
        shareId: Int,
        width: Int,
        height: Int,
        bpp: Int
    ): ByteArray {
        val capsList = ByteArrayOutputStream()
        var numCaps = 0

        // 1. CAPSTYPE_GENERAL (0x0001, 24 bytes) - MS-RDPBCGR 2.2.7.1.1
        val genCap = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        genCap.putShort(0x0001).putShort(24)
        genCap.putShort(0x0001).putShort(0x0003) // osMajor=Windows (1), osMinor=NT (3)
        genCap.putShort(0x0200) // protocolVersion = TS_CAPS_PROTOCOLVERSION (0x0200)
        genCap.putShort(0)      // pad2octetsA = 0
        genCap.putShort(0)      // generalCompressionTypes = 0
        genCap.putShort(0x040D) // FASTPATH_OUTPUT_SUPPORTED(0x01) | LONG_CREDENTIALS(0x04) | AUTORECONNECT(0x08) | NO_BITMAP_COMPRESSION_HDR(0x0400)
        genCap.putShort(0)      // updateCapabilityFlag = 0
        genCap.putShort(0)      // remoteUnshareFlag = 0
        genCap.putShort(0)      // generalCompressionLevel = 0
        genCap.put(1)           // refreshRectSupport = TRUE (1)
        genCap.put(1)           // suppressOutputSupport = TRUE (1)
        capsList.write(genCap.array()); numCaps++

        // 2. CAPSTYPE_BITMAP (0x0002, 28 bytes) - MS-RDPBCGR 2.2.7.1.2
        val negotiatedBpp = bpp.coerceIn(15, 32)
        val bmpCap = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        bmpCap.putShort(0x0002).putShort(28)
        bmpCap.putShort(negotiatedBpp.toShort()) // preferredBitsPerPixel (matches DemandActive)
        bmpCap.putShort(1).putShort(1).putShort(1) // receive1Bit, receive4Bits, receive8Bits = TRUE
        bmpCap.putShort(width.toShort())
        bmpCap.putShort(height.toShort())
        bmpCap.putShort(0) // pad2octets = 0
        bmpCap.putShort(1) // desktopResizeFlag = TRUE (1)
        bmpCap.putShort(1) // bitmapCompressionFlag = TRUE (1)
        bmpCap.put(0)      // highColorFlags = 0
        bmpCap.put(0x08)   // drawingFlags = DRAW_ALLOW_SKIP_ALPHA (0x08)
        bmpCap.putShort(1) // multipleRectangleSupport = TRUE (1)
        bmpCap.putShort(0) // pad2octetsB = 0
        capsList.write(bmpCap.array()); numCaps++

        // 3. CAPSTYPE_ORDER (0x0003, 88 bytes) - MS-RDPBCGR 2.2.7.1.3
        // Critical for Windows RDP: `orderFlags` at byte offset 34 MUST have NEGOTIATEORDERSUPPORT (0x0002) set,
        // and `maximumOrderLevel` at byte offset 30 MUST be ORD_LEVEL_1_ORDERS (1), otherwise Windows Server
        // rejects Confirm Active PDU with SetErrorInfo 0x000010EA (ERRINFO_BADCAPABILITIES)!
        val ordCap = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN)
        ordCap.putShort(0x0003).putShort(88)
        ordCap.position(20)
        ordCap.putInt(0)        // pad4octetsA (offset 20..23)
        ordCap.putShort(1)      // desktopSaveXGranularity = 1 (offset 24..25)
        ordCap.putShort(20)     // desktopSaveYGranularity = 20 (offset 26..27)
        ordCap.putShort(0)      // pad2octetsA = 0 (offset 28..29)
        ordCap.putShort(1)      // maximumOrderLevel = ORD_LEVEL_1_ORDERS (offset 30..31)
        ordCap.putShort(0)      // numberFonts = 0 (offset 32..33)
        ordCap.putShort(0x002A) // orderFlags = NEGOTIATEORDERSUPPORT(0x02) | ZEROBOUNDSDELTASSUPPORT(0x08) | COLORINDEXSUPPORT(0x20) (offset 34..35)
        ordCap.position(76)
        ordCap.putInt(230400)   // desktopSaveSize = 480 * 480 = 230400 (offset 76..79)
        capsList.write(ordCap.array()); numCaps++

        // 4. CAPSTYPE_BITMAPCACHE (0x0004, 40 bytes) - MS-RDPBCGR 2.2.7.1.4
        val cacheCap = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        cacheCap.putShort(0x0004).putShort(40)
        capsList.write(cacheCap.array()); numCaps++

        // 5. CAPSTYPE_CONTROL (0x0005, 12 bytes) - MS-RDPBCGR 2.2.7.1.7
        val ctrlCap = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        ctrlCap.putShort(0x0005).putShort(12)
        ctrlCap.putShort(0).putShort(0).putShort(0x0002).putShort(0x0002)
        capsList.write(ctrlCap.array()); numCaps++

        // 6. CAPSTYPE_ACTIVATION (0x0007, 12 bytes) - MS-RDPBCGR 2.2.7.1.8
        val actCap = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        actCap.putShort(0x0007).putShort(12)
        capsList.write(actCap.array()); numCaps++

        // 7. CAPSTYPE_POINTER (0x0008, 10 bytes) - MS-RDPBCGR 2.2.7.1.5
        val ptrCap = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        ptrCap.putShort(0x0008).putShort(10)
        ptrCap.putShort(1).putShort(20).putShort(20)
        capsList.write(ptrCap.array()); numCaps++

        // 8. CAPSTYPE_SHARE (0x0009, 8 bytes) - MS-RDPBCGR 2.2.7.1.9
        val shareCap = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        shareCap.putShort(0x0009).putShort(8).putShort(0).putShort(0)
        capsList.write(shareCap.array()); numCaps++

        // 9. CAPSTYPE_COLORCACHE (0x000A, 8 bytes) - MS-RDPBCGR 2.2.7.1.10
        val colCap = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        colCap.putShort(0x000A).putShort(8).putShort(6).putShort(0)
        capsList.write(colCap.array()); numCaps++

        // 10. CAPSTYPE_SOUND (0x000C, 8 bytes) - MS-RDPBCGR 2.2.7.1.11
        val sndCap = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        sndCap.putShort(0x000C).putShort(8).putShort(1).putShort(0)
        capsList.write(sndCap.array()); numCaps++

        // 11. CAPSTYPE_INPUT (0x000D, 88 bytes) - MS-RDPBCGR 2.2.7.1.6
        val inpCap = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN)
        inpCap.putShort(0x000D).putShort(88)
        inpCap.putShort(0x0015) // INPUT_FLAG_SCANCODES(0x01) | INPUT_FLAG_MOUSEX(0x04) | INPUT_FLAG_UNICODE(0x10)
        inpCap.putShort(0)
        inpCap.putInt(0x00000409)
        inpCap.putInt(4)
        inpCap.putInt(0)
        inpCap.putInt(12)
        capsList.write(inpCap.array()); numCaps++

        // 12. CAPSTYPE_FONT (0x000E, 8 bytes) - MS-RDPBCGR 2.2.7.1.12
        val fontCap = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fontCap.putShort(0x000E).putShort(8).putShort(1).putShort(0)
        capsList.write(fontCap.array()); numCaps++

        // 13. CAPSTYPE_BRUSH (0x000F, 8 bytes) - MS-RDPBCGR 2.2.7.1.13
        val brushCap = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        brushCap.putShort(0x000F).putShort(8).putInt(0)
        capsList.write(brushCap.array()); numCaps++

        // 14. CAPSTYPE_OFFSCREENCACHE (0x0011, 12 bytes) - MS-RDPBCGR 2.2.7.1.15
        val offCap = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        offCap.putShort(0x0011).putShort(12).putInt(0).putShort(0).putShort(0)
        capsList.write(offCap.array()); numCaps++

        // 15. CAPSTYPE_VIRTUALCHANNEL (0x0014, 8 bytes) - MS-RDPBCGR 2.2.7.1.10
        val vcCap = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        vcCap.putShort(0x0014).putShort(8).putInt(0)
        capsList.write(vcCap.array()); numCaps++

        // 16. CAPSTYPE_MULTIFRAGMENTUPDATE (0x001A, 8 bytes) - MS-RDPBCGR 2.2.7.2.6
        val mfuCap = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        mfuCap.putShort(0x001A).putShort(8).putInt(0x00038000)
        capsList.write(mfuCap.array()); numCaps++

        val capsBytes = capsList.toByteArray()
        val srcDesc = "MSTSC\u0000".toByteArray(Charsets.US_ASCII)
        val bodyLen = 4 + 2 + 2 + 2 + srcDesc.size + 2 + 2 + capsBytes.size
        val totalControlLen = 6 + bodyLen

        val pdu = ByteBuffer.allocate(totalControlLen).order(ByteOrder.LITTLE_ENDIAN)
        pdu.putShort(totalControlLen.toShort())
        pdu.putShort(0x0013) // PDUTYPE_CONFIRMACTIVEPDU
        pdu.putShort(userChannelId.toShort())
        pdu.putInt(shareId)
        pdu.putShort(0x03EA) // originatorId = 1002
        pdu.putShort(srcDesc.size.toShort())
        pdu.putShort((4 + capsBytes.size).toShort())
        pdu.put(srcDesc)
        pdu.putShort(numCaps.toShort())
        pdu.putShort(0)
        pdu.put(capsBytes)

        return wrapShareControlWithOptionalSecurity(userChannelId, ioChannelId, pdu.array())
    }

    private fun buildSynchronizePdu(userChannelId: Int, ioChannelId: Int, shareId: Int): ByteArray {
        val body = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1)
            .putShort(userChannelId.toShort())
            .array()
        return buildShareDataPdu(userChannelId, ioChannelId, shareId, pduType2 = 0x1F, body = body)
    }

    private fun buildControlPdu(userChannelId: Int, ioChannelId: Int, shareId: Int, action: Int): ByteArray {
        val body = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(action.toShort())
            .putShort(0)
            .putInt(0)
            .array()
        return buildShareDataPdu(userChannelId, ioChannelId, shareId, pduType2 = 0x14, body = body)
    }

    private fun buildFontListPdu(userChannelId: Int, ioChannelId: Int, shareId: Int): ByteArray {
        val body = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .putShort(0)
            .putShort(0x0003)
            .putShort(0x0032)
            .array()
        return buildShareDataPdu(userChannelId, ioChannelId, shareId, pduType2 = 0x27, body = body)
    }

    private fun buildRefreshRectPdu(userChannelId: Int, ioChannelId: Int, shareId: Int, width: Int, height: Int): ByteArray {
        val body = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(1)
            .put(0).put(0).put(0)
            .putShort(0)
            .putShort(0)
            .putShort((width - 1).coerceAtLeast(1).toShort())
            .putShort((height - 1).coerceAtLeast(1).toShort())
            .array()
        return buildShareDataPdu(userChannelId, ioChannelId, shareId, pduType2 = 0x21, body = body)
    }

    private fun buildSlowPathInputPdu(
        userChannelId: Int,
        ioChannelId: Int,
        shareId: Int,
        eventTime: Int = 0,
        messageType: Int,
        paramFlags: Int,
        param1: Int,
        param2: Int
    ): ByteArray {
        val body = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1)
            .putShort(0)
            .putInt(eventTime)
            .putShort(messageType.toShort())
            .putShort(paramFlags.toShort())
            .putShort(param1.toShort())
            .putShort(param2.toShort())
            .array()
        return buildShareDataPdu(userChannelId, ioChannelId, shareId, pduType2 = 0x1C, body = body)
    }

    private fun buildShareDataPdu(
        userChannelId: Int,
        ioChannelId: Int,
        shareId: Int,
        pduType2: Int,
        body: ByteArray
    ): ByteArray {
        val totalLen = 18 + body.size
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(totalLen.toShort())
        buf.putShort(0x0017) // PDUTYPE_DATAPDU
        buf.putShort(userChannelId.toShort())
        buf.putInt(shareId)
        buf.put(0)
        buf.put(1)
        buf.putShort((body.size + 4).toShort())
        buf.put(pduType2.toByte())
        buf.put(0)
        buf.putShort(0)
        buf.put(body)
        return wrapShareControlWithOptionalSecurity(userChannelId, ioChannelId, buf.array())
    }

    private fun wrapShareControlWithOptionalSecurity(
        userChannelId: Int,
        ioChannelId: Int,
        shareControlPdu: ByteArray
    ): ByteArray {
        val rdpKeys = standardRdpKeys
        return if (rdpKeys != null) {
            val mac = RdpCryptoAndBitmapEngine.computeStandardRdpMac(rdpKeys.macKey, shareControlPdu, standardRdpEncryptCount++)
            val encrypted = rdpKeys.encryptCipher.process(shareControlPdu)
            val secHdr = byteArrayOf(0x08, 0x00, 0x00, 0x00) // SEC_ENCRYPT
            wrapInMcsSendDataRequest(userChannelId, ioChannelId, secHdr + mac + encrypted)
        } else {
            wrapInMcsSendDataRequest(userChannelId, ioChannelId, shareControlPdu)
        }
    }

    private fun wrapInMcsSendDataRequest(userChannelId: Int, channelId: Int, payload: ByteArray): ByteArray {
        val initiator = (userChannelId - 1001).coerceAtLeast(0)
        val lenBytes = if (payload.size < 0x80) {
            byteArrayOf(payload.size.toByte())
        } else {
            byteArrayOf(
                (((payload.size ushr 8) and 0x7F) or 0x80).toByte(),
                (payload.size and 0xFF).toByte()
            )
        }
        val mcsHdr = ByteArray(6 + lenBytes.size)
        mcsHdr[0] = 0x64.toByte() // SendDataRequest
        mcsHdr[1] = ((initiator ushr 8) and 0xFF).toByte()
        mcsHdr[2] = (initiator and 0xFF).toByte()
        mcsHdr[3] = ((channelId ushr 8) and 0xFF).toByte()
        mcsHdr[4] = (channelId and 0xFF).toByte()
        mcsHdr[5] = 0x70.toByte()
        System.arraycopy(lenBytes, 0, mcsHdr, 6, lenBytes.size)

        return wrapInTpktAndX224Data(mcsHdr + payload)
    }

    private fun wrapInTpktAndX224Data(mcsPdu: ByteArray): ByteArray {
        val totalLen = 4 + 3 + mcsPdu.size
        val out = ByteBuffer.allocate(totalLen)
        out.put(0x03).put(0x00).putShort(totalLen.toShort())
        out.put(0x02).put(0xF0.toByte()).put(0x80.toByte())
        out.put(mcsPdu)
        return out.array()
    }

    // =========================================================================
    // Socket & TLS Helpers (SNI-safe & Reverse-DNS-safe for LAN IPv4 addresses)
    // =========================================================================
    private val ipv4Regex = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    private fun createNoReverseDnsSocketAddress(host: String, port: Int): InetSocketAddress {
        val match = ipv4Regex.matchEntire(host.trim())
        if (match != null) {
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: 0 }
            if (parts.all { it in 0..255 }) {
                val ipBytes = byteArrayOf(
                    parts[0].toByte(),
                    parts[1].toByte(),
                    parts[2].toByte(),
                    parts[3].toByte()
                )
                // Passing `host` alongside `ipBytes` populates InetAddress.hostName without any reverse DNS PTR query!
                val addr = InetAddress.getByAddress(host, ipBytes)
                return InetSocketAddress(addr, port)
            }
        }
        return InetSocketAddress(host, port)
    }

    private fun upgradeSocketToTls(tcpSocket: Socket, host: String, port: Int): SSLSocket {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        )
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())
        val factory: SSLSocketFactory = sslContext.socketFactory
        val sslSocket = factory.createSocket(tcpSocket, host, port, true) as SSLSocket
        sslSocket.useClientMode = true
        sslSocket.soTimeout = 10_000

        // Prefer TLSv1.2 (and TLSv1.1/TLSv1) to avoid TLS 1.3 post-handshake ticket stalls during CredSSP/MCS on Windows SChannel
        val supported = sslSocket.supportedProtocols ?: emptyArray()
        val preferred = supported.filter { it == "TLSv1.2" || it == "TLSv1.1" || it == "TLSv1" }
        if (preferred.isNotEmpty()) {
            sslSocket.enabledProtocols = preferred.toTypedArray()
        }

        // Disable hostname verification & strip SNI for literal IPv4/IPv6 addresses per RFC 6066 / Windows SChannel
        try {
            val params = sslSocket.sslParameters
            params.endpointIdentificationAlgorithm = null
            if (ipv4Regex.matches(host.trim()) || host.contains(":")) {
                params.serverNames = emptyList()
            }
            sslSocket.sslParameters = params
        } catch (_: Throwable) {
        }

        sslSocket.startHandshake()
        return sslSocket
    }

    private fun readTpktPacket(input: InputStream): ByteArray {
        val hdr = readExactBytes(input, 4)
        if (hdr[0] != 0x03.toByte()) {
            throw IllegalStateException("Expected TPKT version 3 (0x03), received 0x${(hdr[0].toInt() and 0xFF).toString(16)}")
        }
        val totalLen = ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
        val body = readExactBytes(input, (totalLen - 4).coerceAtLeast(0))
        return hdr + body
    }

    private fun readDerSequencePacket(input: InputStream): ByteArray {
        val tag = input.read()
        if (tag < 0) throw EOFException("Connection closed during CredSSP handshake")
        val firstLen = input.read()
        if (firstLen < 0) throw EOFException("Truncated DER length")

        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(firstLen)

        val contentLen: Int
        if ((firstLen and 0x80) == 0) {
            contentLen = firstLen
        } else {
            val numLenBytes = firstLen and 0x7F
            val lenBytes = readExactBytes(input, numLenBytes)
            out.write(lenBytes)
            var acc = 0
            for (b in lenBytes) {
                acc = (acc shl 8) or (b.toInt() and 0xFF)
            }
            contentLen = acc
        }
        val content = readExactBytes(input, contentLen)
        out.write(content)
        return out.toByteArray()
    }

    private fun readExactBytes(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var readSoFar = 0
        while (readSoFar < length) {
            val n = input.read(buf, readSoFar, length - readSoFar)
            if (n < 0) throw EOFException("Unexpected EOF after $readSoFar/$length bytes")
            readSoFar += n
        }
        return buf
    }

    private fun rdpNegFailureDescription(code: Int): String = when (code) {
        0x01 -> "SSL_REQUIRED_BY_SERVER (Server requires TLS security)"
        0x02 -> "SSL_NOT_ALLOWED_BY_SERVER (Server requires Standard RDP Security)"
        0x03 -> "SSL_CERT_NOT_ON_SERVER (Server lacks a valid TLS certificate)"
        0x04 -> "INCONSISTENT_FLAGS"
        0x05 -> "HYBRID_REQUIRED_BY_SERVER (Server requires NLA / CredSSP authentication)"
        0x06 -> "SSL_WITH_USER_AUTH_REQUIRED_BY_SERVER"
        else -> "RDP_NEG_FAILURE code 0x${code.toString(16)}"
    }
}
