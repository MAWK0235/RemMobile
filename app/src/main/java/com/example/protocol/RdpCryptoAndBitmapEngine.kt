package com.example.protocol

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives and bitmap decoders for the Android FreeRDP Core Engine:
 * - CredSSP (MS-CSSP v2..v6) + NTLMv2 (MS-NLMP) with SPN TargetName & local-account domain normalization
 * - Standard RDP Security (MS-RDPBCGR 5.3) RSA public key exchange & 128-bit RC4 session encryption
 * - MS-RDPBCGR Slow-Path & Fast-Path Interleaved RLE (15/16/24 bpp), RDP 6.0 Planar (32 bpp),
 *   and Uncompressed bitmap tile decoding into Android ARGB_8888 framebuffers.
 */
object RdpCryptoAndBitmapEngine {

    // =========================================================================
    // 1. Stateful RC4 Cipher (for NTLMSSP Sealing & Standard RDP Security)
    // =========================================================================
    class Rc4Cipher(key: ByteArray) {
        private val s = IntArray(256) { it }
        private var i = 0
        private var j = 0

        init {
            var jInit = 0
            for (idx in 0 until 256) {
                jInit = (jInit + s[idx] + (key[idx % key.size].toInt() and 0xFF)) and 0xFF
                val tmp = s[idx]
                s[idx] = s[jInit]
                s[jInit] = tmp
            }
        }

