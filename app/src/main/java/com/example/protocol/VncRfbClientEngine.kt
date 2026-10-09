package com.example.protocol

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class VncRfbClientEngine(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val onTrace: (String) -> Unit,
    private val onServerResolutionChanged: (Int, Int, String) -> Unit,
    private val onFrameUpdated: (ImageBitmap) -> Unit,
    private val onDisconnected: (String, String) -> Unit
) {
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var output: DataOutputStream? = null
    @Volatile
    var isRunning: Boolean = false
        private set

    private var fbWidth: Int = 1024
    private var fbHeight: Int = 768
    private var pixels: IntArray = IntArray(0)
    private var bitmap: Bitmap? = null
    private val writeLock = Any()

    fun connectAndRunBlocking() {
        val sock = Socket()
        socket = sock
        try {
            onTrace("[vnc-rfb] Connecting TCP socket to $host:$port...")
            sock.connect(InetSocketAddress(host, port), 5000)
            sock.tcpNoDelay = true
            sock.soTimeout = 0 // Persistent streaming socket

            val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 65536))
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 16384))
            output = out

            // 1. Read 12-byte Server ProtocolVersion ("RFB xxx.yyy\n")
            val verBytes = ByteArray(12)
            inp.readFully(verBytes)
            val serverVer = String(verBytes, Charsets.US_ASCII)
            onTrace("[vnc-rfb] Server version: ${serverVer.trim()}")

            val clientVer = when {
                serverVer.startsWith("RFB 003.008") -> "RFB 003.008\n"
                serverVer.startsWith("RFB 003.007") -> "RFB 003.007\n"
                else -> "RFB 003.003\n"
            }
            out.write(clientVer.toByteArray(Charsets.US_ASCII))
            out.flush()

            // 2. Negotiate Security
            if (clientVer == "RFB 003.003\n") {
                val secType = inp.readInt()
                handleSecurityType(secType, inp, out, is38 = false)
            } else {
                val count = inp.readUnsignedByte()
                if (count == 0) {
                    val reasonLen = inp.readInt()
                    val reasonBytes = ByteArray(reasonLen.coerceIn(0, 4096))
                    inp.readFully(reasonBytes)
                    throw IllegalStateException("VNC Server rejected connection: ${String(reasonBytes, Charsets.UTF_8)}")
                }
                val types = ByteArray(count)
                inp.readFully(types)
                val chosen = when {
                    types.contains(2.toByte()) && password.isNotEmpty() -> 2
                    types.contains(1.toByte()) -> 1
                    types.contains(2.toByte()) -> 2
                    else -> types[0].toInt() and 0xFF
                }
                out.writeByte(chosen)
                out.flush()
                handleSecurityType(chosen, inp, out, is38 = clientVer == "RFB 003.008\n")
            }

            // 3. Send ClientInit (shared = 1)
            out.writeByte(1)
            out.flush()

            // 4. Read ServerInit
            fbWidth = inp.readUnsignedShort()
            fbHeight = inp.readUnsignedShort()
            val serverPixFmt = ByteArray(16)
            inp.readFully(serverPixFmt)
            val nameLen = inp.readInt()
            val nameBytes = ByteArray(nameLen.coerceIn(0, 4096))
            inp.readFully(nameBytes)
            val desktopName = String(nameBytes, Charsets.UTF_8)
            onTrace("[vnc-rfb] ServerInit: ${fbWidth}x${fbHeight} '$desktopName'")

            pixels = IntArray(fbWidth * fbHeight) { 0xFF000000.toInt() }
            bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
            onServerResolutionChanged(fbWidth, fbHeight, desktopName)

            // 5. Send SetPixelFormat (32-bit RGBX little-endian so bytes are B, G, R, X)
            synchronized(writeLock) {
                out.writeByte(0) // SetPixelFormat
                out.writeByte(0)
                out.writeByte(0)
                out.writeByte(0)
                out.writeByte(32) // bits-per-pixel = 32
                out.writeByte(24) // depth = 24
                out.writeByte(0)  // big-endian-flag = false
                out.writeByte(1)  // true-color-flag = true
                out.writeShort(255) // red-max
                out.writeShort(255) // green-max
                out.writeShort(255) // blue-max
                out.writeByte(16) // red-shift
                out.writeByte(8)  // green-shift
                out.writeByte(0)  // blue-shift
                out.writeByte(0)  // padding
                out.writeByte(0)
                out.writeByte(0)

                // 6. Send SetEncodings: CopyRect (1), Zlib (6), Hextile (5), RRE (2), Raw (0), DesktopSize (-223)
                val encodings = intArrayOf(1, 6, 5, 2, 0, -223)
                out.writeByte(2) // SetEncodings
                out.writeByte(0) // padding
                out.writeShort(encodings.size)
                encodings.forEach { out.writeInt(it) }
                out.flush()
            }

            isRunning = true
            requestFramebufferUpdate(incremental = false)

            val zlibInflater = Inflater()
            // 7. Continuous Framebuffer Streaming Loop
            while (isRunning && !sock.isClosed) {
                val msgType = inp.readUnsignedByte()
                when (msgType) {
                    0 -> { // FramebufferUpdate
                        inp.readUnsignedByte() // padding
                        val numRects = inp.readUnsignedShort()
                        for (r in 0 until numRects) {
                            val rx = inp.readUnsignedShort()
                            val ry = inp.readUnsignedShort()
                            val rw = inp.readUnsignedShort()
                            val rh = inp.readUnsignedShort()
                            val encoding = inp.readInt()
                            when (encoding) {
                                -223 -> { // DesktopSize pseudo-encoding
                                    fbWidth = rw.coerceAtLeast(1)
                                    fbHeight = rh.coerceAtLeast(1)
                                    pixels = IntArray(fbWidth * fbHeight) { 0xFF000000.toInt() }
                                    bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
                                    onServerResolutionChanged(fbWidth, fbHeight, desktopName)
                                }
                                0 -> decodeRawRect(inp, rx, ry, rw, rh)
                                1 -> decodeCopyRect(inp, rx, ry, rw, rh)
                                2 -> decodeRreRect(inp, rx, ry, rw, rh)
                                5 -> decodeHextileRect(inp, rx, ry, rw, rh)
                                6 -> decodeZlibRect(inp, zlibInflater, rx, ry, rw, rh)
                                else -> {
                                    // Fallback raw read if unknown
                                    decodeRawRect(inp, rx, ry, rw, rh)
                                }
                            }
                        }
                        val bmp = bitmap
                        if (bmp != null) {
                            bmp.setPixels(pixels, 0, fbWidth, 0, 0, fbWidth, fbHeight)
                            onFrameUpdated(bmp.asImageBitmap())
                        }
                        requestFramebufferUpdate(incremental = true)
                    }
                    1 -> { // SetColourMapEntries
                        inp.readUnsignedByte()
                        inp.readUnsignedShort()
                        val numColors = inp.readUnsignedShort()
                        inp.skipBytes(numColors * 6)
                    }
                    2 -> { // Bell
                    }
                    3 -> { // ServerCutText
                        inp.readUnsignedByte()
                        inp.readUnsignedShort()
                        val len = inp.readInt()
                        val cutBytes = ByteArray(len.coerceIn(0, 65536))
                        inp.readFully(cutBytes)
                    }
                    else -> {
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning || bitmap == null) {
                onDisconnected(
                    "Unable to connect to VNC server $host:$port",
                    "${e.javaClass.simpleName}: ${e.localizedMessage ?: "VNC stream closed"}"
                )
            }
        } finally {
            disconnect()
        }
    }

    private fun handleSecurityType(secType: Int, inp: DataInputStream, out: DataOutputStream, is38: Boolean) {
        when (secType) {
            1 -> { // None
                onTrace("[vnc-rfb] Using SecurityType=None (1).")
                if (is38) {
                    val res = inp.readInt()
                    if (res != 0) throw IllegalStateException("VNC Security handshake failed (code $res)")
                }
            }
            2 -> { // VNC DES Challenge-Response Authentication
                onTrace("[vnc-rfb] Performing VNC DES Challenge-Response Authentication...")
                val challenge = ByteArray(16)
                inp.readFully(challenge)
                val response = encryptVncChallenge(challenge, password)
                out.write(response)
                out.flush()
                val status = inp.readInt()
                if (status != 0) {
                    var errReason = "VNC authentication failed (check password)"
                    if (is38) {
                        try {
                            val len = inp.readInt()
                            val rb = ByteArray(len.coerceIn(0, 1024))
                            inp.readFully(rb)
                            errReason = String(rb, Charsets.UTF_8)
                        } catch (_: Exception) {
                        }
                    }
                    throw IllegalStateException(errReason)
                }
                onTrace("[vnc-rfb] VNC Authentication succeeded.")
            }
            else -> {
                throw IllegalStateException("Unsupported VNC Security Type: $secType")
            }
        }
    }

    private fun encryptVncChallenge(challenge: ByteArray, pass: String): ByteArray {
        val keyBytes = ByteArray(8)
        val passBytes = pass.toByteArray(Charsets.US_ASCII)
        for (i in 0 until 8) {
            val b = if (i < passBytes.size) passBytes[i].toInt() and 0xFF else 0
            // RFB reverses bit order in each byte of the DES key
            var rev = 0
            for (bit in 0 until 8) {
                if ((b and (1 shl bit)) != 0) {
                    rev = rev or (1 shl (7 - bit))
                }
            }
            keyBytes[i] = rev.toByte()
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
        return cipher.doFinal(challenge)
    }

    private fun decodeRawRect(inp: DataInputStream, rx: Int, ry: Int, rw: Int, rh: Int) {
        val rowBuf = ByteArray(rw * 4)
        for (y in 0 until rh) {
            inp.readFully(rowBuf)
            val destY = ry + y
            if (destY in 0 until fbHeight) {
                var srcIdx = 0
                val rowOffset = destY * fbWidth
                for (x in 0 until rw) {
                    val destX = rx + x
                    val b = rowBuf[srcIdx].toInt() and 0xFF
                    val g = rowBuf[srcIdx + 1].toInt() and 0xFF
                    val r = rowBuf[srcIdx + 2].toInt() and 0xFF
                    srcIdx += 4
                    if (destX in 0 until fbWidth) {
                        pixels[rowOffset + destX] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
    }

    private fun decodeCopyRect(inp: DataInputStream, rx: Int, ry: Int, rw: Int, rh: Int) {
        val srcX = inp.readUnsignedShort()
        val srcY = inp.readUnsignedShort()
        val temp = IntArray(rw * rh)
        for (y in 0 until rh) {
            val sy = srcY + y
            if (sy in 0 until fbHeight) {
                for (x in 0 until rw) {
                    val sx = srcX + x
                    if (sx in 0 until fbWidth) {
                        temp[y * rw + x] = pixels[sy * fbWidth + sx]
                    }
                }
            }
        }
        for (y in 0 until rh) {
            val dy = ry + y
            if (dy in 0 until fbHeight) {
                for (x in 0 until rw) {
                    val dx = rx + x
                    if (dx in 0 until fbWidth) {
                        pixels[dy * fbWidth + dx] = temp[y * rw + x]
                    }
                }
            }
        }
    }

    private fun decodeRreRect(inp: DataInputStream, rx: Int, ry: Int, rw: Int, rh: Int) {
        val numSubrects = inp.readInt()
        val bgColor = readPixel32(inp)
        fillRect(rx, ry, rw, rh, bgColor)
        for (i in 0 until numSubrects) {
            val color = readPixel32(inp)
            val sx = inp.readUnsignedShort()
            val sy = inp.readUnsignedShort()
            val sw = inp.readUnsignedShort()
            val sh = inp.readUnsignedShort()
            fillRect(rx + sx, ry + sy, sw, sh, color)
        }
    }

    private fun decodeHextileRect(inp: DataInputStream, rx: Int, ry: Int, rw: Int, rh: Int) {
        var bgColor = 0xFF000000.toInt()
        var fgColor = 0xFFFFFFFF.toInt()
        var ty = ry
        while (ty < ry + rh) {
            val th = minOf(16, ry + rh - ty)
            var tx = rx
            while (tx < rx + rw) {
                val tw = minOf(16, rx + rw - tx)
                val subEncoding = inp.readUnsignedByte()
                if ((subEncoding and 1) != 0) {
                    decodeRawRect(inp, tx, ty, tw, th)
                } else {
                    if ((subEncoding and 2) != 0) bgColor = readPixel32(inp)
                    if ((subEncoding and 4) != 0) fgColor = readPixel32(inp)
                    fillRect(tx, ty, tw, th, bgColor)
                    if ((subEncoding and 8) != 0) {
                        val nSubrects = inp.readUnsignedByte()
                        val subrectsColored = (subEncoding and 16) != 0
                        for (s in 0 until nSubrects) {
                            val c = if (subrectsColored) readPixel32(inp) else fgColor
                            val xy = inp.readUnsignedByte()
                            val wh = inp.readUnsignedByte()
                            val sx = (xy shr 4) and 0x0F
                            val sy = xy and 0x0F
                            val sw = ((wh shr 4) and 0x0F) + 1
                            val sh = (wh and 0x0F) + 1
                            fillRect(tx + sx, ty + sy, sw, sh, c)
                        }
                    }
                }
                tx += 16
            }
            ty += 16
        }
    }

    private fun decodeZlibRect(inp: DataInputStream, inflater: Inflater, rx: Int, ry: Int, rw: Int, rh: Int) {
        val compressedLen = inp.readInt()
        val compBuf = ByteArray(compressedLen)
        inp.readFully(compBuf)
        inflater.setInput(compBuf)
        val rawBuf = ByteArray(rw * rh * 4)
        var offset = 0
        while (offset < rawBuf.size && !inflater.needsInput()) {
            val count = inflater.inflate(rawBuf, offset, rawBuf.size - offset)
            if (count <= 0) break
            offset += count
        }
        var srcIdx = 0
        for (y in 0 until rh) {
            val dy = ry + y
            for (x in 0 until rw) {
                val dx = rx + x
                val b = rawBuf[srcIdx].toInt() and 0xFF
                val g = rawBuf[srcIdx + 1].toInt() and 0xFF
                val r = rawBuf[srcIdx + 2].toInt() and 0xFF
                srcIdx += 4
                if (dy in 0 until fbHeight && dx in 0 until fbWidth) {
                    pixels[dy * fbWidth + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    private fun readPixel32(inp: DataInputStream): Int {
        val b = inp.readUnsignedByte()
        val g = inp.readUnsignedByte()
        val r = inp.readUnsignedByte()
        inp.readUnsignedByte()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun fillRect(rx: Int, ry: Int, rw: Int, rh: Int, color: Int) {
        for (y in ry until (ry + rh).coerceAtMost(fbHeight)) {
            if (y < 0) continue
            val rowOffset = y * fbWidth
            for (x in rx until (rx + rw).coerceAtMost(fbWidth)) {
                if (x >= 0) pixels[rowOffset + x] = color
            }
        }
    }

    fun requestFramebufferUpdate(incremental: Boolean) {
        val out = output ?: return
        synchronized(writeLock) {
            try {
                out.writeByte(3) // FramebufferUpdateRequest
                out.writeByte(if (incremental) 1 else 0)
                out.writeShort(0)
                out.writeShort(0)
                out.writeShort(fbWidth)
                out.writeShort(fbHeight)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val out = output ?: return
        synchronized(writeLock) {
            try {
                out.writeByte(5) // PointerEvent
                out.writeByte(buttonMask)
                out.writeShort(x.coerceIn(0, fbWidth - 1))
                out.writeShort(y.coerceIn(0, fbHeight - 1))
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun sendKeySymEvent(keySym: Int, down: Boolean) {
        val out = output ?: return
        synchronized(writeLock) {
            try {
                out.writeByte(4) // KeyEvent
                out.writeByte(if (down) 1 else 0)
                out.writeShort(0) // padding
                out.writeInt(keySym)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun disconnect() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        output = null
    }
}
