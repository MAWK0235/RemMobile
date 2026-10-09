package com.example.protocol

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.data.local.ConnectionProfileEntity
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import java.util.Vector
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

// =============================================================================
// 1. Real SSH Bastion Local Port Forwarding Tunnel (via JSch)
// =============================================================================
class SshBastionTunnel(
    private val profile: ConnectionProfileEntity
) {
    private var jschSession: Session? = null
    var allocatedLocalPort: Int = -1
        private set

    fun establishTunnel(onTrace: (String) -> Unit): Int {
        val host = profile.sshTunnelHost.trim()
        val port = profile.sshTunnelPort
        val user = profile.sshTunnelUsername.trim().ifBlank { profile.username.trim() }
        if (user.isBlank()) {
            throw NeedSshTunnelAuthException("SSH Tunnel username is required for bastion $host:$port.")
        }

        onTrace("[ssh-tunnel] Connecting to SSH bastion $user@$host:$port...")
        val jsch = JSch()
        val session = jsch.getSession(user, host, port)
        val pass = profile.sshTunnelPassword.ifEmpty { profile.password }
        if (pass.isNotEmpty()) {
            session.setPassword(pass)
        }
        val config = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "publickey,password,keyboard-interactive")
        }
        session.setConfig(config)
        session.connect(6000)
        val boundPort = session.setPortForwardingL(0, profile.server.trim(), profile.port)
        jschSession = session
        allocatedLocalPort = boundPort
        onTrace("[ssh-tunnel] Tunnel established: 127.0.0.1:$boundPort -> ${profile.server}:${profile.port}")
        return boundPort
    }

    fun close() {
        try {
            jschSession?.disconnect()
        } catch (_: Exception) {
        }
    }

    class NeedSshTunnelAuthException(message: String) : Exception(message)
}

