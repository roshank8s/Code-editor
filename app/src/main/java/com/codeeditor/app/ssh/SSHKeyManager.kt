package com.codeeditor.app.ssh

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import java.io.File
import java.io.StringWriter
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security

class SSHKeyManager(private val context: Context) {

    init {
        // Ensure the full BouncyCastle replaces Android's stripped version
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    private val keyDir: File
        get() = File(context.filesDir, "ssh_keys").also { it.mkdirs() }

    suspend fun generateKeyPair(name: String, type: KeyType = KeyType.RSA_4096): GeneratedKey =
        withContext(Dispatchers.IO) {
            val keyPair = when (type) {
                KeyType.RSA_2048 -> generateRSAKeyPair(2048)
                KeyType.RSA_4096 -> generateRSAKeyPair(4096)
                KeyType.ED25519 -> generateEd25519KeyPair()
            }

            val privateKeyFile = File(keyDir, name)
            val publicKeyFile = File(keyDir, "$name.pub")

            val privateKeyPem = keyToPEM(keyPair.private)
            privateKeyFile.writeText(privateKeyPem)
            privateKeyFile.setReadable(false, false)
            privateKeyFile.setReadable(true, true)

            val publicKeyStr = formatPublicKey(keyPair)
            publicKeyFile.writeText(publicKeyStr)

            GeneratedKey(
                privateKeyPath = privateKeyFile.absolutePath,
                publicKey = publicKeyStr
            )
        }

    private fun generateRSAKeyPair(keySize: Int): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(keySize)
        return generator.generateKeyPair()
    }

    private fun generateEd25519KeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("Ed25519", "BC")
        return generator.generateKeyPair()
    }

    private fun keyToPEM(key: java.security.PrivateKey): String {
        val writer = StringWriter()
        JcaPEMWriter(writer).use { pemWriter ->
            pemWriter.writeObject(key)
        }
        return writer.toString()
    }

    private fun formatPublicKey(keyPair: KeyPair): String {
        val encoded = java.util.Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val type = when (keyPair.public.algorithm) {
            "RSA" -> "ssh-rsa"
            "Ed25519", "EdDSA" -> "ssh-ed25519"
            else -> "ssh-unknown"
        }
        return "$type $encoded code-editor@android"
    }

    fun getKeyProvider(privateKeyPath: String, passphrase: String? = null): KeyProvider {
        val keyFile = OpenSSHKeyFile()
        val file = File(privateKeyPath)
        if (passphrase != null) {
            keyFile.init(file.readText(), null,
                net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(passphrase.toCharArray())
            )
        } else {
            keyFile.init(file.readText(), null)
        }
        return keyFile
    }

    fun listKeys(): List<String> {
        return keyDir.listFiles()
            ?.filter { !it.name.endsWith(".pub") }
            ?.map { it.absolutePath }
            ?: emptyList()
    }

    fun getPublicKey(privateKeyPath: String): String? {
        val pubFile = File("$privateKeyPath.pub")
        return if (pubFile.exists()) pubFile.readText() else null
    }

    fun deleteKey(name: String) {
        File(keyDir, name).delete()
        File(keyDir, "$name.pub").delete()
    }

    enum class KeyType {
        RSA_2048, RSA_4096, ED25519
    }

    data class GeneratedKey(
        val privateKeyPath: String,
        val publicKey: String
    )
}
