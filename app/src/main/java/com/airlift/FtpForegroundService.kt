package com.airlift

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission

class FtpForegroundService : Service() {
    private var ftpServer: org.apache.ftpserver.FtpServer? = null

    @Volatile
    private var watchdogRunning = false
    private var watchdogThread: Thread? = null
    private val restartLock = Any()
    private var lastRestartTime = 0L
    private var restartCount = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rootPath = intent?.getStringExtra("ROOT_PATH") ?: filesDir.absolutePath
        setupNotification()
        startFtpServer(rootPath)
        return START_STICKY
    }

    @SuppressLint("BatteryLife")
    private fun startFtpServer(path: String) {
        val serverFactory = FtpServerFactory()

        // 1. MAXIMIZE CONNECTION THROUGHPUT
        val connectionConfigFactory = ConnectionConfigFactory().apply {
            // Allow more concurrent control connections to avoid "connection interrupted" when many clients connect
            maxLogins = 500
            isAnonymousLoginEnabled = false
            maxLoginFailures = 10
            loginFailureDelay = 500
        }
        serverFactory.connectionConfig = connectionConfigFactory.createConnectionConfig()

        // 2. OPTIMIZE LISTENER & SOCKET BUFFERS (Optimized for 16GB RAM)
        val listenerFactory = ListenerFactory().apply {
            port = 2121

            // Set Passive Mode and Data Connection configuration
            val dataCfg = DataConnectionConfigurationFactory().apply {
                // Expand passive port range to avoid port exhaustion when clients open multiple parallel data connections
                // Use a high ephemeral range commonly allowed on Android devices in LAN environments
                passivePorts = "20000-20050"
                // Keep reasonable idle time
                idleTime = 0
            }
            dataConnectionConfiguration = dataCfg.createDataConnectionConfiguration()
        }

        // Create the listener instance so we can tune the underlying MINA transport
        val listener = listenerFactory.createListener()

        // Attempt to tune MINA/I0 layer: disable Nagle, set 8MB socket buffers,
        // and attach an OrderedThreadPoolExecutor where possible. Use reflection
        // so the code is robust across Apache MINA / FTPServer versions.
        try {
            // Try to obtain the session config (method name varies across versions)
            val clazz = listener.javaClass
            val getSessionMethod = clazz.methods.firstOrNull { m ->
                val n = m.name.lowercase()
                n.contains("iosessionconfig") || n.contains("iosession") || n.contains("getiosessionconfig") || n.contains(
                    "getsessionconfig"
                )
            }
            val sessionConfig = getSessionMethod?.invoke(listener)

            if (sessionConfig != null) {
                // SocketSessionConfig lives in org.apache.mina.transport.socket
                val socketCfgClass = try {
                    Class.forName("org.apache.mina.transport.socket.SocketSessionConfig")
                } catch (e: ClassNotFoundException) {
                    null
                }

                if (socketCfgClass != null && socketCfgClass.isInstance(sessionConfig)) {
                    // Disable Nagle
                    try {
                        val setTcpNoDelay =
                            socketCfgClass.getMethod("setTcpNoDelay", java.lang.Boolean.TYPE)
                        setTcpNoDelay.invoke(sessionConfig, true)
                    } catch (t: Throwable) {
                        Log.w("FtpForegroundService", "Failed to set TCP_NODELAY", t)
                    }

                    // Set send/receive buffer sizes to 8MB (8 * 1024 * 1024)
                    try {
                        val setSend = socketCfgClass.getMethod("setSendBufferSize", Integer.TYPE)
                        val setRecv = socketCfgClass.getMethod("setReceiveBufferSize", Integer.TYPE)
                        val buffer = 8 * 1024 * 1024
                        setSend.invoke(sessionConfig, buffer)
                        setRecv.invoke(sessionConfig, buffer)
                    } catch (t: Throwable) {
                        Log.w("FtpForegroundService", "Failed to set socket buffer sizes", t)
                    }
                }
            }

            // Try to obtain an acceptor to attach an OrderedThreadPoolExecutor
            val getAcceptor = clazz.methods.firstOrNull { m ->
                m.name.lowercase().contains("getacceptor") || m.name.lowercase()
                    .contains("getioacceptor")
            }
            val acceptor = getAcceptor?.invoke(listener)
            if (acceptor != null) {
                try {
                    val execClass = Class.forName("org.apache.mina.util.OrderedThreadPoolExecutor")
                    // Constructor signature may accept an int for thread count
                    val ctor = execClass.constructors.firstOrNull { it.parameterCount == 1 }
                    val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                    val executor = ctor?.newInstance(processors)

                    if (executor != null) {
                        // Several acceptor implementations provide a method to set an executor; try common names
                        val setExec = acceptor.javaClass.methods.firstOrNull { m ->
                            m.name.lowercase().contains("setexecutor") || m.name.lowercase()
                                .contains("setioexecutor")
                        }
                        setExec?.invoke(acceptor, executor)
                    }
                } catch (t: Throwable) {
                    Log.w("FtpForegroundService", "Failed to attach OrderedThreadPoolExecutor", t)
                }
            }
        } catch (e: Exception) {
            Log.w("FtpForegroundService", "MINA tuning reflection failed", e)
        }

        // 3. APPLY TO SERVER
        serverFactory.addListener("default", listener)

        val user = BaseUser().apply {
            name = "android"
            password = "password"
            homeDirectory = path
            authorities = listOf(WritePermission())
        }
        serverFactory.userManager.save(user)

        // Start the server (guarded) to avoid crashing the Service if server startup fails
        try {
            ftpServer = serverFactory.createServer().apply { start() }
        } catch (t: Throwable) {
            Log.e("FtpForegroundService", "FTP server failed to start", t)
            // Do not rethrow; allow service to remain alive and optionally be monitored by external watchdog
            ftpServer = null
        }

        // Start watchdog to monitor and auto-restart server if it stops unexpectedly
        startWatchdog(path)

        // Boost the current service thread priority so Android is less likely to throttle
        try {
            // Use an elevated priority; be conservative (urgent display is a commonly available negative value)
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (t: Throwable) {
            Log.w("FtpForegroundService", "Failed to raise thread priority", t)
        }

        // Runtime checks: ACCESS_LOCAL_NETWORK and battery optimizations
        try {
            val accessLocalNetwork = "android.permission.ACCESS_LOCAL_NETWORK"
            if (checkSelfPermission(accessLocalNetwork) != PackageManager.PERMISSION_GRANTED) {
                Log.w(
                    "FtpForegroundService",
                    "ACCESS_LOCAL_NETWORK not granted; Android 16 may throttle sockets"
                )

                // Build a small actionable notification to direct the user to app settings
                val settingsIntent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    settingsIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val nm = getSystemService(NotificationManager::class.java)
                val note = NotificationCompat.Builder(this, "ftp_channel")
                    .setContentTitle("Grant Local Network Access")
                    .setContentText("Open app settings to grant local network access for best performance")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build()
                nm.notify(2, note)
            }

            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.w(
                    "FtpForegroundService",
                    "App not excluded from battery optimizations; large transfers may be throttled"
                )

                // Action for requesting to ignore battery optimizations (user must accept)
                val ignoreIntent =
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                val pending = PendingIntent.getActivity(
                    this,
                    1,
                    ignoreIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val nm = getSystemService(NotificationManager::class.java)
                val note = NotificationCompat.Builder(this, "ftp_channel")
                    .setContentTitle("Disable Battery Optimization")
                    .setContentText("Exclude app from battery optimizations to avoid throttling during large transfers")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build()
                nm.notify(3, note)
            }
        } catch (t: Throwable) {
            Log.w("FtpForegroundService", "Runtime checks failed", t)
        }

        // 4. PERFORMANCE HINT (Late 2025):
        // Apache MINA uses internal executor threads. On 16GB/High-core devices,
        // the default thread pool is usually sufficient, but Ensure Binary mode is used by client.
    }

    private fun setupNotification() {
        val channelId = "ftp_channel"
        val channel =
            NotificationChannel(channelId, "High-Speed FTP", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FTP Server: High Performance Mode")
            .setContentText("Listening on port 2121")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        startForeground(
            1,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    /**
     * Watchdog that ensures the FTP server is running. If the server stops
     * unexpectedly, attempt a restart with an exponential-ish backoff to avoid
     * tight restart loops.
     */
    private fun startWatchdog(rootPath: String) {
        synchronized(restartLock) {
            if (watchdogRunning) return
            watchdogRunning = true
        }

        watchdogThread = Thread {
            while (watchdogRunning) {
                try {
                    val server = ftpServer
                    if (server == null || server.isStopped) {
                        val now = System.currentTimeMillis()
                        // simple backoff: if too many restarts in a short window, wait longer
                        if (now - lastRestartTime < 10_000) {
                            restartCount++
                        } else {
                            restartCount = 0
                        }
                        lastRestartTime = now

                        val backoff = (Math.min(restartCount, 6) * 2_000).toLong() // up to ~12s
                        Log.w(
                            "FtpForegroundService",
                            "FTP server stopped; attempting restart in ${backoff}ms (count=$restartCount)"
                        )
                        Thread.sleep(backoff)

                        // attempt restart
                        try {
                            // Re-create server from scratch by calling startFtpServer again.
                            // This is safe because startFtpServer creates a new serverFactory.
                            Log.i("FtpForegroundService", "Restarting FTP server...")
                            startFtpServer(rootPath)
                        } catch (t: Throwable) {
                            Log.e("FtpForegroundService", "Failed to restart FTP server", t)
                        }
                    }

                    // Sleep a bit between checks
                    Thread.sleep(5_000)
                } catch (ie: InterruptedException) {
                    // Thread interrupted; exit if watchdogRunning is false
                    if (!watchdogRunning) break
                } catch (t: Throwable) {
                    Log.w("FtpForegroundService", "Watchdog error", t)
                    try {
                        Thread.sleep(5_000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        watchdogThread?.name = "ftp-watchdog"
        watchdogThread?.isDaemon = true
        watchdogThread?.start()
    }

    override fun onDestroy() {
        // Stop watchdog first to avoid racing restarts
        try {
            watchdogRunning = false
            watchdogThread?.interrupt()
            watchdogThread = null
        } catch (t: Throwable) {
            Log.w("FtpForegroundService", "Failed to stop watchdog", t)
        }

        ftpServer?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