        fun process(input: ByteArray): ByteArray {
            val out = ByteArray(input.size)
            for (k in input.indices) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val tmp = s[i]
                s[i] = s[j]
                s[j] = tmp
                val keystreamByte = s[(s[i] + s[j]) and 0xFF]
                out[k] = ((input[k].toInt() and 0xFF) xor keystreamByte).toByte()
            }
            return out
        }
    }

    // =========================================================================
    // 2. RFC 1320 MD4, MD5, SHA-1, SHA-256, HMAC-MD5
    // =========================================================================
    fun md4(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8L
        val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
        val padded = ByteArray(input.size + 1 + padLen + 8)
        System.arraycopy(input, 0, padded, 0, input.size)
        padded[input.size] = 0x80.toByte()
        for (b in 0 until 8) {
            padded[padded.size - 8 + b] = ((bitLen ushr (8 * b)) and 0xFF).toByte()
        }

        var a = 0x67452301
        var b = 0xEFCDAB89.toInt()
        var c = 0x98BADCFE.toInt()
        var d = 0x10325476

        val x = IntArray(16)
        for (offset in padded.indices step 64) {
            for (k in 0 until 16) {
                val idx = offset + k * 4
                x[k] = (padded[idx].toInt() and 0xFF) or
                    ((padded[idx + 1].toInt() and 0xFF) shl 8) or
                    ((padded[idx + 2].toInt() and 0xFF) shl 16) or
                    ((padded[idx + 3].toInt() and 0xFF) shl 24)
            }
            val aa = a
            val bb = b
            val cc = c
            val dd = d

            val s1 = intArrayOf(3, 7, 11, 19)
            for (k in 0 until 16) {
                val f = (b and c) or (b.inv() and d)
                val next = Integer.rotateLeft(a + f + x[k], s1[k % 4])
                a = d; d = c; c = b; b = next
            }
            val r2Idx = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)
            val s2 = intArrayOf(3, 5, 9, 13)
            for (k in 0 until 16) {
                val g = (b and c) or (b and d) or (c and d)
                val next = Integer.rotateLeft(a + g + x[r2Idx[k]] + 0x5A827999, s2[k % 4])
                a = d; d = c; c = b; b = next
            }
            val r3Idx = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)
            val s3 = intArrayOf(3, 9, 11, 15)
            for (k in 0 until 16) {
                val h = b xor c xor d
                val next = Integer.rotateLeft(a + h + x[r3Idx[k]] + 0x6ED9EBA1, s3[k % 4])
                a = d; d = c; c = b; b = next
            }

            a += aa
            b += bb
            c += cc
            d += dd
        }

        val out = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(a).putInt(b).putInt(c).putInt(d)
        return out.array()
    }

    fun md5(vararg chunks: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        chunks.forEach { digest.update(it) }
        return digest.digest()
    }

    fun sha1(vararg chunks: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        chunks.forEach { digest.update(it) }
        return digest.digest()
    }

    fun sha256(vararg chunks: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        chunks.forEach { digest.update(it) }
        return digest.digest()
    }

    fun hmacMd5(key: ByteArray, vararg data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacMD5"))
        data.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /**
     * Splits "DOMAIN\user", ".\user", or "user@domain" and normalizes "." domain to "" for NTLMv2.
     */
    fun normalizeCredentials(rawUsername: String, rawDomain: String): Pair<String, String> {
        var user = rawUsername.trim()
        var dom = rawDomain.trim()
        if (user.contains("\\")) {
            val parts = user.split("\\", limit = 2)
            if (dom.isBlank()) dom = parts[0].trim()
            user = parts[1].trim()
        } else if (user.contains("@") && dom.isBlank()) {
            val parts = user.split("@", limit = 2)
            user = parts[0].trim()
            dom = parts[1].trim()
        }
        if (dom == "." || dom.equals("local", ignoreCase = true)) {
            dom = ""
        }
        return Pair(user, dom)
    }

    // =========================================================================
    // 3. NTLMSSP & CredSSP (MS-CSSP) Session Context
    // =========================================================================
    class CredSspNtlmContext(
        rawUsername: String,
        val password: String,
        rawDomain: String,
        private val targetHost: String = ""
    ) {
        val username: String
        val domain: String

        init {
            val (u, d) = normalizeCredentials(rawUsername, rawDomain)
            username = u
            domain = d
        }

        val clientNonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        var negotiatedCredSspVersion: Int = 6
        var negotiateMessageBytes: ByteArray = ByteArray(0)
        var challengeMessageBytes: ByteArray = ByteArray(0)
        private var clientSigningKey: ByteArray = ByteArray(16)
        private var serverSigningKey: ByteArray = ByteArray(16)
        private var clientSealingCipher: Rc4Cipher? = null
        private var serverSealingCipher: Rc4Cipher? = null
        private var sequenceNumber: Int = 0

        fun buildNtlmNegotiateMessage(): ByteArray {
            val out = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            out.put("NTLMSSP\u0000".toByteArray(Charsets.US_ASCII))
            out.putInt(1) // Type 1: NEGOTIATE
            // Flags: UNICODE(0x01) | REQUEST_TARGET(0x04) | SIGN(0x10) | SEAL(0x20) | NTLM(0x200) |
            // ALWAYS_SIGN(0x8000) | EXTENDED_SESSIONSECURITY(0x80000) | VERSION(0x2000000) |
            // 128(0x20000000) | KEY_EXCH(0x40000000) | 56(0x80000000)
            out.putInt(0xE2088235.toInt())
            out.putShort(0).putShort(0).putInt(40) // Domain fields
            out.putShort(0).putShort(0).putInt(40) // Workstation fields
            out.put(byteArrayOf(0x0A, 0x00, 0x63, 0x45, 0x00, 0x00, 0x00, 0x0F)) // Version 10.0.17763
            return out.array().also { negotiateMessageBytes = it }
        }

        fun processChallengeAndBuildAuthenticate(challengeMsg: ByteArray): ByteArray {
            challengeMessageBytes = challengeMsg
            val buf = ByteBuffer.wrap(challengeMsg).order(ByteOrder.LITTLE_ENDIAN)
            val sig = ByteArray(8)
            buf.get(sig)
            val msgType = buf.int
            require(msgType == 2) { "Expected NTLMSSP_CHALLENGE (type 2), got $msgType" }

            buf.short // targetNameLen
            buf.short // targetNameMaxLen
            buf.int   // targetNameOffset
            val serverNegotiateFlags = buf.int
            val serverChallenge = ByteArray(8)
            buf.get(serverChallenge)
            buf.long // reserved 8 bytes

            val targetInfoLen = buf.short.toInt() and 0xFFFF
            buf.short // targetInfoMaxLen
            val targetInfoOffset = buf.int
            val rawTargetInfo = if (targetInfoLen > 0 && targetInfoOffset + targetInfoLen <= challengeMsg.size) {
                challengeMsg.copyOfRange(targetInfoOffset, targetInfoOffset + targetInfoLen)
            } else {
                ByteArray(0)
            }

            val (modifiedTargetInfo, extractedTimestamp) = prepareTargetInfoWithMicAndSpn(rawTargetInfo, targetHost)
            val timestampBytes = extractedTimestamp ?: run {
                val intervals = (System.currentTimeMillis() + 11644473600000L) * 10000L
                ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(intervals).array()
            }

            val clientChallenge = ByteArray(8).also { SecureRandom().nextBytes(it) }

            // Build NTLMv2_CLIENT_CHALLENGE (temp)
            val tempStream = ByteArrayOutputStream()
            tempStream.write(byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            tempStream.write(timestampBytes)
            tempStream.write(clientChallenge)
            tempStream.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
            tempStream.write(modifiedTargetInfo)
            tempStream.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
            val tempBlob = tempStream.toByteArray()

            // Compute NTLMv2 Hash & Response
            val pwUtf16Bytes = password.toByteArray(Charsets.UTF_16LE)
            val ntHash = md4(pwUtf16Bytes)
            pwUtf16Bytes.fill(0)
            val userDomainBytes = (username.uppercase() + domain).toByteArray(Charsets.UTF_16LE)
            val responseKeyNT = hmacMd5(ntHash, userDomainBytes)
            ntHash.fill(0)
            userDomainBytes.fill(0)

            val ntProofStr = hmacMd5(responseKeyNT, serverChallenge, tempBlob)
            val ntChallengeResponse = ntProofStr + tempBlob
            val lmChallengeResponse = if (extractedTimestamp != null) {
                ByteArray(24) // Z(24) when MsvAvTimestamp is present per MS-NLMP 3.3.2
            } else {
                val lmProof = hmacMd5(responseKeyNT, serverChallenge, clientChallenge)
                lmProof + clientChallenge
            }

            val sessionBaseKey = hmacMd5(responseKeyNT, ntProofStr)
            responseKeyNT.fill(0)
            val exportedSessionKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val encryptedRandomSessionKey = Rc4Cipher(sessionBaseKey).process(exportedSessionKey)
            sessionBaseKey.fill(0)

            // Derive Signing and Sealing keys (MS-NLMP 3.4.5.2 & 3.4.5.3)
            clientSigningKey = md5(
                exportedSessionKey,
                "session key to client-to-server signing key magic constant\u0000".toByteArray(Charsets.US_ASCII)
            )
            serverSigningKey = md5(
                exportedSessionKey,
                "session key to server-to-client signing key magic constant\u0000".toByteArray(Charsets.US_ASCII)
            )
            val clientSealingKey = md5(
                exportedSessionKey,
                "session key to client-to-server sealing key magic constant\u0000".toByteArray(Charsets.US_ASCII)
            )
            val serverSealingKey = md5(
                exportedSessionKey,
                "session key to server-to-client sealing key magic constant\u0000".toByteArray(Charsets.US_ASCII)
            )
            clientSealingCipher = Rc4Cipher(clientSealingKey)
            serverSealingCipher = Rc4Cipher(serverSealingKey)
            clientSealingKey.fill(0)
            serverSealingKey.fill(0)

            val domainBytes = domain.toByteArray(Charsets.UTF_16LE)
            val userBytes = username.toByteArray(Charsets.UTF_16LE)
            val workstationBytes = "REMMINA-AND".toByteArray(Charsets.UTF_16LE)

            val payloadStart = 88
            val domainOffset = payloadStart
            val userOffset = domainOffset + domainBytes.size
            val workstationOffset = userOffset + userBytes.size
            val lmOffset = workstationOffset + workstationBytes.size
            val ntOffset = lmOffset + lmChallengeResponse.size
            val sessionKeyOffset = ntOffset + ntChallengeResponse.size
            val totalLen = sessionKeyOffset + encryptedRandomSessionKey.size

            // Clean client authenticate flags (clear server-only target type bits 0x00030000)
            val authFlags = (serverNegotiateFlags and 0xFFFCFFFF.toInt()) or 0xE2088235.toInt()

            val authBuf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
            authBuf.put("NTLMSSP\u0000".toByteArray(Charsets.US_ASCII))
            authBuf.putInt(3) // Type 3: AUTHENTICATE
            authBuf.putShort(lmChallengeResponse.size.toShort()).putShort(lmChallengeResponse.size.toShort()).putInt(lmOffset)
            authBuf.putShort(ntChallengeResponse.size.toShort()).putShort(ntChallengeResponse.size.toShort()).putInt(ntOffset)
            authBuf.putShort(domainBytes.size.toShort()).putShort(domainBytes.size.toShort()).putInt(domainOffset)
            authBuf.putShort(userBytes.size.toShort()).putShort(userBytes.size.toShort()).putInt(userOffset)
            authBuf.putShort(workstationBytes.size.toShort()).putShort(workstationBytes.size.toShort()).putInt(workstationOffset)
            authBuf.putShort(encryptedRandomSessionKey.size.toShort()).putShort(encryptedRandomSessionKey.size.toShort()).putInt(sessionKeyOffset)
            authBuf.putInt(authFlags)
            authBuf.put(byteArrayOf(0x0A, 0x00, 0x63, 0x45, 0x00, 0x00, 0x00, 0x0F))
            val micOffset = 72
            authBuf.put(ByteArray(16))

            authBuf.put(domainBytes)
            authBuf.put(userBytes)
            authBuf.put(workstationBytes)
            authBuf.put(lmChallengeResponse)
            authBuf.put(ntChallengeResponse)
            authBuf.put(encryptedRandomSessionKey)

            val authArray = authBuf.array()
            val mic = hmacMd5(exportedSessionKey, negotiateMessageBytes, challengeMessageBytes, authArray)
            System.arraycopy(mic, 0, authArray, micOffset, 16)
            return authArray
        }

        private fun prepareTargetInfoWithMicAndSpn(raw: ByteArray, host: String): Pair<ByteArray, ByteArray?> {
            if (raw.isEmpty()) return Pair(raw, null)
            val out = ByteArrayOutputStream()
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            var timestamp: ByteArray? = null
            var hasFlags = false
            var hasTargetName = false

            while (buf.remaining() >= 4) {
                val avId = buf.short.toInt() and 0xFFFF
                val avLen = buf.short.toInt() and 0xFFFF
                if (avId == 0x0000) {
                    if (!hasFlags) {
                        val flagBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        flagBuf.putShort(0x0006).putShort(4).putInt(0x00000002)
                        out.write(flagBuf.array())
                    }
                    if (!hasTargetName && host.isNotBlank()) {
                        val spnBytes = "TERMSRV/$host".toByteArray(Charsets.UTF_16LE)
                        val spnHdr = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                            .putShort(0x0009)
                            .putShort(spnBytes.size.toShort())
                            .array()
                        out.write(spnHdr)
                        out.write(spnBytes)
                    }
                    out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
                    break
                }
                if (buf.remaining() < avLen) break
                val value = ByteArray(avLen)
                buf.get(value)
                if (avId == 0x0007 && avLen == 8) {
                    timestamp = value
                }
                if (avId == 0x0009) {
                    hasTargetName = true
                }
                if (avId == 0x0006 && avLen == 4) {
                    hasFlags = true
                    val existing = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).int
                    val updated = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(existing or 0x02).array()
                    val hdr = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(0x0006).putShort(4).array()
                    out.write(hdr)
                    out.write(updated)
                } else {
                    val hdr = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(avId.toShort()).putShort(avLen.toShort()).array()
                    out.write(hdr)
                    out.write(value)
                }
            }
            return Pair(out.toByteArray(), timestamp)
        }

        fun sealCredSspPayload(plaintext: ByteArray): ByteArray {
            val cipher = clientSealingCipher ?: error("NTLMSSP sealing cipher not initialized")
            val sealed = cipher.process(plaintext)
            val seqBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sequenceNumber).array()
            val hmac = hmacMd5(clientSigningKey, seqBytes, plaintext)
            val checksum = cipher.process(hmac.copyOfRange(0, 8))

            val sig = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            sig.putInt(1) // Version = 1
            sig.put(checksum)
            sig.putInt(sequenceNumber)
            sequenceNumber++
            return sig.array() + sealed
        }

        fun buildPubKeyAuthToken(cert: X509Certificate): ByteArray {
            val spki = cert.publicKey.encoded
            val subjectPublicKeyBits = extractSubjectPublicKeyBitString(spki)
            return if (negotiatedCredSspVersion >= 5) {
                val bindingPrefix = "CredSSP Client-To-Server Binding Hash\u0000".toByteArray(Charsets.US_ASCII)
                val hash = sha256(bindingPrefix, clientNonce, subjectPublicKeyBits)
                sealCredSspPayload(hash)
            } else {
                sealCredSspPayload(subjectPublicKeyBits)
            }
        }

        fun buildSealedTsCredentials(): ByteArray {
            val domainOctets = derTag(0x04, domain.toByteArray(Charsets.UTF_16LE))
            val userOctets = derTag(0x04, username.toByteArray(Charsets.UTF_16LE))
            val passOctets = derTag(0x04, password.toByteArray(Charsets.UTF_16LE))

            val pwCredsSeq = derTag(
                0x30,
                derTag(0xA0, domainOctets) +
                    derTag(0xA1, userOctets) +
                    derTag(0xA2, passOctets)
            )
            val tsCredsSeq = derTag(
                0x30,
                derTag(0xA0, derInteger(1)) + // credType = 1 (TSPasswordCreds)
                    derTag(0xA1, derTag(0x04, pwCredsSeq))
            )
            return sealCredSspPayload(tsCredsSeq)
        }
    }

    // =========================================================================
    // 4. Standard RDP Security (MS-RDPBCGR 5.3) RSA + RC4 Key Derivation
    // =========================================================================
    data class StandardRdpSecurityKeys(
        val macKey: ByteArray,
        val initialDecryptKey: ByteArray,
        val initialEncryptKey: ByteArray,
        val decryptCipher: Rc4Cipher,
        val encryptCipher: Rc4Cipher
    )

    data class ServerProprietaryRsaKey(
        val serverRandom: ByteArray,
        val modulus: ByteArray,
        val pubExp: Int
    )

    /**
     * Parses TS_UD_SC_SEC1 (0x0C02) inside MCS Connect Response to extract serverRandom and RSA key if Standard RDP Security is negotiated.
     */
    fun parseServerSecurityDataFromMcsResponse(mcsResp: ByteArray): ServerProprietaryRsaKey? {
        for (i in 0..mcsResp.size - 40) {
            if (mcsResp[i] == 0x02.toByte() && mcsResp[i + 1] == 0x0C.toByte()) {
                val blockLen = (mcsResp[i + 2].toInt() and 0xFF) or ((mcsResp[i + 3].toInt() and 0xFF) shl 8)
                if (blockLen >= 20 && i + blockLen <= mcsResp.size) {
                    val buf = ByteBuffer.wrap(mcsResp, i + 4, blockLen - 4).order(ByteOrder.LITTLE_ENDIAN)
                    val encMethod = buf.int
                    val encLevel = buf.int
                    if (encMethod == 0 && encLevel == 0) return null
                    if (buf.remaining() < 8) return null
                    val serverRandLen = buf.int
                    val serverCertLen = buf.int
                    if (serverRandLen != 32 || serverCertLen <= 0 || buf.remaining() < serverRandLen + serverCertLen) return null
                    val serverRandom = ByteArray(32)
                    buf.get(serverRandom)
                    val certBytes = ByteArray(serverCertLen)
                    buf.get(certBytes)
                    // Look for "RSA1" magic (0x31415352) inside Proprietary Server Certificate
                    for (k in 0..certBytes.size - 24) {
                        if (certBytes[k] == 0x52.toByte() && certBytes[k + 1] == 0x53.toByte() &&
                            certBytes[k + 2] == 0x41.toByte() && certBytes[k + 3] == 0x31.toByte()
                        ) {
                            val rsaBuf = ByteBuffer.wrap(certBytes, k + 4, certBytes.size - (k + 4)).order(ByteOrder.LITTLE_ENDIAN)
                            val keyLen = rsaBuf.int // moduluslen + 8 zero padding bytes
                            rsaBuf.int // bitlen
                            val dataLen = rsaBuf.int
                            val pubExp = rsaBuf.int
                            val modLen = (keyLen - 8).coerceIn(64, 512)
                            if (rsaBuf.remaining() >= modLen) {
                                val modLe = ByteArray(modLen)
                                rsaBuf.get(modLe)
                                return ServerProprietaryRsaKey(
                                    serverRandom = serverRandom,
                                    modulus = modLe,
                                    pubExp = pubExp
                                )
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Encrypts 32-byte clientRandom using the server's little-endian RSA1 public key for SEC_EXCHANGE_PKT.
     */
    fun rsaEncryptClientRandomLe(clientRandom: ByteArray, rsaKey: ServerProprietaryRsaKey): ByteArray {
        val modBigEndian = rsaKey.modulus.reversedArray()
        val n = BigInteger(1, modBigEndian)
        val e = BigInteger.valueOf(rsaKey.pubExp.toLong() and 0xFFFFFFFFL)
        val m = BigInteger(1, clientRandom.reversedArray())
        val c = m.modPow(e, n)
        val cBigEndian = c.toByteArray()
        val cLe = cBigEndian.reversedArray()
        val out = ByteArray(rsaKey.modulus.size + 8)
        System.arraycopy(cLe, 0, out, 0, cLe.size.coerceAtMost(rsaKey.modulus.size))
        return out
    }

    fun deriveStandardRdpKeys(clientRandom: ByteArray, serverRandom: ByteArray): StandardRdpSecurityKeys {
        val preMasterSecret = clientRandom.copyOfRange(0, 24) + serverRandom.copyOfRange(0, 24)
        val masterSecret = saltedHash(preMasterSecret, "A", clientRandom, serverRandom) +
            saltedHash(preMasterSecret, "BB", clientRandom, serverRandom) +
            saltedHash(preMasterSecret, "CCC", clientRandom, serverRandom)
        val sessionKeyBlob = saltedHash(masterSecret, "X", clientRandom, serverRandom) +
            saltedHash(masterSecret, "YY", clientRandom, serverRandom) +
            saltedHash(masterSecret, "ZZZ", clientRandom, serverRandom)
        val macKey = sessionKeyBlob.copyOfRange(0, 16)
        val initialDecryptKey = md5(sessionKeyBlob.copyOfRange(16, 32), clientRandom, serverRandom)
        val initialEncryptKey = md5(sessionKeyBlob.copyOfRange(32, 48), clientRandom, serverRandom)
        return StandardRdpSecurityKeys(
            macKey = macKey,
            initialDecryptKey = initialDecryptKey,
            initialEncryptKey = initialEncryptKey,
            decryptCipher = Rc4Cipher(initialDecryptKey),
            encryptCipher = Rc4Cipher(initialEncryptKey)
        )
    }

    private fun saltedHash(secret: ByteArray, saltAscii: String, random1: ByteArray, random2: ByteArray): ByteArray {
        val sha = sha1(saltAscii.toByteArray(Charsets.US_ASCII), secret, random1, random2)
        return md5(secret, sha)
    }

    fun computeStandardRdpMac(macKey: ByteArray, data: ByteArray, encryptCount: Int): ByteArray {
        val pad1 = ByteArray(40) { 0x36 }
        val pad2 = ByteArray(48) { 0x5C }
        val lenLe = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array()
        val cntLe = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(encryptCount).array()
        val sha = sha1(macKey, pad1, lenLe, data)
        val md = md5(macKey, pad2, sha)
        return md.copyOfRange(0, 8)
    }

    /**
     * Deterministic ASN.1 SubjectPublicKeyInfo parser extracting the raw BIT STRING payload (excluding unused-bits byte).
     */
    fun extractSubjectPublicKeyBitString(spkiDer: ByteArray): ByteArray {
        if (spkiDer.size > 8 && spkiDer[0] == 0x30.toByte()) {
            val (seqLen, seqStart) = readDerLength(spkiDer, 1)
            if (seqStart < spkiDer.size && spkiDer[seqStart] == 0x30.toByte()) {
                val (algLen, algStart) = readDerLength(spkiDer, seqStart + 1)
                val bitStringPos = algStart + algLen
                if (bitStringPos < spkiDer.size && spkiDer[bitStringPos] == 0x03.toByte()) {
                    val (bsLen, bsStart) = readDerLength(spkiDer, bitStringPos + 1)
                    if (bsStart + bsLen <= spkiDer.size && bsLen > 1 && spkiDer[bsStart] == 0x00.toByte()) {
                        return spkiDer.copyOfRange(bsStart + 1, bsStart + bsLen)
                    }
                }
            }
        }
        var idx = 0
        while (idx < spkiDer.size - 4) {
            if (spkiDer[idx] == 0x03.toByte()) {
                val (len, contentStart) = readDerLength(spkiDer, idx + 1)
                if (contentStart + len == spkiDer.size && len > 1 && spkiDer[contentStart] == 0x00.toByte()) {
                    return spkiDer.copyOfRange(contentStart + 1, contentStart + len)
                }
            }
            idx++
        }
        return spkiDer
    }

    // =========================================================================
    // 5. ASN.1 DER Helpers for CredSSP TSRequest
    // =========================================================================
    fun derLength(len: Int): ByteArray {
        return when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len <= 0xFF -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), ((len ushr 8) and 0xFF).toByte(), (len and 0xFF).toByte())
        }
    }

    fun derTag(tag: Int, content: ByteArray): ByteArray {
        val lenBytes = derLength(content.size)
        val out = ByteArray(1 + lenBytes.size + content.size)
        out[0] = tag.toByte()
        System.arraycopy(lenBytes, 0, out, 1, lenBytes.size)
        System.arraycopy(content, 0, out, 1 + lenBytes.size, content.size)
        return out
    }

    fun derInteger(value: Int): ByteArray {
        return derTag(0x02, byteArrayOf(value.toByte()))
    }

    fun buildCredSspTsRequest(
        version: Int,
        negoToken: ByteArray? = null,
        authInfo: ByteArray? = null,
        pubKeyAuth: ByteArray? = null,
        clientNonce: ByteArray? = null
    ): ByteArray {
        val fields = ByteArrayOutputStream()
        fields.write(derTag(0xA0, derInteger(version)))
        if (negoToken != null) {
            val innerToken = derTag(0x30, derTag(0xA0, derTag(0x04, negoToken)))
            val negoData = derTag(0x30, innerToken)
            fields.write(derTag(0xA1, negoData))
        }
        if (authInfo != null) {
            fields.write(derTag(0xA2, derTag(0x04, authInfo)))
        }
        if (pubKeyAuth != null) {
            fields.write(derTag(0xA3, derTag(0x04, pubKeyAuth)))
        }
        if (clientNonce != null && version >= 5) {
            fields.write(derTag(0xA5, derTag(0x04, clientNonce)))
        }
        return derTag(0x30, fields.toByteArray())
    }

    data class ParsedTsRequest(
        val version: Int,
        val negoToken: ByteArray?,
        val pubKeyAuth: ByteArray?,
        val errorCode: Int?
    )

    fun parseCredSspTsRequest(der: ByteArray): ParsedTsRequest {
        var version = 2
        var negoToken: ByteArray? = null
        var pubKeyAuth: ByteArray? = null
        var errorCode: Int? = null

        if (der.isEmpty() || der[0] != 0x30.toByte()) {
            return ParsedTsRequest(version, null, null, null)
        }
        val (seqLen, seqStart) = readDerLength(der, 1)
        var pos = seqStart
        val end = (seqStart + seqLen).coerceAtMost(der.size)

        while (pos < end) {
            val tag = der[pos].toInt() and 0xFF
            val (fieldLen, fieldStart) = readDerLength(der, pos + 1)
            val fieldEnd = (fieldStart + fieldLen).coerceAtMost(end)
            val slice = der.copyOfRange(fieldStart, fieldEnd)

            when (tag) {
                0xA0 -> {
                    if (slice.size >= 3 && slice[0] == 0x02.toByte()) {
                        version = slice.last().toInt() and 0xFF
                    }
                }
                0xA1 -> {
                    negoToken = findOctetStringWithPrefix(slice, "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII))
                        ?: unwrapFirstOctetString(slice)
                }
                0xA3 -> {
                    pubKeyAuth = unwrapFirstOctetString(slice)
                }
                0xA4 -> {
                    if (slice.size >= 3 && slice[0] == 0x02.toByte()) {
                        var code = 0
                        val (intLen, intStart) = readDerLength(slice, 1)
                        val intEnd = (intStart + intLen).coerceAtMost(slice.size)
                        for (k in intStart until intEnd) {
                            code = (code shl 8) or (slice[k].toInt() and 0xFF)
                        }
                        errorCode = code
                    }
                }
            }
            pos = fieldEnd
        }

        return ParsedTsRequest(version, negoToken, pubKeyAuth, errorCode)
    }

    private fun readDerLength(data: ByteArray, offset: Int): Pair<Int, Int> {
        if (offset >= data.size) return Pair(0, offset)
        val first = data[offset].toInt() and 0xFF
        if ((first and 0x80) == 0) {
            return Pair(first, offset + 1)
        }
        val numBytes = first and 0x7F
        var len = 0
        for (i in 0 until numBytes) {
            if (offset + 1 + i < data.size) {
                len = (len shl 8) or (data[offset + 1 + i].toInt() and 0xFF)
            }
        }
        return Pair(len, offset + 1 + numBytes)
    }

    private fun unwrapFirstOctetString(slice: ByteArray): ByteArray? {
        var idx = 0
        while (idx < slice.size - 2) {
            if (slice[idx] == 0x04.toByte()) {
                val (len, start) = readDerLength(slice, idx + 1)
                if (start + len <= slice.size) {
                    return slice.copyOfRange(start, start + len)
                }
            }
            idx++
        }
        return null
    }

    private fun findOctetStringWithPrefix(slice: ByteArray, prefix: ByteArray): ByteArray? {
        for (i in 0..slice.size - prefix.size) {
            var match = true
            for (j in prefix.indices) {
                if (slice[i + j] != prefix[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                // Check if preceded by OCTET STRING tag (0x04) to respect exact DER length
                for (back in 2..5) {
                    val tagIdx = i - back
                    if (tagIdx >= 0 && slice[tagIdx] == 0x04.toByte()) {
                        val (len, start) = readDerLength(slice, tagIdx + 1)
                        if (start == i && i + len <= slice.size) {
                            return slice.copyOfRange(i, i + len)
                        }
                    }
                }
                return slice.copyOfRange(i, slice.size)
            }
        }
        return null
    }

    // =========================================================================
    // 6. MS-RDPBCGR Bitmap Tile, Interleaved RLE & RDP 6.0 Planar Decompressor
    //    Hardware-Accelerated Zero-Allocation Tile Pipeline (ThreadLocal Scratchpad)
    // =========================================================================
    private class TileDecodeScratchpad {
        var tilePixels = IntArray(64 * 64)
        var rleRawPixels = IntArray(64 * 64)
        var alphaPlane = ByteArray(64 * 64)
        var yPlane = ByteArray(64 * 64)
        var coPlane = ByteArray(64 * 64)
        var cgPlane = ByteArray(64 * 64)

        fun ensurePixelCapacity(count: Int) {
            if (tilePixels.size < count) {
                val cap = count.coerceAtLeast(tilePixels.size * 2)
                tilePixels = IntArray(cap)
                rleRawPixels = IntArray(cap)
            }
            // Zero out only the active region so partially-filled tiles never leak stale pixels
            java.util.Arrays.fill(tilePixels, 0, count, 0xFF000000.toInt())
            java.util.Arrays.fill(rleRawPixels, 0, count, 0)
        }

        fun ensurePlaneCapacity(lumaCount: Int, chromaCount: Int) {
            if (yPlane.size < lumaCount) {
                val cap = lumaCount.coerceAtLeast(yPlane.size * 2)
                alphaPlane = ByteArray(cap)
                yPlane = ByteArray(cap)
            }
            if (coPlane.size < chromaCount) {
                val cap = chromaCount.coerceAtLeast(coPlane.size * 2)
                coPlane = ByteArray(cap)
                cgPlane = ByteArray(cap)
            }
            java.util.Arrays.fill(yPlane, 0, lumaCount, 0.toByte())
            java.util.Arrays.fill(coPlane, 0, chromaCount, 0.toByte())
            java.util.Arrays.fill(cgPlane, 0, chromaCount, 0.toByte())
        }
    }

    private val threadLocalScratchpad = object : ThreadLocal<TileDecodeScratchpad>() {
        override fun initialValue(): TileDecodeScratchpad = TileDecodeScratchpad()
    }

    fun decodeBitmapRectangleIntoFramebuffer(
        framebuffer: IntArray,
        fbWidth: Int,
        fbHeight: Int,
        destLeft: Int,
        destTop: Int,
        destRight: Int,
        destBottom: Int,
        tileWidth: Int,
        tileHeight: Int,
        bitsPerPixel: Int,
        flags: Int,
        bitmapData: ByteArray
    ): DirtyRectBounds {
        if (tileWidth <= 0 || tileHeight <= 0 || bitmapData.isEmpty() || fbWidth <= 0 || fbHeight <= 0) {
            return DirtyRectBounds(0, 0, -1, -1)
        }
        val bytesPerPixel = when (bitsPerPixel) {
            32 -> 4
            24 -> 3
            15, 16 -> 2
            else -> 1
        }
        val isCompressed = (flags and 0x0001) != 0 || (flags and 0x0400) != 0
        val noCompressionHdr = (flags and 0x0400) != 0

        var srcOffset = 0
        if (isCompressed && !noCompressionHdr && (flags and 0x0008) == 0 && bitmapData.size > 8) {
            // Validate 8-byte TS_CD_HEADER (cbCompFirstRowSize == 0, valid cbCompMainBodySize & cbScanWidth)
            val cbCompFirstRowSize = (bitmapData[0].toInt() and 0xFF) or ((bitmapData[1].toInt() and 0xFF) shl 8)
            val cbCompMainBodySize = (bitmapData[2].toInt() and 0xFF) or ((bitmapData[3].toInt() and 0xFF) shl 8)
            val cbScanWidth = (bitmapData[4].toInt() and 0xFF) or ((bitmapData[5].toInt() and 0xFF) shl 8)
            if (cbCompFirstRowSize == 0 && cbScanWidth > 0 && cbCompMainBodySize in 1..(bitmapData.size - 8)) {
                srcOffset = 8
            }
        }

        val scratch = threadLocalScratchpad.get() ?: TileDecodeScratchpad()
        val pixelCount = tileWidth * tileHeight
        scratch.ensurePixelCapacity(pixelCount)
        val tilePixels = scratch.tilePixels

        if (!isCompressed) {
            decodeUncompressedTile(
                src = bitmapData,
                srcOffset = srcOffset,
                tileWidth = tileWidth,
                tileHeight = tileHeight,
                bpp = bitsPerPixel,
                bytesPerPixel = bytesPerPixel,
                outPixels = tilePixels
            )
        } else if (bitsPerPixel == 32) {
            // 32-bpp compressed tiles use RDP 6.0 Planar Compression (MS-RDPEGDI 2.2.2.5.1)
            val decodedPlanar = decodeRdp60PlanarTile(
                src = bitmapData,
                srcOffset = srcOffset,
                tileWidth = tileWidth,
                tileHeight = tileHeight,
                scratch = scratch,
                outPixels = tilePixels
            )
            if (!decodedPlanar) {
                decompressInterleavedRle(
                    src = bitmapData,
                    srcOffset = srcOffset,
                    tileWidth = tileWidth,
                    tileHeight = tileHeight,
                    bpp = bitsPerPixel,
                    bytesPerPixel = bytesPerPixel,
                    decompressedRaw = scratch.rleRawPixels,
                    outPixels = tilePixels
                )
            }
        } else {
            decompressInterleavedRle(
                src = bitmapData,
                srcOffset = srcOffset,
                tileWidth = tileWidth,
                tileHeight = tileHeight,
                bpp = bitsPerPixel,
                bytesPerPixel = bytesPerPixel,
                decompressedRaw = scratch.rleRawPixels,
                outPixels = tilePixels
            )
        }

        val clipW = (destRight - destLeft + 1).coerceAtMost(tileWidth)
        val clipH = (destBottom - destTop + 1).coerceAtMost(tileHeight)
        if (clipW <= 0 || clipH <= 0) {
            return DirtyRectBounds(0, 0, -1, -1)
        }

        val startCol = if (destLeft < 0) -destLeft else 0
        val dstStartX = (destLeft + startCol).coerceAtLeast(0)
        val copyWidth = (clipW - startCol).coerceAtMost(fbWidth - dstStartX)
        if (copyWidth <= 0) {
            return DirtyRectBounds(0, 0, -1, -1)
        }

        val startRow = if (destTop < 0) -destTop else 0
        val endRowExclusive = clipH.coerceAtMost(fbHeight - destTop)
        if (startRow >= endRowExclusive) {
            return DirtyRectBounds(0, 0, -1, -1)
        }

        // Fast SIMD System.arraycopy scanline blit into framebuffer
        for (row in startRow until endRowExclusive) {
            val dstY = destTop + row
            val srcRowStart = row * tileWidth + startCol
            val dstRowStart = dstY * fbWidth + dstStartX
            System.arraycopy(tilePixels, srcRowStart, framebuffer, dstRowStart, copyWidth)
        }

        return DirtyRectBounds(
            left = dstStartX,
            top = destTop + startRow,
            right = dstStartX + copyWidth - 1,
            bottom = destTop + endRowExclusive - 1
        )
    }

    private fun decodeRdp60PlanarTile(
        src: ByteArray,
        srcOffset: Int,
        tileWidth: Int,
        tileHeight: Int,
        scratch: TileDecodeScratchpad,
        outPixels: IntArray
    ): Boolean {
        if (srcOffset >= src.size) return false
        val formatHeader = src[srcOffset].toInt() and 0xFF
        val cll = formatHeader and 0x07
        val cs = (formatHeader and 0x08) != 0
        val rle = (formatHeader and 0x10) != 0
        val na = (formatHeader and 0x20) != 0
        // If reserved top 2 bits are set or both cll==0 and !rle, not a valid RDP 6.0 Planar header
        if ((formatHeader and 0xC0) != 0) return false
        if (cll == 0 && !rle) return false
        if (cs && cll == 0) return false // Chroma subsampling is only valid when YCoCg (cll > 0) is active

        var pos = srcOffset + 1
        val pixelCount = tileWidth * tileHeight
        val chromaW = if (cs) (tileWidth + 1) / 2 else tileWidth
        val chromaH = if (cs) (tileHeight + 1) / 2 else tileHeight
        val chromaCount = chromaW * chromaH
        scratch.ensurePlaneCapacity(pixelCount, chromaCount)

        if (!rle) {
            val needed = (if (na) 0 else pixelCount) + pixelCount + (2 * chromaCount)
            if (pos + needed > src.size) return false
            if (!na) pos += pixelCount // skip alpha plane
            System.arraycopy(src, pos, scratch.yPlane, 0, pixelCount); pos += pixelCount
            System.arraycopy(src, pos, scratch.coPlane, 0, chromaCount); pos += chromaCount
            System.arraycopy(src, pos, scratch.cgPlane, 0, chromaCount)
            reconstructPlanarPixels(
                tileWidth = tileWidth,
                tileHeight = tileHeight,
                cll = cll,
                cs = cs,
                chromaW = chromaW,
                chromaH = chromaH,
                yPlane = scratch.yPlane,
                coPlane = scratch.coPlane,
                cgPlane = scratch.cgPlane,
                outPixels = outPixels
            )
            return true
        }

        if (!na) {
            pos = decodePlanarRlePlane(src, pos, tileWidth, tileHeight, scratch.alphaPlane)
            if (pos < 0) return false
        }
        pos = decodePlanarRlePlane(src, pos, tileWidth, tileHeight, scratch.yPlane)
        if (pos < 0) return false

        pos = decodePlanarRlePlane(src, pos, chromaW, chromaH, scratch.coPlane)
        if (pos < 0) return false

        pos = decodePlanarRlePlane(src, pos, chromaW, chromaH, scratch.cgPlane)
        if (pos < 0) return false

        reconstructPlanarPixels(
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            cll = cll,
            cs = cs,
            chromaW = chromaW,
            chromaH = chromaH,
            yPlane = scratch.yPlane,
            coPlane = scratch.coPlane,
            cgPlane = scratch.cgPlane,
            outPixels = outPixels
        )
        return true
    }

    private fun reconstructPlanarPixels(
        tileWidth: Int,
        tileHeight: Int,
        cll: Int,
        cs: Boolean,
        chromaW: Int,
        chromaH: Int,
        yPlane: ByteArray,
        coPlane: ByteArray,
        cgPlane: ByteArray,
        outPixels: IntArray
    ) {
        val shift = (cll - 1).coerceAtLeast(0)
        for (row in 0 until tileHeight) {
            val srcY = tileHeight - 1 - row
            val dstRow = row * tileWidth
            val cY = if (cs) (srcY / 2).coerceAtMost(chromaH - 1) else srcY
            for (col in 0 until tileWidth) {
                val lumaIdx = srcY * tileWidth + col
                val cX = if (cs) (col / 2).coerceAtMost(chromaW - 1) else col
                val chromaIdx = cY * chromaW + cX

                if (cll > 0) {
                    val y = yPlane[lumaIdx].toInt() and 0xFF
                    // Co and Cg are signed 8-bit integers shifted by (cll - 1)
                    val co = coPlane[chromaIdx].toInt() shl shift
                    val cg = cgPlane[chromaIdx].toInt() shl shift
                    val t = y - (cg shr 1)
                    val g = (cg + t).coerceIn(0, 255)
                    val b = (t - (co shr 1)).coerceIn(0, 255)
                    val r = (b + co).coerceIn(0, 255)
                    outPixels[dstRow + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    val r = yPlane[lumaIdx].toInt() and 0xFF
                    val g = coPlane[chromaIdx].toInt() and 0xFF
                    val b = cgPlane[chromaIdx].toInt() and 0xFF
                    outPixels[dstRow + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    private fun decodePlanarRlePlane(
        src: ByteArray,
        startOffset: Int,
        width: Int,
        height: Int,
        dstPlane: ByteArray
    ): Int {
        var pos = startOffset
        for (row in 0 until height) {
            var col = 0
            val rowOffset = row * width
            val prevRowOffset = (row - 1) * width
            while (col < width) {
                if (pos >= src.size) return pos
                val control = src[pos++].toInt() and 0xFF
                var nRun = control and 0x0F
                var cRaw = (control ushr 4) and 0x0F
                if (nRun == 1) {
                    nRun = cRaw + 16
                    cRaw = 0
                } else if (nRun == 2) {
                    if (cRaw == 15) {
                        if (pos + 2 > src.size) return -1
                        nRun = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
                        pos += 2
                        cRaw = 0
                    } else {
                        nRun = cRaw + 32
                        cRaw = 0
                    }
                }
                // Raw bytes (deltas)
                for (k in 0 until cRaw) {
                    if (col >= width || pos >= src.size) break
                    val rawByte = src[pos++].toInt() and 0xFF
                    val delta = if ((rawByte and 1) != 0) -((rawByte + 1) shr 1) else (rawByte shr 1)
                    val base = if (row == 0) {
                        if (col == 0) 0 else dstPlane[rowOffset + col - 1].toInt() and 0xFF
                    } else {
                        val above = dstPlane[prevRowOffset + col].toInt() and 0xFF
                        val prevDelta = if (col == 0) 0 else {
                            (dstPlane[rowOffset + col - 1].toInt() and 0xFF) - (dstPlane[prevRowOffset + col - 1].toInt() and 0xFF)
                        }
                        above + prevDelta
                    }
                    dstPlane[rowOffset + col] = ((base + delta) and 0xFF).toByte()
                    col++
                }
                // Run length (zero delta)
                for (k in 0 until nRun) {
                    if (col >= width) break
                    val base = if (row == 0) {
                        if (col == 0) 0 else dstPlane[rowOffset + col - 1].toInt() and 0xFF
                    } else {
                        val above = dstPlane[prevRowOffset + col].toInt() and 0xFF
                        val prevDelta = if (col == 0) 0 else {
                            (dstPlane[rowOffset + col - 1].toInt() and 0xFF) - (dstPlane[prevRowOffset + col - 1].toInt() and 0xFF)
                        }
                        above + prevDelta
                    }
                    dstPlane[rowOffset + col] = (base and 0xFF).toByte()
                    col++
                }
            }
        }
        return pos
    }

    private fun decodeUncompressedTile(
        src: ByteArray,
        srcOffset: Int,
        tileWidth: Int,
        tileHeight: Int,
        bpp: Int,
        bytesPerPixel: Int,
        outPixels: IntArray
    ) {
        val rowStride = tileWidth * bytesPerPixel
        for (srcRow in 0 until tileHeight) {
            val dstRow = (tileHeight - 1 - srcRow)
            val rowByteOffset = srcOffset + srcRow * rowStride
            for (col in 0 until tileWidth) {
                val pOff = rowByteOffset + col * bytesPerPixel
                if (pOff + bytesPerPixel <= src.size) {
                    val rawColor = readPixelValue(src, pOff, bytesPerPixel)
                    outPixels[dstRow * tileWidth + col] = pixelToArgb(rawColor, bpp)
                }
            }
        }
    }

    /**
     * Exact MS-RDPBCGR Section 3.1.9 / FreeRDP `interleaved.c` RLE Bitmap Decompressor.
     * Eliminates horizontal scanline tearing/shift by:
     * 1. Dynamically evaluating `destIdx < tileWidth` per pixel (instead of only at order boundaries).
     * 2. Preserving `insertFgPel` across scanline boundaries (when two consecutive BG_RUN orders occur).
     * 3. Handling `MEGA_MEGA_SET_FG_RUN` (0xF6) and `MEGA_MEGA_SET_FGBG_IMAGE` (0xF7) where the fgPel
     *    follows the 16-bit length field.
     */
    private fun decompressInterleavedRle(
        src: ByteArray,
        srcOffset: Int,
        tileWidth: Int,
        tileHeight: Int,
        bpp: Int,
        bytesPerPixel: Int,
        decompressedRaw: IntArray,
        outPixels: IntArray
    ) {
        val totalPixels = tileWidth * tileHeight
        val whitePixel = when (bpp) {
            15 -> 0x7FFF
            16 -> 0xFFFF
            else -> 0xFFFFFF
        }

        var pos = srcOffset
        var destIdx = 0
        var fgPel = whitePixel
        var insertFgPel = false
        var fFirstLine = true

        while (pos < src.size && destIdx < totalPixels) {
            // MS-RDPBCGR 3.1.9.1.1: Watch out for the end of the first scanline and reset fInsertFgPel
            if (fFirstLine && destIdx >= tileWidth) {
                fFirstLine = false
                insertFgPel = false
            }

            val header = src[pos++].toInt() and 0xFF
            val code = extractRleCodeId(header)
            var runLength = extractRleRunLength(header, code, src, pos)
            if (isMegaOrExtendedRun(header, code)) {
                pos += getExtraLengthBytes(header, code)
            }

            when (code) {
                0x00 -> {
                    // REGULAR_BG_RUN / MEGA_MEGA_BG_RUN (MS-RDPBCGR 3.1.9.1.1)
                    if (insertFgPel && runLength > 0 && destIdx < totalPixels) {
                        val above = if (destIdx < tileWidth) 0 else decompressedRaw[destIdx - tileWidth]
                        decompressedRaw[destIdx++] = above xor fgPel
                        runLength--
                    }
                    while (runLength > 0 && destIdx < totalPixels) {
                        val above = if (destIdx < tileWidth) 0 else decompressedRaw[destIdx - tileWidth]
                        decompressedRaw[destIdx++] = above
                        runLength--
                    }
                    insertFgPel = true
                }

                0x01, 0x06 -> {
                    // REGULAR_FG_RUN / MEGA_MEGA_FG_RUN / LITE_SET_FG_FG_RUN / MEGA_MEGA_SET_FG_RUN
                    if (code == 0x06) {
                        if (pos + bytesPerPixel > src.size) break
                        fgPel = readPixelValue(src, pos, bytesPerPixel)
                        pos += bytesPerPixel
                    }
                    while (runLength > 0 && destIdx < totalPixels) {
                        val above = if (destIdx < tileWidth) 0 else decompressedRaw[destIdx - tileWidth]
                        decompressedRaw[destIdx++] = above xor fgPel
                        runLength--
                    }
                    insertFgPel = false
                }

                0x02, 0x07 -> {
                    // REGULAR_FGBG_IMAGE / MEGA_MEGA_FGBG_IMAGE / LITE_SET_FG_FGBG_IMAGE / MEGA_MEGA_SET_FGBG_IMAGE
                    if (code == 0x07) {
                        if (pos + bytesPerPixel > src.size) break
                        fgPel = readPixelValue(src, pos, bytesPerPixel)
                        pos += bytesPerPixel
                    }
                    while (runLength > 0 && pos < src.size && destIdx < totalPixels) {
                        val mask = src[pos++].toInt() and 0xFF
                        val bits = minOf(8, runLength)
                        for (bit in 0 until bits) {
                            if (destIdx >= totalPixels) break
                            val isFg = ((mask ushr bit) and 1) != 0
                            val above = if (destIdx < tileWidth) 0 else decompressedRaw[destIdx - tileWidth]
                            decompressedRaw[destIdx++] = if (isFg) above xor fgPel else above
                        }
                        runLength -= bits
                    }
                    insertFgPel = false
                }

                0x03 -> {
                    // REGULAR_COLOR_RUN / MEGA_MEGA_COLOR_RUN
                    if (pos + bytesPerPixel > src.size) break
                    val color = readPixelValue(src, pos, bytesPerPixel)
                    pos += bytesPerPixel
                    while (runLength > 0 && destIdx < totalPixels) {
                        decompressedRaw[destIdx++] = color
                        runLength--
                    }
                    insertFgPel = false
                }

                0x04 -> {
                    // REGULAR_COLOR_IMAGE / MEGA_MEGA_COLOR_IMAGE
                    while (runLength > 0 && destIdx < totalPixels && pos + bytesPerPixel <= src.size) {
                        decompressedRaw[destIdx++] = readPixelValue(src, pos, bytesPerPixel)
                        pos += bytesPerPixel
                        runLength--
                    }
                    insertFgPel = false
                }

                0x0E -> {
                    // LITE_DITHERED_RUN / MEGA_MEGA_DITHERED_RUN (runLength pairs of 2 pixels)
                    if (pos + 2 * bytesPerPixel > src.size) break
                    val c1 = readPixelValue(src, pos, bytesPerPixel)
                    pos += bytesPerPixel
                    val c2 = readPixelValue(src, pos, bytesPerPixel)
                    pos += bytesPerPixel
                    while (runLength > 0 && destIdx < totalPixels) {
                        decompressedRaw[destIdx++] = c1
                        if (destIdx < totalPixels) {
                            decompressedRaw[destIdx++] = c2
                        }
                        runLength--
                    }
                    insertFgPel = false
                }

                0xF9, 0xFA -> {
                    // SPECIAL_FGBG_1 (0x03) / SPECIAL_FGBG_2 (0x05)
                    val mask = if (code == 0xF9) 0x03 else 0x05
                    for (bit in 0 until 8) {
                        if (destIdx >= totalPixels) break
                        val isFg = ((mask ushr bit) and 1) != 0
                        val above = if (destIdx < tileWidth) 0 else decompressedRaw[destIdx - tileWidth]
                        decompressedRaw[destIdx++] = if (isFg) above xor fgPel else above
                    }
                    insertFgPel = false
                }

                0xFD -> {
                    // WHITE
                    if (destIdx < totalPixels) {
                        decompressedRaw[destIdx++] = whitePixel
                    }
                    insertFgPel = false
                }

                0xFE -> {
                    // BLACK
                    if (destIdx < totalPixels) {
                        decompressedRaw[destIdx++] = 0
                    }
                    insertFgPel = false
                }

                else -> break
            }
        }

        for (srcRow in 0 until tileHeight) {
            val dstRow = tileHeight - 1 - srcRow
            for (col in 0 until tileWidth) {
                val raw = decompressedRaw[srcRow * tileWidth + col]
                outPixels[dstRow * tileWidth + col] = pixelToArgb(raw, bpp)
            }
        }
    }

    private fun extractRleCodeId(header: Int): Int {
        if (header in 0xF9..0xFE) return header
        return when (header) {
            0xF0 -> 0x00 // MEGA_MEGA_BG_RUN
            0xF1 -> 0x01 // MEGA_MEGA_FG_RUN
            0xF2 -> 0x02 // MEGA_MEGA_FGBG_IMAGE
            0xF3 -> 0x03 // MEGA_MEGA_COLOR_RUN
            0xF4 -> 0x04 // MEGA_MEGA_COLOR_IMAGE
            0xF6 -> 0x06 // MEGA_MEGA_SET_FG_RUN
            0xF7 -> 0x07 // MEGA_MEGA_SET_FGBG_IMAGE
            0xF8 -> 0x0E // MEGA_MEGA_DITHERED_RUN
            else -> {
                val hi3 = (header ushr 5) and 0x07
                if (hi3 in 0..4) {
                    hi3
                } else {
                    when ((header ushr 4) and 0x0F) {
                        0x0C -> 0x06 // LITE_SET_FG_FG_RUN
                        0x0D -> 0x07 // LITE_SET_FG_FGBG_IMAGE
                        0x0E -> 0x0E // LITE_DITHERED_RUN
                        else -> hi3
                    }
                }
            }
        }
    }

    private fun isMegaOrExtendedRun(header: Int, code: Int): Boolean {
        if (header in 0xF0..0xF8) return true
        val rem = if (code in 0..4) header and 0x1F else header and 0x0F
        return rem == 0
    }

    private fun getExtraLengthBytes(header: Int, code: Int): Int {
        return if (header in 0xF0..0xF8) 2 else 1
    }

    private fun extractRleRunLength(header: Int, code: Int, src: ByteArray, pos: Int): Int {
        if (header in 0xF9..0xFE) return 1
        if (header in 0xF0..0xF8) {
            if (pos + 2 > src.size) return 0
            val rawLen = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
            // Per MS-RDPBCGR 2.2.9.1.1.3.1.2.4, MEGA_MEGA length is the exact run length (or +1 for FGBG if 0, FreeRDP uses rawLen)
            return rawLen
        }
        val isFgBgImage = (code == 0x02 || code == 0x07)
        val mask = if (code in 0..4) 0x1F else 0x0F
        val base = header and mask
        return if (base != 0) {
            if (isFgBgImage) base * 8 else base
        } else {
            if (pos >= src.size) 0
            else {
                val ext = src[pos].toInt() and 0xFF
                if (isFgBgImage) {
                    ext + 1
                } else {
                    ext + mask + 1
                }
            }
        }
    }

    private fun readPixelValue(src: ByteArray, offset: Int, bytesPerPixel: Int): Int {
        return when (bytesPerPixel) {
            1 -> src[offset].toInt() and 0xFF
            2 -> (src[offset].toInt() and 0xFF) or ((src[offset + 1].toInt() and 0xFF) shl 8)
            3 -> (src[offset].toInt() and 0xFF) or
                ((src[offset + 1].toInt() and 0xFF) shl 8) or
                ((src[offset + 2].toInt() and 0xFF) shl 16)
            else -> (src[offset].toInt() and 0xFF) or
                ((src[offset + 1].toInt() and 0xFF) shl 8) or
                ((src[offset + 2].toInt() and 0xFF) shl 16) or
                ((src[offset + 3].toInt() and 0xFF) shl 24)
        }
    }

    private fun pixelToArgb(raw: Int, bpp: Int): Int {
        return when (bpp) {
            15 -> {
                val r5 = (raw ushr 10) and 0x1F
                val g5 = (raw ushr 5) and 0x1F
                val b5 = raw and 0x1F
                val r = (r5 shl 3) or (r5 ushr 2)
                val g = (g5 shl 3) or (g5 ushr 2)
                val b = (b5 shl 3) or (b5 ushr 2)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            16 -> {
                val r5 = (raw ushr 11) and 0x1F
                val g6 = (raw ushr 5) and 0x3F
                val b5 = raw and 0x1F
                val r = (r5 shl 3) or (r5 ushr 2)
                val g = (g6 shl 2) or (g6 ushr 4)
                val b = (b5 shl 3) or (b5 ushr 2)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            24, 32 -> {
                val b = raw and 0xFF
                val g = (raw ushr 8) and 0xFF
                val r = (raw ushr 16) and 0xFF
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> {
                val v = raw and 0xFF
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }
}
