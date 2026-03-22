package com.codeeditor.app.connections

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PASSWORD,
    val encryptedPassword: String? = null,
    val privateKeyPath: String? = null,
    val keyPassphrase: String? = null,
    val lastConnected: Long = 0,
    val codeServerPort: Int = 8080
) {
    enum class AuthType {
        PASSWORD, SSH_KEY
    }

    val displayAddress: String
        get() = "$username@$host:$port"
}
