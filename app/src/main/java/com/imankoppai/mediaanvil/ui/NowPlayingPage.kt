package com.imankoppai.mediaanvil.ui

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.media3.session.SessionCommand
import com.imankoppai.mediaanvil.playback.PlaybackService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.model.SubtitleCue
import com.imankoppai.mediaanvil.subtitles.PreviewLyrics
import com.imankoppai.mediaanvil.subtitles.LyricsFileStore
import com.imankoppai.mediaanvil.subtitles.OnlineLyricsCandidate
import com.imankoppai.mediaanvil.subtitles.OnlineLyricsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
internal fun NowPlayingPage(
    library: LibraryViewModel,
    settings: SettingsViewModel,
    controller: MediaController?,
    onOpenLibrary: () -> Unit,
) {
    val context = LocalContext.current
    val listening: ListeningViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var bookmarksOpen by rememberSaveable { mutableStateOf(false) }
    val track = library.selectedTrack
    // Seed from the player so opening this page paints the real position straight
    // away instead of sliding up from 0:00 on the first poll.
    var isPlaying by remember { mutableStateOf(controller?.isPlaying == true) }
    var positionMs by remember { mutableLongStateOf(controller?.currentPosition?.coerceAtLeast(0L) ?: 0L) }
    var durationMs by remember { mutableLongStateOf(controller?.duration?.coerceAtLeast(0L) ?: 0L) }
    // A/B loop points in ms, shared with the seek bar markers; -1 means unset. They are
    // stored per track so a rotation cannot drop the markers while the service keeps
    // looping.
    var loopA by remember { mutableLongStateOf(-1L) }
    var loopB by remember { mutableLongStateOf(-1L) }
    fun storeLoopPoints(start: Long, end: Long) {
        val preferences = settings.preferences
        preferences.loopTrackUri = track?.uri?.toString().orEmpty()
        preferences.loopStartMs = start
        preferences.loopEndMs = end
    }
    var sleepTimerEndAt by remember { mutableStateOf(settings.sleepTimerEndAt) }
    LaunchedEffect(Unit) {
        settings.refreshSleepTimer()
        while (sleepTimerEndAt != null) {
            delay(30_000L)
            settings.refreshSleepTimer()
            sleepTimerEndAt = settings.sleepTimerEndAt
        }
    }
    LaunchedEffect(track?.uri) {
        val preferences = settings.preferences
        val restored = track != null && preferences.loopTrackUri == track.uri.toString()
        loopA = if (restored) preferences.loopStartMs else -1L
        loopB = if (restored) preferences.loopEndMs else -1L
    }
    var speed by remember { mutableStateOf(settings.preferences.playbackSpeed) }
    var queueOpen by remember { mutableStateOf(false) }
    var pendingCoverTrack by remember { mutableStateOf<android.net.Uri?>(null) }
    var customCoverUri by remember(track?.uri) {
        mutableStateOf(track?.uri?.let(settings.preferences::customCoverFor))
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { imageUri ->
        if (imageUri != null) {
            pendingCoverTrack?.let { trackUri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        imageUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                settings.preferences.setCustomCover(trackUri, imageUri)
                CoverLoader.invalidate()
                if (track?.uri == trackUri) customCoverUri = imageUri
                android.widget.Toast.makeText(context, R.string.custom_cover_saved, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        pendingCoverTrack = null
    }
    val pagerState = rememberPagerState(initialPage = 0) { 2 }

    fun syncFromPlayer(player: Player, includeProgress: Boolean = true) {
        isPlaying = player.isPlaying
        speed = player.playbackParameters.speed
        if (includeProgress) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
        }
        // The player queue may be a filtered subset (group, search,
        // album groups, shuffle order); map by media id instead of
        // treating its index as an index into the full track list.
        val currentId = player.currentMediaItem?.mediaId
        val mapped = currentId?.let { id -> library.tracks.indexOfFirst { it.uri.toString() == id } } ?: -1
        if (mapped >= 0) library.selectedIndex = mapped
    }

    DisposableEffect(controller, library.tracks) {
        val player = controller
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = syncFromPlayer(player)
        }
        syncFromPlayer(player)
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Position changes continuously only during playback. Discrete state changes
    // (track, pause, seek, duration) arrive through Player.Listener above.
    LaunchedEffect(controller, isPlaying) {
        val player = controller ?: return@LaunchedEffect
        while (isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (track == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.choose_track_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenLibrary) {
                        Text(stringResource(R.string.open_library))
                    }
                }
            }
            return@Scaffold
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val chooseCover = {
                pendingCoverTrack = track.uri
                coverPicker.launch(arrayOf("image/*"))
            }
            val playbackModes: @Composable () -> Unit = {
                PlaybackModeRow(
                    controller = controller,
                    positionMs = positionMs,
                    speed = speed,
                    loopA = loopA,
                    loopB = loopB,
                    onLoopA = {
                        loopA = it
                        storeLoopPoints(it, loopB)
                    },
                    onLoopB = {
                        loopB = it
                        storeLoopPoints(loopA, it)
                    },
                    onSpeedChange = { option ->
                        speed = option
                        settings.preferences.playbackSpeed = option
                        settings.preferences.setTrackSpeeds(listOf(track.uri.toString()), option)
                        controller?.playbackParameters = androidx.media3.common.PlaybackParameters(option)
                    },
                    onShuffleChange = { settings.preferences.shuffleEnabled = it },
                    onRepeatChange = { settings.preferences.repeatMode = it },
                    onOpenQueue = { queueOpen = true },
                    onOpenBookmarks = { bookmarksOpen = true },
                )
            }
            if (maxWidth >= PlayerTwoPaneMinWidth) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        PlaybackErrorLine(library.playbackError)
                        Spacer(Modifier.height(8.dp))
                        // Artwork is capped so the controls stay close beneath it; the
                        // leftover height stays at the bottom of the pane.
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val side = minOf(maxWidth, (maxHeight - 300.dp).coerceAtLeast(160.dp))
                                .coerceAtMost(440.dp)
                            Box(Modifier.size(side).align(Alignment.Center)) {
                                NowPlayingCover(track, customCoverUri, chooseCover)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        TrackTitleBlock(track, Modifier.fillMaxWidth().height(56.dp))
                        Spacer(Modifier.height(4.dp))
                        PlayerSeekBar(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            loopA = loopA,
                            loopB = loopB,
                            onSeek = { controller?.seekTo(it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TimeLabels(positionMs, durationMs, Modifier.fillMaxWidth())
                        sleepTimerEndAt?.let { end ->
                            val minutesLeft = ((end - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000L).toInt()
                            Text(
                                text = stringResource(R.string.sleep_timer_remaining, minutesLeft),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        playbackModes()
                        Spacer(Modifier.height(21.dp))
                        TransportRow(library, settings, controller, isPlaying)
                    }
                    VerticalDivider(Modifier.padding(horizontal = 20.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        LyricsView(
                            library = library,
                            settings = settings,
                            track = track,
                            positionMs = positionMs,
                            onSeek = { controller?.seekTo(it) },
                            autoLoadExternal = settings.autoLoadLyrics,
                            showTimestamps = settings.showLyricsTimestamps,
                        )
                    }
                }
            } else {
                CenteredPageContent(maxWidth = PlayerSinglePaneMaxWidth) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp)
                            // The transport row sat close to the bottom bar. The column
                            // is centred, so this padding lifts the whole block by half
                            // of it and gives the row room to breathe.
                            .padding(bottom = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        PlaybackErrorLine(library.playbackError)
                        Spacer(Modifier.height(8.dp))
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            // Artwork grows into the height the controls do not need, so the
                            // player fills the screen with an even margin instead of sitting
                            // in a band at the top.
                            val side = minOf(maxWidth, (maxHeight - 280.dp).coerceAtLeast(200.dp))
                            val pageHeight = side + 108.dp
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxWidth().height(pageHeight),
                            ) { page ->
                                if (page == 0) {
                                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Spacer(Modifier.height(32.dp))
                                        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
                                            NowPlayingCover(track, customCoverUri, chooseCover)
                                        }
                                        Spacer(Modifier.height(28.dp))
                                        TrackTitleBlock(track, Modifier.fillMaxWidth().height(56.dp))
                                    }
                                } else {
                                    LyricsView(
                                        library = library,
                                        settings = settings,
                                        track = track,
                                        positionMs = positionMs,
                                        onSeek = { controller?.seekTo(it) },
                                        autoLoadExternal = settings.autoLoadLyrics,
                                        showTimestamps = settings.showLyricsTimestamps,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(0.dp))
                        PlayerSeekBar(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            loopA = loopA,
                            loopB = loopB,
                            onSeek = { controller?.seekTo(it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TimeLabels(positionMs, durationMs, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(18.dp))
                        playbackModes()
                        Spacer(Modifier.height(21.dp))
                        TransportRow(library, settings, controller, isPlaying)
                    }
                }
            }
        }
    }

    if (bookmarksOpen && track != null) {
        IntervalTagsSheet(track.uri.toString(), listening, controller, track.durationMs, loopA, loopB,
            onJump = { tag ->
                loopA = -1; loopB = -1
                storeLoopPoints(-1, -1)
                controller?.sendCustomCommand(SessionCommand(PlaybackService.COMMAND_LOOP_CLEAR, Bundle.EMPTY), Bundle.EMPTY)
                controller?.seekTo(tag.positionMs)
            },
            onLoop = { tag ->
                val end = tag.endPositionMs ?: return@IntervalTagsSheet
                loopA = tag.positionMs; loopB = end
                storeLoopPoints(loopA, loopB)
                controller?.sendCustomCommand(SessionCommand(PlaybackService.COMMAND_LOOP_RANGE, Bundle.EMPTY), Bundle().apply {
                    putString("uri", tag.trackUri); putLong("start", tag.positionMs); putLong("end", end)
                })
            }, onDismiss = { bookmarksOpen = false })
    }
    if (queueOpen) {
        QueueSheet(library, controller, onDismiss = { queueOpen = false })
    }
}
