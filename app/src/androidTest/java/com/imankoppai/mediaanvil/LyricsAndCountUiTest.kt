package com.imankoppai.mediaanvil

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.subtitles.*
import com.imankoppai.mediaanvil.ui.LocalFullPlayCounts
import com.imankoppai.mediaanvil.ui.LyricsCandidatePreview
import com.imankoppai.mediaanvil.ui.TrackRow
import com.imankoppai.mediaanvil.ui.theme.MediaAnvilTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LyricsAndCountUiTest {
    @get:Rule val compose = DeviceComposeRule()

    private fun snapshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        java.io.File(context.cacheDir, name).outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    @Test fun previewSwitchesFromOriginalAndSavesExactlyTheDisplayedScript() {
        val original = "[ti:原標題]\n[00:01.20]聽見風聲 Hello\n[00:03.45]頭髮與風景\n"
        val candidate = OnlineLyricsCandidate(1, "Test title", "Test artist", "", 10, original)
        var selected by mutableStateOf(LyricsScript.Original)
        var saved: String? = null
        compose.setContent { MediaAnvilTheme(false) { Column(Modifier.fillMaxSize()) {
            LyricsCandidatePreview(candidate, selected, { selected = it }, false, null, {}, {}, { saved = it })
        } } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("聽見風聲 Hello").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("lyrics_script_simplified").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("听见风声 Hello").fetchSemanticsNodes().isNotEmpty() }
        snapshot("v1.11-lyrics-preview.png")
        compose.onNodeWithTag("lyrics_preview_save").performClick()
        assertEquals("[ti:原標題]\n[00:01.20]听见风声 Hello\n[00:03.45]头发与风景\n", saved)
        compose.onNodeWithTag("lyrics_script_traditional").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("聽見風聲 Hello").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("lyrics_script_original").performClick()
        compose.waitUntil(10000) { compose.onNodeWithTag("lyrics_script_original").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Selected] }
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("lyrics_preview_save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("lyrics_preview_save").performClick()
        assertEquals(original, saved)
    }

    @Test fun countBadgeAppearsOnTheRightOnlyWhenThereAreCompletePlays() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val track = AudioTrack(Uri.parse("content://test/song"), "test.mp3", "Test track", null, null, 10000, null, null)
        var counts by mutableStateOf<Map<String, Long>>(emptyMap())
        compose.setContent { MediaAnvilTheme(false) {
            CompositionLocalProvider(LocalFullPlayCounts provides counts) {
                TrackRow(track, false, {}, onEdit = {})
            }
        } }
        val description = context.resources.getQuantityString(R.plurals.full_play_count_description, 3, 3L)
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
        compose.runOnIdle { counts = mapOf(track.uri.toString() to 3L) }
        val badge = compose.onNodeWithContentDescription(description, useUnmergedTree = true).assertExists().fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Test track", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The badge must sit to the right of the track title: badge=$badge title=$title", badge.left >= title.right)
        snapshot("v1.11-count-badge.png")
    }
}
