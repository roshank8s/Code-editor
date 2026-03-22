package com.codeeditor.app.editor

import com.codeeditor.app.ssh.RemoteCommandRunner
import com.codeeditor.app.ssh.SSHManager

class CodeServerSetup(private val commandRunner: RemoteCommandRunner) {

    var onStatusUpdate: ((String) -> Unit)? = null

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

        // Kill any existing code-server processes
        onStatusUpdate?.invoke("Starting code-server...")
        commandRunner.run("pkill -f 'code-server.*--bind-addr' 2>/dev/null || true", 5)

        // Start code-server in background
        val startResult = commandRunner.runInBackground(
            "code-server --bind-addr 127.0.0.1:$port --auth none --disable-telemetry"
        )

        if (!startResult.isSuccess) {
            return SetupResult.Error("Failed to start code-server: ${startResult.stderr}")
        }

        val pid = startResult.stdout.trim()

        // Wait for code-server to be ready
        onStatusUpdate?.invoke("Waiting for code-server to start...")
        var attempts = 0
        while (attempts < 30) {
            val checkReady = commandRunner.run(
                "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$port/ 2>/dev/null || echo 'FAIL'",
                5
            )
            if (checkReady.stdout.trim().startsWith("200") || checkReady.stdout.trim().startsWith("302")) {
                return SetupResult.Success(port, pid)
            }
            kotlinx.coroutines.delay(1000)
            attempts++
        }

        return SetupResult.Error("code-server did not start within 30 seconds")
    }

    suspend fun stopCodeServer() {
        commandRunner.run("pkill -f 'code-server.*--bind-addr' 2>/dev/null || true", 5)
    }

    suspend fun isCodeServerRunning(port: Int = 8080): Boolean {
        val result = commandRunner.run(
            "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$port/ 2>/dev/null",
            5
        )
        return result.stdout.trim().startsWith("200") || result.stdout.trim().startsWith("302")
    }

    sealed class SetupResult {
        data class Success(val port: Int, val pid: String) : SetupResult()
        data class Error(val message: String) : SetupResult()
    }
}
