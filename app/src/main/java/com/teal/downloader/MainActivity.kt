package com.teal.downloader

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.teal.downloader.ui.MainViewModel
import com.teal.downloader.ui.navigation.TealAppRoot
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
        // Dark-only app. enableEdgeToEdge()'s default SystemBarStyle.auto follows
        // the *system* light/dark setting, so on a device set to light mode it
        // would ask for dark system-bar icons on this app's dark bars and leave
        // them unreadable. Force dark, which means light icons. The navigation
        // bar scrim is the app background, for the API levels that cannot make
        // the bar transparent.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(
                ContextCompat.getColor(this, R.color.teal_background),
            ),
        )
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
