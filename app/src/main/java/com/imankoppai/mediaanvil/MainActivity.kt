package com.imankoppai.mediaanvil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.view.WindowCompat
import com.imankoppai.mediaanvil.data.PlaybackPreferences
import com.imankoppai.mediaanvil.ui.MediaAnvilApp
import com.imankoppai.mediaanvil.ui.theme.MediaAnvilTheme
import com.imankoppai.mediaanvil.ui.theme.ThemeController
import com.imankoppai.mediaanvil.ui.theme.LocalThemeIsDark
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toDrawable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var settingsReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val preferences = withContext(Dispatchers.IO) { PlaybackPreferences(applicationContext) }
                ThemeController.load(preferences)
                settingsReady = true
            }
            val darkTheme = when (ThemeController.mode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            // The loading theme is separate so saved dark mode starts at its final
            // palette, while subsequent user/system theme changes animate in place.
            key(settingsReady) {
                MediaAnvilTheme(darkTheme = darkTheme) {
                    val displayedDark = LocalThemeIsDark.current
                    val background = MaterialTheme.colorScheme.background
                    val windowBackground = remember { background.toArgb().toDrawable() }
                    SideEffect {
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !displayedDark
                            isAppearanceLightNavigationBars = !displayedDark
                        }
                        windowBackground.color = background.toArgb()
                        window.setBackgroundDrawable(windowBackground)
                    }
                    if (settingsReady) MediaAnvilApp()
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}
