package com.imankoppai.mediaanvil.playback

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.IntentCompat
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import android.provider.MediaStore
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.data.PlaybackPreferences

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private lateinit var preferences: PlaybackPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var loopStartMs = -1L
    private var loopEndMs = -1L
    private var stopAfterTrackEnd = false
    private var closeAfterSleepStop = false
    private var lastResumeSaveAt = 0L
    private var lastButtonClickAt = 0L
    private var pendingSinglePress: Runnable? = null

    /** Keeps the notification buttons and the seek increments on the current settings. */
    private val preferencesListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "seek_back_seconds", "seek_forward_seconds" -> {
                    exoPlayer?.let { player ->
                        player.setSeekBackIncrementMs(preferences.seekBackSeconds * 1_000L)
                        player.setSeekForwardIncrementMs(preferences.seekForwardSeconds * 1_000L)
                    }
                    mediaSession?.setMediaButtonPreferences(notificationButtons())
                }
            }
        }

    private val loopTicker = object : Runnable {
        override fun run() {
            var nextDelayMs = IDLE_POLL_MS
            if (preferences.sleepTimerDeadlineAt != 0L &&
                System.currentTimeMillis() >= preferences.sleepTimerDeadlineAt
            ) {
                fireSleepTimer()
            } else if (preferences.sleepTimerDeadlineAt != 0L) {
                nextDelayMs = PLAYBACK_POLL_MS
            }
            mediaSession?.player?.let { player ->
                val active = loopEndMs > loopStartMs
                val playing = player.isPlaying && player.currentPosition >= loopEndMs
                // A B point at (or past) the end lets the track finish instead
                // of crossing the marker while playing; rewind that case too.
                val finished = player.playbackState == Player.STATE_ENDED
                if (active && (playing || finished)) {
                    player.seekTo(loopStartMs)
                }
                if (active && player.isPlaying) nextDelayMs = LOOP_POLL_MS
                // A stop at the end of a track that is not the last one never reaches
                // STATE_ENDED, so watch for it here when the app must also close.
                if (stopAfterTrackEnd && closeAfterSleepStop) {
                    val duration = player.duration
                    if (!player.isPlaying && duration > 0L &&
                        player.currentPosition >= duration - 500L
                    ) {
                        finishStopAfterTrack()
                    }
                }
                if (player.isPlaying) {
                    nextDelayMs = minOf(nextDelayMs, PLAYBACK_POLL_MS)
                    saveResumeState(player)
                }
            }
            handler.postDelayed(this, nextDelayMs)
        }
    }

    /** Persists the current queue, index and position for "continue where you left off". */
    private fun saveResumeState(player: Player, force: Boolean = false) {
        if (!preferences.resumePlayback) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastResumeSaveAt < POSITION_SAVE_INTERVAL_MS) return
        lastResumeSaveAt = now
        val id = player.currentMediaItem?.mediaId ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        // A track played to within a few seconds of its end counts as finished.
        val savedPosition = when {
            pos >= duration - 15_000L -> -1L
            pos > 3_000L -> pos
            else -> -1L
        }
        val uris = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        preferences.savePlaybackSnapshot(
            uri = id,
            positionMs = savedPosition,
            queueUris = uris,
            queueIndex = player.currentMediaItemIndex,
            synchronous = force,
        )
    }

    /** Runs the pending "stop after this track" work exactly once. */
    private fun finishStopAfterTrack() {
        stopAfterTrackEnd = false
        exoPlayer?.pauseAtEndOfMediaItems = false
        if (closeAfterSleepStop) closeForSleep()
    }

    private val wrapAroundListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = mediaSession?.player ?: return
            if (playbackState != Player.STATE_ENDED) return
            // "Finish this track, then stop" is spent once its track has ended; it
            // also suppresses the wrap-around below.
            val stopHere = stopAfterTrackEnd
            if (stopHere) finishStopAfterTrack()
            // Sequential playback returns to the top of the list after the
            // last track instead of stopping at the end. An active A/B loop
            // wins: its own ticker rewinds the finished track.
            val loopActive = loopEndMs > loopStartMs
            if (!stopHere &&
                !loopActive &&
                player.repeatMode == Player.REPEAT_MODE_OFF &&
                player.mediaItemCount > 1 &&
                player.currentMediaItemIndex == player.mediaItemCount - 1
            ) {
                player.seekTo(0, 0)
                player.play()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Playback resumed, so the pending "stop after this track" is spent: clear
            // it here or it would pause every later track as well. The pause the timer
            // itself causes reports playWhenReady = false, so it does not clear it.
            if (playWhenReady && stopAfterTrackEnd) finishStopAfterTrack()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Media3's default notification icon is a colour drawable, which the system
        // cannot turn into a status-bar icon; it then rejects the foreground-service
        // notification and kills the app. Use our own monochrome icon instead.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_small) },
        )
        preferences = PlaybackPreferences(this)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            // Rewind / fast-forward (including the notification's buttons) follow the
            // intervals configured on the settings page.
            .setSeekBackIncrementMs(preferences.seekBackSeconds * 1_000L)
            .setSeekForwardIncrementMs(preferences.seekForwardSeconds * 1_000L)
            .build()
        exoPlayer = player
        preferences.registerChangeListener(preferencesListener)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                clearLoop()
            }
        })
        player.addListener(wrapAroundListener)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    mediaSession?.player?.let { saveResumeState(it, force = true) }
                }
                updateWidget()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateWidget()
            }
        })
        restoreLastQueue()
        // The ticker slows down while idle and only uses the fast cadence for A/B looping.
        handler.postDelayed(loopTicker, IDLE_POLL_MS)
        mediaSession = MediaSession.Builder(this, player)
            .setMediaButtonPreferences(notificationButtons())
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(COMMAND_LOOP_A, Bundle.EMPTY))
                        .add(SessionCommand(COMMAND_LOOP_B, Bundle.EMPTY))
                        .add(SessionCommand(COMMAND_LOOP_CLEAR, Bundle.EMPTY))
                        .add(SessionCommand(COMMAND_STOP_AFTER_ON, Bundle.EMPTY))
                        .add(SessionCommand(COMMAND_STOP_AFTER_OFF, Bundle.EMPTY))
                        .build()
                    // The media notification keeps all five transport buttons; the
                    // player drops the skip commands while nothing seekable is loaded.
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_BACK)
                        .add(Player.COMMAND_SEEK_FORWARD)
                        .build()
                    return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
                }

                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent,
                ): Boolean {
                    val event = IntentCompat.getParcelableExtra(
                        intent,
                        Intent.EXTRA_KEY_EVENT,
                        KeyEvent::class.java,
                    )
                    if (event?.action == KeyEvent.ACTION_DOWN &&
                        (event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    ) {
                        handlePlayPauseClick(player)
                        return true
                    }
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    command: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    when (command.customAction) {
                        COMMAND_LOOP_A -> {
                            loopStartMs = player.currentPosition
                            loopEndMs = -1
                        }
                        COMMAND_LOOP_B -> {
                            loopEndMs = player.currentPosition
                            if (loopStartMs < 0) loopStartMs = 0
                        }
                        COMMAND_LOOP_CLEAR -> clearLoop()
                        COMMAND_STOP_AFTER_ON -> {
                            stopAfterTrackEnd = true
                            // The service owns the stop, so it still works after the
                            // screen that armed the sleep timer is gone.
                            exoPlayer?.pauseAtEndOfMediaItems = true
                        }
                        COMMAND_STOP_AFTER_OFF -> {
                            stopAfterTrackEnd = false
                            exoPlayer?.pauseAtEndOfMediaItems = false
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
    }

    /** Single press toggles playback (after the double-press window); double press runs the configured action. */
    private fun handlePlayPauseClick(player: Player) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastButtonClickAt <= DOUBLE_PRESS_WINDOW_MS) {
            lastButtonClickAt = 0
            pendingSinglePress?.let { handler.removeCallbacks(it) }
            pendingSinglePress = null
            when (preferences.doublePressAction) {
                "previous" -> player.seekToPreviousMediaItem()
                "pause" -> player.pause()
                else -> player.seekToNextMediaItem()
            }
            return
        }
        lastButtonClickAt = now
        val toggle = Runnable {
            player.playWhenReady = !player.playWhenReady
            lastButtonClickAt = 0
        }
        pendingSinglePress = toggle
        handler.postDelayed(toggle, DOUBLE_PRESS_WINDOW_MS)
    }

    /** Rebuilds the last queue (paused) after a reboot or process death. */
    private fun restoreLastQueue() {
        if (!preferences.resumePlayback) return
        val uris = runCatching {
            val array = org.json.JSONArray(preferences.lastQueueUris)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
        val savedIndex = preferences.lastQueueIndex
        if (uris.isEmpty() || savedIndex !in uris.indices) return
        Thread {
            val metadata = mutableMapOf<String, Triple<String, String?, String?>>()
            runCatching {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                )
                // Same collections the library scanner uses, so restored URIs match saved
                // ones. getExternalVolumeNames is API 29+, hence the version check.
                val collections = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    MediaStore.getExternalVolumeNames(applicationContext)
                        .map(MediaStore.Audio.Media::getContentUri)
                } else {
                    listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                }.ifEmpty { listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) }
                for (collection in collections) {
                    applicationContext.contentResolver.query(
                        collection, projection, null, null, null,
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                        while (cursor.moveToNext()) {
                            val id = collection.buildUpon()
                                .appendPath(cursor.getLong(idColumn).toString())
                                .build()
                                .toString()
                            metadata[id] = Triple(
                                cursor.getString(titleColumn).orEmpty(),
                                cursor.getString(artistColumn)?.takeIf(String::isNotBlank),
                                cursor.getString(albumColumn)?.takeIf(String::isNotBlank),
                            )
                        }
                    }
                }
            }
            handler.post {
                val player = mediaSession?.player ?: return@post
                if (player.currentMediaItem != null) return@post
                val items = mutableListOf<MediaItem>()
                var restoredIndex = -1
                uris.forEachIndexed { position, id ->
                    val found = metadata[id]
                    if (found != null) {
                        if (position == savedIndex) restoredIndex = items.size
                        items += MediaItem.Builder()
                            .setUri(android.net.Uri.parse(id))
                            .setMediaId(id)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(found.first)
                                    .setArtist(found.second)
                                    .setAlbumTitle(found.third)
                                    .build(),
                            )
                            .build()
                    }
                }
                if (items.isEmpty()) return@post
                val start = restoredIndex.coerceIn(0, items.size - 1)
                val savedPos = preferences.playbackPositionFor(uris[savedIndex.coerceAtLeast(0)])
                // Read the stored loop before setMediaItems: that call fires a media item
                // transition, and the transition listener clears the stored markers.
                val storedLoopUri = preferences.loopTrackUri
                val storedLoopStart = preferences.loopStartMs
                val storedLoopEnd = preferences.loopEndMs
                player.setMediaItems(items, start, savedPos.takeIf { it > 0L } ?: androidx.media3.common.C.TIME_UNSET)
                player.prepare()
                // A loop the user had marked on this track is still stored.
                if (storedLoopUri == uris[savedIndex.coerceAtLeast(0)] && storedLoopEnd > storedLoopStart) {
                    loopStartMs = storedLoopStart
                    loopEndMs = storedLoopEnd
                }
                updateWidget()
            }
        }.start()
    }

    /**
     * The media notification mirrors the player page's transport row. The buttons are
     * declared up front because the player only reports the seek commands once a
     * seekable item is loaded, which is too late for the notification. The skip
     * buttons claim the overflow slots, otherwise they would lose the single
     * back / forward slot to the skip-to-previous / next buttons.
     */
    private fun notificationButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setDisplayName(getString(R.string.previous))
            .build(),
        CommandButton.Builder(seekIcon(preferences.seekBackSeconds, backward = true))
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName(getString(R.string.seek_back_setting))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setDisplayName(getString(R.string.play))
            .build(),
        CommandButton.Builder(seekIcon(preferences.seekForwardSeconds, backward = false))
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName(getString(R.string.seek_forward_setting))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setDisplayName(getString(R.string.next))
            .build(),
    )

    /** Picks the skip icon that matches the configured interval where one exists. */
    private fun seekIcon(seconds: Int, backward: Boolean): Int = if (backward) {
        when (seconds) {
            5 -> CommandButton.ICON_SKIP_BACK_5
            10 -> CommandButton.ICON_SKIP_BACK_10
            15 -> CommandButton.ICON_SKIP_BACK_15
            30 -> CommandButton.ICON_SKIP_BACK_30
            else -> CommandButton.ICON_REWIND
        }
    } else {
        when (seconds) {
            5 -> CommandButton.ICON_SKIP_FORWARD_5
            10 -> CommandButton.ICON_SKIP_FORWARD_10
            15 -> CommandButton.ICON_SKIP_FORWARD_15
            30 -> CommandButton.ICON_SKIP_FORWARD_30
            else -> CommandButton.ICON_FAST_FORWARD
        }
    }

    /**
     * The sleep timer reached its deadline: pause now, or let the current track
     * finish first. It lives in the service so the timer keeps working across screen
     * rotation and while the app sits in the background; the screen only arms it.
     */
    private fun fireSleepTimer() {
        preferences.sleepTimerDeadlineAt = 0L
        val player = mediaSession?.player
        if (preferences.sleepFinishTrack && player?.isPlaying == true) {
            // An active A/B loop would keep the track from ever ending.
            clearLoop()
            stopAfterTrackEnd = true
            exoPlayer?.pauseAtEndOfMediaItems = true
        } else {
            stopAfterTrackEnd = false
            exoPlayer?.pauseAtEndOfMediaItems = false
            player?.pause()
        }
        if (preferences.sleepCloseApp) {
            if (stopAfterTrackEnd) closeAfterSleepStop = true else closeForSleep()
        }
    }

    /** Shuts the app down after the sleep timer has done its work. */
    private fun closeForSleep() {
        closeAfterSleepStop = false
        preferences.sleepTimerClosedAt = System.currentTimeMillis()
        runCatching { stopSelf() }
    }

    private fun updateWidget() {
        val player = mediaSession?.player ?: return
        com.imankoppai.mediaanvil.widget.PlayerWidgetProvider.refresh(
            applicationContext,
            player.currentMediaItem?.mediaMetadata,
            player.isPlaying,
        )
    }

    private fun clearLoop() {
        loopStartMs = -1
        loopEndMs = -1
        // Keep the stored markers in step so a reopened player never shows a loop the
        // service is no longer enforcing.
        preferences.loopTrackUri = ""
        preferences.loopStartMs = -1L
        preferences.loopEndMs = -1L
        // The ticker stays scheduled at a low idle cadence for the sleep timer.
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::preferences.isInitialized) preferences.unregisterChangeListener(preferencesListener)
        clearLoop()
        mediaSession?.run {
            saveResumeState(player, force = true)
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        const val COMMAND_LOOP_A = "com.imankoppai.mediaanvil.LOOP_A"
        const val COMMAND_LOOP_B = "com.imankoppai.mediaanvil.LOOP_B"
        const val COMMAND_LOOP_CLEAR = "com.imankoppai.mediaanvil.LOOP_CLEAR"
        const val COMMAND_STOP_AFTER_ON = "com.imankoppai.mediaanvil.STOP_AFTER_ON"
        const val COMMAND_STOP_AFTER_OFF = "com.imankoppai.mediaanvil.STOP_AFTER_OFF"
        private const val DOUBLE_PRESS_WINDOW_MS = 350L
        private const val LOOP_POLL_MS = 250L
        private const val PLAYBACK_POLL_MS = 1_000L
        private const val IDLE_POLL_MS = 5_000L
        private const val POSITION_SAVE_INTERVAL_MS = 15_000L
    }
}
