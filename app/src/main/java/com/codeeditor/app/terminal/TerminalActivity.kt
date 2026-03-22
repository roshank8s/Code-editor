package com.codeeditor.app.terminal

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.inputmethod.EditorInfo
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
    private var ctrlMode = false
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
        connectAndStartSession(connectionId)
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.terminalOutput.movementMethod = ScrollingMovementMethod()

        // Command input
        binding.terminalInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else false
        }

        binding.btnSend.setOnClickListener { sendCurrentInput() }

        // Extra keys
        binding.keyEsc.setOnClickListener {
            terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ESC)
        }
        binding.keyTab.setOnClickListener {
            terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.TAB)
        }
        binding.keyCtrl.setOnClickListener {
            ctrlMode = !ctrlMode
            binding.keyCtrl.alpha = if (ctrlMode) 1.0f else 0.5f
        }
        binding.keyAlt.setOnClickListener {
            // Alt key handled via escape sequence prefix
        }
        binding.keyPipe.setOnClickListener { appendToInput("|") }
        binding.keySlash.setOnClickListener { appendToInput("/") }
        binding.keyDash.setOnClickListener { appendToInput("-") }
        binding.keyUp.setOnClickListener {
            terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_UP)
        }
        binding.keyDown.setOnClickListener {
            terminalSession?.sendSpecialKey(TerminalSession.SpecialKey.ARROW_DOWN)
        }

        binding.keyCtrl.alpha = 0.5f
    }

    private fun sendCurrentInput() {
        val command = binding.terminalInput.text.toString()
        binding.terminalInput.text?.clear()

        if (ctrlMode && command.length == 1) {
            terminalSession?.sendCtrlKey(command[0])
            ctrlMode = false
            binding.keyCtrl.alpha = 0.5f
        } else {
            terminalSession?.sendCommand(command)
        }
    }

    private fun appendToInput(text: String) {
        binding.terminalInput.append(text)
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
                binding.toolbar.subtitle = connection.displayAddress

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
                        Toast.makeText(this@TerminalActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                    }
                }

                session.start()

            } catch (e: Exception) {
                appendOutput("\nConnection failed: ${e.message}\n")
                Toast.makeText(this@TerminalActivity, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
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
            binding.terminalScrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        terminalSession?.close()
        sshManager?.close()
        super.onDestroy()
    }
}
