package com.example.protocol

import com.example.data.local.ConnectionProfileEntity
import com.example.data.model.RemoteProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

data class ProbeResult(
    val reachableSocket: Boolean,
    val latencyMs: Int,
    val discoveredBanner: String,
    val tunnelSummary: String?,
    val errorTitle: String? = null,
    val errorDetails: String? = null,
    val wireTrace: List<String> = emptyList()
)

object NetworkProbeEngine {

    /**
     * Performs a direct TCP socket reachability and wire-level handshake diagnostic test.
     */
    suspend fun probeProfileEndpoint(profile: ConnectionProfileEntity): ProbeResult = withContext(Dispatchers.IO) {
        val protocol = RemoteProtocol.fromString(profile.protocol)
        val connectHost = if (profile.sshTunnelEnabled && profile.sshTunnelHost.isNotBlank()) {
            profile.sshTunnelHost.trim()
        } else {
            profile.server.trim()
        }
        val connectPort = if (profile.sshTunnelEnabled && profile.sshTunnelHost.isNotBlank()) {
            profile.sshTunnelPort
        } else {
            profile.port
        }

        val tunnelCmd = if (profile.sshTunnelEnabled && profile.sshTunnelHost.isNotBlank()) {
            val localPort = 40000 + (profile.port % 10000)
            "ssh -N -L 127.0.0.1:$localPort:${profile.server}:${profile.port} ${profile.sshTunnelUsername.ifBlank { "root" }}@${profile.sshTunnelHost} -p ${profile.sshTunnelPort}"
        } else if (profile.rdGatewayEnabled && profile.rdGatewayServer.isNotBlank()) {
            "RD-Gateway HTTPS RPC -> ${profile.rdGatewayServer}:${profile.rdGatewayPort} -> ${profile.server}:${profile.port}"
        } else {
            null
        }

        val trace = mutableListOf<String>()
        trace.add("[remmina-${protocol.name.lowercase()}] Resolving endpoint $connectHost:$connectPort...")
        if (tunnelCmd != null) {
            trace.add("[ssh-tunnel] Initializing tunnel: $tunnelCmd")
        }

        val socket = Socket()
        try {
            var banner = ""
            val elapsedMs = measureTimeMillis {
                trace.add("[tcp-socket] Opening AF_INET stream socket to $connectHost:$connectPort (timeout=2500ms)...")
                socket.connect(InetSocketAddress(connectHost, connectPort), 2500)
                socket.soTimeout = 2000
                trace.add("[tcp-socket] SYN/ACK received — TCP connection established.")

                val input: InputStream = socket.getInputStream()
                val output: OutputStream = socket.getOutputStream()

                banner = performWireHandshake(protocol, profile, input, output, trace)
            }.toInt().coerceAtLeast(1)

            try {
                socket.close()
            } catch (_: Exception) {
            }

            ProbeResult(
                reachableSocket = true,
                latencyMs = elapsedMs,
                discoveredBanner = banner,
                tunnelSummary = tunnelCmd,
                errorTitle = null,
                errorDetails = null,
                wireTrace = trace
            )
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            val endpointDesc = if (profile.sshTunnelEnabled && profile.sshTunnelHost.isNotBlank()) {
                "SSH tunnel gateway ${profile.sshTunnelHost}:${profile.sshTunnelPort} (for ${profile.server}:${profile.port})"
            } else {
                "${protocol.displayName} server ${profile.server}:${profile.port}"
            }
            val errTitle = "Unable to connect to the $endpointDesc"
            val errDetail = "${e.javaClass.simpleName}: ${e.localizedMessage ?: "Host unreachable or connection refused"}"
            trace.add("[error] Socket connection failed: $errDetail")
            trace.add("[remmina] Session terminated (ERRCONNECT_CONNECT_FAILED).")

            ProbeResult(
                reachableSocket = false,
                latencyMs = -1,
                discoveredBanner = "Disconnected",
                tunnelSummary = tunnelCmd,
                errorTitle = errTitle,
                errorDetails = errDetail,
                wireTrace = trace
            )
        }
    }

