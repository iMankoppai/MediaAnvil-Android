package com.imankoppai.mediaanvil

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imankoppai.mediaanvil.data.*
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.model.TrackGroup
import com.imankoppai.mediaanvil.ui.LibraryQuery
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningUpgradeTest {
    private lateinit var context: Context
    @Before fun before() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mediaanvil_playback", Context.MODE_PRIVATE).edit().clear().commit()
        SettingsStore.clearForTests(context)
        SettingsStore.resetForTests()
    }
    @After fun after() { SettingsStore.clearForTests(context); SettingsStore.resetForTests() }

    private fun track(id: String, folder: String = "Books/") = AudioTrack(Uri.parse(id), "episode2.mp3", "Episode 2", "Author", "Series", 600000, null, null, folder, 123456)

    @Test fun completeBackupRemapsProgressQueueBookmarksAndAllAssociations() {
        val prefs = PlaybackPreferences(context)
        prefs.trackGroups = listOf(TrackGroup("g", "Series", listOf("old")))
        prefs.favoriteTrackUris = listOf("old")
        prefs.hiddenTrackUris = setOf("old")
        prefs.recordPlayed("old", 100)
        prefs.savePlaybackSnapshot("old", 100000, listOf("old"), 0, true)
        prefs.bookmarksRaw = Bookmarks.encode(listOf(AudioBookmark("b", "old", 45000, "第三章", 100, 90000)))
        prefs.setLyricsOffset(Uri.parse("old"), 500)
        prefs.setCustomCover(Uri.parse("old"), Uri.parse("content://covers/1"))
        prefs.resumeRewindSeconds = 5
        val root = PlayerDataBackup.createBackup(prefs, listOf(track("old")))
        assertEquals(5, root.getInt("schemaVersion"))
        val source = BackupTrackReference("old", "episode2.mp3", "Books/", 600000, 123456)
        val match = BackupTrackMatcher.match(source, listOf(source.copy(uri = "new", relativeFolder = "Moved/")))
        val plan = PlayerDataBackup.RestorePlan(root, listOf(match), 1, false)
        PlayerDataBackup.restore(plan, emptyMap(), prefs)
        prefs.flushPendingWrites()
        SettingsStore.resetForTests()
        val restored = PlaybackPreferences(context)
        assertEquals(listOf("new"), restored.trackGroups.single().trackUris)
        assertEquals(listOf("new"), restored.favoriteTrackUris)
        assertEquals(setOf("new"), restored.hiddenTrackUris)
        assertEquals("new", restored.playHistory().single().uri)
        assertEquals(100000L, restored.playbackPositionFor("new"))
        assertEquals("new", JSONArray(restored.lastQueueUris).getString(0))
        assertEquals(0, restored.lastQueueIndex)
        assertEquals("new", Bookmarks.decode(restored.bookmarksRaw).single().trackUri)
        assertEquals(90000L, Bookmarks.decode(restored.bookmarksRaw).single().endPositionMs)
        assertEquals(500L, restored.lyricsOffsetFor(Uri.parse("new")))
        assertEquals(5, restored.resumeRewindSeconds)
    }

    @Test fun unmatchedRecordsRemainRecoverableWithoutRowIdCollision() {
        val prefs = PlaybackPreferences(context)
        prefs.favoriteTrackUris = listOf("old")
        prefs.setPlaybackPosition("old", 5000)
        val root = PlayerDataBackup.createBackup(prefs, listOf(track("old")))
        val ref = BackupTrackReference("old", "episode2.mp3", "Books/", 600000, 123456)
        PlayerDataBackup.restore(PlayerDataBackup.RestorePlan(root, listOf(BackupTrackMatcher.Match(ref, emptyList())), 0, false), emptyMap(), prefs)
        val missing = BackupTrackMatcher.missingUri("old")
        assertEquals(listOf(missing), prefs.favoriteTrackUris)
        val reexported = PlayerDataBackup.createBackup(prefs, emptyList())
        val retained = reexported.getJSONArray("trackReferences").getJSONObject(0)
        assertEquals("episode2.mp3", retained.getString("fileName"))
        val repaired = BackupTrackMatcher.match(ref.copy(uri = missing), listOf(ref.copy(uri = "new")))
        PlayerDataBackup.restore(PlayerDataBackup.RestorePlan(reexported, listOf(repaired), 0, false), emptyMap(), prefs)
        assertEquals(5000L, prefs.playbackPositionFor("new"))
    }

    @Test fun oldBackupDoesNotEraseNewerListeningData() {
        val prefs = PlaybackPreferences(context)
        prefs.setPlaybackPosition("current", 5000)
        prefs.bookmarksRaw = Bookmarks.encode(listOf(AudioBookmark("b", "current", 2000, "note", 100)))
        prefs.resumeRewindSeconds = 5
        val old = JSONObject().put("schemaVersion", 1).put("settings", JSONObject()).put("groups", JSONArray())
        prefs.restoreFromBackup(PlayerDataBackup.validate(old))
        assertEquals(5000L, prefs.playbackPositionFor("current"))
        assertEquals(1, Bookmarks.decode(prefs.bookmarksRaw).size)
        assertEquals(5, prefs.resumeRewindSeconds)
    }

    @Test fun futureBackupIsRejectedBeforeAnyWrites() {
        val prefs = PlaybackPreferences(context)
        prefs.favoriteTrackUris = listOf("keep")
        val invalid = JSONObject().put("schemaVersion", 999).put("settings", JSONObject()).put("groups", JSONArray())
        assertTrue(runCatching { prefs.restoreFromBackup(PlayerDataBackup.validate(invalid)) }.isFailure)
        assertEquals(listOf("keep"), prefs.favoriteTrackUris)
    }

    @Test fun preparedSearchMatchesAllTermsAndSupportsCancellation() {
        val tracks = listOf(track("1"), track("2").copy(title = "Other", artist = "Else", album = "Else", fileName = "other.mp3"))
        val index = LibraryQuery.Index(tracks)
        assertEquals(listOf("1"), index.search("AUTHOR series 2", "fileName").map { it.uri.toString() })
        assertTrue(index.search("author missing", "fileName").isEmpty())
        assertTrue(runCatching { index.search("", "title") { throw CancellationException() } }.exceptionOrNull() is CancellationException)
    }

    @Test fun explicitAmbiguousChoiceRemapsAllReferencesAndRejectsInvalidChoice() {
        val prefs = PlaybackPreferences(context)
        prefs.favoriteTrackUris = listOf("old")
        val source = BackupTrackReference("old", "episode2.mp3", "Books/", 600000, 123456)
        val match = BackupTrackMatcher.match(source, listOf(source.copy(uri = "a"), source.copy(uri = "b")))
        val plan = PlayerDataBackup.RestorePlan(PlayerDataBackup.createBackup(prefs, listOf(track("old"))), listOf(match), 0, false)
        assertTrue(runCatching { PlayerDataBackup.restore(plan, mapOf("old" to "invalid"), prefs) }.isFailure)
        assertEquals(listOf("old"), prefs.favoriteTrackUris)
        PlayerDataBackup.restore(plan, mapOf("old" to "b"), prefs)
        assertEquals(listOf("b"), prefs.favoriteTrackUris)
    }

    @Test fun finishedStatusRestoresAndReplayingMakesItInProgressAgain() {
        val prefs = PlaybackPreferences(context)
        prefs.markFinished("old", true)
        val source = BackupTrackReference("old", "episode2.mp3", "Books/", 600000, 123456)
        val plan = PlayerDataBackup.RestorePlan(PlayerDataBackup.createBackup(prefs, listOf(track("old"))),
            listOf(BackupTrackMatcher.match(source, listOf(source.copy(uri = "new")))), 0, false)
        PlayerDataBackup.restore(plan, emptyMap(), prefs)
        assertEquals(setOf("new"), prefs.finishedTrackUris)
        prefs.savePlaybackSnapshot("new", 10000, listOf("new"), 0, true)
        assertTrue(prefs.finishedTrackUris.isEmpty())
        assertEquals(10000L, prefs.playbackPositionFor("new"))
    }

    @Test fun backupFileReadsUnicodeAndRejectsMalformedInputWithoutChangingData() {
        val prefs = PlaybackPreferences(context)
        prefs.bookmarksRaw = Bookmarks.encode(listOf(AudioBookmark("b", "old", 1000, "第三章", 100)))
        val file = java.io.File(context.cacheDir, "listening-backup-test.json")
        try {
            file.writeText(PlayerDataBackup.createBackup(prefs, listOf(track("old"))).toString(), Charsets.UTF_8)
            assertEquals(prefs.bookmarksRaw, PlayerDataBackup.read(context, Uri.fromFile(file)).getString("bookmarks"))
            file.writeText("{truncated", Charsets.UTF_8)
            assertTrue(runCatching { PlayerDataBackup.read(context, Uri.fromFile(file)) }.isFailure)
            assertEquals("第三章", Bookmarks.decode(prefs.bookmarksRaw).single().note)
        } finally { file.delete() }
    }

    @Test fun preparedSearchPerformanceOver10000Tracks() {
        val tracks = List(10000) { track("content://media/external/audio/media/$it").copy(title = "第${it}集", fileName = "episode$it.mp3") }
        val index = LibraryQuery.Index(tracks)
        val samples = LongArray(15) {
            val started = System.nanoTime()
            assertEquals(10000, index.search("Author Series episode", "fileName").size)
            (System.nanoTime() - started) / 1000
        }.sorted()
        java.io.File(context.getExternalFilesDir(null), "upgrade-perf.txt").writeText("10000 prepared search median=${samples[7]}us p95=${samples[14]}us\n")
    }
}
