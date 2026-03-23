package com.codeeditor.app.terminal

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
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
    private lateinit var emulator: TerminalEmulator
    private var ctrlActive = false
    private var altActive = false
    private var ctrlKeyView: TextView? = null
    private var altKeyView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val connectionId = intent.getLongExtra(Constants.EXTRA_CONNECTION_ID, -1)
        if (connectionId < 0) { finish(); return }

        emulator = TerminalEmulator()
        setupUI()
        setupShortcutBar()
        connectAndStartSession(connectionId)
    }

    private fun setupUI() {
        binding.btnClose.setOnClickListener { finish() }

        // Wire terminal view
        binding.terminalView.emulator = emulator
        binding.terminalView.onTerminalSizeChanged = { rows, cols ->
            emulator.resize(rows, cols)
            terminalSession?.resizePTY(cols, rows)
        }

        // Tap terminal to show system keyboard
        binding.terminalView.setOnClickListener {
            binding.terminalInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.terminalInput, InputMethodManager.SHOW_IMPLICIT)
        }

        binding.terminalInput.requestFocus()
        setupDirectInput()
    }

    // =========================================================================
    // Direct keyboard input - each keystroke sent immediately
    // =========================================================================

    private fun setupDirectInput() {
        var ignoreChange = false

        binding.terminalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (ignoreChange || count <= 0 || s == null) return
                val text = s.substring(start, start + count)
                if (ctrlActive && text.length == 1) {
                    terminalSession?.sendCtrlKey(text[0])
                    ctrlActive = false
                    ctrlKeyView?.alpha = 0.6f
                } else {
                    terminalSession?.write(text)
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (ignoreChange || s.isNullOrEmpty()) return
                ignoreChange = true
                s.clear()
                ignoreChange = false
            }
        })

        binding.terminalInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                val key = when (keyCode) {
                    KeyEvent.KEYCODE_DEL -> TerminalSession.SpecialKey.BACKSPACE
                    KeyEvent.KEYCODE_ENTER -> TerminalSession.SpecialKey.ENTER
                    KeyEvent.KEYCODE_DPAD_UP -> TerminalSession.SpecialKey.ARROW_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> TerminalSession.SpecialKey.ARROW_DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> TerminalSession.SpecialKey.ARROW_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalSession.SpecialKey.ARROW_RIGHT
                    KeyEvent.KEYCODE_TAB -> TerminalSession.SpecialKey.TAB
                    else -> null
                }
                if (key != null) {
                    terminalSession?.sendSpecialKey(key)
                    true
                } else false
            } else false
        }
    }

    // =========================================================================
    // Shortcut bar (above system keyboard like Termux)
    // =========================================================================

    private fun setupShortcutBar() {
        val container = binding.shortcutKeysContainer
        val dp = resources.displayMetrics.density

        data class SK(val label: String, val isMod: Boolean = false, val action: () -> Unit)

        val keys = listOf(
            SK("ESC") { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ESC) },
            SK("TAB") { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.TAB) },
            SK("CTRL", true) { ctrlActive = !ctrlActive; ctrlKeyView?.alpha = if (ctrlActive) 1f else 0.6f },
            SK("ALT", true) { altActive = !altActive; altKeyView?.alpha = if (altActive) 1f else 0.6f },
            SK("|") { sendChar("|") }, SK("/") { sendChar("/") }, SK("-") { sendChar("-") },
            SK("~") { sendChar("~") }, SK("_") { sendChar("_") }, SK(":") { sendChar(":") },
            SK(";") { sendChar(";") }, SK("{") { sendChar("{") }, SK("}") { sendChar("}") },
            SK("[") { sendChar("[") }, SK("]") { sendChar("]") }, SK("'") { sendChar("'") },
            SK("\"") { sendChar("\"") }, SK("\\") { sendChar("\\") }, SK("&") { sendChar("&") },
            SK("<") { sendChar("<") }, SK(">") { sendChar(">") }, SK("$") { sendChar("$") },
            SK("#") { sendChar("#") }, SK("=") { sendChar("=") }, SK("*") { sendChar("*") },
            SK("`") { sendChar("`") },
            SK("\u2191") { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_UP) },
            SK("\u2193") { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_DOWN) },
            SK("\u2190") { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_LEFT) },
            SK("\u2192") { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_RIGHT) },
            SK("HOME") { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.HOME) },
            SK("END") { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.END) },
            SK("PGUP") { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.PAGE_UP) },
            SK("PGDN") { terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.PAGE_DOWN) },
        )

        for (key in keys) {
            val btn = TextView(this).apply {
                text = key.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_key)
                isClickable = true; isFocusable = false; isFocusableInTouchMode = false
                minWidth = ((if (key.label.length > 2) 42 else 32) * dp).toInt()
                val m = (2 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (30 * dp).toInt()
                ).apply { setMargins(m, m, m, m) }
                setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
                if (key.isMod) alpha = 0.6f
                setOnClickListener { key.action() }
            }
            if (key.label == "CTRL") ctrlKeyView = btn
            if (key.label == "ALT") altKeyView = btn
            container.addView(btn)
        }
    }

    private fun sendChar(c: String) {
        if (ctrlActive && c.length == 1) {
            terminalSession?.sendCtrlKey(c[0])
            ctrlActive = false; ctrlKeyView?.alpha = 0.6f
        } else {
            terminalSession?.write(c)
        }
        if (altActive) { altActive = false; altKeyView?.alpha = 0.6f }
    }

    // =========================================================================
    // SSH Connection
    // =========================================================================

    private fun connectAndStartSession(connectionId: Long) {
        lifecycleScope.launch {
            try {
                val db = ConnectionDatabase.getInstance(this@TerminalActivity)
                val connection = db.connectionDao().getConnectionById(connectionId)
                if (connection == null) {
                    Toast.makeText(this@TerminalActivity, "Connection not found", Toast.LENGTH_SHORT).show()
                    finish(); return@launch
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
                                host = connection.host, port = connection.port,
                                username = connection.username, password = password
                            )
                        }
                        ConnectionEntity.AuthType.SSH_KEY -> {
                            val keyManager = SSHKeyManager(this@TerminalActivity)
                            val keyProvider = connection.privateKeyPath?.let { path ->
                                val passphrase = connection.keyPassphrase?.let { SecurityUtils.decrypt(it) }
                                keyManager.getKeyProvider(path, passphrase)
                            }
                            manager.connect(
                                host = connection.host, port = connection.port,
                                username = connection.username, keyProvider = keyProvider
                            )
                        }
                    }
                }

                binding.toolbarSubtitle.text = connection.displayAddress

                // Compute terminal size from view
                val (rows, cols) = binding.terminalView.computeSize()
                emulator.resize(rows, cols)

                val commandRunner = RemoteCommandRunner(manager)
                val session = TerminalSession(commandRunner)
                terminalSession = session

                session.onError = { error ->
                    runOnUiThread {
                        Toast.makeText(this@TerminalActivity, error, Toast.LENGTH_SHORT).show()
                    }
                }
                session.onDisconnected = {
                    runOnUiThread {
                        Toast.makeText(this@TerminalActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                    }
                }

                // Start session on IO thread - reader/writer threads start inside
                withContext(Dispatchers.IO) {
                    session.start(emulator, binding.terminalView)
                }

                // Send initial PTY size
                session.resizePTY(cols, rows)

            } catch (e: Exception) {
                Toast.makeText(
                    this@TerminalActivity,
                    "Connection failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        terminalSession?.close()
        sshManager?.close()
        super.onDestroy()
    }
}
