package com.airlift

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Enforced in API 36

        setContent {
            MaterialTheme {
                FtpServerScreen(
                    onStartService = { startFtpService() },
                    onStopService = { stopService(Intent(this, FtpForegroundService::class.java)) }
                )
            }
        }
    }

    private fun startFtpService() {
        val intent = Intent(this, FtpForegroundService::class.java).apply {
            putExtra("ROOT_PATH", Environment.getExternalStorageDirectory().absolutePath)
        }
        startForegroundService(intent)
    }
}

@Composable
fun FtpServerScreen(onStartService: () -> Unit, onStopService: () -> Unit) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(false) }
    val ipAddress = remember { getLocalIpAddress(context) }

    Scaffold(
        modifier = Modifier.fillMaxSize().systemBarsPadding()
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Connect To: ftp://$ipAddress:2121\nUser: android | Pass: password",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Status: ${if (isRunning) "ACTIVE" else "STOPPED"}",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(Modifier.height(24.dp))

            Button(
                modifier = Modifier.fillMaxWidth(0.7f),
                onClick = {
                    if (isRunning) {
                        onStopService()
                        isRunning = false
                    } else {
                        // 2025 Permission Logic for All Files Access
                        if (hasAllFilesPermission()) {
                            onStartService()
                            isRunning = true
                        } else {
                            requestAllFilesPermission(context)
                        }
                    }
                }
            ) {
                Text(if (isRunning) "Stop FTP Server" else "Start FTP Server")
            }
        }
    }
}

/**
 * 2025 Modern IP Detection using ConnectivityManager
 */
fun getLocalIpAddress(context: Context): String {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val linkProps = cm.getLinkProperties(cm.activeNetwork)
        val ipAddr = linkProps?.linkAddresses?.find {
            it.address is java.net.Inet4Address
        }?.address?.hostAddress
        ipAddr ?: "127.0.0.1"
    } catch (e: Exception) {
        "Unavailable"
    }
}

/**
 * Check for MANAGE_EXTERNAL_STORAGE (API 30-36)
 */
fun hasAllFilesPermission(): Boolean {
    return Environment.isExternalStorageManager()
}

/**
 * Open System Settings for All Files Access
 */
fun requestAllFilesPermission(context: Context) {
    Toast.makeText(context, "Please enable 'All files access' for this app", Toast.LENGTH_LONG).show()
    try {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = "package:${context.packageName}".toUri()
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        context.startActivity(intent)
    }
}
