package com.barion.filmscans

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect

class MainActivity : ComponentActivity() {
    private val model: AppModel get() = (application as EtTuTiffApp).model

    // Asked once, the first time a long job starts, so its progress can show while you're in other apps.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotifications", false)) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme = model.theme
            // Status bar icons follow the app's theme, not the phone's dark mode.
            LaunchedEffect(theme) {
                val bars = if (theme.dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            // Keep the screen on while converting, so the phone doesn't sleep mid-roll.
            LaunchedEffect(model.phase) {
                val busy = model.phase == Phase.Converting || model.phase == Phase.Unzipping
                if (busy) { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); maybeAskNotifications() }
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MaterialTheme(colorScheme = theme.colors(this)) { App(model) }
        }
    }
}
