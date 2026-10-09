package com.example.data.repository

import com.example.data.local.ConnectionProfileEntity
import com.example.data.local.KeyMappingEntity
import com.example.data.local.ProtocolPortDefaultEntity
import com.example.data.local.RemminaDao
import com.example.data.local.SshKeyEntity
import com.example.data.model.RemoteProtocol
import com.example.security.CredentialVaultManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class RemminaRepository(
    private val dao: RemminaDao,
    val vaultManager: CredentialVaultManager
) {
    val allProfiles: Flow<List<ConnectionProfileEntity>> = dao.observeAllProfiles()
    val allSshKeys: Flow<List<SshKeyEntity>> = dao.observeAllSshKeys()
    val allKeyMappings: Flow<List<KeyMappingEntity>> = dao.observeAllKeyMappings()

    val protocolDefaultPorts: Flow<Map<String, Int>> = dao.observeProtocolDefaultPorts().map { list ->
        val map = mutableMapOf(
            "RDP" to 3389,
            "SSH" to 22,
            "VNC" to 5900,
            "SPICE" to 5900,
            "SSH_TUNNEL" to 22,
            "RD_GATEWAY" to 443
        )
        list.forEach { item ->
            map[item.protocolName.uppercase()] = item.defaultPort
        }
        map
    }

    suspend fun getDefaultPortForProtocol(protocolName: String): Int {
        val snapshot = dao.getProtocolDefaultPortsSnapshot()
        return snapshot.firstOrNull { it.protocolName.equals(protocolName, ignoreCase = true) }?.defaultPort
            ?: RemoteProtocol.fromString(protocolName).defaultPort
    }

    suspend fun updateProtocolDefaultPort(protocolName: String, newPort: Int) {
        val validPort = newPort.coerceIn(1, 65535)
        dao.upsertProtocolDefaultPort(ProtocolPortDefaultEntity(protocolName.uppercase(), validPort))
    }

    suspend fun resetAllDefaultPortsToStandard() {
        dao.upsertProtocolDefaultPort(ProtocolPortDefaultEntity("RDP", 3389))
        dao.upsertProtocolDefaultPort(ProtocolPortDefaultEntity("SSH", 22))
        dao.upsertProtocolDefaultPort(ProtocolPortDefaultEntity("VNC", 5900))
        dao.upsertProtocolDefaultPort(ProtocolPortDefaultEntity("SPICE", 5900))
        dao.upsertProtocolDefaultPort(ProtocolPortDefaultEntity("SSH_TUNNEL", 22))
        dao.upsertProtocolDefaultPort(ProtocolPortDefaultEntity("RD_GATEWAY", 443))
    }

    suspend fun saveProfile(profile: ConnectionProfileEntity): Long {
        val encryptedProfile = vaultManager.encryptProfileSecrets(profile)
        return if (encryptedProfile.id == 0L) {
            dao.insertProfile(encryptedProfile)
        } else {
            dao.updateProfile(encryptedProfile)
            encryptedProfile.id
        }
    }

    /**
     * Decrypts a profile's credentials (`password`, `sshTunnelPassword`) in memory for an authenticated
     * remote session handshake or an authenticated Profile Editor dialog.
     */
    fun decryptProfileSecrets(profile: ConnectionProfileEntity): ConnectionProfileEntity {
        return vaultManager.decryptProfileSecrets(profile)
    }

    /**
     * Scans all profiles in SQLite and encrypts any legacy unencrypted passwords in-place using AES-256-GCM.
     */
    suspend fun migratePlaintextPasswordsInDatabase(): Int {
        val all = dao.getAllProfilesSnapshot()
        var migrated = 0
        for (p in all) {
            val needsPwEnc = p.password.isNotEmpty() && !vaultManager.isEncryptedCiphertext(p.password)
            val needsSshEnc = p.sshTunnelPassword.isNotEmpty() && !vaultManager.isEncryptedCiphertext(p.sshTunnelPassword)
            if (needsPwEnc || needsSshEnc) {
                dao.updateProfile(vaultManager.encryptProfileSecrets(p))
                migrated++
            }
        }
        return migrated
    }

    suspend fun getRawDatabaseProfilesSnapshot(): List<ConnectionProfileEntity> {
        return dao.getAllProfilesSnapshot()
    }

    suspend fun updatePinnedCertificate(profileId: Long, sha256Fingerprint: String) {
        if (profileId != 0L && sha256Fingerprint.isNotBlank()) {
            dao.updatePinnedCertificate(profileId, sha256Fingerprint)
        }
    }

    suspend fun updateProfileDisplaySettings(
        profileId: Long,
        resolution: String,
        resolutionMode: String,
        scalingMode: String,
        colorDepth: Int,
        dpiScale: Int
    ) {
        if (profileId != 0L) {
            dao.updateProfileDisplaySettings(profileId, resolution, resolutionMode, scalingMode, colorDepth, dpiScale)
        }
    }

    suspend fun updateProfileCredentials(
        profileId: Long,
        username: String,
        password: String,
        domain: String,
        securityMode: String
    ) {
        if (profileId != 0L) {
            val encryptedPassword = if (password.isNotEmpty()) vaultManager.encryptSecret(password) else ""
            dao.updateProfileCredentials(profileId, username, encryptedPassword, domain, securityMode)
        }
    }

    suspend fun deleteProfile(profile: ConnectionProfileEntity) {
        dao.deleteProfile(profile)
    }

    suspend fun toggleFavorite(profile: ConnectionProfileEntity) {
        dao.setFavorite(profile.id, !profile.isFavorite)
    }

    suspend fun recordConnection(profileId: Long, latencyMs: Int) {
        dao.updateConnectionStats(profileId, System.currentTimeMillis(), latencyMs)
    }

    suspend fun generateSshKey(alias: String, algorithm: String, passphraseProtected: Boolean): SshKeyEntity {
        val randomBytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val digest = MessageDigest.getInstance("SHA-256").digest(randomBytes)
        val base64Hash = Base64.getEncoder().withoutPadding().encodeToString(digest)
        val privateKeyMaterialB64 = Base64.getEncoder().encodeToString(randomBytes)
        randomBytes.fill(0)

        val prefix = when (algorithm) {
            "RSA-4096" -> "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQC"
            "ECDSA-P256" -> "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAI"
            else -> "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI"
        }
        val cleanAlias = alias.ifBlank { "remmobile-key-${System.currentTimeMillis() % 1000}" }
        val rawPrivatePem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$privateKeyMaterialB64\n-----END OPENSSH PRIVATE KEY-----"
        val encryptedPem = vaultManager.encryptSecret(rawPrivatePem)

        val key = SshKeyEntity(
            alias = cleanAlias,
            algorithm = algorithm,
            publicKeyOpenSsh = "$prefix$base64Hash $cleanAlias@remmobile-android",
            encryptedPrivateKeyPem = encryptedPem,
            fingerprintSha256 = "SHA256:$base64Hash",
            passphraseProtected = passphraseProtected
        )
        val id = dao.insertSshKey(key)
        return key.copy(id = id)
    }

    suspend fun deleteSshKey(key: SshKeyEntity) {
        dao.deleteSshKey(key)
    }

    suspend fun saveKeyMapping(mapping: KeyMappingEntity): Long {
        return if (mapping.id == 0L) {
            dao.insertKeyMapping(mapping)
        } else {
            dao.updateKeyMapping(mapping)
            mapping.id
        }
    }

    suspend fun toggleKeyMapping(mapping: KeyMappingEntity) {
        dao.setKeyMappingEnabled(mapping.id, !mapping.isEnabled)
    }

    suspend fun deleteKeyMapping(mapping: KeyMappingEntity) {
        dao.deleteKeyMapping(mapping)
    }

    /**
     * Parses a quick-connect URI or hostname (e.g. `rdp://user@192.168.101.3`, `192.168.101.3`, `ssh://root@host`)
     * using the user-configured default port for the selected protocol when no explicit `:port` is given.
     */
    fun parseQuickConnectUri(
        rawInput: String,
        fallbackProtocol: RemoteProtocol = RemoteProtocol.RDP,
        customDefaultPorts: Map<String, Int> = emptyMap()
    ): ConnectionProfileEntity {
        val trimmed = rawInput.trim()
        val schemeSplit = trimmed.split("://", limit = 2)
        val protocol = if (schemeSplit.size == 2) {
            RemoteProtocol.fromString(schemeSplit[0])
        } else {
            fallbackProtocol
        }
        val remainder = if (schemeSplit.size == 2) schemeSplit[1] else trimmed
        val userHostSplit = remainder.split("@", limit = 2)
        val userPart = if (userHostSplit.size == 2) userHostSplit[0] else ""
        val username = userPart.substringBefore(":")
        val password = userPart.substringAfter(":", "")
        val hostPort = if (userHostSplit.size == 2) userHostSplit[1] else userHostSplit[0]
        val hpSplit = hostPort.split(":", limit = 2)
        val host = hpSplit[0].ifBlank { "127.0.0.1" }
        val configuredDefaultPort = customDefaultPorts[protocol.name] ?: protocol.defaultPort
        val port = hpSplit.getOrNull(1)?.toIntOrNull() ?: configuredDefaultPort

        return ConnectionProfileEntity(
            name = "$host (${protocol.displayName})",
            groupName = "Quick Connect",
            protocol = protocol.name,
            server = host,
            port = port,
            username = username,
            password = password,
            resolutionMode = "DYNAMIC_CLIENT",
            resolution = "1280x720",
            scalingMode = "FIT_WINDOW",
            colorDepth = 16,
            securityMode = "Negotiate",
            codec = protocol.defaultCodec
        )
    }

    /**
     * Exports a profile to authentic Desktop Remmina `.remmina` INI keyfile format.
     */
    fun exportProfileToRemminaIni(profile: ConnectionProfileEntity): String {
        val w = profile.resolution.substringBefore("x", "1280").trim()
        val h = profile.resolution.substringAfter("x", "720").trim()
        val resModeInt = when (profile.resolutionMode) {
            "DYNAMIC_CLIENT" -> 0
            "CUSTOM" -> 1
            else -> 2
        }
        val scaleInt = when (profile.scalingMode) {
            "FIT_WINDOW" -> 1
            "STRETCH" -> 2
            else -> 0
        }
        return buildString {
            appendLine("[remmina]")
            appendLine("name=${profile.name}")
            appendLine("group=${profile.groupName}")
            appendLine("protocol=${profile.protocol}")
            appendLine("server=${profile.server}:${profile.port}")
            appendLine("username=${profile.username}")
            appendLine("# password=[REDACTED_AES256GCM_ENCRYPTED_IN_REMMOBILE_VAULT]")
            appendLine("domain=${profile.domain}")
            appendLine("resolution_mode=$resModeInt")
            appendLine("resolution_width=$w")
            appendLine("resolution_height=$h")
            appendLine("scale=$scaleInt")
            appendLine("viewmode=${if (profile.startFullScreen) 4 else 1}")
            appendLine("colordepth=${profile.colorDepth}")
            appendLine("quality=${profile.qualityPreset}")
            appendLine("security=${profile.securityMode}")
            appendLine("sound=${if (profile.enableAudioRedirection) "local" else "off"}")
            appendLine("disableclipboard=${if (profile.enableClipboardSync) 0 else 1}")
            appendLine("ssh_tunnel_enabled=${if (profile.sshTunnelEnabled) 1 else 0}")
            appendLine("ssh_tunnel_server=${profile.sshTunnelHost}:${profile.sshTunnelPort}")
            appendLine("ssh_tunnel_username=${profile.sshTunnelUsername}")
            appendLine("ssh_tunnel_auth=${profile.sshTunnelAuthMethod}")
            appendLine("ssh_tunnel_loopback=${if (profile.sshTunnelLoopback) 1 else 0}")
            appendLine("gwtransp=${if (profile.rdGatewayEnabled) "http" else "auto"}")
            appendLine("gateway_server=${profile.rdGatewayServer}:${profile.rdGatewayPort}")
            appendLine("gateway_username=${profile.rdGatewayUsername}")
            appendLine("gateway_domain=${profile.rdGatewayDomain}")
            appendLine("precommand=${profile.preExecCommand}")
        }
    }

    /**
     * Imports one or multiple Desktop Remmina `.remmina` INI sections from file content or text.
     */
    suspend fun importFromRemminaIni(iniText: String): List<ConnectionProfileEntity> {
        val sections = mutableListOf<Map<String, String>>()
        var currentMap = mutableMapOf<String, String>()

        iniText.lines().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.equals("[remmina]", ignoreCase = true)) {
                if (currentMap.isNotEmpty()) {
                    sections.add(currentMap)
                    currentMap = mutableMapOf()
                }
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("[") && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val key = trimmed.substringBefore("=").trim()
                val value = trimmed.substringAfter("=").trim()
                currentMap[key] = value
            }
        }
        if (currentMap.isNotEmpty()) {
            sections.add(currentMap)
        }

        val importedProfiles = mutableListOf<ConnectionProfileEntity>()
        val portDefaults = dao.getProtocolDefaultPortsSnapshot().associate { it.protocolName.uppercase() to it.defaultPort }

        for (map in sections) {
            val serverRaw = map["server"]?.takeIf { it.isNotBlank() } ?: continue
            val protocol = RemoteProtocol.fromString(map["protocol"] ?: "RDP")
            val defaultPort = portDefaults[protocol.name] ?: protocol.defaultPort
            val sshTunnelDefault = portDefaults["SSH_TUNNEL"] ?: 22
            val rdGwDefault = portDefaults["RD_GATEWAY"] ?: 443

            val host = serverRaw.substringBefore(":")
            val port = serverRaw.substringAfter(":", "").toIntOrNull() ?: defaultPort

            val legacyRes = map["resolution"]
            val width = map["resolution_width"]?.takeIf { it.isNotBlank() }
                ?: legacyRes?.substringBefore("x")?.takeIf { it.isNotBlank() }
                ?: "1280"
            val height = map["resolution_height"]?.takeIf { it.isNotBlank() }
                ?: legacyRes?.substringAfter("x")?.takeIf { it.isNotBlank() }
                ?: "720"

            val resMode = when (map["resolution_mode"]?.toIntOrNull()) {
                0 -> "DYNAMIC_CLIENT"
                1 -> "CUSTOM"
                else -> "FIXED_PRESET"
            }
            val scaleMode = when (map["scale"]?.toIntOrNull()) {
                1 -> "FIT_WINDOW"
                2 -> "STRETCH"
                else -> "ONE_TO_ONE"
            }

            val tunnelServerRaw = map["ssh_tunnel_server"] ?: ""
            val gwServerRaw = map["gateway_server"] ?: ""

            val entity = ConnectionProfileEntity(
                name = map["name"]?.ifBlank { "${protocol.displayName} • $host" } ?: "${protocol.displayName} • $host",
                groupName = map["group"]?.ifBlank { "Imported" } ?: "Imported",
                protocol = protocol.name,
                server = host,
                port = port,
                username = map["username"] ?: map["ssh_username"] ?: "",
                password = (map["password"] ?: "").let { if (it.isNotEmpty()) vaultManager.encryptSecret(it) else "" },
                domain = map["domain"] ?: "",
                resolutionMode = resMode,
                resolution = "${width}x${height}",
                scalingMode = scaleMode,
                startFullScreen = map["viewmode"] == "4" || map["fullscreen"] == "1",
                colorDepth = map["colordepth"]?.toIntOrNull() ?: 16,
                codec = protocol.defaultCodec,
                securityMode = map["security"]?.ifBlank { "Negotiate" } ?: "Negotiate",
                enableAudioRedirection = map["sound"] != "off",
                enableClipboardSync = map["disableclipboard"] != "1",
                sshTunnelEnabled = map["ssh_tunnel_enabled"] == "1" || map["ssh_tunnel_enabled"] == "true",
                sshTunnelHost = tunnelServerRaw.substringBefore(":"),
                sshTunnelPort = tunnelServerRaw.substringAfter(":", "").toIntOrNull() ?: sshTunnelDefault,
                sshTunnelUsername = map["ssh_tunnel_username"] ?: "",
                sshTunnelLoopback = map["ssh_tunnel_loopback"] == "1",
                rdGatewayEnabled = gwServerRaw.isNotBlank(),
                rdGatewayServer = gwServerRaw.substringBefore(":"),
                rdGatewayPort = gwServerRaw.substringAfter(":", "").toIntOrNull() ?: rdGwDefault,
                rdGatewayUsername = map["gateway_username"] ?: "",
                rdGatewayDomain = map["gateway_domain"] ?: "",
                preExecCommand = map["precommand"] ?: ""
            )
            val id = dao.insertProfile(entity)
            importedProfiles.add(entity.copy(id = id))
        }
        return importedProfiles
    }

    /**
     * Exports all profiles, default ports, and custom hardware keyboard mappings into a 100% local portable JSON bundle.
     */
    suspend fun exportLocalPortableBundle(): String {
        val root = JSONObject()
        root.put("format", "remmina-android-local-bundle-v3")
        root.put("exportedAtEpochMs", System.currentTimeMillis())

        val portsObj = JSONObject()
        dao.getProtocolDefaultPortsSnapshot().forEach { p ->
            portsObj.put(p.protocolName, p.defaultPort)
        }
        root.put("defaultPorts", portsObj)

        val profilesArr = JSONArray()
        dao.getAllProfilesSnapshot().forEach { p ->
            val obj = JSONObject().apply {
                put("name", p.name)
                put("groupName", p.groupName)
                put("protocol", p.protocol)
                put("server", p.server)
                put("port", p.port)
                put("username", p.username)
                put("domain", p.domain)
                put("securityMode", p.securityMode)
                put("resolutionMode", p.resolutionMode)
                put("resolution", p.resolution)
                put("scalingMode", p.scalingMode)
                put("startFullScreen", p.startFullScreen)
                put("colorDepth", p.colorDepth)
                put("codec", p.codec)
                put("sshTunnelEnabled", p.sshTunnelEnabled)
                put("sshTunnelHost", p.sshTunnelHost)
                put("sshTunnelPort", p.sshTunnelPort)
                put("sshTunnelUsername", p.sshTunnelUsername)
                put("rdGatewayEnabled", p.rdGatewayEnabled)
                put("rdGatewayServer", p.rdGatewayServer)
                put("rdGatewayPort", p.rdGatewayPort)
            }
            profilesArr.put(obj)
        }
        root.put("profiles", profilesArr)

        val keysArr = JSONArray()
        dao.getAllKeyMappingsSnapshot().forEach { k ->
            val obj = JSONObject().apply {
                put("name", k.name)
                put("targetProtocol", k.targetProtocol)
                put("sourceKeyLabel", k.sourceKeyLabel)
                put("sourceAndroidKeyCode", k.sourceAndroidKeyCode)
                put("requireCtrl", k.requireCtrl)
                put("requireAlt", k.requireAlt)
                put("requireShift", k.requireShift)
                put("mappedActionType", k.mappedActionType)
                put("targetKeyLabel", k.targetKeyLabel)
                put("targetAndroidKeyCode", k.targetAndroidKeyCode)
                put("targetModifiersMask", k.targetModifiersMask)
                put("customSequence", k.customSequence)
            }
            keysArr.put(obj)
        }
        root.put("keyMappings", keysArr)
        return root.toString(2)
    }

    suspend fun importLocalPortableBundle(jsonString: String): Int {
        val root = JSONObject(jsonString)
        val portsObj = root.optJSONObject("defaultPorts")
        if (portsObj != null) {
            portsObj.keys().forEach { key ->
                val portVal = portsObj.optInt(key, -1)
                if (portVal in 1..65535) {
                    dao.upsertProtocolDefaultPort(ProtocolPortDefaultEntity(key.uppercase(), portVal))
                }
            }
        }

        var importedCount = 0
        val profilesArr = root.optJSONArray("profiles")
        if (profilesArr != null) {
            for (i in 0 until profilesArr.length()) {
                val obj = profilesArr.getJSONObject(i)
                val protoName = obj.optString("protocol", "RDP")
                val defaultPort = getDefaultPortForProtocol(protoName)
                val entity = ConnectionProfileEntity(
                    name = obj.optString("name", "Imported Server"),
                    groupName = obj.optString("groupName", "Imported"),
                    protocol = protoName,
                    server = obj.optString("server", "127.0.0.1"),
                    port = obj.optInt("port", defaultPort),
                    username = obj.optString("username", ""),
                    password = obj.optString("password", "").let { if (it.isNotEmpty()) vaultManager.encryptSecret(it) else "" },
                    domain = obj.optString("domain", ""),
                    securityMode = obj.optString("securityMode", "Negotiate"),
                    resolutionMode = obj.optString("resolutionMode", "FIXED_PRESET"),
                    resolution = obj.optString("resolution", "1280x720"),
                    scalingMode = obj.optString("scalingMode", "FIT_WINDOW"),
                    startFullScreen = obj.optBoolean("startFullScreen", false),
                    colorDepth = obj.optInt("colorDepth", 16),
                    codec = obj.optString("codec", "RemoteFX / RLE Bitmap"),
                    sshTunnelEnabled = obj.optBoolean("sshTunnelEnabled", false),
                    sshTunnelHost = obj.optString("sshTunnelHost", ""),
                    sshTunnelPort = obj.optInt("sshTunnelPort", 22),
                    sshTunnelUsername = obj.optString("sshTunnelUsername", ""),
                    rdGatewayEnabled = obj.optBoolean("rdGatewayEnabled", false),
                    rdGatewayServer = obj.optString("rdGatewayServer", ""),
                    rdGatewayPort = obj.optInt("rdGatewayPort", 443)
                )
                dao.insertProfile(entity)
                importedCount++
            }
        }
        return importedCount
    }
}
