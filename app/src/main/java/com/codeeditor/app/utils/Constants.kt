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
}
