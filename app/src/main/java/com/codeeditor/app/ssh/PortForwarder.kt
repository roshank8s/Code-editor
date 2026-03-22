package com.codeeditor.app.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket

class PortForwarder(private val sshClient: SSHClient) : Closeable {

    private var serverSocket: ServerSocket? = null
    private var forwarder: LocalPortForwarder? = null
    private var forwardJob: Job? = null
    private var _localPort: Int = 0

    val localPort: Int get() = _localPort
    val isActive: Boolean get() = serverSocket != null && !serverSocket!!.isClosed

    suspend fun startForwarding(
        remoteHost: String = "127.0.0.1",
        remotePort: Int,
        localPort: Int = 0
    ): Int {
        withContext(Dispatchers.IO) {
            stopForwarding()

            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", localPort))
            _localPort = ss.localPort
            serverSocket = ss

            val params = net.schmizz.sshj.connection.channel.direct.Parameters(
                "127.0.0.1",
                _localPort,
                remoteHost,
                remotePort
            )

            forwarder = sshClient.newLocalPortForwarder(params, ss)
        }

        forwardJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                forwarder?.listen()
            } catch (e: Exception) {
                if (isActive) {
                    throw e
                }
            }
        }

        return _localPort
    }

    fun stopForwarding() {
        forwardJob?.cancel()
        forwardJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        forwarder = null
        _localPort = 0
    }

    override fun close() {
        stopForwarding()
    }
}
