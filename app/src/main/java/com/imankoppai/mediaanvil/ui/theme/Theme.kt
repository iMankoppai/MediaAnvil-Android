package com.imankoppai.mediaanvil.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

// Desktop visual language: light blue canvas, white rounded cards, #1677FF accent.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1677FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E8FF),
    onPrimaryContainer = Color(0xFF082B62),
    secondary = Color(0xFF43536D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EAF5),
    onSecondaryContainer = Color(0xFF1B2A44),
    tertiary = Color(0xFF22B07D),
    background = Color(0xFFF4F8FF),
    onBackground = Color(0xFF16233B),
    surface = Color.White,
    onSurface = Color(0xFF16233B),
    surfaceVariant = Color(0xFFE9F0FA),
    onSurfaceVariant = Color(0xFF5A6B85),
    outlineVariant = Color(0xFFDCE6F4),
    error = Color(0xFFD4383E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FB1FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF134A85),
    onPrimaryContainer = Color(0xFFD5E6FF),
    secondary = Color(0xFFAFC4E3),
    onSecondary = Color(0xFF1B2A44),
    secondaryContainer = Color(0xFF2D3F5A),
    onSecondaryContainer = Color(0xFFDCE6F8),
    tertiary = Color(0xFF5BD3A2),
    background = Color(0xFF0F1725),
    onBackground = Color(0xFFDCE5F2),
    surface = Color(0xFF162135),
    onSurface = Color(0xFFDCE5F2),
    surfaceVariant = Color(0xFF22304A),
    onSurfaceVariant = Color(0xFF96A7C2),
    outlineVariant = Color(0xFF2C3B55),
    error = Color(0xFFFF8B90),
)

/** Observable mirror of the persisted theme prefs so toggles apply without recreation. */
object ThemeController {
    var mode by mutableStateOf("")

    fun load(preferences: com.imankoppai.mediaanvil.data.PlaybackPreferences) {
        mode = preferences.themeMode
    }
}

internal const val ThemeTransitionMillis = 300
internal val LocalThemeIsDark = compositionLocalOf { false }

@Composable
fun MediaAnvilTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    var displayedDark by remember { mutableStateOf(darkTheme) }
    var previousFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val opacity = remember { Animatable(0f) }
    val frame = rememberGraphicsLayer()

    LaunchedEffect(darkTheme) {
        if (darkTheme == displayedDark && previousFrame == null) return@LaunchedEffect
        // Capture the visible composite, including a running fade, before changing
        // the palette. Repeated taps therefore continue from the current picture.
        val snapshot = if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f ||
            frame.size.width == 0 || frame.size.height == 0) null
        else try { frame.toImageBitmap() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } // A failed capture must not prevent a theme change.
        coroutineContext.ensureActive()
        opacity.snapTo(1f)
        previousFrame = snapshot
        displayedDark = darkTheme
        if (snapshot != null) opacity.animateTo(0f, tween(ThemeTransitionMillis, easing = FastOutSlowInEasing))
        previousFrame = null
    }

    val colors = if (displayedDark) DarkColors else LightColors
    CompositionLocalProvider(LocalThemeIsDark provides displayedDark) {
        MaterialTheme(colorScheme = colors) {
            Box(Modifier.drawWithContent {
                frame.record {
                    drawRect(colors.background)
                    this@drawWithContent.drawContent()
                    // Only the drawing layer reads the animation clock: page content
                    // keeps its final palette and is not recomposed on every frame.
                    previousFrame?.let { drawImage(it, alpha = opacity.value) }
                }
                drawLayer(frame)
            }) { content() }
        }
    }
}
