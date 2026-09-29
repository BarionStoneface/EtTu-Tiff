package com.barion.filmscans

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Keep the screen on while converting, so the phone doesn't sleep mid-roll.
            LaunchedEffect(model.phase) {
                if (model.phase == Phase.Converting) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MaterialTheme(colorScheme = Colors) { App(model) }
        }
    }
}

private val Colors = darkColorScheme(
    primary = Color(0xFFE8A33D),
    onPrimary = Color(0xFF2A1A00),
    secondary = Color(0xFFD9C2A6),
    background = Color(0xFF1B1714),
    surface = Color(0xFF1B1714),
    surfaceVariant = Color(0xFF2B2520),
    surfaceContainer = Color(0xFF26211D),
    surfaceContainerHigh = Color(0xFF2E2823),
    error = Color(0xFFFF8A80),
)
