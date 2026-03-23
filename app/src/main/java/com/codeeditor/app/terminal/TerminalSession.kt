package com.codeeditor.app.terminal

import com.codeeditor.app.ssh.RemoteCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

class TerminalSession(
    private val commandRunner: RemoteCommandRunner,
    private val scope: CoroutineScope
) : Closeable {

    private var interactiveSession: RemoteCommandRunner.InteractiveSession? = null
    private var readJob: Job? = null
    private var errorReadJob: Job? = null

    var onOutput: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun start() {
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    commandRunner.openInteractiveSession()
                }
                interactiveSession = session

                // Read stdout
                readJob = launch(Dispatchers.IO) {
                    try {
                        val buffer = CharArray(4096)
                        while (isActive) {
                            val count = session.inputStream.read(buffer)
                            if (count == -1) break
                            val text = String(buffer, 0, count)
                            onOutput?.invoke(text)
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            onError?.invoke("Connection lost: ${e.message}")
                            onDisconnected?.invoke()
                        }
                    }
                }

                // Read stderr
                errorReadJob = launch(Dispatchers.IO) {
                    try {
                        val buffer = CharArray(4096)
                        while (isActive) {
                            val count = session.errorStream.read(buffer)
                            if (count == -1) break
                            val text = String(buffer, 0, count)
                            onOutput?.invoke(text)
                        }
                    } catch (_: Exception) {
                        // Ignore stderr read errors
                    }
                }
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                onError?.invoke("Failed to start session: $detail")
            }
        }
    }

    fun sendCommand(command: String) {
        interactiveSession?.sendCommand(command)
    }

    /** Send raw text directly to the terminal without appending newline */
    fun sendText(text: String) {
        interactiveSession?.sendSpecialKey(text)
    }

    fun sendSpecialKey(key: SpecialKey) {
        val sequence = when (key) {
            SpecialKey.ESC -> "\u001B"
            SpecialKey.TAB -> "\t"
            SpecialKey.BACKSPACE -> "\u007F"
            SpecialKey.ENTER -> "\r"
            SpecialKey.DELETE -> "\u001B[3~"
            SpecialKey.HOME -> "\u001B[H"
            SpecialKey.END -> "\u001B[F"
            SpecialKey.PAGE_UP -> "\u001B[5~"
            SpecialKey.PAGE_DOWN -> "\u001B[6~"
            SpecialKey.CTRL_C -> "\u0003"
            SpecialKey.CTRL_D -> "\u0004"
            SpecialKey.CTRL_Z -> "\u001A"
            SpecialKey.CTRL_L -> "\u000C"
            SpecialKey.ARROW_UP -> "\u001B[A"
            SpecialKey.ARROW_DOWN -> "\u001B[B"
            SpecialKey.ARROW_RIGHT -> "\u001B[C"
            SpecialKey.ARROW_LEFT -> "\u001B[D"
        }
        interactiveSession?.sendSpecialKey(sequence)
    }

    fun sendCtrlKey(char: Char) {
        val code = char.uppercaseChar().code - 64
        if (code in 1..26) {
            interactiveSession?.sendSpecialKey(code.toChar().toString())
        }
    }

    override fun close() {
        readJob?.cancel()
        errorReadJob?.cancel()
        interactiveSession?.close()
    }

    enum class SpecialKey {
        ESC, TAB, BACKSPACE, ENTER, DELETE,
        HOME, END, PAGE_UP, PAGE_DOWN,
        CTRL_C, CTRL_D, CTRL_Z, CTRL_L,
        ARROW_UP, ARROW_DOWN, ARROW_RIGHT, ARROW_LEFT
    }
}
