package com.imankoppai.mediaanvil.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.model.AudioTrack
@Composable
internal fun TrackCover(track: AudioTrack, size: androidx.compose.ui.unit.Dp, corner: Int = 12) {
    val context = LocalContext.current
    var cover by remember(track.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(track.uri, CoverLoader.revision) {
        cover = CoverLoader.load(context, track.uri, thumbnail = true)
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
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
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun MiniPlayer(
    track: AudioTrack,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackCover(track, size = 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist ?: stringResource(R.string.unknown_artist),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onQueue) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = stringResource(R.string.play_queue),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A flat, playable list used by the Favourites and Recently played tabs. The order is
 * the caller's: favourites keep their saved order and history stays newest first, and
 * entries whose audio is gone were already dropped, so tapping always starts a valid
 * queue in exactly the order shown.
 */
@Composable
internal fun SimpleTrackList(
    tracks: List<AudioTrack>,
    emptyText: String,
    library: LibraryViewModel,
    playlists: PlaylistViewModel,
    settings: SettingsViewModel,
    controller: androidx.media3.session.MediaController?,
    onEditTrack: (AudioTrack) -> Unit,
) {
    if (tracks.isEmpty()) {
        SectionPlaceholder(emptyText)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        itemsIndexed(tracks, key = { _, track -> track.uri.toString() }) { _, track ->
            TrackRow(
                track = track,
                current = track.uri == library.selectedTrack?.uri,
                onClick = { playFromLibrary(library, settings, controller, tracks.indexOf(track), tracks) },
                onEdit = { onEditTrack(track) },
                onRemoveFromPlayer = {
                    removeTrackFromPlayer(library, playlists, controller, track)
                },
                isFavorite = playlists.isFavorite(track.uri),
                onToggleFavorite = { playlists.toggleFavorite(track.uri) },
            )
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
internal fun EmptyLibrary(
    hint: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
internal fun SectionPlaceholder(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun formatLabel(track: AudioTrack): String =
    track.fileName.substringAfterLast('.', "").uppercase().ifEmpty { "AUDIO" }

@Composable
internal fun sortLabel(mode: String): String = when (mode) {
    "title" -> stringResource(R.string.sort_title)
    "duration" -> stringResource(R.string.sort_duration)
    else -> stringResource(R.string.sort_file_name)
}

internal fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}


internal fun playFromLibrary(
    library: LibraryViewModel,
    settings: SettingsViewModel,
    controller: androidx.media3.session.MediaController?,
    index: Int,
    queue: List<AudioTrack>,
    shuffle: Boolean = false,
    work: String? = null,
) {
    val player = controller ?: return
    library.playbackError = null
    if (queue.isEmpty() || index !in queue.indices) return
    val mediaItems = queue.map { track ->
        androidx.media3.common.MediaItem.Builder()
            .setUri(track.uri)
            .setMediaId(track.uri.toString())
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setExtras(android.os.Bundle().apply { putString(com.imankoppai.mediaanvil.data.WORK_KEY,
                        com.imankoppai.mediaanvil.data.workKey(track)) })
                    .build(),
            )
            .build()
    }
    // Resume from the tapped track's saved position when the feature is on.
    val startPos = if (settings.resumePlayback) {
        settings.preferences.playbackPositionFor(queue[index].uri.toString()).takeIf { it > 0L }
    } else {
        null
    }
    val resumedPosition = startPos?.let {
        com.imankoppai.mediaanvil.data.ListeningProgress.resumePosition(it,
            settings.preferences.rewindFor(com.imankoppai.mediaanvil.data.workKey(queue[index])), queue[index].durationMs)
    }
    player.setMediaItems(mediaItems, index, resumedPosition ?: androidx.media3.common.C.TIME_UNSET)
    player.shuffleModeEnabled = shuffle || (work != null && settings.preferences.orderFor(work) == "shuffle")
    if (work != null && settings.preferences.folderOrder(work) != null) player.repeatMode = if (settings.preferences.orderFor(work) == "loop")
        androidx.media3.common.Player.REPEAT_MODE_ALL else androidx.media3.common.Player.REPEAT_MODE_OFF
    player.prepare()
    player.play()
    library.selectedIndex = library.tracks.indexOfFirst { it.uri == queue[index].uri }
}
