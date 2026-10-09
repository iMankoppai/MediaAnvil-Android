package com.imankoppai.mediaanvil

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.imankoppai.mediaanvil.data.*
import com.imankoppai.mediaanvil.playback.FullPlayAttempt
import com.imankoppai.mediaanvil.playback.PlaybackService
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class CompletePlayDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var controller: MediaController? = null
    private var activity: DebugTestActivity? = null
    private val files = mutableListOf<File>()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(timeout: Long = 20000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(40) }
        fail("Timed out waiting for playback condition")
    }
    @Before fun before() {
        main { context.stopService(Intent(context, PlaybackService::class.java)) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        PlaybackPreferences(context).flushPendingWrites()
        context.getSharedPreferences("mediaanvil_playback", Context.MODE_PRIVATE).edit().clear().commit()
        SettingsStore.clearForTests(context); SettingsStore.resetForTests()
        activity = launchDeviceTestActivity()
    }
    @After fun after() {
        main { controller?.release(); context.stopService(Intent(context, PlaybackService::class.java)); activity?.finish() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        PlaybackPreferences(context).flushPendingWrites()
        SettingsStore.clearForTests(context); SettingsStore.resetForTests()
        files.forEach { it.delete() }
    }
    private fun audio(seconds: Int): MediaItem {
        val file = File(context.cacheDir, "complete-play-${UUID.randomUUID()}.wav")
        files += file
        val pcm = ByteArray(8000 * 2 * seconds)
        file.writeBytes(ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size).put(pcm).array())
        val uri = Uri.fromFile(file)
        return MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).build()
    }
    private fun connect(): MediaController {
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        return future!!.get(15, TimeUnit.SECONDS).also { controller = it }
    }
    private fun command(player: MediaController, action: String, args: Bundle = Bundle.EMPTY) {
        var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>? = null
        main { future = player.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
        assertEquals(0, future!!.get(10, TimeUnit.SECONDS).resultCode)
    }

    @Test fun repeatAndAutomaticQueueEndsCountOnlyCompletePasses() {
        val first = audio(2); val second = audio(2)
        val prefs = PlaybackPreferences(context)
        prefs.markFinished(first.mediaId, true)
        assertEquals(0L, prefs.fullPlayCount(first.mediaId))
        prefs.sleepEpisodesRemaining = 2
        val player = connect()
        main { player.setMediaItems(listOf(first, second)); player.prepare(); player.play() }
        await { prefs.fullPlayCount(second.mediaId) == 1L }
        assertEquals(1L, prefs.fullPlayCount(first.mediaId))
        main { player.setMediaItem(first); player.repeatMode = Player.REPEAT_MODE_ONE; player.prepare(); player.setPlaybackSpeed(2f); player.play() }
        await { prefs.fullPlayCount(first.mediaId) >= 3 }
        main { player.pause() }
        assertEquals(3L, prefs.fullPlayCount(first.mediaId))
    }

    @Test fun seekToEndAndRangeLoopsNeverIncreaseCounts() {
        val item = audio(8)
        val prefs = PlaybackPreferences(context)
        val player = connect()
        main { player.setMediaItem(item); player.prepare(); player.play() }
        await { var position = 0L; main { position = player.currentPosition }; position >= 500 }
        main { player.seekTo(5800) }
        await { var ended = false; main { ended = player.playbackState == Player.STATE_ENDED }; ended }
        assertEquals(0L, prefs.fullPlayCount(item.mediaId))
        main { player.setMediaItem(item); player.prepare() }
        await { var ready = false; main { ready = player.playbackState == Player.STATE_READY }; ready }
        command(player, PlaybackService.COMMAND_LOOP_RANGE, Bundle().apply {
            putString("uri", item.mediaId); putLong("start", 0); putLong("end", 1000)
        })
        Thread.sleep(2600)
        assertEquals(0L, prefs.fullPlayCount(item.mediaId))
        command(player, PlaybackService.COMMAND_LOOP_CLEAR)
        main { player.pause() }
    }

    @Test fun pausedPrefixSurvivesServiceRestartAndBackupRemapsCountAndAttempt() {
        val item = audio(8)
        val prefs = PlaybackPreferences(context)
        prefs.resumeRewindSeconds = 0
        val player = connect()
        main { player.setMediaItem(item); player.prepare(); player.play() }
        await { var position = 0L; main { position = player.currentPosition }; position >= 3300 }
        main { player.pause() }
        command(player, PlaybackService.COMMAND_SAVE_PLAYBACK)
        val savedPosition = prefs.playbackPositionFor(item.mediaId)
        assertTrue(savedPosition >= 3300)
        assertTrue(prefs.fullPlayAttempt(item.mediaId)!!.throughMs >= savedPosition)
        main { player.release(); controller = null; context.stopService(Intent(context, PlaybackService::class.java)) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        prefs.flushPendingWrites(); SettingsStore.resetForTests()
        val restored = PlaybackPreferences(context)
        assertNotNull(restored.fullPlayAttempt(item.mediaId))
        val resumed = connect()
        main { resumed.setMediaItem(item, savedPosition); resumed.prepare(); resumed.play() }
        await { restored.fullPlayCount(item.mediaId) == 1L }
        main { resumed.pause() }
        restored.lyricsScript = "simplified"
        restored.saveFullPlayAttempt("partial", FullPlayAttempt(1000, 6000))
        val root = PlayerDataBackup.createBackup(restored, emptyList())
        assertEquals(5, root.getInt("schemaVersion"))
        val sources = listOf(item.mediaId, "partial").map { BackupTrackReference(it, "", "", 6000) }
        val plan = PlayerDataBackup.RestorePlan(root, sources.map { source ->
            BackupTrackMatcher.Match(source, listOf(source.copy(uri = "new:${source.uri}")))
        }, 0, false)
        PlayerDataBackup.restore(plan, emptyMap(), restored)
        restored.flushPendingWrites(); SettingsStore.resetForTests()
        val imported = PlaybackPreferences(context)
        assertEquals(1L, imported.fullPlayCount("new:${item.mediaId}"))
        assertEquals(FullPlayAttempt(1000, 6000), imported.fullPlayAttempt("new:partial"))
        assertEquals("simplified", imported.lyricsScript)
        val old = org.json.JSONObject().put("settings", org.json.JSONObject()).put("groups", org.json.JSONArray())
        imported.restoreFromBackup(old)
        assertEquals(1L, imported.fullPlayCount("new:${item.mediaId}"))
    }
}
