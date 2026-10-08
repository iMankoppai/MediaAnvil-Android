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
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.imankoppai.mediaanvil.data.SettingsStore
import com.imankoppai.mediaanvil.playback.PlaybackService
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class IntervalPlaybackTest {
    @Test fun savedIntervalStartsFromPausedLoopsAndRejectsInvalidBoundaries() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        context.stopService(Intent(context,PlaybackService::class.java))
        context.getSharedPreferences("mediaanvil_playback",Context.MODE_PRIVATE).edit().clear().commit()
        SettingsStore.clearForTests(context); SettingsStore.resetForTests()
        val file=File(context.cacheDir,"interval-playback-${java.util.UUID.randomUUID()}.wav")
        val data=ByteArray(8000*2*30)
        file.writeBytes(ByteBuffer.allocate(44+data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36+data.size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(data.size).put(data).array())
        var controller: MediaController?=null
        try {
            var future: com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
            main { future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync() }
            val player=future!!.get(15,TimeUnit.SECONDS)
            controller=player
            val id=Uri.fromFile(file).toString()
            main { player.setMediaItem(MediaItem.Builder().setUri(id).setMediaId(id).build()); player.prepare() }
            val deadline=System.currentTimeMillis()+10000
            var ready=false
            while (!ready && System.currentTimeMillis()<deadline) {
                main { ready=player.playbackState==Player.STATE_READY }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Prepared test audio",ready)
            fun command(start: Long,end: Long): SessionResult {
                var result: com.google.common.util.concurrent.ListenableFuture<SessionResult>?=null
                main { result=player.sendCustomCommand(SessionCommand(PlaybackService.COMMAND_LOOP_RANGE,Bundle.EMPTY),Bundle().apply {
                    putString("uri",id); putLong("start",start); putLong("end",end)
                }) }
                return result!!.get(5,TimeUnit.SECONDS)
            }
            assertEquals(SessionResult.RESULT_SUCCESS,command(1000,1500).resultCode)
            val positions=mutableListOf<Long>()
            repeat(14) { Thread.sleep(150); main { positions += player.currentPosition } }
            main { assertTrue(player.isPlaying) }
            assertTrue("Repeated the saved interval: $positions",positions.zipWithNext().any { (a,b) -> b<a })
            assertTrue("Did not keep playing beyond the interval: $positions",positions.all { it in 900..2250 })
            assertTrue(command(2000,1000).resultCode<0)
            assertTrue(command(1000,60000).resultCode<0)
            main { player.pause() }
        } finally {
            main { controller?.pause(); controller?.clearMediaItems(); controller?.release() }
            context.stopService(Intent(context,PlaybackService::class.java))
            SettingsStore.clearForTests(context); SettingsStore.resetForTests()
            file.delete()
        }
    }
}
