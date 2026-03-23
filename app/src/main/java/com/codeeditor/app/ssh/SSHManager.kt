package com.codeeditor.app.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.Closeable
import java.io.IOException
import java.security.Security
import java.util.concurrent.TimeUnit

class SSHManager : Closeable {

    companion object {
        init {
            // Android ships a stripped BouncyCastle that lacks X25519.
            // Remove it and insert the full provider so SSHJ can find all algorithms.
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private var client: SSHClient? = null
    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected && client?.isConnected == true

    var keepAliveInterval: Int = 30
    var onDisconnected: (() -> Unit)? = null

    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        password: String? = null,
        keyProvider: KeyProvider? = null,
        passphrase: String? = null
    ) = withContext(Dispatchers.IO) {
        disconnect()

        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connection.keepAlive.keepAliveInterval = keepAliveInterval

        try {
            ssh.connect(host, port)

            when {
                keyProvider != null -> ssh.authPublickey(username, keyProvider)
                password != null -> ssh.authPassword(username, password)
                else -> throw IllegalArgumentException("No authentication method provided")
            }

            client = ssh
            _isConnected = true
        } catch (e: Exception) {
            ssh.close()
            throw e
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            client?.disconnect()
        } catch (_: Exception) {
        } finally {
            client = null
            _isConnected = false
        }
    }

    suspend fun executeCommand(command: String, timeoutSeconds: Long = 30): CommandResult =
        withContext(Dispatchers.IO) {
            val ssh = client ?: throw IOException("Not connected")
            if (!ssh.isConnected) {
                _isConnected = false
                throw IOException("SSH connection lost")
            }
            var session: Session? = null
            try {
                session = ssh.startSession()
                val cmd = session.exec(command)
                cmd.join(timeoutSeconds, TimeUnit.SECONDS)

                val stdout = IOUtils.readFully(cmd.inputStream).toString(Charsets.UTF_8)
                val stderr = IOUtils.readFully(cmd.errorStream).toString(Charsets.UTF_8)
                val exitCode = cmd.exitStatus ?: -1

                CommandResult(stdout, stderr, exitCode)
            } finally {
                session?.close()
            }
        }

    fun openSession(): Session {
        val ssh = client ?: throw IOException("Not connected")
        return ssh.startSession()
    }

    fun getClient(): SSHClient {
        return client ?: throw IOException("Not connected")
    }

    override fun close() {
        try {
            client?.disconnect()
        } catch (_: Exception) {
        }
        client = null
        _isConnected = false
    }

    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    ) {
        val isSuccess: Boolean get() = exitCode == 0
        val output: String get() = stdout.ifEmpty { stderr }
    }
}
