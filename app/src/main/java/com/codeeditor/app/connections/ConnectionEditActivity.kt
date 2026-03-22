package com.codeeditor.app.connections

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.codeeditor.app.R
import com.codeeditor.app.databinding.ActivityConnectionEditBinding
import com.codeeditor.app.ssh.SSHKeyManager
import com.codeeditor.app.utils.Constants
import com.codeeditor.app.utils.SecurityUtils
import kotlinx.coroutines.launch

class ConnectionEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionEditBinding
    private lateinit var database: ConnectionDatabase
    private lateinit var keyManager: SSHKeyManager

    private var connectionId: Long = -1
    private var selectedKeyPath: String? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val inputStream = contentResolver.openInputStream(it)
            val keyContent = inputStream?.bufferedReader()?.readText()
            inputStream?.close()

            if (keyContent != null) {
                val keyFile = java.io.File(filesDir, "ssh_keys/imported_${System.currentTimeMillis()}")
                keyFile.parentFile?.mkdirs()
                keyFile.writeText(keyContent)
                selectedKeyPath = keyFile.absolutePath
                binding.keyPathText.text = keyFile.name
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = ConnectionDatabase.getInstance(this)
        keyManager = SSHKeyManager(this)

        connectionId = intent.getLongExtra(Constants.EXTRA_CONNECTION_ID, -1)

        setupToolbar()
        setupAuthToggle()
        setupButtons()

        if (connectionId > 0) {
            loadConnection()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (connectionId > 0) {
            binding.toolbar.title = getString(R.string.edit_connection)
        }
    }

    private fun setupAuthToggle() {
        binding.authMethodGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioPassword -> {
                    binding.passwordLayout.visibility = View.VISIBLE
                    binding.keyLayout.visibility = View.GONE
                }
                R.id.radioKey -> {
                    binding.passwordLayout.visibility = View.GONE
                    binding.keyLayout.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener { saveConnection() }

        binding.btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage("Delete this connection?")
                .setPositiveButton(R.string.delete) { _, _ -> deleteConnection() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnSelectKey.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        binding.btnGenerateKey.setOnClickListener {
            generateNewKey()
        }

        if (connectionId > 0) {
            binding.btnDelete.visibility = View.VISIBLE
        }
    }

    private fun loadConnection() {
        lifecycleScope.launch {
            val connection = database.connectionDao().getConnectionById(connectionId) ?: return@launch

            binding.editName.setText(connection.name)
            binding.editHost.setText(connection.host)
            binding.editPort.setText(connection.port.toString())
            binding.editUsername.setText(connection.username)

            when (connection.authType) {
                ConnectionEntity.AuthType.PASSWORD -> {
                    binding.radioPassword.isChecked = true
                    connection.encryptedPassword?.let {
                        try {
                            binding.editPassword.setText(SecurityUtils.decrypt(it))
                        } catch (_: Exception) {
                        }
                    }
                }
                ConnectionEntity.AuthType.SSH_KEY -> {
                    binding.radioKey.isChecked = true
                    selectedKeyPath = connection.privateKeyPath
                    binding.keyPathText.text = connection.privateKeyPath?.let {
                        java.io.File(it).name
                    } ?: "No key selected"
                }
            }
        }
    }

    private fun saveConnection() {
        val name = binding.editName.text.toString().trim()
        val host = binding.editHost.text.toString().trim()
        val portStr = binding.editPort.text.toString().trim()
        val username = binding.editUsername.text.toString().trim()

        if (name.isEmpty() || host.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, "Please fill in all required fields", Toast.LENGTH_SHORT).show()
            return
        }

        val port = portStr.toIntOrNull() ?: 22
        val isPasswordAuth = binding.radioPassword.isChecked

        val connection = ConnectionEntity(
            id = if (connectionId > 0) connectionId else 0,
            name = name,
            host = host,
            port = port,
            username = username,
            authType = if (isPasswordAuth) ConnectionEntity.AuthType.PASSWORD else ConnectionEntity.AuthType.SSH_KEY,
            encryptedPassword = if (isPasswordAuth) {
                val pw = binding.editPassword.text.toString()
                if (pw.isNotEmpty()) SecurityUtils.encrypt(pw) else null
            } else null,
            privateKeyPath = if (!isPasswordAuth) selectedKeyPath else null,
            keyPassphrase = if (!isPasswordAuth) {
                val pp = binding.editKeyPassphrase.text.toString()
                if (pp.isNotEmpty()) SecurityUtils.encrypt(pp) else null
            } else null
        )

        lifecycleScope.launch {
            if (connectionId > 0) {
                database.connectionDao().update(connection)
            } else {
                database.connectionDao().insert(connection)
            }
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun deleteConnection() {
        lifecycleScope.launch {
            database.connectionDao().deleteById(connectionId)
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun generateNewKey() {
        lifecycleScope.launch {
            try {
                val keyName = "id_rsa_${System.currentTimeMillis()}"
                val result = keyManager.generateKeyPair(keyName)
                selectedKeyPath = result.privateKeyPath
                binding.keyPathText.text = java.io.File(result.privateKeyPath).name

                AlertDialog.Builder(this@ConnectionEditActivity)
                    .setTitle("SSH Key Generated")
                    .setMessage("Public key (add this to your server's ~/.ssh/authorized_keys):\n\n${result.publicKey}")
                    .setPositiveButton("Copy") { _, _ ->
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("SSH Public Key", result.publicKey))
                        Toast.makeText(this@ConnectionEditActivity, "Public key copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("OK", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@ConnectionEditActivity, "Key generation failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
