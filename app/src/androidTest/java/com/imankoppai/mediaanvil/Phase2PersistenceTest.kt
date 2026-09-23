package com.imankoppai.mediaanvil

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imankoppai.mediaanvil.data.PlayHistory
import com.imankoppai.mediaanvil.data.PlaybackPreferences
import com.imankoppai.mediaanvil.ui.LibraryQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the Phase 2 data contract against real on-device SharedPreferences, which
 * is what "restart and the data is still there" actually depends on. A fresh
 * [PlaybackPreferences] instance stands in for an app restart.
 */
@RunWith(AndroidJUnit4::class)
class Phase2PersistenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPlayerData()
    }

    @After
    fun tearDown() {
        clearPlayerData()
    }

    private fun clearPlayerData() {
        // Both stores have to go: the legacy file is what the migration reads, and the
        // DataStore holds what it wrote. Clear the legacy file first so constructing
        // the store below cannot migrate it, then clear the DataStore through its API
        // — the preferencesDataStore delegate caches one instance per Context, so
        // deleting the file behind its back would leave stale values in memory and
        // make these assertions depend on execution order.
        context.getSharedPreferences("mediaanvil_playback", Context.MODE_PRIVATE)
            .edit().clear().commit()
        com.imankoppai.mediaanvil.data.SettingsStore.clearForTests(context)
        com.imankoppai.mediaanvil.data.SettingsStore.resetForTests()
    }

    /**
     * What a relaunch sees. The store is a process-wide singleton and writes are
     * asynchronous, so this flushes and drops the singleton — otherwise it would
     * hand back the same in-memory map and prove nothing about persistence.
     */
    private fun afterRestart(): PlaybackPreferences {
        PlaybackPreferences(context).flushPendingWrites()
        com.imankoppai.mediaanvil.data.SettingsStore.resetForTests()
        return PlaybackPreferences(context)
    }

    @Test
    fun favoritesSurviveARestartInTheOrderTheyWereAdded() {
        val preferences = PlaybackPreferences(context)
        preferences.favoriteTrackUris = listOf("uri-c", "uri-a", "uri-b")

        assertEquals(listOf("uri-c", "uri-a", "uri-b"), afterRestart().favoriteTrackUris)
    }

    @Test
    fun groupsSurviveARestartAndKeepPlaybackOrder() {
        val preferences = PlaybackPreferences(context)
        preferences.trackGroups = listOf(
            com.imankoppai.mediaanvil.model.TrackGroup(
                id = "g1",
                name = "睡前",
                trackUris = listOf("uri-3", "uri-1", "uri-2"),
            ),
        )

        val restored = afterRestart().trackGroups
        assertEquals(1, restored.size)
        assertEquals("睡前", restored[0].name)
        // The stored order is the playback order, not library order.
        assertEquals(listOf("uri-3", "uri-1", "uri-2"), restored[0].trackUris)
    }

    @Test
    fun playHistorySurvivesARestartNewestFirst() {
        val preferences = PlaybackPreferences(context)
        preferences.recordPlayed("uri-1", playedAt = 100L)
        preferences.recordPlayed("uri-2", playedAt = 200L)
        preferences.recordPlayed("uri-1", playedAt = 300L)

        assertEquals(
            listOf("uri-1", "uri-2"),
            afterRestart().playHistory().map { it.uri },
        )
    }

    @Test
    fun aV102GroupStoredAsASetStillLoadsInItsOriginalOrder() {
        // V1.02 wrote trackUris as a JSON array that was read into a Set.
        val legacy = """[{"id":"old","name":"旧分组","trackUris":["uri-a","uri-b","uri-c"]}]"""
        context.getSharedPreferences("mediaanvil_playback", Context.MODE_PRIVATE)
            .edit().putString("track_groups", legacy).commit()

        val restored = afterRestart().trackGroups
        assertEquals(1, restored.size)
        assertEquals(listOf("uri-a", "uri-b", "uri-c"), restored[0].trackUris)
    }

    @Test
    fun missingAudioIsSkippedWithoutBreakingTheSavedOrder() {
        val library = listOf("uri-b", "uri-c")
        val favorites = listOf("uri-a", "uri-b", "uri-c")

        // uri-a was deleted or moved; the rest still play in saved order.
        val playable = LibraryQuery.resolve(library, { it }, favorites)
        assertEquals(listOf("uri-b", "uri-c"), playable)
        assertTrue(playable.none { it == "uri-a" })
    }

    @Test
    fun historyEntriesForMissingAudioDoNotBreakResume() {
        val preferences = PlaybackPreferences(context)
        preferences.recordPlayed("gone", playedAt = 100L)
        preferences.recordPlayed("here", playedAt = 200L)

        val entries = afterRestart().playHistory()
        assertEquals(listOf("here"), PlayHistory.availableUris(entries, setOf("here")))
    }
}
