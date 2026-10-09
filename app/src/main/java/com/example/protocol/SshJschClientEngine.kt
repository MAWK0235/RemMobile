package com.example.protocol

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.Vector

class SshJschClientEngine(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val cols: Int = 120,
    private val rows: Int = 36,
    private val preExecCommand: String = "",
    private val onTrace: (String) -> Unit,
    private val onTerminalOutput: (String) -> Unit,
    private val onSftpFilesUpdated: (String, List<SftpRemoteFile>) -> Unit,
    private val onDisconnected: (String, String) -> Unit
) {
    @Volatile
    private var jschSession: Session? = null
    @Volatile
    private var shellChannel: ChannelShell? = null
    @Volatile
    private var shellOutput: OutputStream? = null
    @Volatile
    var isRunning: Boolean = false
        private set

    fun connectAndStreamShellBlocking() {
        try {
            val jsch = JSch()
            val user = username.ifBlank { "root" }
            onTrace("[ssh-jsch] Connecting to $user@$host:$port...")

            val session = jsch.getSession(user, host, port)
            if (password.isNotEmpty()) {
                session.setPassword(password)
            }
            val config = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "password,keyboard-interactive,publickey")
            }
            session.setConfig(config)
            session.connect(6000)
            jschSession = session

            val hostKeyFp = try {
                session.hostKey?.getFingerPrint(jsch) ?: "verified"
            } catch (_: Exception) {
                "verified"
            }
            onTrace("[ssh-jsch] Authenticated (${session.serverVersion}, HostKey: $hostKeyFp)")

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPtyType("xterm-256color", cols, rows, cols * 9, rows * 18)
            val inputStream: InputStream = channel.inputStream
            val outStream: OutputStream = channel.outputStream
            shellOutput = outStream
            shellChannel = channel

            channel.connect(5000)
            isRunning = true

            if (preExecCommand.isNotBlank()) {
                sendRawText("$preExecCommand\n")
            }

            val buf = ByteArray(4096)
            while (isRunning && channel.isConnected) {
                val readLen = inputStream.read(buf)
                if (readLen < 0) break
                if (readLen > 0) {
                    val rawChunk = String(buf, 0, readLen, Charsets.UTF_8)
                    val cleaned = stripAnsiControlCodes(rawChunk)
                    if (cleaned.isNotEmpty()) {
                        onTerminalOutput(cleaned)
                    }
                }
            }
        } catch (e: Exception) {
            onDisconnected(
                "Unable to connect to SSH server $host:$port",
                "${e.javaClass.simpleName}: ${e.localizedMessage ?: "SSH authentication or connection failed"}"
            )
        } finally {
            disconnect()
        }
    }

    fun sendRawText(data: String) {
        val out = shellOutput ?: return
        try {
            out.write(data.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Exception) {
        }
    }

    fun resizePty(newCols: Int, newRows: Int, widthPx: Int, heightPx: Int) {
        try {
            shellChannel?.setPtySize(
                newCols.coerceAtLeast(20),
                newRows.coerceAtLeast(10),
                widthPx.coerceAtLeast(320),
                heightPx.coerceAtLeast(240)
            )
        } catch (_: Exception) {
        }
    }

    fun fetchSftpDirectory(path: String = ".") {
        val session = jschSession ?: return
        if (!session.isConnected) return
        try {
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(4000)
            val targetPath = if (path.isBlank()) sftp.pwd() else path
            val entries = sftp.ls(targetPath) as Vector<*>
            val parsed = mutableListOf<SftpRemoteFile>()
            for (item in entries) {
                val entry = item as? ChannelSftp.LsEntry ?: continue
                if (entry.filename == "." || entry.filename == "..") continue
                val attrs = entry.attrs
                parsed.add(
                    SftpRemoteFile(
                        name = entry.filename,
                        permissions = attrs.permissionsString,
                        sizeBytes = attrs.size,
                        isDirectory = attrs.isDir,
                        modifiedDate = attrs.mtimeString
                    )
                )
            }
            val resolvedPwd = sftp.pwd()
            sftp.disconnect()
            onSftpFilesUpdated(resolvedPwd, parsed.sortedWith(compareByDescending<SftpRemoteFile> { it.isDirectory }.thenBy { it.name }))
        } catch (e: Exception) {
            onTrace("[sftp] SFTP list failed: ${e.localizedMessage}")
        }
    }

    fun disconnect() {
        isRunning = false
        try {
            shellChannel?.disconnect()
        } catch (_: Exception) {
        }
        try {
            jschSession?.disconnect()
        } catch (_: Exception) {
        }
        shellChannel = null
        jschSession = null
        shellOutput = null
    }

    companion object {
        /**
         * Opens a real local SSH port-forwarding tunnel (`ssh -L 127.0.0.1:ephemeral:remoteHost:remotePort`)
         * and returns the connected JSch Session + local forwarded port.
         */
        fun openBastionPortForwardTunnel(
            bastionHost: String,
            bastionPort: Int,
            bastionUser: String,
            bastionPass: String,
            targetHost: String,
            targetPort: Int,
            onTrace: (String) -> Unit
        ): Pair<Session, Int> {
            val jsch = JSch()
            val session = jsch.getSession(bastionUser.ifBlank { "root" }, bastionHost, bastionPort)
            if (bastionPass.isNotEmpty()) {
                session.setPassword(bastionPass)
            }
            val config = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "password,keyboard-interactive,publickey")
            }
            session.setConfig(config)
            onTrace("[ssh-tunnel] Connecting to SSH bastion $bastionUser@$bastionHost:$bastionPort...")
            session.connect(6000)
            val assignedLocalPort = session.setPortForwardingL("127.0.0.1", 0, targetHost, targetPort)
            onTrace("[ssh-tunnel] Port forward active: 127.0.0.1:$assignedLocalPort -> $targetHost:$targetPort")
            return Pair(session, assignedLocalPort)
        }

        private fun stripAnsiControlCodes(raw: String): String {
            return raw
                .replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
                .replace(Regex("\u001B\\][^\u0007]*\u0007"), "")
                .replace("\r", "")
        }
    }
}
