package com.imankoppai.mediaanvil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.model.AudioTrack

private val QueueRowHeight = 64.dp

private data class QueueEntry(val index: Int, val mediaId: String, val title: String, val artist: String?)

private fun queueEntries(controller: MediaController?): List<QueueEntry> {
    val player = controller ?: return emptyList()
    return (0 until player.mediaItemCount).map { index ->
        val item = player.getMediaItemAt(index)
        QueueEntry(
            index = index,
            mediaId = item.mediaId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString(),
        )
    }
}

private fun playNextAfterCurrent(controller: MediaController?, track: AudioTrack, afterIndex: Int) {
    val player = controller ?: return
    val item = MediaItem.Builder()
        .setUri(track.uri)
        .setMediaId(track.uri.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .build(),
        )
        .build()
    player.addMediaItems((afterIndex + 1).coerceAtMost(player.mediaItemCount), listOf(item))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(library: LibraryViewModel, controller: MediaController?, onDismiss: () -> Unit) {
    var entries by remember { mutableStateOf(queueEntries(controller)) }
    var currentIndex by remember { mutableIntStateOf(controller?.currentMediaItemIndex ?: -1) }
    DisposableEffect(controller) {
        val player = controller
        if (player == null) return@DisposableEffect onDispose { }
        fun syncQueue() {
            entries = queueEntries(player)
            currentIndex = player.currentMediaItemIndex
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = syncQueue()
        }
        syncQueue()
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { QueueRowHeight.toPx() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.play_queue) + " · " + stringResource(R.string.queue_count, entries.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            itemsIndexed(entries) { position, entry ->
                val track = library.tracks.firstOrNull { it.uri.toString() == entry.mediaId }
                val dragging = draggingIndex == position
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(QueueRowHeight)
                        .graphicsLayer { if (dragging) translationY = dragOffsetY }
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggingIndex = position; dragOffsetY = 0f },
                                onDrag = { change, amount -> change.consume(); dragOffsetY += amount.y },
                                onDragEnd = {
                                    val from = draggingIndex
                                    if (from in entries.indices && dragOffsetY != 0f) {
                                        val to = (from + kotlin.math.round(dragOffsetY / rowHeightPx).toInt())
                                            .coerceIn(0, entries.size - 1)
                                        if (to != from) controller?.moveMediaItem(from, to)
                                    }
                                    draggingIndex = -1
                                    dragOffsetY = 0f
                                },
                                onDragCancel = { draggingIndex = -1; dragOffsetY = 0f },
                            )
                        }
                        .clickable {
                            controller?.seekTo(entry.index, 0)
                            controller?.play()
                            onDismiss()
                        }
                        .background(
                            if (entry.index == currentIndex) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    track?.let { TrackCover(it, size = 40.dp) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title.ifEmpty { stringResource(R.string.unknown_artist) },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (entry.index == currentIndex) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entry.artist ?: stringResource(R.string.unknown_artist),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    track?.let {
                        IconButton(onClick = { playNextAfterCurrent(controller, it, entry.index) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = stringResource(R.string.play_next),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { controller?.removeMediaItem(entry.index) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.remove_from_queue),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
