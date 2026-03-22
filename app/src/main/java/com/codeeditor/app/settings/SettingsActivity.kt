package com.codeeditor.app.settings

import android.content.Context
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.codeeditor.app.databinding.ActivitySettingsBinding
import com.codeeditor.app.utils.Constants

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadSettings()
        setupListeners()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        val fontSize = prefs.getInt(Constants.PREF_FONT_SIZE, 14)
        binding.seekFontSize.progress = fontSize
        binding.fontSizeValue.text = "${fontSize}sp"

        binding.switchDarkTheme.isChecked = prefs.getBoolean(Constants.PREF_DARK_THEME, true)
        binding.switchAutoReconnect.isChecked = prefs.getBoolean(Constants.PREF_AUTO_RECONNECT, true)

        binding.editKeepAlive.setText(
            prefs.getInt(Constants.PREF_KEEP_ALIVE, Constants.DEFAULT_KEEP_ALIVE_INTERVAL).toString()
        )
        binding.editCodeServerPort.setText(
            prefs.getInt(Constants.PREF_CODE_SERVER_PORT, Constants.DEFAULT_CODE_SERVER_PORT).toString()
        )
    }

    private fun setupListeners() {
        binding.seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.fontSizeValue.text = "${progress}sp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnSaveSettings.setOnClickListener { saveSettings() }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(Constants.PREF_FONT_SIZE, binding.seekFontSize.progress)
            putBoolean(Constants.PREF_DARK_THEME, binding.switchDarkTheme.isChecked)
            putBoolean(Constants.PREF_AUTO_RECONNECT, binding.switchAutoReconnect.isChecked)
            putInt(Constants.PREF_KEEP_ALIVE,
                binding.editKeepAlive.text.toString().toIntOrNull() ?: Constants.DEFAULT_KEEP_ALIVE_INTERVAL)
            putInt(Constants.PREF_CODE_SERVER_PORT,
                binding.editCodeServerPort.text.toString().toIntOrNull() ?: Constants.DEFAULT_CODE_SERVER_PORT)
            apply()
        }
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
