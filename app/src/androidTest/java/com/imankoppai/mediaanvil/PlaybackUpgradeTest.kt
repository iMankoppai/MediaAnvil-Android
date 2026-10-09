package com.imankoppai.mediaanvil

import android.content.*
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.media3.common.*
import androidx.media3.session.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.imankoppai.mediaanvil.data.*
import com.imankoppai.mediaanvil.playback.PlaybackService
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackUpgradeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    @Before fun before() {
        main { context.stopService(Intent(context, PlaybackService::class.java)) }
        PlaybackPreferences(context).flushPendingWrites()
        context.getSharedPreferences("mediaanvil_playback", Context.MODE_PRIVATE).edit().clear().commit()
        SettingsStore.clearForTests(context); SettingsStore.resetForTests()
    }
    @After fun after() {
        main { context.stopService(Intent(context, PlaybackService::class.java)) }
        PlaybackPreferences(context).flushPendingWrites()
        SettingsStore.clearForTests(context); SettingsStore.resetForTests()
    }
    @Test fun legacyPositionsMigrateAndPeriodicSavesLeaveQueueAlone() {
        context.getSharedPreferences("mediaanvil_playback", Context.MODE_PRIVATE).edit()
            .putString("playback_positions", """{"old":12345}""").commit()
        val prefs = PlaybackPreferences(context)
        assertEquals(12345L, prefs.playbackPositionFor("old"))
        prefs.savePlaybackSnapshot("current", 5000, listOf("old", "current"), 1, true)
        val queue = prefs.lastQueueUris
        val changes = mutableListOf<String>()
        val listener: (String) -> Unit = { changes += it }
        prefs.registerChangeListener(listener)
        repeat(100) { prefs.savePlaybackSnapshot("current", 6000L + it, null, 1, false) }
        prefs.unregisterChangeListener(listener)
        assertTrue(changes.none { it == "last_queue_uris" || it == "playback_positions" })
        prefs.flushPendingWrites(); SettingsStore.resetForTests()
        val restored = PlaybackPreferences(context)
        assertEquals(queue, restored.lastQueueUris)
        assertEquals(6099L, restored.playbackPositionFor("current"))
        assertEquals(12345L, JSONObject(restored.playbackPositions).getLong("old"))
    }
    @Test fun batchSpeedsAndWorkDefaultsSurviveRestartAndBackupRemapsIndividualChoices() {
        val prefs = PlaybackPreferences(context)
        prefs.setWorkPlayback("external_primary|Books", 1.25f, 5, "shuffle")
        prefs.setTrackSpeeds(listOf("a", "b"), 0.5f)
        prefs.flushPendingWrites(); SettingsStore.resetForTests()
        val restored = PlaybackPreferences(context)
        assertEquals(0.5f, restored.speedFor("a", "external_primary|Books"))
        assertEquals(1.25f, restored.speedFor("new", "external_primary|Books"))
        assertEquals(5, restored.rewindFor("external_primary|Books"))
        assertEquals("shuffle", restored.orderFor("external_primary|Books"))
        restored.setTrackSpeeds(listOf("a"), null)
        assertEquals(1.25f, restored.speedFor("a", "external_primary|Books"))
        val track = com.imankoppai.mediaanvil.model.AudioTrack(Uri.parse("b"), "ep.wav", "Episode", null, null, 60000, null, null, "Books/")
        val root = PlayerDataBackup.createBackup(restored, listOf(track))
        val ref = BackupTrackReference("b", "ep.wav", "Books/", 60000)
        val plan = PlayerDataBackup.RestorePlan(root, listOf(BackupTrackMatcher.Match(ref, listOf(ref.copy(uri = "new")))), 0, false)
        PlayerDataBackup.restore(plan, emptyMap(), restored)
        assertEquals(0.5f, restored.speedFor("new", "external_primary|Books"))
    }
    @Test fun halfSpeedFollowsTracksAndEpisodeTimerStopsAtSecondNaturalEnd() {
        // Launch the app through the shell on OEM devices that restrict background
        // test-activity launches. The media service still runs in the normal app UID.
        val activity = launchDeviceTestActivity()
        val prefs = PlaybackPreferences(context)
        val work = "external_primary|Test"
        prefs.setWorkPlayback(work, 1.5f, 0, "natural")
        val files = List(3) { index -> File(context.cacheDir, "upgrade-${java.util.UUID.randomUUID()}-$index.wav").apply {
            val audio = ByteArray(8000 * 2 * 2)
            writeBytes(ByteBuffer.allocate(44 + audio.size).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + audio.size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(audio.size).put(audio).array())
        } }
        var controller: MediaController? = null
        try {
            var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            val player = future!!.get(15, TimeUnit.SECONDS); controller = player
            val ids = files.map { Uri.fromFile(it).toString() }
            prefs.setTrackSpeeds(listOf(ids[0]), 0.5f)
            main {
                player.setMediaItems(ids.map { id -> MediaItem.Builder().setUri(id).setMediaId(id)
                    .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putString(WORK_KEY, work) }).build()).build() })
                player.prepare()
            }
            await { player.playbackState == Player.STATE_READY && player.playbackParameters.speed == 0.5f }
            prefs.sleepEpisodesRemaining = 2
            main { player.play() }
            await(15000) { prefs.sleepEpisodesRemaining == 0 && !player.isPlaying }
            main {
                assertEquals("Stopped on second episode", 1, player.currentMediaItemIndex)
                assertEquals(1.5f, player.playbackParameters.speed)
                assertTrue("Second episode reached its end", player.currentPosition >= 1900L)
            }
            assertTrue(ids[0] in prefs.finishedTrackUris)
        } finally {
            main { controller?.pause(); controller?.clearMediaItems(); controller?.release() }
            main { activity.finish() }
            files.forEach { it.delete() }
        }
    }
    private fun await(timeout: Long = 10000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        var done = false
        while (!done && System.currentTimeMillis() < deadline) {
            main { done = condition() }
            if (!done) Thread.sleep(50)
        }
        assertTrue("Timed out waiting for playback", done)
    }
}
