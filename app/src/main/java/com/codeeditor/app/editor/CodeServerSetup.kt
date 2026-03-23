package com.codeeditor.app.editor

import com.codeeditor.app.ssh.RemoteCommandRunner
import com.codeeditor.app.ssh.SSHManager

class CodeServerSetup(private val commandRunner: RemoteCommandRunner) {

    var onStatusUpdate: ((String) -> Unit)? = null

    companion object {
        private val FALLBACK_PORTS = listOf(8080, 8443, 9090, 9443, 3000)
    }

    suspend fun setupAndStart(port: Int = 8080): SetupResult {
        onStatusUpdate?.invoke("Checking for code-server...")

        val checkResult = commandRunner.run("which code-server 2>/dev/null || echo 'NOT_FOUND'")
        val isInstalled = checkResult.isSuccess && !checkResult.stdout.contains("NOT_FOUND")

        if (!isInstalled) {
            onStatusUpdate?.invoke("Installing code-server (this may take a minute)...")
            val installResult = commandRunner.run(
                "curl -fsSL https://code-server.dev/install.sh | sh",
                timeoutSeconds = 300
            )
            if (!installResult.isSuccess) {
                return SetupResult.Error("Failed to install code-server: ${installResult.stderr}")
            }
        }

        // Kill any existing code-server processes we started
        onStatusUpdate?.invoke("Starting code-server...")
        commandRunner.run("pkill -f 'code-server.*--bind-addr' 2>/dev/null || true", 5)

        // Check if the requested port is already occupied by another service
        val actualPort = findAvailablePort(port)
        if (actualPort != port) {
            onStatusUpdate?.invoke("Port $port in use, using port $actualPort...")
        }

        // Start code-server in background
        val startResult = commandRunner.runInBackground(
            "code-server --bind-addr 127.0.0.1:$actualPort --auth none --disable-telemetry"
        )

        if (!startResult.isSuccess) {
            return SetupResult.Error("Failed to start code-server: ${startResult.stderr}")
        }

        val pid = startResult.stdout.trim()

        // Wait for code-server to be ready by checking for its specific response
        onStatusUpdate?.invoke("Waiting for code-server to start...")
        var attempts = 0
        while (attempts < 60) {
            if (isCodeServerOnPort(actualPort)) {
                return SetupResult.Success(actualPort, pid)
            }

            // Every 10 seconds, verify the process is still alive
            if (attempts > 0 && attempts % 10 == 0) {
                val alive = commandRunner.run(
                    "kill -0 $pid 2>/dev/null && echo 'ALIVE' || echo 'DEAD'", 5
                )
                if (alive.stdout.trim() == "DEAD") {
                    val logs = commandRunner.run(
                        "journalctl -u code-server --no-pager -n 20 2>/dev/null || echo 'No logs available'", 5
                    )
                    return SetupResult.Error(
                        "code-server process exited unexpectedly.\n${logs.stdout.take(500)}"
                    )
                }
                onStatusUpdate?.invoke("Still waiting for code-server (${attempts}s)...")
            }

            kotlinx.coroutines.delay(1000)
            attempts++
        }

        return SetupResult.Error("code-server did not start within 60 seconds")
    }

    /**
     * Find an available port, checking if something else is already listening.
     */
    private suspend fun findAvailablePort(preferredPort: Int): Int {
        // Build candidate list with preferred port first
        val candidates = (listOf(preferredPort) + FALLBACK_PORTS).distinct()

        for (candidate in candidates) {
            val check = commandRunner.run(
                "ss -tlnp 2>/dev/null | grep -q ':$candidate ' && echo 'IN_USE' || echo 'FREE'",
                5
            )
            if (check.stdout.trim() == "FREE") {
                return candidate
            }
        }

        // All candidates taken — let the OS pick a free port
        val randomPort = commandRunner.run(
            "python3 -c \"import socket; s=socket.socket(); s.bind(('',0)); print(s.getsockname()[1]); s.close()\" 2>/dev/null || shuf -i 10000-60000 -n 1",
            5
        )
        return randomPort.stdout.trim().toIntOrNull() ?: preferredPort
    }

    /**
     * Verify that code-server is actually responding on the given port
     * by checking for its characteristic response headers/body.
     */
    private suspend fun isCodeServerOnPort(port: Int): Boolean {
        // Try curl first, fall back to wget, then raw connection check
        val result = commandRunner.run(
            "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$port/ 2>/dev/null || " +
                "wget -q -O /dev/null --server-response http://127.0.0.1:$port/ 2>&1 | head -1 || " +
                "echo 'FAIL'",
            5
        )
        val output = result.stdout.trim()
        // HTTP 200 or 302 means code-server is responding
        return output == "200" || output == "302" || output.contains("200 OK") || output.contains("302")
    }

    suspend fun stopCodeServer() {
        commandRunner.run("pkill -f 'code-server.*--bind-addr' 2>/dev/null || true", 5)
    }

    suspend fun isCodeServerRunning(port: Int = 8080): Boolean {
        return isCodeServerOnPort(port)
    }

    sealed class SetupResult {
        data class Success(val port: Int, val pid: String) : SetupResult()
        data class Error(val message: String) : SetupResult()
    }
}
