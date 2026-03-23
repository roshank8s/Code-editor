package com.codeeditor.app.terminal

import android.os.Handler
import android.os.HandlerThread
import com.codeeditor.app.ssh.RemoteCommandRunner
import java.io.Closeable

/**
 * Manages SSH shell I/O with dedicated threads for reading and writing.
 * No coroutine overhead per keystroke - direct byte-level I/O like JuiceSSH.
 */
class TerminalSession(
    private val commandRunner: RemoteCommandRunner
) : Closeable {

    private var session: RemoteCommandRunner.InteractiveSession? = null
    private var readThread: Thread? = null
    private var errorThread: Thread? = null
    private var writeThread: HandlerThread? = null
    private var writeHandler: Handler? = null
    @Volatile private var running = false

    var onOutput: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    /** Start the shell session with dedicated I/O threads */
    fun start(emulator: TerminalEmulator, view: TerminalView) {
        val s = commandRunner.openInteractiveSession()
        session = s
        running = true

        // Dedicated writer thread - zero coroutine overhead per keystroke
        val wt = HandlerThread("ssh-writer").apply { start() }
        writeThread = wt
        writeHandler = Handler(wt.looper)

        // Reader thread - reads raw bytes, feeds to emulator
        readThread = Thread({
            val buffer = ByteArray(8192)
            try {
                while (running) {
                    val count = s.inputStream.read(buffer)
                    if (count == -1) break
                    emulator.processBytes(buffer, 0, count)
                    view.postInvalidate()
                }
            } catch (e: Exception) {
                if (running) onError?.invoke("Connection lost: ${e.message}")
            }
            if (running) onDisconnected?.invoke()
        }, "ssh-reader").apply {
            isDaemon = true
            start()
        }

        // Stderr reader
        errorThread = Thread({
            val buffer = ByteArray(4096)
            try {
                while (running) {
                    val count = s.errorStream.read(buffer)
                    if (count == -1) break
                    emulator.processBytes(buffer, 0, count)
                    view.postInvalidate()
                }
            } catch (_: Exception) {}
        }, "ssh-stderr").apply {
            isDaemon = true
            start()
        }
    }

    /** Write raw bytes to the SSH session - called from any thread, dispatched to writer */
    fun write(data: ByteArray) {
        writeHandler?.post {
            try {
                session?.write(data)
            } catch (_: Exception) {}
        }
    }

    /** Write text to the SSH session */
    fun write(text: String) = write(text.toByteArray())

    /** Send special key escape sequence */
    fun sendSpecialKey(key: SpecialKey) = write(key.sequence)

    /** Send Ctrl+key combo */
    fun sendCtrlKey(char: Char) {
        val code = char.uppercaseChar().code - 64
        if (code in 1..26) write(byteArrayOf(code.toByte()))
    }

    /** Resize the remote PTY */
    fun resizePTY(cols: Int, rows: Int) {
        writeHandler?.post {
            try { session?.resizePTY(cols, rows) } catch (_: Exception) {}
        }
    }

    override fun close() {
        running = false
        readThread?.interrupt()
        errorThread?.interrupt()
        writeThread?.quitSafely()
        try { session?.close() } catch (_: Exception) {}
    }

    enum class SpecialKey(val sequence: String) {
        ESC("\u001B"),
        TAB("\t"),
        BACKSPACE("\u007F"),
        ENTER("\r"),
        DELETE("\u001B[3~"),
        HOME("\u001B[H"),
        END("\u001B[F"),
        PAGE_UP("\u001B[5~"),
        PAGE_DOWN("\u001B[6~"),
        ARROW_UP("\u001B[A"),
        ARROW_DOWN("\u001B[B"),
        ARROW_RIGHT("\u001B[C"),
        ARROW_LEFT("\u001B[D"),
        CTRL_C("\u0003"),
        CTRL_D("\u0004"),
        CTRL_Z("\u001A"),
        CTRL_L("\u000C"),
    }
}
