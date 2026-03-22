package com.codeeditor.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.codeeditor.app.connections.ConnectionAdapter
import com.codeeditor.app.connections.ConnectionDatabase
import com.codeeditor.app.connections.ConnectionEditActivity
import com.codeeditor.app.connections.ConnectionEntity
import com.codeeditor.app.databinding.ActivityMainBinding
import com.codeeditor.app.editor.EditorActivity
import com.codeeditor.app.settings.SettingsActivity
import com.codeeditor.app.terminal.TerminalActivity
import com.codeeditor.app.utils.Constants
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: ConnectionDatabase
    private lateinit var adapter: ConnectionAdapter

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* list auto-updates via Flow */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = ConnectionDatabase.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeConnections()
    }

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ConnectionAdapter(
            onItemClick = { connection -> openEditor(connection) },
            onTerminalClick = { connection -> openTerminal(connection) },
            onMoreClick = { connection, view -> showPopupMenu(connection, view) }
        )
        binding.connectionsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.connectionsRecyclerView.adapter = adapter
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            val intent = Intent(this, ConnectionEditActivity::class.java)
            editLauncher.launch(intent)
        }
    }

    private fun observeConnections() {
        lifecycleScope.launch {
            database.connectionDao().getAllConnections().collectLatest { connections ->
                adapter.submitList(connections)
                binding.emptyView.visibility = if (connections.isEmpty()) View.VISIBLE else View.GONE
                binding.connectionsRecyclerView.visibility = if (connections.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun openEditor(connection: ConnectionEntity) {
        lifecycleScope.launch {
            database.connectionDao().updateLastConnected(connection.id, System.currentTimeMillis())
        }
        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra(Constants.EXTRA_CONNECTION_ID, connection.id)
        }
        startActivity(intent)
    }

    private fun openTerminal(connection: ConnectionEntity) {
        lifecycleScope.launch {
            database.connectionDao().updateLastConnected(connection.id, System.currentTimeMillis())
        }
        val intent = Intent(this, TerminalActivity::class.java).apply {
            putExtra(Constants.EXTRA_CONNECTION_ID, connection.id)
        }
        startActivity(intent)
    }

    private fun showPopupMenu(connection: ConnectionEntity, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.open_editor))
        popup.menu.add(0, 2, 1, getString(R.string.open_terminal))
        popup.menu.add(0, 3, 2, getString(R.string.edit_connection))
        popup.menu.add(0, 4, 3, getString(R.string.delete))

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { openEditor(connection); true }
                2 -> { openTerminal(connection); true }
                3 -> {
                    val intent = Intent(this, ConnectionEditActivity::class.java).apply {
                        putExtra(Constants.EXTRA_CONNECTION_ID, connection.id)
                    }
                    editLauncher.launch(intent)
                    true
                }
                4 -> {
                    lifecycleScope.launch {
                        database.connectionDao().delete(connection)
                        Toast.makeText(this@MainActivity, "Connection deleted", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
}
