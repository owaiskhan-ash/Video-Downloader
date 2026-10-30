package com.teal.downloader

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.teal.downloader.ui.MainViewModel
import com.teal.downloader.ui.TealAppRoot
import com.teal.downloader.ui.theme.TealTheme

/**
 * The only activity. Navigation, theming and all screens are Compose.
 *
 * It also owns the two ways a link can arrive from outside: ACTION_SEND (share
 * sheet) and ACTION_VIEW (opening an http(s) URL with Teal).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* denied is fine */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIncoming(intent)
        askForNotificationPermission()
        setContent {
            TealTheme {
                TealAppRoot(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND ->
                if (intent.type == "text/plain") {
                    IncomingLink.publish(intent.getStringExtra(Intent.EXTRA_TEXT))
                }
            Intent.ACTION_VIEW -> IncomingLink.publish(intent.dataString)
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
