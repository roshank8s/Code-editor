package com.codeeditor.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.codeeditor.app.R
import com.codeeditor.app.ssh.PortForwarder
import com.codeeditor.app.ssh.SSHConnectionService
import com.codeeditor.app.utils.Constants
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PortBrowserSheet : BottomSheetDialogFragment() {

    private var sshService: SSHConnectionService? = null
    private val activeTunnels = mutableMapOf<Int, Pair<PortForwarder, Int>>() // remotePort -> (forwarder, localPort)
    private var browserWebView: WebView? = null

    fun setSSHService(service: SSHConnectionService?) {
        sshService = service
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_port_browser, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val portInput = view.findViewById<android.widget.EditText>(R.id.portInput)
        val btnGo = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGo)
        val btnClose = view.findViewById<android.widget.ImageButton>(R.id.btnCloseBrowser)
        val btnBack = view.findViewById<android.widget.ImageButton>(R.id.btnBack)
        val btnForward = view.findViewById<android.widget.ImageButton>(R.id.btnForward)
        val btnRefresh = view.findViewById<android.widget.ImageButton>(R.id.btnRefresh)
        val browserUrl = view.findViewById<android.widget.TextView>(R.id.browserUrl)
        val recentPortsContainer = view.findViewById<android.widget.HorizontalScrollView>(R.id.recentPortsContainer)
        val recentPortsChips = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.recentPortsChips)
        browserWebView = view.findViewById(R.id.browserWebView)

        // Setup WebView
        browserWebView?.settings?.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        browserWebView?.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                browserUrl.text = url ?: ""
            }
        }

        // Load recent ports
        val recentPorts = loadRecentPorts()
        if (recentPorts.isNotEmpty()) {
            recentPortsContainer.visibility = View.VISIBLE
            recentPorts.forEach { port ->
                val chip = Chip(requireContext()).apply {
                    text = port.toString()
                    isClickable = true
                    setOnClickListener {
                        portInput.setText(port.toString())
                        navigateToPort(port)
                    }
                }
                recentPortsChips.addView(chip)
            }
        }

        // Go button
        btnGo.setOnClickListener {
            val port = portInput.text.toString().toIntOrNull() ?: return@setOnClickListener
            navigateToPort(port)
        }

        portInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val port = portInput.text.toString().toIntOrNull() ?: return@setOnEditorActionListener false
                navigateToPort(port)
                true
            } else false
        }

        // Navigation
        btnBack.setOnClickListener { browserWebView?.goBack() }
        btnForward.setOnClickListener { browserWebView?.goForward() }
        btnRefresh.setOnClickListener { browserWebView?.reload() }
        btnClose.setOnClickListener { dismiss() }

        // Expand bottom sheet fully
        dialog?.setOnShowListener { dlg ->
            val bottomSheet = (dlg as? com.google.android.material.bottomsheet.BottomSheetDialog)
                ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    private fun navigateToPort(remotePort: Int) {
        val service = sshService ?: return

        // Check if we already have a tunnel for this port
        val existing = activeTunnels[remotePort]
        if (existing != null) {
            browserWebView?.loadUrl("http://127.0.0.1:${existing.second}")
            return
        }

        // Create new tunnel
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val forwarder = service.createPortForwarder() ?: return@launch
                val localPort = withContext(Dispatchers.IO) {
                    forwarder.startForwarding(remotePort = remotePort)
                }
                activeTunnels[remotePort] = Pair(forwarder, localPort)
                saveRecentPort(remotePort)
                browserWebView?.loadUrl("http://127.0.0.1:$localPort")
            } catch (e: Exception) {
                view?.findViewById<android.widget.TextView>(R.id.browserUrl)?.text =
                    "Error: ${e.message}"
            }
        }
    }

    private fun loadRecentPorts(): List<Int> {
        val prefs = requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val portsStr = prefs.getString(Constants.PREF_RECENT_PORTS, "") ?: ""
        return portsStr.split(",").mapNotNull { it.trim().toIntOrNull() }.distinct().take(5)
    }

    private fun saveRecentPort(port: Int) {
        val prefs = requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = loadRecentPorts().toMutableList()
        existing.remove(port)
        existing.add(0, port)
        prefs.edit().putString(Constants.PREF_RECENT_PORTS, existing.take(5).joinToString(",")).apply()
    }

    override fun onDestroyView() {
        browserWebView?.destroy()
        browserWebView = null
        // Clean up tunnels
        activeTunnels.values.forEach { (forwarder, _) -> forwarder.close() }
        activeTunnels.clear()
        super.onDestroyView()
    }
}
