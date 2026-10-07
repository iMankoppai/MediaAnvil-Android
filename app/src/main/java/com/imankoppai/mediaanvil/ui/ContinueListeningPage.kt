package com.imankoppai.mediaanvil.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.data.ListeningProgress
import com.imankoppai.mediaanvil.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ContinueListeningPage(
    library: LibraryViewModel, playlists: PlaylistViewModel, settings: SettingsViewModel,
    controller: MediaController?, onEditTrack: (AudioTrack) -> Unit,
) {
    val listening: ListeningViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val context = LocalContext.current
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ListeningProgress.Status.IN_PROGRESS) }
    LaunchedEffect(Unit) { listening.refresh() }
    val source = library.tracks
    val positions = listening.positions
    val finished = listening.finishedUris
    val recent = listening.recentEntries
    val playedAt = remember(recent) { recent.associate { it.uri to it.playedAt } }
    val tracks by produceState<List<AudioTrack>>(emptyList(), source, positions, finished, recent, filter) {
        value = withContext(Dispatchers.Default) {
            val recentOrder = recent.withIndex().associate { it.value.uri to it.index }
            source.filter {
                ListeningProgress.status(positions[it.uri.toString()] ?: 0, it.uri.toString() in finished) == filter
            }.sortedBy { recentOrder[it.uri.toString()] ?: Int.MAX_VALUE }
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(ListeningProgress.Status.entries) { _, status ->
                FilterChip(selected = filter == status, onClick = { filter = status }, label = { Text(stringResource(status.label())) })
            }
        }
        if (tracks.isEmpty()) SectionPlaceholder(stringResource(R.string.continue_empty))
        else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            itemsIndexed(tracks, key = { _, track -> track.uri.toString() }) { index, track ->
                val uri = track.uri.toString()
                val pos = listening.positions[uri] ?: 0L
                val remaining = (track.durationMs - pos).coerceAtLeast(0)
                val progress = if (filter == ListeningProgress.Status.IN_PROGRESS) stringResource(R.string.continue_progress,
                    if (track.durationMs > 0) (pos.toDouble() / track.durationMs * 100).toInt().coerceIn(0, 100) else 0,
                    formatTime(remaining)) else stringResource(filter.label())
                val lastPlayed = playedAt[uri]?.takeIf { it > 0 }?.let {
                    stringResource(R.string.continue_last_played, android.text.format.DateUtils.formatDateTime(context, it,
                        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or
                            android.text.format.DateUtils.FORMAT_SHOW_YEAR or android.text.format.DateUtils.FORMAT_ABBREV_MONTH))
                }
                TrackRow(
                    track = track, current = library.selectedTrack?.uri == track.uri,
                    onClick = { playFromLibrary(library, settings, controller, index, tracks) },
                    onEdit = { onEditTrack(track) },
                    isFavorite = playlists.isFavorite(track.uri), onToggleFavorite = { playlists.toggleFavorite(track.uri) },
                    detail = listOfNotNull(progress, lastPlayed).joinToString("\n"),
                    finished = uri in listening.finishedUris,
                    onToggleFinished = { listening.markFinished(uri, uri !in listening.finishedUris) },
                )
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

private fun ListeningProgress.Status.label(): Int = when (this) {
    ListeningProgress.Status.NOT_STARTED -> R.string.listening_not_started
    ListeningProgress.Status.IN_PROGRESS -> R.string.listening_in_progress
    ListeningProgress.Status.FINISHED -> R.string.listening_finished
}
