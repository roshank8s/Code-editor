package com.codeeditor.app.utils

object Constants {
    const val DEFAULT_SSH_PORT = 22
    const val DEFAULT_CODE_SERVER_PORT = 8080
    const val DEFAULT_KEEP_ALIVE_INTERVAL = 30
    const val NOTIFICATION_CHANNEL_ID = "ssh_connection"
    const val NOTIFICATION_ID = 1001
    const val SSH_KEY_DIR = "ssh_keys"
    const val DATABASE_NAME = "code_editor_db"

    const val EXTRA_CONNECTION_ID = "connection_id"
    const val EXTRA_HOST = "host"
    const val EXTRA_PORT = "port"
    const val EXTRA_USERNAME = "username"

    const val PREFS_NAME = "code_editor_prefs"
    const val PREF_FONT_SIZE = "font_size"
    const val PREF_DARK_THEME = "dark_theme"
    const val PREF_KEEP_ALIVE = "keep_alive_interval"
    const val PREF_AUTO_RECONNECT = "auto_reconnect"
    const val PREF_CODE_SERVER_PORT = "code_server_port"

    // Floating keyboard prefs
    const val PREF_KB_VISIBLE = "kb_visible"
    const val PREF_KB_POSITION_X = "kb_position_x"
    const val PREF_KB_POSITION_Y = "kb_position_y"
    const val PREF_KB_WIDTH = "kb_width"
    const val PREF_KB_HEIGHT = "kb_height"
    const val PREF_KB_OPACITY = "kb_opacity"
    const val PREF_KB_COLLAPSED = "kb_collapsed"

    // Browser prefs
    const val PREF_RECENT_PORTS = "recent_ports"

    // Snippets prefs
    const val PREF_CUSTOM_SNIPPETS = "custom_snippets"

    // Reconnect
    const val MAX_RECONNECT_ATTEMPTS = 5
    const val RECONNECT_BASE_DELAY_MS = 2000L
}