// =============================================================================
// 2. Real VNC (RFB 003.008 / 003.007 / 003.003) Live Session Client
// =============================================================================
class VncLiveSessionClient(
    private val scope: CoroutineScope,
    private val connectHost: String,
    private val connectPort: Int,
    private val profile: ConnectionProfileEntity,
    private val callbacks: RdpSessionCallbacks
) {
    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var dataOut: DataOutputStream? = null

    @Volatile
    private var isConnectedAndActive: Boolean = false

    private var sessionJob: Job? = null
    private var inputWorkerJob: Job? = null
    private val inputQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val writeLock = Any()

    private var fbWidth: Int = 1024
    private var fbHeight: Int = 768
    private var framebufferPixels: IntArray = IntArray(fbWidth * fbHeight) { 0xFF000000.toInt() }
    private var decodedFrames: Int = 0
    private var bytesReceivedTotal: Long = 0L
    private var streamStartEpochMs: Long = System.currentTimeMillis()

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
            runVncSession()
        }
    }

    fun disconnect() {
        isConnectedAndActive = false
        inputWorkerJob?.cancel()
        sessionJob?.cancel()
        try {
            dataOut?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private suspend fun runVncSession() = withContext(Dispatchers.IO) {
        try {
            callbacks.onTraceLog("[vnc-rfb] Opening TCP socket to $connectHost:$connectPort...")
            callbacks.onPhaseChanged(SessionConnectionPhase.CONNECTING_SOCKET, "Connecting to $connectHost:$connectPort...")

            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(connectHost, connectPort), 5000)
            s.soTimeout = 10000
            socket = s

            val input = DataInputStream(s.getInputStream())
            val output = DataOutputStream(s.getOutputStream())
            dataOut = output

            // 1. Read Server ProtocolVersion (12 bytes: "RFB xxx.yyy\n")
            val verBytes = ByteArray(12)
            input.readFully(verBytes)
            val serverVer = String(verBytes, Charsets.US_ASCII).trim()
            callbacks.onTraceLog("[vnc-rfb] Server version: $serverVer. Replying with RFB 003.008...")
            callbacks.onPhaseChanged(SessionConnectionPhase.NEGOTIATING_SECURITY, "Negotiating $serverVer Security...")

            val isRfb33 = serverVer.endsWith("003.003")
            val clientVer = if (isRfb33) "RFB 003.003\n" else "RFB 003.008\n"
            output.write(clientVer.toByteArray(Charsets.US_ASCII))
            output.flush()

            // 2. Security Negotiation
            val selectedSecType: Int
            if (isRfb33) {
                selectedSecType = input.readInt()
                if (selectedSecType == 0) {
                    val reasonLen = input.readInt()
                    val reasonBytes = ByteArray(reasonLen.coerceIn(0, 4096))
                    input.readFully(reasonBytes)
                    throw IllegalStateException("VNC Server rejected connection: ${String(reasonBytes, Charsets.UTF_8)}")
                }
            } else {
                val numSecTypes = input.readUnsignedByte()
                if (numSecTypes == 0) {
                    val reasonLen = input.readInt()
                    val reasonBytes = ByteArray(reasonLen.coerceIn(0, 4096))
                    input.readFully(reasonBytes)
                    throw IllegalStateException("VNC Server rejected connection: ${String(reasonBytes, Charsets.UTF_8)}")
                }
                val secTypes = ByteArray(numSecTypes)
                input.readFully(secTypes)
                val typesList = secTypes.map { it.toInt() and 0xFF }
                callbacks.onTraceLog("[vnc-rfb] Server offered security types: $typesList")

                selectedSecType = when {
                    typesList.contains(1) -> 1 // None
                    typesList.contains(2) -> 2 // VNC Authentication
                    else -> throw IllegalStateException("Unsupported VNC security types $typesList (configure SSH tunnel or VNC password auth)")
                }
                output.writeByte(selectedSecType)
                output.flush()
            }

            if (selectedSecType == 2) {
                if (profile.password.isEmpty()) {
                    s.close()
                    callbacks.onAuthenticationRequired(
                        reason = "VNC Server $connectHost:$connectPort requires VNC Password Authentication (RFB Security Type 2).",
                        certSubject = null,
                        certFingerprint = null
                    )
                    return@withContext
                }
                val challenge = ByteArray(16)
                input.readFully(challenge)
                val response = encryptVncDesChallenge(challenge, profile.password)
                output.write(response)
                output.flush()
            }

            if (!isRfb33 || selectedSecType == 2) {
                val secResult = input.readInt()
                if (secResult != 0) {
                    s.close()
                    callbacks.onAuthenticationRequired(
                        reason = "VNC Authentication failed on $connectHost:$connectPort (invalid VNC password).",
                        certSubject = null,
                        certFingerprint = null
                    )
                    return@withContext
                }
            }

            // 3. ClientInit (shared = 1) & ServerInit
            output.writeByte(1)
            output.flush()

            fbWidth = input.readUnsignedShort().coerceIn(64, 4096)
            fbHeight = input.readUnsignedShort().coerceIn(64, 2160)
            val pixelFormatBytes = ByteArray(16)
            input.readFully(pixelFormatBytes)
            val nameLen = input.readInt().coerceIn(0, 1024)
            val nameBytes = ByteArray(nameLen)
            input.readFully(nameBytes)
            val desktopName = String(nameBytes, Charsets.UTF_8)

            framebufferPixels = IntArray(fbWidth * fbHeight) { 0xFF000000.toInt() }
            callbacks.onResolutionNegotiated(fbWidth, fbHeight, 32)
            callbacks.onTraceLog("[vnc-rfb] ServerInit: '$desktopName' (${fbWidth}x${fbHeight}). Setting 32-bit RGBX pixel format...")

            // 4. Send SetPixelFormat (32-bit true-color, 8 bits per channel, R shift 16, G shift 8, B shift 0)
            synchronized(writeLock) {
                output.writeByte(0) // message-type = 0 (SetPixelFormat)
                output.write(byteArrayOf(0, 0, 0)) // padding
                output.writeByte(32) // bits-per-pixel
                output.writeByte(24) // depth
                output.writeByte(0)  // big-endian-flag = false
                output.writeByte(1)  // true-colour-flag = true
                output.writeShort(255) // red-max
                output.writeShort(255) // green-max
                output.writeShort(255) // blue-max
                output.writeByte(16) // red-shift
                output.writeByte(8)  // green-shift
                output.writeByte(0)  // blue-shift
                output.write(byteArrayOf(0, 0, 0)) // padding

                // 5. Send SetEncodings (CopyRect = 1, RRE = 2, Hextile = 5, Raw = 0, DesktopSize = -223)
                output.writeByte(2) // message-type = 2 (SetEncodings)
                output.writeByte(0) // padding
                output.writeShort(5) // number-of-encodings
                output.writeInt(1)    // CopyRect
                output.writeInt(2)    // RRE
                output.writeInt(5)    // Hextile
                output.writeInt(0)    // Raw
                output.writeInt(-223) // Pseudo-encoding DesktopSize
                output.flush()
            }

            isConnectedAndActive = true
            s.soTimeout = 0
            streamStartEpochMs = System.currentTimeMillis()
            callbacks.onPhaseChanged(
                SessionConnectionPhase.STREAMING_FRAMEBUFFER,
                "VNC Connected: $desktopName (${fbWidth}×${fbHeight})"
            )

            // Request full initial framebuffer
            sendFramebufferUpdateRequest(incremental = false)

            while (scope.isActive) {
                val msgType = input.readUnsignedByte()
                when (msgType) {
                    0 -> {
                        // FramebufferUpdate
                        input.readUnsignedByte() // padding
                        val numRects = input.readUnsignedShort()
                        for (r in 0 until numRects) {
                            val rx = input.readUnsignedShort()
                            val ry = input.readUnsignedShort()
                            val rw = input.readUnsignedShort()
                            val rh = input.readUnsignedShort()
                            val encoding = input.readInt()
                            decodeVncRectangle(input, rx, ry, rw, rh, encoding)
                        }
                        emitVncBitmap()
                        sendFramebufferUpdateRequest(incremental = true)
                    }
                    1 -> {
                        // SetColourMapEntries
                        input.readUnsignedByte()
                        input.readUnsignedShort()
                        val numColors = input.readUnsignedShort()
                        input.skipBytes(numColors * 6)
                    }
                    2 -> {
                        // Bell
                    }
                    3 -> {
                        // ServerCutText
                        input.skipBytes(3)
                        val len = input.readInt().coerceIn(0, 65536)
                        val cutBytes = ByteArray(len)
                        input.readFully(cutBytes)
                    }
                }
            }
        } catch (e: Exception) {
            val isCancelled = !scope.isActive
            disconnect()
            if (!isCancelled) {
                val msg = e.localizedMessage ?: e.javaClass.simpleName
                callbacks.onTraceLog("[error] VNC session terminated: $msg")
                callbacks.onDisconnected(
                    errorTitle = "Unable to complete VNC session with $connectHost:$connectPort",
                    errorDetails = msg
                )
            }
        }
    }

    private fun decodeVncRectangle(
        input: DataInputStream,
        rx: Int,
        ry: Int,
        rw: Int,
        rh: Int,
        encoding: Int
    ) {
        when (encoding) {
            -223 -> {
                // DesktopSize pseudo-encoding
                if (rw in 64..4096 && rh in 64..2160) {
                    fbWidth = rw
                    fbHeight = rh
                    framebufferPixels = IntArray(fbWidth * fbHeight) { 0xFF000000.toInt() }
                    callbacks.onResolutionNegotiated(fbWidth, fbHeight, 32)
                }
            }
            0 -> {
                // Raw Encoding (4 bytes per pixel)
                val rowBuf = ByteArray(rw * 4)
                for (y in 0 until rh) {
                    input.readFully(rowBuf)
                    bytesReceivedTotal += rowBuf.size
                    val dstY = ry + y
                    if (dstY !in 0 until fbHeight) continue
                    val rowOffset = dstY * fbWidth
                    for (x in 0 until rw) {
                        val dstX = rx + x
                        if (dstX in 0 until fbWidth) {
                            val i = x * 4
                            val b = rowBuf[i].toInt() and 0xFF
                            val g = rowBuf[i + 1].toInt() and 0xFF
                            val r = rowBuf[i + 2].toInt() and 0xFF
                            framebufferPixels[rowOffset + dstX] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
            }
            1 -> {
                // CopyRect Encoding
                val srcX = input.readUnsignedShort()
                val srcY = input.readUnsignedShort()
                val temp = IntArray(rw * rh)
                for (y in 0 until rh) {
                    val sy = (srcY + y).coerceIn(0, fbHeight - 1)
                    for (x in 0 until rw) {
                        val sx = (srcX + x).coerceIn(0, fbWidth - 1)
                        temp[y * rw + x] = framebufferPixels[sy * fbWidth + sx]
                    }
                }
                for (y in 0 until rh) {
                    val dy = ry + y
                    if (dy !in 0 until fbHeight) continue
                    for (x in 0 until rw) {
                        val dx = rx + x
                        if (dx in 0 until fbWidth) {
                            framebufferPixels[dy * fbWidth + dx] = temp[y * rw + x]
                        }
                    }
                }
            }
            2 -> {
                // RRE Encoding
                val numSubrects = input.readInt()
                val bg = readVncPixel32(input)
                fillRect(rx, ry, rw, rh, bg)
                for (i in 0 until numSubrects) {
                    val pix = readVncPixel32(input)
                    val sx = input.readUnsignedShort()
                    val sy = input.readUnsignedShort()
                    val sw = input.readUnsignedShort()
                    val sh = input.readUnsignedShort()
                    fillRect(rx + sx, ry + sy, sw, sh, pix)
                }
            }
            5 -> {
                // Hextile Encoding
                var bg = 0xFF000000.toInt()
                var fg = 0xFFFFFFFF.toInt()
                var ty = ry
                while (ty < ry + rh) {
                    val th = minOf(16, ry + rh - ty)
                    var tx = rx
                    while (tx < rx + rw) {
                        val tw = minOf(16, rx + rw - tx)
                        val subencoding = input.readUnsignedByte()
                        if ((subencoding and 0x01) != 0) {
                            // Raw tile
                            decodeVncRectangle(input, tx, ty, tw, th, 0)
                        } else {
                            if ((subencoding and 0x02) != 0) bg = readVncPixel32(input)
                            if ((subencoding and 0x04) != 0) fg = readVncPixel32(input)
                            fillRect(tx, ty, tw, th, bg)
                            if ((subencoding and 0x08) != 0) {
                                val numSubrects = input.readUnsignedByte()
                                val subrectsColoured = (subencoding and 0x10) != 0
                                for (s in 0 until numSubrects) {
                                    val color = if (subrectsColoured) readVncPixel32(input) else fg
                                    val xy = input.readUnsignedByte()
                                    val wh = input.readUnsignedByte()
                                    val sx = (xy ushr 4) and 0x0F
                                    val sy = xy and 0x0F
                                    val sw = ((wh ushr 4) and 0x0F) + 1
                                    val sh = (wh and 0x0F) + 1
                                    fillRect(tx + sx, ty + sy, sw, sh, color)
                                }
                            }
                        }
                        tx += 16
                    }
                    ty += 16
                }
            }
            else -> {
                throw EOFException("Unsupported VNC encoding $encoding")
            }
        }
    }

    private fun readVncPixel32(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        input.readUnsignedByte()
        bytesReceivedTotal += 4
        return (0xFF shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        for (row in y until (y + h).coerceAtMost(fbHeight)) {
            if (row < 0) continue
            val rowOffset = row * fbWidth
            for (col in x until (x + w).coerceAtMost(fbWidth)) {
                if (col >= 0) {
                    framebufferPixels[rowOffset + col] = color
                }
            }
        }
    }

    private fun emitVncBitmap() {
        decodedFrames++
        val elapsedSec = ((System.currentTimeMillis() - streamStartEpochMs) / 1000f).coerceAtLeast(0.25f)
        val mbps = ((bytesReceivedTotal * 8f) / (elapsedSec * 1_000_000f)).coerceIn(0.1f, 500f)
        val bmp = Bitmap.createBitmap(framebufferPixels, fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
        callbacks.onFramebufferUpdated(bmp.asImageBitmap(), decodedFrames, mbps)
    }

    private fun sendFramebufferUpdateRequest(incremental: Boolean) {
        val out = dataOut ?: return
        synchronized(writeLock) {
            out.writeByte(3) // FramebufferUpdateRequest
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(fbWidth)
            out.writeShort(fbHeight)
            out.flush()
        }
    }

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

    private fun writePointerEventSync(x: Int, y: Int, buttonMask: Int) {
        val out = dataOut ?: return
        synchronized(writeLock) {
            out.writeByte(5) // PointerEvent
            out.writeByte(buttonMask)
            out.writeShort(x)
            out.writeShort(y)
            out.flush()
        }
    }

    fun sendPointerEvent(normX: Float, normY: Float, buttonMask: Int) {
        if (!isConnectedAndActive) return
        val x = normToServerX(normX)
        val y = normToServerY(normY)
        inputQueue.trySend {
            lastSentPointerX = x
            lastSentPointerY = y
            writePointerEventSync(x, y, buttonMask)
        }
    }

    fun sendMouseClick(normX: Float, normY: Float, isRightClick: Boolean = false, isMiddleClick: Boolean = false) {
        if (!isConnectedAndActive) return
        val x = normToServerX(normX)
        val y = normToServerY(normY)
        val mask = when {
            isMiddleClick -> 2 // VNC Button 2 (Middle)
            isRightClick -> 4  // VNC Button 3 (Right)
            else -> 1          // VNC Button 1 (Left)
        }
        inputQueue.trySend {
            writePointerEventSync(x, y, 0)
            writePointerEventSync(x, y, mask)
            delay(18)
            writePointerEventSync(x, y, 0)
            lastSentPointerX = x
            lastSentPointerY = y
        }
    }

    fun sendMouseWheelScroll(normX: Float, normY: Float, verticalScrollDelta: Float, horizontalScrollDelta: Float = 0f) {
        if (!isConnectedAndActive) return
        val x = normToServerX(normX)
        val y = normToServerY(normY)
        inputQueue.trySend {
            if (kotlin.math.abs(verticalScrollDelta) > 0.01f) {
                // RFB / X11 convention: Button 4 (0x08) = Wheel Up, Button 5 (0x10) = Wheel Down
                val wheelMask = if (verticalScrollDelta > 0f) 0x10 else 0x08
                writePointerEventSync(x, y, wheelMask)
                delay(8)
                writePointerEventSync(x, y, 0)
            }
            if (kotlin.math.abs(horizontalScrollDelta) > 0.01f) {
                // RFB horizontal scroll: Button 6 (0x20) = Left, Button 7 (0x40) = Right
                val hMask = if (horizontalScrollDelta > 0f) 0x40 else 0x20
                writePointerEventSync(x, y, hMask)
                delay(8)
                writePointerEventSync(x, y, 0)
            }
            lastSentPointerX = x
            lastSentPointerY = y
        }
    }

    fun sendMouseDragStart(normX: Float, normY: Float) {
        if (!isConnectedAndActive) return
        val x = normToServerX(normX)
        val y = normToServerY(normY)
        latestDragTargetX = x
        latestDragTargetY = y
        inputQueue.trySend {
            writePointerEventSync(x, y, 0)
            delay(8)
            writePointerEventSync(x, y, 1) // Button 1 held down
            lastSentPointerX = x
            lastSentPointerY = y
        }
    }

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
                writePointerEventSync(targetX, targetY, 1) // Keep Button 1 depressed during drag
            }
        }
    }

    fun sendMouseDragEnd(normX: Float, normY: Float) {
        if (!isConnectedAndActive) return
        val x = normToServerX(normX)
        val y = normToServerY(normY)
        latestDragTargetX = -1
        latestDragTargetY = -1
        inputQueue.trySend {
            if (x != lastSentPointerX || y != lastSentPointerY) {
                writePointerEventSync(x, y, 1)
                delay(6)
            }
            writePointerEventSync(x, y, 0) // Release Button 1
            lastSentPointerX = x
            lastSentPointerY = y
        }
    }

    fun sendKeySymEvent(keySym: Int, down: Boolean) {
        if (!isConnectedAndActive) return
        inputQueue.trySend {
            val out = dataOut ?: return@trySend
            synchronized(writeLock) {
                out.writeByte(4) // KeyEvent
                out.writeByte(if (down) 1 else 0)
                out.writeShort(0) // padding
                out.writeInt(keySym)
                out.flush()
            }
        }
    }

    private fun encryptVncDesChallenge(challenge: ByteArray, password: String): ByteArray {
        val rawKey = ByteArray(8)
        val pwBytes = password.toByteArray(Charsets.US_ASCII)
        for (i in 0 until minOf(8, pwBytes.size)) {
            // RFB VNC Auth reverses bit order of each byte in the 8-byte DES key
            var b = pwBytes[i].toInt() and 0xFF
            var rev = 0
            for (bit in 0 until 8) {
                rev = (rev shl 1) or (b and 1)
                b = b ushr 1
            }
            rawKey[i] = rev.toByte()
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rawKey, "DES"))
        return cipher.doFinal(challenge)
    }
}

// =============================================================================
// 3. Real Interactive SSH & SFTP Session Client (via JSch)
// =============================================================================
interface SshSessionCallbacks {
    fun onTraceLog(line: String)
    fun onPhaseChanged(phase: SessionConnectionPhase, banner: String, latencyMs: Int)
    fun onAuthenticationRequired(reason: String)
    fun onTerminalOutputReceived(lines: List<String>)
    fun onSftpFilesListed(currentPath: String, files: List<SftpRemoteFile>)
    fun onDisconnected(errorTitle: String, errorDetails: String)
}

class SshLiveSessionClient(
    private val scope: CoroutineScope,
    private val connectHost: String,
    private val connectPort: Int,
    private val profile: ConnectionProfileEntity,
    private val callbacks: SshSessionCallbacks
) {
    private var jschSession: Session? = null
    private var shellChannel: ChannelShell? = null
    private var shellOut: OutputStream? = null
    private var sessionJob: Job? = null

    fun start() {
        sessionJob = scope.launch(Dispatchers.IO) {
            runSshSession()
        }
    }

    fun disconnect() {
        sessionJob?.cancel()
        try {
            shellChannel?.disconnect()
        } catch (_: Exception) {
        }
        try {
            jschSession?.disconnect()
        } catch (_: Exception) {
        }
    }

    private suspend fun runSshSession() = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            if (profile.username.isBlank()) {
                callbacks.onAuthenticationRequired(
                    "SSH connection to $connectHost:$connectPort requires a username and password."
                )
                return@withContext
            }

            callbacks.onTraceLog("[ssh] Connecting to ${profile.username}@$connectHost:$connectPort...")
            callbacks.onPhaseChanged(
                SessionConnectionPhase.CONNECTING_SOCKET,
                "Connecting to ${profile.username}@$connectHost:$connectPort...",
                -1
            )

            val jsch = JSch()
            val session = jsch.getSession(profile.username.trim(), connectHost, connectPort)
            if (profile.password.isNotEmpty()) {
                session.setPassword(profile.password)
            }
            val config = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "password,keyboard-interactive,publickey")
            }
            session.setConfig(config)
            session.connect(6000)
            jschSession = session

            val rtt = (System.currentTimeMillis() - startMs).toInt().coerceAtLeast(1)
            val serverVer = session.serverVersion ?: "SSH-2.0"
            callbacks.onTraceLog("[ssh] Authenticated with $serverVer (${rtt}ms). Opening interactive PTY shell...")

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPty(true)
            channel.setPtyType("xterm-256color", 120, 36, 960, 720)
            val inputStream = channel.inputStream
            shellOut = channel.outputStream
            channel.connect(4000)
            shellChannel = channel

            callbacks.onPhaseChanged(
                SessionConnectionPhase.STREAMING_FRAMEBUFFER,
                serverVer,
                rtt
            )

            if (profile.preExecCommand.isNotBlank()) {
                sendRawText(profile.preExecCommand + "\n")
            }

            val buf = ByteArray(4096)
            val ansiRegex = Regex("\u001B\\[[;\\d]*[ -/]*[@-~]")
            while (scope.isActive && channel.isConnected) {
                val read = inputStream.read(buf)
                if (read < 0) break
                if (read > 0) {
                    val rawStr = String(buf, 0, read, Charsets.UTF_8)
                    val cleaned = rawStr.replace(ansiRegex, "").replace("\r", "")
                    val lines = cleaned.split("\n").filter { it.isNotEmpty() }
                    if (lines.isNotEmpty()) {
                        callbacks.onTerminalOutputReceived(lines)
                    }
                }
            }
        } catch (e: Exception) {
            val isCancelled = !scope.isActive
            disconnect()
            if (!isCancelled) {
                val msg = e.localizedMessage ?: e.javaClass.simpleName
                if (msg.contains("Auth fail", ignoreCase = true) || msg.contains("Auth cancel", ignoreCase = true)) {
                    callbacks.onAuthenticationRequired(
                        "SSH authentication failed for ${profile.username}@$connectHost:$connectPort. Please enter valid SSH credentials."
                    )
                } else {
                    callbacks.onTraceLog("[error] SSH session failed: $msg")
                    callbacks.onDisconnected(
                        errorTitle = "Unable to connect to SSH server $connectHost:$connectPort",
                        errorDetails = msg
                    )
                }
            }
        }
    }

    fun sendRawText(payload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val out = shellOut ?: return@launch
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun resizePty(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                shellChannel?.setPtySize(
                    cols.coerceAtLeast(40),
                    rows.coerceAtLeast(12),
                    widthPx.coerceAtLeast(320),
                    heightPx.coerceAtLeast(240)
                )
            } catch (_: Exception) {
            }
        }
    }

    fun requestSftpDirectoryListing(path: String = ".") {
        scope.launch(Dispatchers.IO) {
            try {
                val session = jschSession ?: return@launch
                if (!session.isConnected) return@launch
                val sftp = session.openChannel("sftp") as ChannelSftp
                sftp.connect(4000)
                val targetDir = if (path.isBlank()) sftp.pwd() else path
                val rawEntries = sftp.ls(targetDir) as Vector<*>
                val parsed = mutableListOf<SftpRemoteFile>()
                for (item in rawEntries) {
                    val entry = item as? ChannelSftp.LsEntry ?: continue
                    if (entry.filename == "." || entry.filename == "..") continue
                    parsed.add(
                        SftpRemoteFile(
                            name = entry.filename,
                            permissions = entry.attrs.permissionsString,
                            sizeBytes = entry.attrs.size,
                            isDirectory = entry.attrs.isDir,
                            modifiedDate = entry.attrs.mtimeString
                        )
                    )
                }
                val pwd = sftp.pwd()
                sftp.disconnect()
                callbacks.onSftpFilesListed(pwd, parsed.sortedWith(compareByDescending<SftpRemoteFile> { it.isDirectory }.thenBy { it.name }))
            } catch (e: Exception) {
                callbacks.onTraceLog("[sftp] SFTP listing error: ${e.localizedMessage}")
            }
        }
    }
}
