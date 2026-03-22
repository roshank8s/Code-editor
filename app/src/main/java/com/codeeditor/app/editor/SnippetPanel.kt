package com.codeeditor.app.editor

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeeditor.app.R
import com.codeeditor.app.utils.Constants
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup

class SnippetPanel(
    private val context: Context,
    private val onSnippetSelected: (String) -> Unit
) {
    private val snippets = mapOf(
        "git" to listOf(
            "git status",
            "git add .",
            "git commit -m \"\"",
            "git push",
            "git pull",
            "git log --oneline -10",
            "git diff",
            "git branch",
            "git checkout -b ",
            "git stash"
        ),
        "files" to listOf(
            "ls -la",
            "cd ",
            "mkdir -p ",
            "cat ",
            "chmod +x ",
            "cp -r ",
            "mv ",
            "rm -rf ",
            "find . -name \"\"",
            "du -sh *"
        ),
        "dev" to listOf(
            "npm install",
            "npm start",
            "npm run build",
            "npm run dev",
            "python3 ",
            "pip install ",
            "docker ps",
            "docker-compose up -d",
            "docker logs ",
            "node ",
            "go run .",
            "cargo build"
        ),
        "system" to listOf(
            "top",
            "htop",
            "df -h",
            "free -m",
            "ps aux",
            "kill ",
            "systemctl status ",
            "journalctl -f",
            "netstat -tlnp",
            "curl -s ",
            "whoami",
            "uname -a"
        )
    )

    fun show() {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.layout_snippet_panel, null)

        val categoryChips = view.findViewById<ChipGroup>(R.id.categoryChips)
        val snippetList = view.findViewById<RecyclerView>(R.id.snippetList)
        val btnAddSnippet = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddSnippet)

        snippetList.layoutManager = LinearLayoutManager(context)

        var currentCategory = "git"
        val adapter = SnippetAdapter(snippets[currentCategory] ?: emptyList()) { snippet ->
            onSnippetSelected(snippet)
            dialog.dismiss()
        }
        snippetList.adapter = adapter

        categoryChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val chipId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            currentCategory = when (chipId) {
                R.id.chipGit -> "git"
                R.id.chipFiles -> "files"
                R.id.chipDev -> "dev"
                R.id.chipSystem -> "system"
                R.id.chipCustom -> "custom"
                else -> "git"
            }

            btnAddSnippet.visibility = if (currentCategory == "custom") View.VISIBLE else View.GONE

            val items = if (currentCategory == "custom") loadCustomSnippets() else snippets[currentCategory] ?: emptyList()
            adapter.updateItems(items)
        }

        btnAddSnippet.setOnClickListener {
            showAddSnippetDialog { newSnippet ->
                saveCustomSnippet(newSnippet)
                adapter.updateItems(loadCustomSnippets())
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun loadCustomSnippets(): List<String> {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(Constants.PREF_CUSTOM_SNIPPETS, "") ?: ""
        return if (json.isBlank()) emptyList()
        else json.split("\n").filter { it.isNotBlank() }
    }

    private fun saveCustomSnippet(snippet: String) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = loadCustomSnippets().toMutableList()
        existing.add(snippet)
        prefs.edit().putString(Constants.PREF_CUSTOM_SNIPPETS, existing.joinToString("\n")).apply()
    }

    private fun showAddSnippetDialog(onAdded: (String) -> Unit) {
        val input = EditText(context).apply {
            hint = "Enter command snippet"
            setTextColor(context.getColor(R.color.terminal_fg))
            setHintTextColor(context.getColor(R.color.floating_kb_handle))
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle("Add Snippet")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onAdded(text)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class SnippetAdapter(
        private var items: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<SnippetAdapter.VH>() {

        fun updateItems(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(32, 24, 32, 24)
                setTextColor(parent.context.getColor(R.color.terminal_fg))
                textSize = 13f
                setBackgroundResource(android.R.attr.selectableItemBackground)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            (holder.itemView as TextView).text = items[position]
            holder.itemView.setOnClickListener { onClick(items[position]) }
        }

        override fun getItemCount() = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view)
    }
}
