package com.codeeditor.app.editor

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private var sshService: SSHConnectionService? = null
    private var serviceBound = false
    private var localPort = 0
    private var connectionId: Long = -1

    // Modifiers state
    private var ctrlPressed = false
    private var altPressed = false
    private var shiftPressed = false

    // Floating keyboard
    private var floatingKeyboard: FloatingKeyboardView? = null

    // FAB state
    private var fabExpanded = false
    private val fabHandler = Handler(Looper.getMainLooper())
    private val fabFadeRunnable = Runnable {
        if (!fabExpanded) binding.fabMain.alpha = 0.4f
    }

    // Fullscreen state
    private var isFullscreen = false
    private var insetsController: WindowInsetsControllerCompat? = null

    // Connection info
    private var connectionStartTime = 0L
    private var connectedHost = ""
    private var connectedUser = ""
    private var connectedPort = 0

    // Auto-reconnect
    private var reconnectAttempts = 0
    private var isReconnecting = false
    private var currentConnection: ConnectionEntity? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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

        // Setup window insets controller for fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, true)
        insetsController = WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setupWebView()
        setupStatusBar()
        setupFloatingKeyboard()
        setupFAB()
        setupGestures()
        registerNetworkCallback()

        // Start foreground service and bind
        val serviceIntent = Intent(this, SSHConnectionService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Schedule FAB fade
        scheduleFabFade()
    }

    // =========================================================================
    // WebView Setup
    // =========================================================================

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
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
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

    // =========================================================================
    // Status Bar
    // =========================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupStatusBar() {
        binding.btnDisconnect.setOnClickListener {
            disconnect()
            finish()
        }

        binding.btnToggleKeyboard.setOnClickListener {
            toggleFloatingKeyboard()
        }

        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen()
        }

        // Double-tap status bar for fullscreen
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleFullscreen()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                showConnectionInfo()
            }
        })

        binding.statusBar.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    // =========================================================================
    // Floating Keyboard
    // =========================================================================

    private fun setupFloatingKeyboard() {
        val container = binding.floatingKeyboardContainer
        floatingKeyboard = FloatingKeyboardView(this, container).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        floatingKeyboard?.onKeyPressed = { action ->
            handleKeyAction(action)
        }

        container.addView(floatingKeyboard)

        // Restore visibility preference
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val kbVisible = prefs.getBoolean(Constants.PREF_KB_VISIBLE, true)
        if (kbVisible) floatingKeyboard?.show() else floatingKeyboard?.hide()
    }

    private fun toggleFloatingKeyboard() {
        val kb = floatingKeyboard ?: return
        if (kb.isKeyboardVisible) {
            kb.hide()
        } else {
            kb.show()
        }
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(Constants.PREF_KB_VISIBLE, kb.isKeyboardVisible).apply()
    }

    private fun handleKeyAction(action: FloatingKeyboardView.KeyAction) {
        when (action) {
            is FloatingKeyboardView.KeyAction.ModifierToggle -> {
                when (action.modifier) {
                    FloatingKeyboardView.KeyAction.Modifier.CTRL -> {
                        ctrlPressed = !ctrlPressed
                        floatingKeyboard?.updateModifierState(action.modifier, ctrlPressed)
                    }
                    FloatingKeyboardView.KeyAction.Modifier.ALT -> {
                        altPressed = !altPressed
                        floatingKeyboard?.updateModifierState(action.modifier, altPressed)
                    }
                    FloatingKeyboardView.KeyAction.Modifier.SHIFT -> {
                        shiftPressed = !shiftPressed
                        floatingKeyboard?.updateModifierState(action.modifier, shiftPressed)
                    }
                }
            }
            is FloatingKeyboardView.KeyAction.SpecialKey -> {
                when (action.key) {
                    FloatingKeyboardView.KeyAction.Special.ESC -> sendJSKey(27)
                    FloatingKeyboardView.KeyAction.Special.TAB -> sendJSKey(9)
                }
                resetModifiers()
            }
            is FloatingKeyboardView.KeyAction.Character -> {
                sendJSChar(action.char)
                resetModifiers()
            }
            is FloatingKeyboardView.KeyAction.ShortcutKey -> {
                when (action.shortcut) {
                    FloatingKeyboardView.KeyAction.Shortcut.SAVE ->
                        sendJSKey(83, ctrl = true) // Ctrl+S
                    FloatingKeyboardView.KeyAction.Shortcut.UNDO ->
                        sendJSKey(90, ctrl = true) // Ctrl+Z
                    FloatingKeyboardView.KeyAction.Shortcut.REDO ->
                        sendJSKey(90, ctrl = true, shift = true) // Ctrl+Shift+Z
                    FloatingKeyboardView.KeyAction.Shortcut.COMMAND_PALETTE ->
                        sendJSKey(80, ctrl = true, shift = true) // Ctrl+Shift+P
                    FloatingKeyboardView.KeyAction.Shortcut.FIND ->
                        sendJSKey(70, ctrl = true) // Ctrl+F
                    FloatingKeyboardView.KeyAction.Shortcut.CLOSE_TAB ->
                        sendJSKey(87, ctrl = true) // Ctrl+W
                }
                // Don't reset modifiers for shortcuts - they have their own
            }
            is FloatingKeyboardView.KeyAction.NavigationKey -> {
                val keyCode = when (action.nav) {
                    FloatingKeyboardView.KeyAction.Navigation.HOME -> KeyEvent.KEYCODE_MOVE_HOME
                    FloatingKeyboardView.KeyAction.Navigation.END -> KeyEvent.KEYCODE_MOVE_END
                    FloatingKeyboardView.KeyAction.Navigation.PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
                    FloatingKeyboardView.KeyAction.Navigation.PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
                    FloatingKeyboardView.KeyAction.Navigation.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                    FloatingKeyboardView.KeyAction.Navigation.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                    FloatingKeyboardView.KeyAction.Navigation.UP -> KeyEvent.KEYCODE_DPAD_UP
                    FloatingKeyboardView.KeyAction.Navigation.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                    FloatingKeyboardView.KeyAction.Navigation.DELETE -> KeyEvent.KEYCODE_FORWARD_DEL
                }
                sendNativeKey(keyCode)
                resetModifiers()
            }
        }
    }

    private fun sendJSKey(keyCode: Int, ctrl: Boolean = ctrlPressed, alt: Boolean = altPressed, shift: Boolean = shiftPressed) {
        val js = """
            (function() {
                var e = new KeyboardEvent('keydown', {
                    keyCode: $keyCode, which: $keyCode,
                    ctrlKey: $ctrl, altKey: $alt, shiftKey: $shift,
                    bubbles: true, cancelable: true
                });
                document.activeElement.dispatchEvent(e);
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun sendJSChar(char: String) {
        val escaped = char.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function() {
                var e = new KeyboardEvent('keydown', {
                    key: '$escaped', ctrlKey: $ctrlPressed, altKey: $altPressed, shiftKey: $shiftPressed,
                    bubbles: true, cancelable: true
                });
                document.activeElement.dispatchEvent(e);
                var e2 = new KeyboardEvent('keypress', {
                    key: '$escaped', ctrlKey: $ctrlPressed, altKey: $altPressed, shiftKey: $shiftPressed,
                    bubbles: true, cancelable: true
                });
                document.activeElement.dispatchEvent(e2);
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun sendNativeKey(keyCode: Int) {
        val meta = (if (ctrlPressed) KeyEvent.META_CTRL_ON else 0) or
                (if (altPressed) KeyEvent.META_ALT_ON else 0) or
                (if (shiftPressed) KeyEvent.META_SHIFT_ON else 0)
        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta)
        val up = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta)
        binding.webView.dispatchKeyEvent(down)
        binding.webView.dispatchKeyEvent(up)
    }

    private fun resetModifiers() {
        ctrlPressed = false
        altPressed = false
        shiftPressed = false
        floatingKeyboard?.updateModifierState(FloatingKeyboardView.KeyAction.Modifier.CTRL, false)
        floatingKeyboard?.updateModifierState(FloatingKeyboardView.KeyAction.Modifier.ALT, false)
        floatingKeyboard?.updateModifierState(FloatingKeyboardView.KeyAction.Modifier.SHIFT, false)
    }

    // =========================================================================
    // FAB Speed Dial
    // =========================================================================

    private fun setupFAB() {
        binding.fabMain.setOnClickListener {
            toggleFABMenu()
            scheduleFabFade()
        }

        binding.fabBrowser.setOnClickListener {
            collapseFABMenu()
            showPortBrowser()
        }

        binding.fabKeyboard.setOnClickListener {
            collapseFABMenu()
            toggleFloatingKeyboard()
        }

        binding.fabFullscreen.setOnClickListener {
            collapseFABMenu()
            toggleFullscreen()
        }

        binding.fabSnippets.setOnClickListener {
            collapseFABMenu()
            showSnippetPanel()
        }

        // Touch listener to reset FAB opacity
        binding.fabMain.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                binding.fabMain.alpha = 1.0f
                scheduleFabFade()
            }
            false
        }
    }

    private fun toggleFABMenu() {
        if (fabExpanded) collapseFABMenu() else expandFABMenu()
    }

    private fun expandFABMenu() {
        fabExpanded = true
        binding.fabMain.animate().rotation(45f).setDuration(200).start()

        val miniFabs = listOf(binding.fabBrowser, binding.fabKeyboard, binding.fabFullscreen, binding.fabSnippets)
        miniFabs.forEachIndexed { index, fab ->
            fab.visibility = View.VISIBLE
            fab.alpha = 0f
            fab.translationY = 40f
            fab.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .setStartDelay(index * 30L)
                .start()
        }
    }

    private fun collapseFABMenu() {
        fabExpanded = false
        binding.fabMain.animate().rotation(0f).setDuration(200).start()

        val miniFabs = listOf(binding.fabSnippets, binding.fabFullscreen, binding.fabKeyboard, binding.fabBrowser)
        miniFabs.forEachIndexed { index, fab ->
            fab.animate()
                .alpha(0f)
                .translationY(40f)
                .setDuration(150)
                .setStartDelay(index * 20L)
                .withEndAction { fab.visibility = View.GONE }
                .start()
        }
    }

    private fun scheduleFabFade() {
        fabHandler.removeCallbacks(fabFadeRunnable)
        binding.fabMain.alpha = 1.0f
        fabHandler.postDelayed(fabFadeRunnable, 4000)
    }

    // =========================================================================
    // Fullscreen / Immersive Mode
    // =========================================================================

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            binding.statusBar.visibility = View.GONE
            binding.btnFullscreen.setImageResource(R.drawable.ic_fullscreen_exit_24)
        } else {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            binding.statusBar.visibility = View.VISIBLE
            binding.btnFullscreen.setImageResource(R.drawable.ic_fullscreen_24)
        }
    }

    // =========================================================================
    // Port Browser
    // =========================================================================

    private fun showPortBrowser() {
        val sheet = PortBrowserSheet()
        sheet.setSSHService(sshService)
        sheet.show(supportFragmentManager, "port_browser")
    }

    // =========================================================================
    // Snippet Panel
    // =========================================================================

    private fun showSnippetPanel() {
        SnippetPanel(this) { snippet ->
            // Type snippet into code-server terminal via JS
            val escaped = snippet.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            val js = """
                (function() {
                    var term = document.querySelector('.xterm-helper-textarea');
                    if (term) {
                        term.focus();
                        document.execCommand('insertText', false, '$escaped');
                    } else {
                        var active = document.activeElement;
                        if (active) {
                            active.focus();
                            document.execCommand('insertText', false, '$escaped');
                        }
                    }
                })();
            """.trimIndent()
            binding.webView.evaluateJavascript(js, null)
        }.show()
    }

    // =========================================================================
    // Gesture Navigation
    // =========================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        // Volume keys for scrolling
        // Handled in dispatchKeyEvent
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.webView.hasFocus()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        binding.webView.scrollBy(0, -100)
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        binding.webView.scrollBy(0, 100)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // =========================================================================
    // Connection Info Overlay
    // =========================================================================

    private fun showConnectionInfo() {
        val uptime = if (connectionStartTime > 0) {
            val elapsed = SystemClock.elapsedRealtime() - connectionStartTime
            val hours = elapsed / 3600000
            val minutes = (elapsed % 3600000) / 60000
            val seconds = (elapsed % 60000) / 1000
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else "N/A"

        val tunnelCount = sshService?.activeTunnelCount() ?: 0

        val info = buildString {
            appendLine("Host: $connectedUser@$connectedHost:$connectedPort")
            appendLine("Uptime: $uptime")
            appendLine("Active Tunnels: $tunnelCount")
            appendLine("Code-Server Port: $localPort")
        }

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle("Connection Info")
            .setMessage(info)
            .setPositiveButton("OK", null)
            .show()
    }

    // =========================================================================
    // Auto-Reconnect
    // =========================================================================

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isReconnecting || sshService?.sshManager?.isConnected == false) {
                    runOnUiThread { attemptReconnect() }
                }
            }
        }

        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun attemptReconnect() {
        if (isReconnecting || currentConnection == null) return
        isReconnecting = true
        reconnectAttempts = 0
        binding.reconnectBanner.visibility = View.VISIBLE

        lifecycleScope.launch {
            while (reconnectAttempts < Constants.MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++
                binding.reconnectBanner.text = "Reconnecting (${reconnectAttempts}/${Constants.MAX_RECONNECT_ATTEMPTS})..."

                try {
                    val connection = currentConnection ?: break
                    val service = sshService ?: break
                    val sshManager = service.initSSH()

                    withContext(Dispatchers.IO) {
                        when (connection.authType) {
                            ConnectionEntity.AuthType.PASSWORD -> {
                                val password = connection.encryptedPassword?.let { SecurityUtils.decrypt(it) } ?: ""
                                sshManager.connect(connection.host, connection.port, connection.username, password = password)
                            }
                            ConnectionEntity.AuthType.SSH_KEY -> {
                                val keyManager = SSHKeyManager(this@EditorActivity)
                                val keyProvider = connection.privateKeyPath?.let { path ->
                                    val passphrase = connection.keyPassphrase?.let { SecurityUtils.decrypt(it) }
                                    keyManager.getKeyProvider(path, passphrase)
                                }
                                sshManager.connect(connection.host, connection.port, connection.username, keyProvider = keyProvider)
                            }
                        }
                    }

                    // Re-establish tunnel
                    val forwarder = service.initPortForwarder() ?: break
                    localPort = forwarder.startForwarding(remotePort = connection.codeServerPort)

                    // Reload WebView
                    binding.webView.loadUrl("http://127.0.0.1:$localPort")
                    binding.reconnectBanner.visibility = View.GONE
                    updateStatus("Connected", R.color.status_connected)
                    connectionStartTime = SystemClock.elapsedRealtime()
                    isReconnecting = false
                    return@launch

                } catch (e: Exception) {
                    val backoff = Constants.RECONNECT_BASE_DELAY_MS * (1L shl (reconnectAttempts - 1).coerceAtMost(4))
                    delay(backoff)
                }
            }

            // All attempts failed
            isReconnecting = false
            binding.reconnectBanner.text = getString(R.string.reconnect_failed)
            updateStatus("Disconnected", R.color.status_disconnected)
        }
    }

    // =========================================================================
    // SSH Connection
    // =========================================================================

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

                currentConnection = connection
                connectedHost = connection.host
                connectedUser = connection.username
                connectedPort = connection.port

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

                connectionStartTime = SystemClock.elapsedRealtime()
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

    // =========================================================================
    // Utility
    // =========================================================================

    private fun updateStatus(text: String, colorRes: Int) {
        binding.statusText.text = text
        binding.statusDot.setBackgroundColor(getColor(colorRes))
    }

    private fun disconnect() {
        sshService?.cleanup()
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        fabHandler.removeCallbacksAndMessages(null)
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(it)
        }
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
