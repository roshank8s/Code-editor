package com.codeeditor.app.editor

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.codeeditor.app.R
import com.codeeditor.app.connections.ConnectionDatabase
import com.codeeditor.app.connections.ConnectionEntity
import com.codeeditor.app.databinding.ActivityEditorBinding
import com.codeeditor.app.ssh.SSHConnectionService
import com.codeeditor.app.ssh.SSHKeyManager
import com.codeeditor.app.utils.Constants
import com.codeeditor.app.utils.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private var sshService: SSHConnectionService? = null
    private var serviceBound = false
    private var localPort = 0
    private var connectionId: Long = -1

    private var ctrlPressed = false
    private var altPressed = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as SSHConnectionService.LocalBinder).getService()
            sshService = service
            serviceBound = true
            startConnection()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sshService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectionId = intent.getLongExtra(Constants.EXTRA_CONNECTION_ID, -1)
        if (connectionId < 0) {
            finish()
            return
        }

        setupWebView()
        setupExtraKeys()
        setupStatusBar()

        // Start foreground service and bind
        val serviceIntent = Intent(this, SSHConnectionService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
            textZoom = 100
        }

        binding.webView.webViewClient = EditorWebViewClient(
            onPageStarted = {
                runOnUiThread { updateStatus("Loading editor...", R.color.status_connecting) }
            },
            onPageFinished = {
                runOnUiThread {
                    binding.loadingOverlay.visibility = View.GONE
                    updateStatus("Connected", R.color.status_connected)
                }
            },
            onError = { error ->
                runOnUiThread {
                    updateStatus("Error: $error", R.color.status_disconnected)
                }
            }
        )

        WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun setupExtraKeys() {
        binding.keyEsc.setOnClickListener { sendKey(27) } // ESC
        binding.keyTab.setOnClickListener { sendKey(9) } // Tab

        binding.keyCtrl.setOnClickListener {
            ctrlPressed = !ctrlPressed
            binding.keyCtrl.alpha = if (ctrlPressed) 1.0f else 0.5f
        }

        binding.keyAlt.setOnClickListener {
            altPressed = !altPressed
            binding.keyAlt.alpha = if (altPressed) 1.0f else 0.5f
        }

        binding.keyUp.setOnClickListener { sendArrowKey(KeyEvent.KEYCODE_DPAD_UP) }
        binding.keyDown.setOnClickListener { sendArrowKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        binding.keyLeft.setOnClickListener { sendArrowKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        binding.keyRight.setOnClickListener { sendArrowKey(KeyEvent.KEYCODE_DPAD_RIGHT) }

        // Initial state
        binding.keyCtrl.alpha = 0.5f
        binding.keyAlt.alpha = 0.5f
    }

    private fun sendKey(keyCode: Int) {
        val js = "document.activeElement.dispatchEvent(new KeyboardEvent('keydown', {keyCode: $keyCode, ctrlKey: $ctrlPressed, altKey: $altPressed}));"
        binding.webView.evaluateJavascript(js, null)
        resetModifiers()
    }

    private fun sendArrowKey(keyCode: Int) {
        val keyDown = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0,
            (if (ctrlPressed) KeyEvent.META_CTRL_ON else 0) or
            (if (altPressed) KeyEvent.META_ALT_ON else 0))
        val keyUp = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0)
        binding.webView.dispatchKeyEvent(keyDown)
        binding.webView.dispatchKeyEvent(keyUp)
        resetModifiers()
    }

    private fun resetModifiers() {
        ctrlPressed = false
        altPressed = false
        binding.keyCtrl.alpha = 0.5f
        binding.keyAlt.alpha = 0.5f
    }

    private fun setupStatusBar() {
        binding.btnDisconnect.setOnClickListener {
            disconnect()
            finish()
        }
    }

    private fun startConnection() {
        lifecycleScope.launch {
            try {
                updateStatus("Connecting...", R.color.status_connecting)

                val db = ConnectionDatabase.getInstance(this@EditorActivity)
                val connection = db.connectionDao().getConnectionById(connectionId)
                if (connection == null) {
                    updateStatus("Connection not found", R.color.status_disconnected)
                    return@launch
                }

                val service = sshService ?: return@launch
                val sshManager = service.initSSH()

                // Connect SSH
                withContext(Dispatchers.IO) {
                    when (connection.authType) {
                        ConnectionEntity.AuthType.PASSWORD -> {
                            val password = connection.encryptedPassword?.let {
                                SecurityUtils.decrypt(it)
                            } ?: ""
                            sshManager.connect(
                                host = connection.host,
                                port = connection.port,
                                username = connection.username,
                                password = password
                            )
                        }
                        ConnectionEntity.AuthType.SSH_KEY -> {
                            val keyManager = SSHKeyManager(this@EditorActivity)
                            val keyProvider = connection.privateKeyPath?.let { path ->
                                val passphrase = connection.keyPassphrase?.let {
                                    SecurityUtils.decrypt(it)
                                }
                                keyManager.getKeyProvider(path, passphrase)
                            }
                            sshManager.connect(
                                host = connection.host,
                                port = connection.port,
                                username = connection.username,
                                keyProvider = keyProvider
                            )
                        }
                    }
                }

                updateStatus("SSH connected. Setting up code-server...", R.color.status_connecting)
                service.updateNotification("Connected to ${connection.host}")

                // Setup code-server
                val commandRunner = service.commandRunner ?: return@launch
                val codeServerSetup = CodeServerSetup(commandRunner)
                codeServerSetup.onStatusUpdate = { status ->
                    runOnUiThread {
                        binding.loadingText.text = status
                    }
                }

                val codeServerPort = connection.codeServerPort
                val result = codeServerSetup.setupAndStart(codeServerPort)

                when (result) {
                    is CodeServerSetup.SetupResult.Success -> {
                        // Setup port forwarding
                        updateStatus("Setting up tunnel...", R.color.status_connecting)
                        val forwarder = service.initPortForwarder() ?: run {
                            updateStatus("Failed to create port forwarder", R.color.status_disconnected)
                            return@launch
                        }

                        localPort = forwarder.startForwarding(
                            remotePort = result.port
                        )

                        // Load code-server in WebView
                        updateStatus("Loading VS Code...", R.color.status_connecting)
                        binding.loadingText.text = getString(R.string.loading_editor)
                        binding.webView.loadUrl("http://127.0.0.1:$localPort")
                    }
                    is CodeServerSetup.SetupResult.Error -> {
                        updateStatus("Error: ${result.message}", R.color.status_disconnected)
                        binding.loadingText.text = result.message
                    }
                }
            } catch (e: Exception) {
                updateStatus("Connection failed: ${e.message}", R.color.status_disconnected)
                binding.loadingText.text = "Error: ${e.message}"
                Toast.makeText(this@EditorActivity, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateStatus(text: String, colorRes: Int) {
        binding.statusText.text = text
        binding.statusDot.setBackgroundColor(getColor(colorRes))
    }

    private fun disconnect() {
        sshService?.cleanup()
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
