package com.codeeditor.app.terminal

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.codeeditor.app.R
import com.codeeditor.app.connections.ConnectionDatabase
import com.codeeditor.app.connections.ConnectionEntity
import com.codeeditor.app.databinding.ActivityTerminalBinding
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
    private var ctrlActive = false
    private var altActive = false
    private val outputBuffer = StringBuilder()

    // Shortcut key views for modifier state highlighting
    private var ctrlKeyView: TextView? = null
    private var altKeyView: TextView? = null

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
        setupShortcutBar()
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

        // Direct input: each character typed is sent immediately to the terminal
        setupDirectInput()
    }

    // =========================================================================
    // Shortcut Bar (above system keyboard, like Termux)
    // =========================================================================

    private fun setupShortcutBar() {
        val container = binding.shortcutKeysContainer
        val dp = resources.displayMetrics.density

        // Define shortcut keys: label -> action
        data class ShortcutKey(
            val label: String,
            val isModifier: Boolean = false,
            val action: () -> Unit
        )

        val keys = listOf(
            ShortcutKey("ESC") { sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ESC) } },
            ShortcutKey("TAB") { sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.TAB) } },
            ShortcutKey("CTRL", isModifier = true) {
                ctrlActive = !ctrlActive
                ctrlKeyView?.alpha = if (ctrlActive) 1.0f else 0.6f
            },
            ShortcutKey("ALT", isModifier = true) {
                altActive = !altActive
                altKeyView?.alpha = if (altActive) 1.0f else 0.6f
            },
            ShortcutKey("|") { sendChar("|") },
            ShortcutKey("/") { sendChar("/") },
            ShortcutKey("-") { sendChar("-") },
            ShortcutKey("~") { sendChar("~") },
            ShortcutKey("_") { sendChar("_") },
            ShortcutKey(":") { sendChar(":") },
            ShortcutKey(";") { sendChar(";") },
            ShortcutKey("{") { sendChar("{") },
            ShortcutKey("}") { sendChar("}") },
            ShortcutKey("[") { sendChar("[") },
            ShortcutKey("]") { sendChar("]") },
            ShortcutKey("'") { sendChar("'") },
            ShortcutKey("\"") { sendChar("\"") },
            ShortcutKey("\\") { sendChar("\\") },
            ShortcutKey("&") { sendChar("&") },
            ShortcutKey("<") { sendChar("<") },
            ShortcutKey(">") { sendChar(">") },
            ShortcutKey("$") { sendChar("$") },
            ShortcutKey("#") { sendChar("#") },
            ShortcutKey("=") { sendChar("=") },
            ShortcutKey("*") { sendChar("*") },
            ShortcutKey("`") { sendChar("`") },
            ShortcutKey("\u2191") { sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_UP) } },
            ShortcutKey("\u2193") { sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_DOWN) } },
            ShortcutKey("\u2190") { sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_LEFT) } },
            ShortcutKey("\u2192") { sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_RIGHT) } },
            ShortcutKey("HOME") { sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.HOME) } },
            ShortcutKey("END") { sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.END) } },
            ShortcutKey("PGUP") { sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.PAGE_UP) } },
            ShortcutKey("PGDN") { sendToTerminal { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.PAGE_DOWN) } },
        )

        for (key in keys) {
            val btn = TextView(this).apply {
                text = key.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_key)
                isClickable = true
                isFocusable = false
                isFocusableInTouchMode = false
                minWidth = (if (key.label.length > 2) 42 else 32).let { (it * dp).toInt() }
                val margin = (2 * dp).toInt()
                val height = (30 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    height
                ).apply {
                    setMargins(margin, margin, margin, margin)
                }
                setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
                if (key.isModifier) alpha = 0.6f
                setOnClickListener { key.action() }
            }

            // Track modifier views for state highlighting
            if (key.label == "CTRL") ctrlKeyView = btn
            if (key.label == "ALT") altKeyView = btn

            container.addView(btn)
        }
    }

    private fun sendChar(c: String) {
        if (ctrlActive && c.length == 1) {
            sendToTerminal { terminalSession?.sendCtrlKey(c[0]) }
            ctrlActive = false
            ctrlKeyView?.alpha = 0.6f
        } else {
            sendToTerminal { terminalSession?.sendText(c) }
        }
        if (altActive) {
            altActive = false
            altKeyView?.alpha = 0.6f
        }
    }

    // =========================================================================
    // Direct keyboard input
    // =========================================================================

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
                        runOnUiThread { ctrlKeyView?.alpha = 0.6f }
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

    /** Send terminal commands on IO thread to avoid crashes */
    private fun sendToTerminal(block: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { block() }
    }

    // =========================================================================
    // SSH Connection
    // =========================================================================

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
