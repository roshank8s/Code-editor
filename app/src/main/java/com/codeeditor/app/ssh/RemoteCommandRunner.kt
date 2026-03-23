package com.codeeditor.app.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream

class RemoteCommandRunner(private val sshManager: SSHManager) {

    suspend fun run(command: String, timeoutSeconds: Long = 30): SSHManager.CommandResult {
        return sshManager.executeCommand(command, timeoutSeconds)
    }

    suspend fun runInBackground(command: String): SSHManager.CommandResult {
        return sshManager.executeCommand("nohup $command > /dev/null 2>&1 & echo $!", 10)
    }

    suspend fun isProcessRunning(pid: String): Boolean = withContext(Dispatchers.IO) {
        val result = sshManager.executeCommand("kill -0 $pid 2>/dev/null && echo yes || echo no", 5)
        result.stdout.trim() == "yes"
    }

    suspend fun killProcess(pid: String) = withContext(Dispatchers.IO) {
        sshManager.executeCommand("kill $pid 2>/dev/null", 5)
    }

    fun openInteractiveSession(): InteractiveSession {
        if (!sshManager.isConnected) {
            throw IOException("SSH connection is not active")
        }

        var lastException: Exception? = null
        repeat(3) { attempt ->
            try {
                val session = sshManager.openSession()
                try {
                    session.allocateDefaultPTY()
                    val shell = session.startShell()
                    return InteractiveSession(
                        session = session,
                        inputStream = BufferedReader(InputStreamReader(shell.inputStream)),
                        errorStream = BufferedReader(InputStreamReader(shell.errorStream)),
                        outputStream = shell.outputStream
                    )
                } catch (e: Exception) {
                    session.close()
                    throw e
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < 2) {
                    Thread.sleep((attempt + 1) * 1000L)
                }
            }
        }
        throw lastException ?: IOException("Failed to open interactive session")
    }

    class InteractiveSession(
        private val session: Session,
        val inputStream: BufferedReader,
        val errorStream: BufferedReader,
        val outputStream: OutputStream
    ) : AutoCloseable {

        fun sendCommand(command: String) {
            outputStream.write("$command\n".toByteArray())
            outputStream.flush()
        }

        fun sendSpecialKey(key: String) {
            outputStream.write(key.toByteArray())
            outputStream.flush()
        }

        override fun close() {
            try {
                session.close()
            } catch (_: Exception) {
            }
        }
    }
}
