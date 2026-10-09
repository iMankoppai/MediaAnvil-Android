package com.imankoppai.mediaanvil.ui

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.media3.session.SessionCommand
import com.imankoppai.mediaanvil.playback.PlaybackService
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.BookmarkBorder
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.model.AudioTrack
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun PlaybackErrorLine(text: String?) {
    text ?: return
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    )
}

@Composable
internal fun TrackTitleBlock(track: AudioTrack, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            track.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(iterations = Int.MAX_VALUE),
            textAlign = TextAlign.Center,
        )
        Text(
            track.artist ?: stringResource(R.string.unknown_artist),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** Seek bar with the A/B loop ticks and region highlight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    loopA: Long,
    loopB: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat(),
        onValueChange = { onSeek(it.toLong()) },
        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
        thumb = {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        },
        track = { state ->
            val fraction = if (state.valueRange.endInclusive > state.valueRange.start) {
                ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start))
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            ) {
                val trackWidth = maxWidth
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                val loopSpan = durationMs.coerceAtLeast(1L).toFloat()
                val tickFractions = buildList {
                    if (loopA >= 0L) add((loopA / loopSpan).coerceIn(0f, 1f))
                    if (loopB > loopA) add((loopB / loopSpan).coerceIn(0f, 1f))
                }
                if (tickFractions.size == 2) {
                    Box(
                        Modifier
                            .offset(x = trackWidth * tickFractions[0])
                            .width(trackWidth * (tickFractions[1] - tickFractions[0]))
                            .height(4.dp)
                            .align(Alignment.CenterStart)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)),
                    )
                }
                tickFractions.forEach { tickFraction ->
                    Box(
                        Modifier
                            .offset(x = trackWidth * tickFraction - 1.dp)
                            .width(2.dp)
                            .height(8.dp)
                            .align(Alignment.CenterStart)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
internal fun TimeLabels(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatTime(positionMs), style = MaterialTheme.typography.labelSmall)
        Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun TransportRow(
    library: LibraryViewModel,
    settings: SettingsViewModel,
    controller: MediaController?,
    isPlaying: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = { controller?.seekToPreviousMediaItem() },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.previous),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SeekIntervalButton(
                seconds = settings.preferences.seekBackSeconds,
                backward = true,
                modifier = Modifier.offset(x = (-10).dp),
                onClick = {
                    controller?.let { player ->
                        player.seekTo(seekBackTarget(player.currentPosition, settings.preferences.seekBackSeconds))
                    }
                },
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            FilledIconButton(
                onClick = {
                    val player = controller
                    if (player?.isPlaying == true) player.pause() else player?.play()
                },
                // requiredSize, not size: this row gives each control a fifth of the
                // width, which is about 62dp on a 360dp screen — narrower than the
                // button. size() honours that incoming maximum, so the button came out
                // 62x68 and CircleShape drew an ellipse. A square is what makes the
                // circle round.
                modifier = Modifier.requiredSize(68.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SeekIntervalButton(
                seconds = settings.preferences.seekForwardSeconds,
                backward = false,
                modifier = Modifier.offset(x = 10.dp),
                onClick = {
                    controller?.let { player ->
                        player.seekTo(
                            seekForwardTarget(
                                player.currentPosition,
                                player.duration,
                                settings.preferences.seekForwardSeconds,
                            ),
                        )
                    }
                },
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = { controller?.seekToNextMediaItem() },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.next),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

internal fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×"

/** Hold the cover this long to open the cover picker. */
private const val COVER_LONG_PRESS_MS = 600L

@Composable
internal fun NowPlayingCover(
    track: AudioTrack,
    customCoverUri: android.net.Uri?,
    onChooseCover: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var cover by remember(track.uri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(track.uri, customCoverUri, CoverLoader.revision) {
        cover = customCoverUri?.let { CoverLoader.loadImage(context, it) }
            ?: CoverLoader.load(context, track.uri, thumbnail = false)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .pointerInput(track.uri, customCoverUri) {
                detectTapGestures(
                    onPress = {
                        val releasedBeforeThreshold = withTimeoutOrNull(COVER_LONG_PRESS_MS) {
                            tryAwaitRelease()
                        }
                        if (releasedBeforeThreshold == null) {
                            haptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                            )
                            onChooseCover()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val image = cover
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Lyrics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.cover_long_press_hint),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** [0:13.07] style stamp shown above each lyric line when timestamps are enabled. */
internal fun lyricsTimestampLabel(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val minutes = clamped / 60_000
    val seconds = (clamped % 60_000) / 1_000
    val centis = (clamped % 1_000) / 10
    return "[%d:%02d.%02d]".format(minutes, seconds, centis)
}

@Composable
internal fun SeekIntervalButton(
    seconds: Int,
    backward: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val description = stringResource(
        if (backward) R.string.seek_back_action else R.string.seek_forward_action,
        seconds,
    )
    IconButton(onClick = onClick, modifier = modifier.size(52.dp)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Replay,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = if (backward) 1f else -1f),
            )
            Text(
                seconds.toString(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

internal fun seekBackTarget(currentMs: Long, seconds: Int): Long =
    (currentMs - seconds.coerceIn(1, 300) * 1_000L).coerceAtLeast(0L)

internal fun seekForwardTarget(currentMs: Long, durationMs: Long, seconds: Int): Long {
    val target = currentMs.coerceAtLeast(0L) + seconds.coerceIn(1, 300) * 1_000L
    return if (durationMs > 0L) target.coerceAtMost(durationMs) else target
}

/** Playback modes laid out as shuffle, A, speed, B and repeat. */
@Composable
@SuppressLint("UnsafeOptInUsageError")
internal fun PlaybackModeRow(
    controller: androidx.media3.session.MediaController?,
    positionMs: Long,
    speed: Float,
    loopA: Long,
    loopB: Long,
    onLoopA: (Long) -> Unit,
    onLoopB: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatChange: (Int) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenBookmarks: () -> Unit,
) {
    val context = LocalContext.current
    val loopASaved = stringResource(R.string.loop_a_saved, formatTime(positionMs))
    val loopBSaved = stringResource(R.string.loop_b_saved, formatTime(positionMs))
    var speedMenu by remember { mutableStateOf(false) }
    var shuffleEnabled by remember(controller) { mutableStateOf(controller?.shuffleModeEnabled == true) }
    var repeatMode by remember(controller) { mutableIntStateOf(controller?.repeatMode ?: Player.REPEAT_MODE_OFF) }
    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                shuffleEnabled = player.shuffleModeEnabled
                repeatMode = player.repeatMode
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    fun send(action: String) {
        runCatching {
            controller?.sendCustomCommand(
                androidx.media3.session.SessionCommand(action, android.os.Bundle.EMPTY),
                android.os.Bundle.EMPTY,
            )
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(onClick = {
                val player = controller ?: return@IconButton
                player.shuffleModeEnabled = !player.shuffleModeEnabled
                shuffleEnabled = player.shuffleModeEnabled
                onShuffleChange(shuffleEnabled)
            }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = stringResource(R.string.shuffle_play),
                    tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.TextButton(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), onClick = {
                when {
                    loopA < 0L -> {
                        onLoopA(positionMs)
                        send(com.imankoppai.mediaanvil.playback.PlaybackService.COMMAND_LOOP_A)
                        android.widget.Toast.makeText(
                            context,
                            loopASaved,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    loopB <= loopA && positionMs > loopA -> {
                        onLoopB(positionMs)
                        send(com.imankoppai.mediaanvil.playback.PlaybackService.COMMAND_LOOP_B)
                        android.widget.Toast.makeText(
                            context,
                            loopBSaved,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    loopB <= loopA -> {
                        android.widget.Toast.makeText(
                            context,
                            R.string.loop_b_after_a,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    else -> {
                        onLoopA(-1L)
                        onLoopB(-1L)
                        send(com.imankoppai.mediaanvil.playback.PlaybackService.COMMAND_LOOP_CLEAR)
                        android.widget.Toast.makeText(
                            context,
                            R.string.loop_cleared,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }) {
                Text(
                    "A/B",
                    maxLines = 1,
                    softWrap = false,
                    color = if (loopA >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 18.sp,
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box {
                Text(
                    speedLabel(speed),
                    maxLines = 1,
                    softWrap = false,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { speedMenu = true }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    com.imankoppai.mediaanvil.data.playbackSpeeds.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(speedLabel(option)) },
                            onClick = {
                                onSpeedChange(option)
                                speedMenu = false
                            },
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(onClick = onOpenBookmarks) {
                Icon(Icons.Filled.BookmarkBorder, contentDescription = stringResource(R.string.bookmarks))
            }
        }
        Box(
            Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onOpenQueue) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = stringResource(R.string.play_queue),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(onClick = {
                val player = controller ?: return@IconButton
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                repeatMode = player.repeatMode
                onRepeatChange(repeatMode)
            }) {
                Icon(
                    if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = stringResource(R.string.repeat),
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