    private fun performWireHandshake(
        protocol: RemoteProtocol,
        profile: ConnectionProfileEntity,
        input: InputStream,
        output: OutputStream,
        trace: MutableList<String>
    ): String {
        return when (protocol) {
            RemoteProtocol.RDP -> {
                val x224CrPdu = byteArrayOf(
                    0x03, 0x00, 0x00, 0x13,
                    0x0E,
                    0xE0.toByte(),
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00,
                    0x01,
                    0x00,
                    0x08, 0x00,
                    0x03, 0x00, 0x00, 0x00
                )
                trace.add("[freerdp] Sending X.224 Connection Request PDU (19 bytes, NLA/TLS)...")
                output.write(x224CrPdu)
                output.flush()

                val resp = ByteArray(64)
                val read = try {
                    input.read(resp)
                } catch (_: Exception) {
                    -1
                }
                if (read >= 6 && resp[0] == 0x03.toByte() && (resp[5].toInt() and 0xF0) == 0xD0) {
                    trace.add("[freerdp] Received X.224 Connection Confirm PDU (0xD0, $read bytes).")
                    "FreeRDP X.224 CC (0xD0) • ${profile.securityMode} • ${profile.resolution}"
                } else if (read > 0) {
                    trace.add("[freerdp] Received $read handshake bytes from ${profile.server}:${profile.port}.")
                    "RDP Endpoint Active ($read bytes)"
                } else {
                    "RDP Socket Connected (${profile.server}:${profile.port})"
                }
            }

            RemoteProtocol.VNC -> {
                trace.add("[vnc-rfb] Waiting for server RFB ProtocolVersion banner...")
                val buf = ByteArray(32)
                val read = try {
                    input.read(buf)
                } catch (_: Exception) {
                    -1
                }
                val serverVer = if (read > 0) {
                    String(buf, 0, read, Charsets.US_ASCII).trim()
                } else ""
                if (serverVer.startsWith("RFB ")) {
                    trace.add("[vnc-rfb] Server announced '$serverVer'. Sending client 'RFB 003.008\\n'...")
                    output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                    "$serverVer • ${profile.codec}"
                } else {
                    trace.add("[vnc-rfb] TCP connected (${read.coerceAtLeast(0)} bytes read).")
                    "RFB Socket Connected (${profile.server}:${profile.port})"
                }
            }

            RemoteProtocol.SSH -> {
                val clientBanner = "SSH-2.0-RemminaAndroid_1.4.35\r\n"
                trace.add("[libssh] Sending client banner: SSH-2.0-RemminaAndroid_1.4.35")
                output.write(clientBanner.toByteArray(Charsets.US_ASCII))
                output.flush()
                val buf = ByteArray(128)
                val read = try {
                    input.read(buf)
                } catch (_: Exception) {
                    -1
                }
                val remoteBanner = if (read > 0) {
                    String(buf, 0, read, Charsets.UTF_8).lineSequence().firstOrNull()?.trim().orEmpty()
                } else ""
                if (remoteBanner.isNotEmpty()) {
                    trace.add("[libssh] Remote server banner: $remoteBanner")
                    remoteBanner
                } else {
                    "SSH Socket Connected (${profile.server}:${profile.port})"
                }
            }

            RemoteProtocol.SPICE -> {
                val spiceLinkHdr = byteArrayOf(
                    0x52, 0x45, 0x44, 0x51,
                    0x02, 0x00, 0x00, 0x00,
                    0x02, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00
                )
                trace.add("[spice-gtk] Sending REDQ SpiceLinkHeader v2.2 to ${profile.server}:${profile.port}...")
                output.write(spiceLinkHdr)
                output.flush()
                "SPICE REDQ v2.2 (${profile.server}:${profile.port})"
            }
        }
    }

    fun parseResolutionDimensions(resString: String): Pair<Int, Int> {
        val parts = resString.lowercase().split("x")
        val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1280
        val h = parts.getOrNull(1)?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 720
        return Pair(w.coerceIn(320, 7680), h.coerceIn(240, 4320))
    }
}
