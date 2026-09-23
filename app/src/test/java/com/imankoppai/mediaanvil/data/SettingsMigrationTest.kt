package com.imankoppai.mediaanvil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SharedPreferences to DataStore migration is the only step in V1.03 that can
 * destroy a user's playlists, favourites or playback positions, so its planning
 * step is covered directly: it must copy everything once, never twice, and never
 * overwrite data that is already live.
 */
class SettingsMigrationTest {

    private val legacy = mapOf<String, Any?>(
        "language" to "zh-CN",
        "auto_load_lyrics" to false,
        "seek_back_seconds" to 5,
        "seek_forward_seconds" to 30,
        "playback_speed" to 1.25f,
        "resume_playback" to true,
        "playback_positions" to """{"uri-1":3000}""",
        "track_groups" to """[{"id":"g1","name":"歌单","trackUris":["uri-1","uri-2"]}]""",
        "hidden_track_uris" to setOf("uri-9"),
        "favorite_track_uris" to """["uri-2","uri-1"]""",
        "play_history" to "uri-1\t1700000000000",
        "lyrics_offsets" to """{"uri-1":500}""",
        "custom_covers" to """{"uri-1":"content://cover/1"}""",
    )

    @Test
    fun `first run copies every legacy value and records the marker`() {
        val plan = SettingsMigration.plan(existing = emptyMap(), legacy = legacy)

        assertTrue(plan != null)
        legacy.forEach { (key, value) ->
            assertEquals("$key must survive the migration", value, plan!![key])
        }
        assertEquals(SettingsMigration.CURRENT_VERSION, plan!![SettingsMigration.MARKER_KEY])
    }

    @Test
    fun `running again is a no-op`() {
        val first = SettingsMigration.plan(existing = emptyMap(), legacy = legacy)!!
        // The second launch sees exactly what the first one wrote.
        val second = SettingsMigration.plan(existing = first, legacy = legacy)

        assertNull("a migrated store must not be migrated twice", second)
    }

    @Test
    fun `a live DataStore value is never overwritten by the legacy value`() {
        // The user changed the sort order after the DataStore write but before the
        // legacy read; the newer value must win.
        val plan = SettingsMigration.plan(
            existing = mapOf("library_sort" to "title"),
            legacy = legacy + ("library_sort" to "duration"),
        )!!

        assertFalse("an existing value must be kept", plan.containsKey("library_sort"))
        assertEquals("zh-CN", plan["language"])
    }

    @Test
    fun `a legacy marker is ignored so the marker cannot be spoofed`() {
        val plan = SettingsMigration.plan(
            existing = emptyMap(),
            legacy = mapOf(SettingsMigration.MARKER_KEY to 999, "language" to "en"),
        )!!

        assertEquals(SettingsMigration.CURRENT_VERSION, plan[SettingsMigration.MARKER_KEY])
        assertEquals("en", plan["language"])
    }

    @Test
    fun `an install with no legacy file still records the marker`() {
        val plan = SettingsMigration.plan(existing = emptyMap(), legacy = emptyMap())

        assertEquals(
            mapOf<String, Any?>(SettingsMigration.MARKER_KEY to SettingsMigration.CURRENT_VERSION),
            plan,
        )
    }

    @Test
    fun `null legacy values are skipped instead of clearing a key`() {
        val plan = SettingsMigration.plan(
            existing = emptyMap(),
            legacy = mapOf("language" to null, "library_sort" to "title"),
        )!!

        assertFalse(plan.containsKey("language"))
        assertEquals("title", plan["library_sort"])
    }

    @Test
    fun `a store written by a future version is not re-migrated`() {
        val plan = SettingsMigration.plan(
            existing = mapOf(SettingsMigration.MARKER_KEY to SettingsMigration.CURRENT_VERSION),
            legacy = legacy,
        )

        assertNull(plan)
    }
}
