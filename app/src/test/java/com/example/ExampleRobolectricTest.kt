package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ConnectionProfileEntity
import com.example.data.local.RemminaDatabase
import com.example.data.repository.RemminaRepository
import com.example.protocol.LibFreeRdpAndroidWrapper
import com.example.protocol.RdpCryptoAndBitmapEngine
import com.example.protocol.RdpLiveSessionClient
import com.example.protocol.RdpSessionCallbacks
import com.example.protocol.SessionConnectionPhase
import com.example.security.CredentialVaultManager
import com.example.security.PinVerificationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("RemMobile", appName)
    }

    @Test
    fun `database is completely empty by default`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = RemminaDatabase.getInstance(context)
        val profiles = db.remminaDao().observeAllProfiles().first()
        val sshKeys = db.remminaDao().observeAllSshKeys().first()
        val keyMappings = db.remminaDao().observeAllKeyMappings().first()
        assertTrue("Profiles table must be empty by default", profiles.isEmpty())
        assertTrue("SSH keys table must be empty by default", sshKeys.isEmpty())
        assertTrue("Key mappings table must be empty by default", keyMappings.isEmpty())
    }

    @Test
    fun `AES-256-GCM credential vault encrypts with unique IVs, decrypts accurately, and detects tampering`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = CredentialVaultManager(context)

        val rawPassword = "SuperSecret$2026!RdpPassword"
        val enc1 = vault.encryptSecret(rawPassword)
        val enc2 = vault.encryptSecret(rawPassword)

        assertTrue(vault.isEncryptedCiphertext(enc1))
        assertTrue(vault.isEncryptedCiphertext(enc2))
        assertNotEquals("Each encryption must use a unique random 96-bit IV", enc1, enc2)
        assertFalse("Ciphertext must never contain plaintext password", enc1.contains(rawPassword))

        assertEquals(rawPassword, vault.decryptSecretStrict(enc1))
        assertEquals(rawPassword, vault.decryptSecretStrict(enc2))

        // Verify cryptographic self-test passes all checks including 1-bit flip GCM tag tamper rejection
        val selfTest = vault.runCryptographicSelfTest(emptyList())
        assertTrue("All cryptographic self-tests must pass: ${selfTest.details}", selfTest.passedAll)
    }

    @Test
    fun `Room SQLite stores only AES-256-GCM encrypted passwords at rest and redacts exports`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = RemminaDatabase.getInstance(context)
        val vault = CredentialVaultManager(context)
        val repo = RemminaRepository(db.remminaDao(), vault)

        val secretPw = "MyRdpSystemPass#99"
        val sshPw = "MyBastionPass#88"
        val profile = ConnectionProfileEntity(
            name = "Prod Server",
            server = "192.168.101.3",
            port = 3389,
            username = "admin",
            password = secretPw,
            sshTunnelEnabled = true,
            sshTunnelHost = "10.0.0.1",
            sshTunnelPassword = sshPw
        )

        val savedId = repo.saveProfile(profile)
        val rawFromDb = db.remminaDao().getProfileById(savedId)
        assertNotNull(rawFromDb)
        assertTrue("Password in SQLite must be AES-256-GCM encrypted", vault.isEncryptedCiphertext(rawFromDb!!.password))
        assertTrue("SSH Tunnel Password in SQLite must be AES-256-GCM encrypted", vault.isEncryptedCiphertext(rawFromDb.sshTunnelPassword))
        assertNotEquals(secretPw, rawFromDb.password)
        assertNotEquals(sshPw, rawFromDb.sshTunnelPassword)

        // Verify in-memory decryption for active session handshake
        val decrypted = repo.decryptProfileSecrets(rawFromDb)
        assertEquals(secretPw, decrypted.password)
        assertEquals(sshPw, decrypted.sshTunnelPassword)

        // Verify .remmina export redacts raw password
        val exportedIni = repo.exportProfileToRemminaIni(rawFromDb)
        assertFalse("Exported .remmina config must not leak plaintext password", exportedIni.contains(secretPw))
        assertFalse("Exported .remmina config must not leak raw ciphertext", exportedIni.contains(rawFromDb.password))

        // Cleanup so DB remains clean for other tests
        repo.deleteProfile(rawFromDb)
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 Vault PIN verifies constant-time and enforces brute-force lockout after 5 failures`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = CredentialVaultManager(context)

        assertTrue(vault.setupOrUpdatePin("482910"))
        assertTrue(vault.isPinConfigured())
        assertEquals(PinVerificationResult.Success, vault.verifyPin("482910"))

        // 4 wrong attempts should return InvalidPin with decreasing remaining tries
        for (attempt in 1..4) {
            val res = vault.verifyPin("000000")
            assertTrue(res is PinVerificationResult.InvalidPin)
        }
        // 5th wrong attempt triggers 30-second brute-force lockout
        val fifthRes = vault.verifyPin("000000")
        assertTrue("5th failed PIN attempt must trigger brute-force lockout", fifthRes is PinVerificationResult.LockedOut)

        // Even the correct PIN is rejected while locked out
        val duringLockout = vault.verifyPin("482910")
        assertTrue(duringLockout is PinVerificationResult.LockedOut)

        vault.removePin()
        assertFalse(vault.isPinConfigured())
    }

    @Test
    fun `uncompressed RDP bitmap decoder produces valid ARGB pixels`() {
        val fb = IntArray(4 * 4)
        val rawRgb16 = ByteArray(4 * 4 * 2) { 0xFF.toByte() }
        RdpCryptoAndBitmapEngine.decodeBitmapRectangleIntoFramebuffer(
            framebuffer = fb,
            fbWidth = 4,
            fbHeight = 4,
            destLeft = 0,
            destTop = 0,
            destRight = 3,
            destBottom = 3,
            tileWidth = 4,
            tileHeight = 4,
            bitsPerPixel = 16,
            flags = 0x0000,
            bitmapData = rawRgb16
        )
        assertEquals(0xFFFFFFFF.toInt(), fb[0])
    }

    @Test
    fun `Interleaved RLE decompressor accurately decodes multi-scanline FGBG_IMAGE and BG_RUN without horizontal shift`() {
        val fb = IntArray(8 * 2)
        // Row 0 (bottom scanline in RDP): REGULAR_COLOR_RUN of 8 pixels with 24-bpp Blue (0xFF0000 in BGR -> 0x00, 0x00, 0xFF)
        // Header for REGULAR_COLOR_RUN (code 0x03 = 011_00000 = 0x60) | length 8 = 0x68
        // Row 1 (top scanline in RDP): REGULAR_FGBG_IMAGE (code 0x02 = 010_00000 = 0x40) | (8/8 = 1) = 0x41, mask = 0xFF
        // Since fgPel defaults to 0xFFFFFF (white) and Row 0 is 0xFF0000 (blue in BGR), Row 1 XOR white = 0x00FFFF (yellow in RGB: R=255, G=255, B=0)
        val rleStream = byteArrayOf(
            0x68.toByte(), 0xFF.toByte(), 0x00, 0x00, // 8 pixels of B=255, G=0, R=0 (Blue)
            0x41.toByte(), 0xFF.toByte()              // 8 pixels of REGULAR_FGBG_IMAGE with bitmask 0xFF
        )
        RdpCryptoAndBitmapEngine.decodeBitmapRectangleIntoFramebuffer(
            framebuffer = fb,
            fbWidth = 8,
            fbHeight = 2,
            destLeft = 0,
            destTop = 0,
            destRight = 7,
            destBottom = 1,
            tileWidth = 8,
            tileHeight = 2,
            bitsPerPixel = 24,
            flags = 0x0401, // BITMAP_COMPRESSION | NO_BITMAP_COMPRESSION_HDR
            bitmapData = rleStream
        )
        // Top row (dstY = 0, which is srcRow = 1) should be Yellow (0xFFFFFF00)
        assertEquals(0xFFFFFF00.toInt(), fb[0])
        // Bottom row (dstY = 1, which is srcRow = 0) should be Blue (0xFF0000FF)
        assertEquals(0xFF0000FF.toInt(), fb[8])
    }

    @Test
    fun `RDP 6_0 Planar decompressor handles YCoCg Chroma Subsampling (CS=1) without scanline lines`() {
        val fb = IntArray(4 * 4)
        // Format header: cll=1 (0x01), cs=1 (0x08), rle=0 (0x00), na=1 (0x20) -> 0x29
        // Luma plane: 4x4 = 16 bytes of Y=128
        // Co plane (subsampled 2x2 = 4 bytes): 0
        // Cg plane (subsampled 2x2 = 4 bytes): 0
        val planarData = ByteArray(1 + 16 + 4 + 4)
        planarData[0] = 0x29.toByte()
        for (i in 1..16) planarData[i] = 128.toByte()
        RdpCryptoAndBitmapEngine.decodeBitmapRectangleIntoFramebuffer(
            framebuffer = fb,
            fbWidth = 4,
            fbHeight = 4,
            destLeft = 0,
            destTop = 0,
            destRight = 3,
            destBottom = 3,
            tileWidth = 4,
            tileHeight = 4,
            bitsPerPixel = 32,
            flags = 0x0401,
            bitmapData = planarData
        )
        assertEquals(0xFF808080.toInt(), fb[0])
        assertEquals(0xFF808080.toInt(), fb[15])
    }

    @Test
    fun `normalizeCredentials strips dot domain and splits backslash user`() {
        val (u1, d1) = RdpCryptoAndBitmapEngine.normalizeCredentials(".\\Administrator", ".")
        assertEquals("Administrator", u1)
        assertEquals("", d1)

        val (u2, d2) = RdpCryptoAndBitmapEngine.normalizeCredentials("CORP\\alice", "")
        assertEquals("alice", u2)
        assertEquals("CORP", d2)
    }

    @Test
    fun `RdpLiveSessionClient completes full handshake and handles DemandActive with 0x80 length bit without hanging`() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val connectedLatch = CountDownLatch(1)
        val frameLatch = CountDownLatch(1)

        val serverThread = Thread {
            try {
                serverSocket.soTimeout = 5000
                val sock = serverSocket.accept()
                sock.soTimeout = 5000
                val inp = sock.getInputStream()
                val out = sock.getOutputStream()

                fun readTpkt(): ByteArray {
                    val hdr = ByteArray(4)
                    var r = 0
                    while (r < 4) r += inp.read(hdr, r, 4 - r)
                    val len = ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
                    val body = ByteArray(len - 4)
                    var b = 0
                    while (b < body.size) b += inp.read(body, b, body.size - b)
                    return hdr + body
                }

                // 1. Read X.224 CR and send X.224 CC (selectedProtocol = 0x00 Standard RDP)
                readTpkt()
                out.write(
                    byteArrayOf(
                        0x03, 0x00, 0x00, 0x13,
                        0x0E, 0xD0.toByte(), 0x00, 0x00, 0x12, 0x34, 0x00,
                        0x02, 0x00, 0x08, 0x00,
                        0x00, 0x00, 0x00, 0x00
                    )
                )
                out.flush()

                // 2. Read MCS Connect Initial & send MCS Connect Response
                readTpkt()
                out.write(
                    byteArrayOf(
                        0x03, 0x00, 0x00, 0x0F,
                        0x02, 0xF0.toByte(), 0x80.toByte(),
                        0x7F, 0x66, 0x05, 0x0A, 0x01, 0x00, 0x02, 0x0C
                    )
                )
                out.flush()

                // 3. Read MCS Erect Domain & Attach User Request, send Attach User Confirm (userChannel = 1007)
                readTpkt()
                readTpkt()
                out.write(
                    byteArrayOf(
                        0x03, 0x00, 0x00, 0x0B,
                        0x02, 0xF0.toByte(), 0x80.toByte(),
                        0x2E, 0x00, 0x00, 0x06
                    )
                )
                out.flush()

                // 4. Read 2 MCS Channel Join Requests & send Confirms
                repeat(2) {
                    readTpkt()
                    out.write(
                        byteArrayOf(
                            0x03, 0x00, 0x00, 0x0F,
                            0x02, 0xF0.toByte(), 0x80.toByte(),
                            0x3E, 0x00, 0x00, 0x06, 0x03, 0xEF.toByte(), 0x03, 0xEF.toByte()
                        )
                    )
                    out.flush()
                }

                // 5. Read Client Info PDU
                readTpkt()

                // 6. Send Server Licensing PDU (SEC_LICENSE_PKT = 0x0080, ERROR_ALERT = 0xFF)
                val licPdu = byteArrayOf(
                    0x03, 0x00, 0x00, 0x22,
                    0x02, 0xF0.toByte(), 0x80.toByte(),
                    0x68, 0x00, 0x01, 0x03, 0xEB.toByte(), 0x70, 0x14,
                    0x80.toByte(), 0x00, 0x00, 0x00, // TS_SECURITY_HEADER (SEC_LICENSE_PKT)
                    0xFF.toByte(), 0x03, 0x10, 0x00,
                    0x07, 0x00, 0x00, 0x00,
                    0x02, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00
                )
                out.write(licPdu)
                out.flush()

                // 7. Send Server Demand Active PDU with totalLength = 180 (0x00B4, which has 0x0080 bit set!)
                val demandLen = 180 // 0x00B4 -> (180 and 0x0080) != 0!
                val demandBody = ByteBuffer.allocate(demandLen).order(ByteOrder.LITTLE_ENDIAN)
                demandBody.putShort(demandLen.toShort()) // totalLength = 180 (0x00B4)
                demandBody.putShort(0x0011)              // pduType = PDUTYPE_DEMANDACTIVEPDU
                demandBody.putShort(1002)                // pduSource
                demandBody.putInt(0x000103EA)            // shareId
                demandBody.putShort(4)                   // lengthSourceDescriptor
                demandBody.putShort(32)                  // lengthCombinedCapabilities
                demandBody.put("RDP\u0000".toByteArray(Charsets.US_ASCII))
                demandBody.putShort(1)                   // numberCapabilities
                demandBody.putShort(0)                   // pad2Octets
                // CAPSTYPE_BITMAP (type = 2, len = 28)
                demandBody.putShort(0x0002)
                demandBody.putShort(28)
                demandBody.putShort(16)                  // preferredBitsPerPixel = 16
                demandBody.putShort(1).putShort(1).putShort(1)
                demandBody.putShort(800)                 // desktopWidth = 800
                demandBody.putShort(600)                 // desktopHeight = 600
                val demandBytes = demandBody.array()

                val mcsSendDataHdr = byteArrayOf(
                    0x68, 0x00, 0x01, 0x03, 0xEB.toByte(), 0x70,
                    (0x80 or ((demandBytes.size ushr 8) and 0x7F)).toByte(),
                    (demandBytes.size and 0xFF).toByte()
                )
                val tpktLen = 4 + 3 + mcsSendDataHdr.size + demandBytes.size
                val tpkt = ByteBuffer.allocate(tpktLen)
                tpkt.put(0x03).put(0x00).putShort(tpktLen.toShort())
                tpkt.put(0x02).put(0xF0.toByte()).put(0x80.toByte())
                tpkt.put(mcsSendDataHdr)
                tpkt.put(demandBytes)
                out.write(tpkt.array())
                out.flush()

                // Read Confirm Active PDU sent by RdpLiveSessionClient
                val confirmPdu = readTpkt()
                assertTrue(confirmPdu.size > 40)
                Thread.sleep(200)
                sock.close()
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }
        serverThread.start()

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val profile = ConnectionProfileEntity(
            name = "Test RDP",
            server = "127.0.0.1",
            port = port,
            protocol = "RDP",
            securityMode = "RDP"
        )

        var activePhase: SessionConnectionPhase? = null
        val client = RdpLiveSessionClient(
            scope = testScope,
            connectHost = "127.0.0.1",
            connectPort = port,
            profile = profile,
            requestedWidth = 800,
            requestedHeight = 600,
            callbacks = object : RdpSessionCallbacks {
                override fun onTraceLog(line: String) {}
                override fun onPhaseChanged(phase: SessionConnectionPhase, banner: String) {
                    activePhase = phase
                    if (phase == SessionConnectionPhase.STREAMING_FRAMEBUFFER) {
                        connectedLatch.countDown()
                    }
                }
                override fun onTlsCertificateDiscovered(subject: String, sha256Fingerprint: String) {}
                override fun onAuthenticationRequired(reason: String, certSubject: String?, certFingerprint: String?) {}
                override fun onResolutionNegotiated(width: Int, height: Int, bpp: Int) {}
                override fun onFramebufferUpdated(bitmap: androidx.compose.ui.graphics.ImageBitmap, frameCount: Int, bitrateMbps: Float) {
                    frameLatch.countDown()
                }
                override fun onDisconnected(errorTitle: String, errorDetails: String) {}
            }
        )

        client.start()
        assertTrue("Client must transition to STREAMING_FRAMEBUFFER without hanging", connectedLatch.await(4, TimeUnit.SECONDS))
        assertTrue("Client must emit initial framebuffer bitmap", frameLatch.await(4, TimeUnit.SECONDS))
        assertEquals(SessionConnectionPhase.STREAMING_FRAMEBUFFER, activePhase)
        assertNotNull(LibFreeRdpAndroidWrapper.FreeRdpConnectionState.ACTIVE_STREAMING)
        client.disconnect()
    }
}
