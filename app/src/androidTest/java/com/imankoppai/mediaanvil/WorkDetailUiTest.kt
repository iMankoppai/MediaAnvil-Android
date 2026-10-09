package com.imankoppai.mediaanvil

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.FileInputStream
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.imankoppai.mediaanvil.data.*
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.ui.*
import com.imankoppai.mediaanvil.ui.theme.MediaAnvilTheme
import org.junit.*
import org.junit.Assert.*

@Suppress("DEPRECATION")
class WorkDetailUiTest {
    @get:Rule val compose = DeviceComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val models = ViewModelStore()
    @Before fun before() {
        context.getSharedPreferences("mediaanvil_playback", Context.MODE_PRIVATE).edit().clear().commit()
        SettingsStore.clearForTests(context); SettingsStore.resetForTests()
    }
    @After fun after() {
        compose.runOnIdle { models.clear() }
        PlaybackPreferences(context).flushPendingWrites()
        SettingsStore.clearForTests(context); SettingsStore.resetForTests()
    }
    @Test fun aThousandEpisodesScrollAndBulkSpeedAndCompletionWork() {
        val prefs = PlaybackPreferences(context)
        val app = context.applicationContext as Application
        val library = LibraryViewModel(app).also { models.put("library", it) }
        val playlists = PlaylistViewModel(app).also { models.put("playlists", it) }
        val settings = SettingsViewModel(app).also { models.put("settings", it) }
        val tracks = List(1000) { index -> AudioTrack(Uri.parse("content://media/external_primary/audio/media/${990000 + index}"),
            "episode${index + 1}.wav", "Episode ${index + 1}", null, null, 60000, null, null, "Books/UITest/") }
        compose.setContent { MediaAnvilTheme(false) { FolderBrowser(tracks, library, playlists, settings, null, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Books/UITest").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Books/UITest").performClick()
        compose.onNodeWithText(context.getString(R.string.work_preferences)).performClick()
        compose.onNodeWithText("0.5\u00D7").performClick()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        compose.runOnIdle { assertEquals(0.5f, prefs.folderSpeed("external_primary|Books/UITest")) }
        compose.onNode(hasScrollAction()).performScrollToIndex(999)
        compose.onNodeWithText("Episode 1000").assertIsDisplayed().performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.selection_select_all)).performClick()
        compose.onNodeWithText(context.getString(R.string.batch_speed)).performClick()
        compose.onNodeWithText("1.25\u00D7").performClick()
        compose.runOnIdle { assertEquals(1.25f, prefs.speedFor(tracks[0].uri.toString(), "external_primary|Books/UITest")) }
        compose.onNodeWithText(context.getString(R.string.mark_finished)).performClick()
        compose.waitUntil(20000) { prefs.finishedTrackUris.size == 1000 }
        compose.waitUntil(20000) { compose.onAllNodesWithText(context.getString(R.string.work_progress, 1000, 1000, 100)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.work_progress, 1000, 1000, 100)).assertIsDisplayed()
    }
}
