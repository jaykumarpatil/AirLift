package com.airlift

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

class FtpForegroundService : Service() {
    private var ftpServer: org.apache.ftpserver.FtpServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rootPath = intent?.getStringExtra("ROOT_PATH") ?: filesDir.absolutePath

        // Build Notification (Mandatory for Foreground Services)
        val channelId = "ftp_channel"
        val channel = NotificationChannel(channelId, "FTP Server", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FTP Server Running")
            .setContentText("Listening on port 2121")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        // Start Foreground with declared type for API 36
        startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        startFtpServer(rootPath)
        return START_STICKY
    }

    private fun startFtpServer(path: String) {
        val serverFactory = FtpServerFactory()
        val listenerFactory = ListenerFactory().apply { port = 2121 }
        serverFactory.addListener("default", listenerFactory.createListener())

        val user = BaseUser().apply {
            name = "android"
            password = "password"
            homeDirectory = path
            authorities = listOf(WritePermission())
        }
        serverFactory.userManager.save(user)

        ftpServer = serverFactory.createServer().apply { start() }
    }

    override fun onDestroy() {
        ftpServer?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
