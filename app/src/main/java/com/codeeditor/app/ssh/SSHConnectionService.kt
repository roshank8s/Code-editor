package com.codeeditor.app.ssh

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.codeeditor.app.MainActivity
import com.codeeditor.app.R
import com.codeeditor.app.utils.Constants

class SSHConnectionService : Service() {

    private val binder = LocalBinder()
    var sshManager: SSHManager? = null
        private set
    var portForwarder: PortForwarder? = null
        private set
    var commandRunner: RemoteCommandRunner? = null
        private set

    // Additional port forwarders for browser tunnels
    private val additionalForwarders = mutableListOf<PortForwarder>()

    inner class LocalBinder : Binder() {
        fun getService(): SSHConnectionService = this@SSHConnectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Constants.NOTIFICATION_ID, createNotification("SSH Connected"))
        return START_STICKY
    }

    fun initSSH(): SSHManager {
        val manager = SSHManager()
        sshManager = manager
        commandRunner = RemoteCommandRunner(manager)
        return manager
    }

    fun initPortForwarder(): PortForwarder? {
        val client = sshManager?.getClient() ?: return null
        val forwarder = PortForwarder(client)
        portForwarder = forwarder
        return forwarder
    }

    /**
     * Create an additional PortForwarder for browser tunnels.
     * Uses the same SSH client as the main connection.
     */
    fun createPortForwarder(): PortForwarder? {
        val client = sshManager?.getClient() ?: return null
        val forwarder = PortForwarder(client)
        additionalForwarders.add(forwarder)
        return forwarder
    }

    /**
     * Get count of active tunnels (main + additional).
     */
    fun activeTunnelCount(): Int {
        var count = if (portForwarder?.isActive == true) 1 else 0
        count += additionalForwarders.count { it.isActive }
        return count
    }

    fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(Constants.NOTIFICATION_ID, notification)
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Code Editor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun cleanup() {
        // Close all additional forwarders
        additionalForwarders.forEach { it.close() }
        additionalForwarders.clear()

        portForwarder?.close()
        sshManager?.close()
        portForwarder = null
        commandRunner = null
        sshManager = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
