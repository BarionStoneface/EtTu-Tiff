package com.barion.filmscans

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

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
                if (model.phase == Phase.Converting || model.phase == Phase.Unzipping) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MaterialTheme(colorScheme = theme.colors(this)) { App(model) }
        }
    }
}
