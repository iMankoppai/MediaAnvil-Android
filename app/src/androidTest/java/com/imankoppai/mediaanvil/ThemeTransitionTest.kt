package com.imankoppai.mediaanvil

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import com.imankoppai.mediaanvil.ui.theme.LocalThemeIsDark
import com.imankoppai.mediaanvil.ui.theme.MediaAnvilTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

@Suppress("DEPRECATION")
class ThemeTransitionTest {
    @get:Rule val compose = DeviceComposeRule(createEmptyComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    }))

    private fun backgroundPixel(): Color = compose.onRoot().captureToImage().toPixelMap().let {
        it[it.width / 2, it.height / 2]
    }

    private fun assertNear(expected: Color, actual: Color, tolerance: Float = 0.03f) {
        assertEquals(expected.red, actual.red, tolerance)
        assertEquals(expected.green, actual.green, tolerance)
        assertEquals(expected.blue, actual.blue, tolerance)
    }

    @Test fun shortFadeDrawsIntermediateFramesWithoutRecomposingThePage() {
        var dark by mutableStateOf(false)
        var displayedDark = false
        var palette = Color.Unspecified
        var identity: Any? = null
        var compositions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MediaAnvilTheme(dark) {
                val pageIdentity = remember { Any() }
                val isDark = LocalThemeIsDark.current
                val colors = MaterialTheme.colorScheme
                SideEffect { displayedDark = isDark; palette = colors.background; identity = pageIdentity; compositions++ }
                Box(Modifier.fillMaxSize().background(colors.background)) { Text("Persistent page") }
            }
        }
        compose.waitForIdle()
        val lightFrame = backgroundPixel()
        val originalPage = identity
        compose.runOnIdle { dark = true }
        compose.waitUntil(10000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            displayedDark
        }
        val finalPalette = palette
        val initialCompositions = compositions
        compose.mainClock.advanceTimeBy(100)
        val middleFrame = backgroundPixel()
        assertTrue("Fade should draw a frame between the endpoints", middleFrame.red > finalPalette.red + 0.05f && middleFrame.red < lightFrame.red - 0.05f)
        compose.runOnIdle {
            assertEquals("The page palette stays fixed during the fade", finalPalette, palette)
            assertEquals("Animation must update drawing rather than compose the page every frame", initialCompositions, compositions)
            assertSame(originalPage, identity)
        }
        compose.mainClock.advanceTimeBy(250)
        assertNear(finalPalette, backgroundPixel())

        compose.runOnIdle { dark = false }
        compose.waitUntil(10000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            !displayedDark
        }
        compose.mainClock.advanceTimeBy(100)
        val beforeReverse = backgroundPixel()
        compose.runOnIdle { dark = true }
        compose.waitUntil(10000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            displayedDark
        }
        val afterReverse = backgroundPixel()
        assertTrue("Repeated taps must continue from the visible picture", abs(beforeReverse.red - afterReverse.red) < 0.08f)
        compose.mainClock.advanceTimeBy(350)
        assertNear(finalPalette, backgroundPixel())
        compose.runOnIdle { assertSame(originalPage, identity) }
    }

    @Test fun initiallyDarkPagesShowTheirFinalPaletteImmediately() {
        var palette = Color.Unspecified
        var displayedDark = false
        compose.mainClock.autoAdvance = false
        compose.setContent { MediaAnvilTheme(true) {
            val colors = MaterialTheme.colorScheme
            val isDark = LocalThemeIsDark.current
            SideEffect { palette = colors.background; displayedDark = isDark }
            Box(Modifier.fillMaxSize().background(colors.background)) { Text("Dark from first frame") }
        } }
        compose.waitForIdle()
        assertTrue(displayedDark)
        assertNear(palette, backgroundPixel())
    }
}
