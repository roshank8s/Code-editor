package com.codeeditor.app.terminal

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.codeeditor.app.connections.ConnectionDatabase
import com.codeeditor.app.connections.ConnectionEntity
import com.codeeditor.app.databinding.ActivityTerminalBinding
import com.codeeditor.app.editor.FloatingKeyboardView
import com.codeeditor.app.ssh.RemoteCommandRunner
import com.codeeditor.app.ssh.SSHKeyManager
import com.codeeditor.app.ssh.SSHManager
import com.codeeditor.app.utils.Constants
import com.codeeditor.app.utils.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private var sshManager: SSHManager? = null
    private var terminalSession: TerminalSession? = null
    private var floatingKeyboard: FloatingKeyboardView? = null
    private var ctrlActive = false
    private var altActive = false
    private val outputBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val connectionId = intent.getLongExtra(Constants.EXTRA_CONNECTION_ID, -1)
        if (connectionId < 0) {
            finish()
            return
        }

        setupUI()
        setupFloatingKeyboard()
        connectAndStartSession(connectionId)
    }

    private fun setupUI() {
        binding.btnClose.setOnClickListener { finish() }
        binding.terminalOutput.movementMethod = ScrollingMovementMethod()

        // Tap anywhere on terminal to show system keyboard
        val showKeyboard = View.OnClickListener {
            binding.terminalInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.terminalInput, InputMethodManager.SHOW_IMPLICIT)
        }
        binding.terminalOutput.setOnClickListener(showKeyboard)
        binding.terminalScrollView.setOnClickListener(showKeyboard)

        // Auto-focus the hidden input so system keyboard works immediately
        binding.terminalInput.requestFocus()

        // Toggle floating keyboard
        binding.btnToggleKeyboard.setOnClickListener {
            floatingKeyboard?.let {
                if (it.isKeyboardVisible) it.hide() else it.show()
            }
        }

        // Direct input: each character typed is sent immediately to the terminal
        setupDirectInput()
    }

    private fun setupDirectInput() {
        var ignoreChange = false

        binding.terminalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (ignoreChange) return
                if (count > 0 && s != null) {
                    val newText = s.substring(start, start + count)
                    if (ctrlActive && newText.length == 1) {
                        sendToTerminal { terminalSession?.sendCtrlKey(newText[0]) }
                        ctrlActive = false
                        floatingKeyboard?.updateModifierState(
                            FloatingKeyboardView.KeyAction.Modifier.CTRL, false
                        )
                    } else {
                        sendToTerminal { terminalSession?.sendText(newText) }
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (ignoreChange || s.isNullOrEmpty()) return
                ignoreChange = true
                s.clear()
                ignoreChange = false
            }
        })

        // Handle special keys from system keyboard
        binding.terminalInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.BACKSPACE) }
                        true
                    }
                    KeyEvent.KEYCODE_ENTER -> {
                        sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ENTER) }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_UP) }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_DOWN) }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_LEFT) }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_RIGHT) }
                        true
                    }
                    KeyEvent.KEYCODE_TAB -> {
                        sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.TAB) }
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    /** Send terminal commands on IO thread to avoid crashes from writing on main thread */
    private fun sendToTerminal(block: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { block() }
    }

    private fun setupFloatingKeyboard() {
        val container = binding.floatingKeyboardContainer
        val keyboard = FloatingKeyboardView(this, container).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        floatingKeyboard = keyboard
        container.addView(keyboard)

        keyboard.onKeyPressed = { action -> handleKeyAction(action) }

        // Show keyboard by default
        keyboard.show()
    }

    private fun handleKeyAction(action: FloatingKeyboardView.KeyAction) {
        when (action) {
            is FloatingKeyboardView.KeyAction.ModifierToggle -> {
                when (action.modifier) {
                    FloatingKeyboardView.KeyAction.Modifier.CTRL -> {
                        ctrlActive = !ctrlActive
                        floatingKeyboard?.updateModifierState(action.modifier, ctrlActive)
                    }
                    FloatingKeyboardView.KeyAction.Modifier.ALT -> {
                        altActive = !altActive
                        floatingKeyboard?.updateModifierState(action.modifier, altActive)
                    }
                    FloatingKeyboardView.KeyAction.Modifier.SHIFT -> {
                        // Shift is handled internally by the keyboard for letter case
                    }
                }
            }
            is FloatingKeyboardView.KeyAction.SpecialKey -> {
                when (action.key) {
                    FloatingKeyboardView.KeyAction.Special.ESC ->
                        sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ESC) }
                    FloatingKeyboardView.KeyAction.Special.TAB ->
                        sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.TAB) }
                }
                resetModifiers()
            }
            is FloatingKeyboardView.KeyAction.Character -> {
                val char = action.char
                if (ctrlActive && char.length == 1) {
                    sendToTerminal { terminalSession?.sendCtrlKey(char[0]) }
                } else {
                    sendToTerminal { terminalSession?.sendText(char) }
                }
                resetModifiers()
            }
            is FloatingKeyboardView.KeyAction.Backspace -> {
                sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.BACKSPACE) }
                resetModifiers()
            }
            is FloatingKeyboardView.KeyAction.Enter -> {
                sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ENTER) }
                resetModifiers()
            }
            is FloatingKeyboardView.KeyAction.ShortcutKey -> {
                when (action.shortcut) {
                    FloatingKeyboardView.KeyAction.Shortcut.SAVE ->
                        sendToTerminal { terminalSession?.sendCtrlKey('S') }
                    FloatingKeyboardView.KeyAction.Shortcut.UNDO ->
                        sendToTerminal { terminalSession?.sendCtrlKey('Z') }
                    FloatingKeyboardView.KeyAction.Shortcut.REDO ->
                        sendToTerminal { terminalSession?.sendCtrlKey('Y') }
                    FloatingKeyboardView.KeyAction.Shortcut.COMMAND_PALETTE -> {}
                    FloatingKeyboardView.KeyAction.Shortcut.FIND ->
                        sendToTerminal { terminalSession?.sendCtrlKey('F') }
                    FloatingKeyboardView.KeyAction.Shortcut.CLOSE_TAB ->
                        sendToTerminal { terminalSession?.sendCtrlKey('C') }
                }
            }
            is FloatingKeyboardView.KeyAction.NavigationKey -> {
                val specialKey = when (action.nav) {
                    FloatingKeyboardView.KeyAction.Navigation.HOME ->
                        TerminalSession.SpecialKey.HOME
                    FloatingKeyboardView.KeyAction.Navigation.END ->
                        TerminalSession.SpecialKey.END
                    FloatingKeyboardView.KeyAction.Navigation.PAGE_UP ->
                        TerminalSession.SpecialKey.PAGE_UP
                    FloatingKeyboardView.KeyAction.Navigation.PAGE_DOWN ->
                        TerminalSession.SpecialKey.PAGE_DOWN
                    FloatingKeyboardView.KeyAction.Navigation.LEFT ->
                        TerminalSession.SpecialKey.ARROW_LEFT
                    FloatingKeyboardView.KeyAction.Navigation.RIGHT ->
                        TerminalSession.SpecialKey.ARROW_RIGHT
                    FloatingKeyboardView.KeyAction.Navigation.UP ->
                        TerminalSession.SpecialKey.ARROW_UP
                    FloatingKeyboardView.KeyAction.Navigation.DOWN ->
                        TerminalSession.SpecialKey.ARROW_DOWN
                    FloatingKeyboardView.KeyAction.Navigation.DELETE ->
                        TerminalSession.SpecialKey.DELETE
                }
                sendToTerminal { terminalSession?.sendSpecialKey(specialKey) }
                resetModifiers()
            }
        }
    }

    private fun resetModifiers() {
        if (ctrlActive) {
            ctrlActive = false
            floatingKeyboard?.updateModifierState(
                FloatingKeyboardView.KeyAction.Modifier.CTRL, false
            )
        }
        if (altActive) {
            altActive = false
            floatingKeyboard?.updateModifierState(
                FloatingKeyboardView.KeyAction.Modifier.ALT, false
            )
        }
    }

    private fun connectAndStartSession(connectionId: Long) {
        lifecycleScope.launch {
            try {
                appendOutput("Connecting...\n")

                val db = ConnectionDatabase.getInstance(this@TerminalActivity)
                val connection = db.connectionDao().getConnectionById(connectionId)
                if (connection == null) {
                    appendOutput("Error: Connection not found\n")
                    return@launch
                }

                val manager = SSHManager()
                sshManager = manager

                withContext(Dispatchers.IO) {
                    when (connection.authType) {
                        ConnectionEntity.AuthType.PASSWORD -> {
                            val password = connection.encryptedPassword?.let {
                                SecurityUtils.decrypt(it)
                            } ?: ""
                            manager.connect(
                                host = connection.host,
                                port = connection.port,
                                username = connection.username,
                                password = password
                            )
                        }
                        ConnectionEntity.AuthType.SSH_KEY -> {
                            val keyManager = SSHKeyManager(this@TerminalActivity)
                            val keyProvider = connection.privateKeyPath?.let { path ->
                                val passphrase = connection.keyPassphrase?.let {
                                    SecurityUtils.decrypt(it)
                                }
                                keyManager.getKeyProvider(path, passphrase)
                            }
                            manager.connect(
                                host = connection.host,
                                port = connection.port,
                                username = connection.username,
                                keyProvider = keyProvider
                            )
                        }
                    }
                }

                appendOutput("Connected to ${connection.host}\n")
                binding.toolbarSubtitle.text = connection.displayAddress

                val commandRunner = RemoteCommandRunner(manager)
                val session = TerminalSession(commandRunner, lifecycleScope)
                terminalSession = session

                session.onOutput = { text ->
                    runOnUiThread { appendOutput(text) }
                }
                session.onError = { error ->
                    runOnUiThread { appendOutput("\nError: $error\n") }
                }
                session.onDisconnected = {
                    runOnUiThread {
                        appendOutput("\nDisconnected.\n")
                        Toast.makeText(this@TerminalActivity, "Disconnected", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                session.start()

            } catch (e: Exception) {
                appendOutput("\nConnection failed: ${e.message}\n")
                Toast.makeText(
                    this@TerminalActivity,
                    "Connection failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun appendOutput(text: String) {
        outputBuffer.append(text)
        // Keep buffer reasonable
        if (outputBuffer.length > 100000) {
            outputBuffer.delete(0, outputBuffer.length - 50000)
        }
        binding.terminalOutput.text = outputBuffer.toString()
        binding.terminalScrollView.post {
            binding.terminalScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        terminalSession?.close()
        sshManager?.close()
        super.onDestroy()
    }
}
