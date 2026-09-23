package com.imankoppai.mediaanvil

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imankoppai.mediaanvil.data.PlaybackPreferences
import com.imankoppai.mediaanvil.data.SettingsStore
import com.imankoppai.mediaanvil.model.TrackGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Phase 4 storage migration against the real DataStore on a device: a V1.02
 * SharedPreferences file must be copied over once, survive a restart, and never be
 * copied twice or lose an entry. This is the one change in V1.03 that can destroy a
 * user's playlists, favourites or playback positions, so it is asserted end to end
 * rather than only at the planning level covered by the JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class Phase4MigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearEverything()
    }

    @After
    fun tearDown() {
        clearEverything()
    }

    private fun clearEverything() {
        SettingsStore.resetForTests()
        context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit().clear().commit()
        SettingsStore.dataStoreFile(context).delete()
        SettingsStore.resetForTests()
    }

    /** Writes a legacy file exactly as V1.02 would have left it. */
    private fun writeLegacyV102Data() {
        context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit()
            .putString("language", "zh-CN")
            .putInt("seek_back_seconds", 10)
            .putInt("seek_forward_seconds", 45)
            .putBoolean("auto_load_lyrics", false)
            .putFloat("playback_speed", 1.5f)
            .putString("library_sort", "title")
            .putString("theme_mode", "dark")
            .putString("track_groups", GROUPS_JSON)
            .putString("favorite_track_uris", """["uri-fav-1","uri-fav-2"]""")
            .putString("play_history", "uri-fav-2\t1700000002000\nuri-fav-1\t1700000001000")
            .putString("playback_positions", """{"uri-fav-1":42000}""")
            .putString("lyrics_offsets", """{"uri-fav-1":750}""")
            .putString("custom_covers", """{"uri-fav-1":"content://cover/1"}""")
            .putStringSet("hidden_track_uris", setOf("uri-hidden"))
            .putStringSet("scan_folders", setOf("Music/live/"))
            .commit()
    }

    @Test
    fun everyLegacyValueIsMigratedAndSurvivesARestart() {
        writeLegacyV102Data()

        val first = PlaybackPreferences(context)
        assertEquals("zh-CN", first.language)
        assertEquals(10, first.seekBackSeconds)
        assertEquals(45, first.seekForwardSeconds)
        assertEquals(false, first.autoLoadLyrics)
        assertEquals(1.5f, first.playbackSpeed, 0.001f)
        assertEquals("title", first.librarySort)
        assertEquals("dark", first.themeMode)
        assertEquals(listOf("uri-fav-1", "uri-fav-2"), first.favoriteTrackUris)
        assertEquals(setOf("uri-hidden"), first.hiddenTrackUris)
        assertEquals(setOf("Music/live/"), first.scanFolders)
        assertEquals(42000L, first.playbackPositionFor("uri-fav-1"))
        assertEquals(750L, first.lyricsOffsetFor(android.net.Uri.parse("uri-fav-1")))
        assertEquals(
            android.net.Uri.parse("content://cover/1"),
            first.customCoverFor(android.net.Uri.parse("uri-fav-1")),
        )
        assertEquals(listOf("uri-fav-2", "uri-fav-1"), first.playHistory().map { it.uri })

        val group = first.trackGroups.single()
        assertEquals("g1", group.id)
        assertEquals("睡前", group.name)
        // Saved order is playback order and must not be re-sorted by the migration.
        assertEquals(listOf("uri-3", "uri-1", "uri-2"), group.trackUris)

        // A second construction is what the next launch sees.
        SettingsStore.resetForTests()
        val afterRestart = PlaybackPreferences(context)
        assertEquals(listOf("uri-fav-1", "uri-fav-2"), afterRestart.favoriteTrackUris)
        assertEquals(listOf("uri-3", "uri-1", "uri-2"), afterRestart.trackGroups.single().trackUris)
        assertEquals(42000L, afterRestart.playbackPositionFor("uri-fav-1"))
        assertEquals(listOf("uri-fav-2", "uri-fav-1"), afterRestart.playHistory().map { it.uri })
    }

    @Test
    fun aSecondLaunchDoesNotMigrateAgainOrRevertLaterEdits() {
        writeLegacyV102Data()
        PlaybackPreferences(context).let { first ->
            // The user changes a migrated setting after the move.
            first.librarySort = "duration"
            first.favoriteTrackUris = listOf("uri-new")
            first.flushPendingWrites()
        }

        SettingsStore.resetForTests()
        val afterRestart = PlaybackPreferences(context)

        // If the migration re-ran it would copy the legacy values back over these.
        assertEquals("duration", afterRestart.librarySort)
        assertEquals(listOf("uri-new"), afterRestart.favoriteTrackUris)
    }

    @Test
    fun theLegacyFileIsLeftInPlaceSoTheMigrationStaysRecoverable() {
        writeLegacyV102Data()
        PlaybackPreferences(context).language

        val legacy = context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE)
        assertTrue("the V1.02 file must not be deleted", legacy.contains("track_groups"))
        assertEquals(GROUPS_JSON, legacy.getString("track_groups", null))
    }

    @Test
    fun anEmptyInstallStillRecordsTheMarkerAndStartsClean() {
        val preferences = PlaybackPreferences(context)

        assertEquals("", preferences.language)
        assertTrue(preferences.favoriteTrackUris.isEmpty())
        assertTrue(preferences.trackGroups.isEmpty())
        // Defaults are still the documented ones.
        assertEquals(5, preferences.seekBackSeconds)
        assertEquals(30, preferences.seekForwardSeconds)
        assertTrue(preferences.autoLoadLyrics)
        assertTrue(preferences.resumePlayback)

        SettingsStore.resetForTests()
        assertTrue(PlaybackPreferences(context).favoriteTrackUris.isEmpty())
    }

    @Test
    fun aWriteMadeBeforeARestartIsOnDisk() {
        val preferences = PlaybackPreferences(context)
        preferences.trackGroups = listOf(
            TrackGroup(id = "g9", name = "通勤", trackUris = listOf("uri-b", "uri-a")),
        )
        preferences.flushPendingWrites()

        SettingsStore.resetForTests()
        val restored = PlaybackPreferences(context).trackGroups.single()
        assertEquals("通勤", restored.name)
        assertEquals(listOf("uri-b", "uri-a"), restored.trackUris)
    }

    @Test
    fun clearingFavouritesPersistsAsAnEmptyList() {
        val preferences = PlaybackPreferences(context)
        preferences.favoriteTrackUris = listOf("uri-1", "uri-2")
        preferences.flushPendingWrites()
        preferences.favoriteTrackUris = emptyList()
        preferences.flushPendingWrites()

        SettingsStore.resetForTests()
        assertTrue(PlaybackPreferences(context).favoriteTrackUris.isEmpty())
        assertFalse(PlaybackPreferences(context).favoriteTrackUris.contains("uri-1"))
    }

    private companion object {
        const val LEGACY = "mediaanvil_playback"
        const val GROUPS_JSON =
            """[{"id":"g1","name":"睡前","trackUris":["uri-3","uri-1","uri-2"]}]"""
    }
}
