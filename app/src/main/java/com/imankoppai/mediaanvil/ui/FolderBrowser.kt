package com.imankoppai.mediaanvil.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AudioFolder(val key: String, val path: String, val volume: String, val tracks: List<AudioTrack>)

internal fun folderGroups(tracks: List<AudioTrack>): List<AudioFolder> = tracks
    .groupBy { it.uri.pathSegments.firstOrNull().orEmpty() to it.relativeFolder.trim('/') }
    .map { (identity, entries) -> AudioFolder("${identity.first}|${identity.second}", identity.second, identity.first,
        entries.sortedWith { a, b -> NaturalOrder.compare(a.fileName, b.fileName) }) }
    .sortedWith { a, b -> NaturalOrder.compare(a.path, b.path).takeIf { it != 0 } ?: a.volume.compareTo(b.volume) }

@Composable
internal fun FolderBrowser(
    tracks: List<AudioTrack>, library: LibraryViewModel, playlists: PlaylistViewModel, settings: SettingsViewModel,
    controller: MediaController?, onEditTrack: (AudioTrack) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val listening: ListeningViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val groups by produceState<Pair<List<AudioTrack>, List<AudioFolder>>?>(null, tracks) {
        value = tracks to withContext(Dispatchers.Default) { folderGroups(tracks) }
    }
    BackHandler(selected != null) { selected = null }
    val folders = groups?.takeIf { it.first === tracks }?.second
    if (folders == null) { CircularProgressIndicator(); return }
    val open = folders.firstOrNull { it.key == selected }
    if (selected == null) {
        if (folders.isEmpty()) SectionPlaceholder(stringResource(R.string.no_tracks))
        else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(folders, key = { it.key }) { folder ->
                Row(Modifier.fillMaxWidth().clickable { selected = folder.key }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(folder.path.ifBlank { stringResource(R.string.folder_root) }, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.folder_progress, folder.tracks.size,
                            folder.tracks.count { it.uri.toString() in listening.finishedUris }), style = MaterialTheme.typography.bodySmall)
                        Text(if (folder.volume in setOf("external", "external_primary")) stringResource(R.string.folder_storage)
                            else folder.volume, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    } else if (open == null) {
        TextButton(onClick = { selected = null }) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.folder_missing), Modifier.padding(16.dp))
    } else {
        WorkDetailPage(open, library, playlists, settings, listening, controller, onEditTrack) { selected = null }
    }
}

@Composable
private fun WorkDetailPage(
    folder: AudioFolder, library: LibraryViewModel, playlists: PlaylistViewModel, settings: SettingsViewModel,
    listening: ListeningViewModel, controller: MediaController?, onEditTrack: (AudioTrack) -> Unit, onBack: () -> Unit,
) {
    var selectedUris by remember(folder.key) { mutableStateOf<Set<String>>(emptySet()) }
    var selecting by remember(folder.key) { mutableStateOf(false) }
    var speedDialog by remember(folder.key) { mutableStateOf(false) }
    var settingsDialog by remember(folder.key) { mutableStateOf(false) }
    val summary by produceState<WorkSummary?>(null, folder, listening.positions, listening.finishedUris, listening.recentEntries) {
        val positions = listening.positions
        val finished = listening.finishedUris
        val recent = listening.recentEntries
        value = withContext(Dispatchers.Default) {
            summarizeWork(folder.tracks.map { EpisodeProgress(it.uri.toString(), it.durationMs,
                positions[it.uri.toString()] ?: 0L, it.uri.toString() in finished) }, recent.map { it.uri })
        }
    }
    fun play(index: Int) = playFromLibrary(library, settings, controller, index, folder.tracks, work = folder.key)
    fun toggle(uri: String) { selectedUris = if (uri in selectedUris) selectedUris - uri else selectedUris + uri }
    BackHandler(selecting) { selecting = false; selectedUris = emptySet() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(folder.path.ifBlank { stringResource(R.string.folder_root) }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        }
        summary?.let { progress ->
            Text(stringResource(R.string.work_progress, progress.finishedCount, folder.tracks.size,
                (progress.progress * 100).toInt()), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { progress.progress }, modifier = Modifier.fillMaxWidth().padding(16.dp))
            progress.continueIndex?.let { index ->
                Text(stringResource(R.string.work_continue_episode, folder.tracks.getOrNull(index)?.title.orEmpty()),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = summary?.continueIndex != null, onClick = { summary?.continueIndex?.let(::play) }) { Text(stringResource(R.string.work_continue)) }
            TextButton(enabled = summary?.nextIndex != null, onClick = { summary?.nextIndex?.let(::play) }) { Text(stringResource(R.string.work_next)) }
            TextButton(onClick = { play(0) }) { Text(stringResource(R.string.folder_play_all)) }
            TextButton(onClick = { settingsDialog = true }) { Text(stringResource(R.string.work_preferences)) }
            TextButton(onClick = { selecting = !selecting; selectedUris = emptySet() }) { Text(stringResource(R.string.work_select)) }
        }
        if (selecting) {
            FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { selectedUris = if (selectedUris.size == folder.tracks.size) emptySet() else folder.tracks.map { it.uri.toString() }.toSet() }) { Text(stringResource(R.string.selection_select_all)) }
                TextButton(enabled = selectedUris.isNotEmpty(), onClick = { speedDialog = true }) { Text(stringResource(R.string.batch_speed)) }
                TextButton(enabled = selectedUris.isNotEmpty(), onClick = { listening.markFinished(selectedUris, true); selecting = false; selectedUris = emptySet() }) { Text(stringResource(R.string.mark_finished)) }
                TextButton(enabled = selectedUris.isNotEmpty(), onClick = { listening.markFinished(selectedUris, false); selecting = false; selectedUris = emptySet() }) { Text(stringResource(R.string.mark_unfinished)) }
                Text(stringResource(R.string.selection_count, selectedUris.size), Modifier.padding(12.dp))
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            itemsIndexed(folder.tracks, key = { _, track -> track.uri.toString() }) { index, track ->
                val uri = track.uri.toString()
                TrackRow(track, current = library.selectedTrack?.uri == track.uri,
                    onClick = { if (selecting) toggle(uri) else play(index) },
                    onLongClick = { selecting = true; selectedUris = setOf(uri) },
                    selectable = selecting, selected = uri in selectedUris, onToggleSelect = { toggle(uri) },
                    onEdit = { onEditTrack(track) }, isFavorite = playlists.isFavorite(track.uri),
                    onToggleFavorite = { playlists.toggleFavorite(track.uri) },
                    detail = if (uri in listening.finishedUris) stringResource(R.string.mark_finished) else
                        listening.positions[uri]?.let { stringResource(R.string.work_episode_position, formatTime(it)) },
                    finished = uri in listening.finishedUris,
                    onToggleFinished = { listening.markFinished(uri, uri !in listening.finishedUris) })
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
    if (speedDialog) BatchSpeedDialog(onApply = { settings.preferences.setTrackSpeeds(selectedUris, it) }, onDismiss = { speedDialog = false })
    if (settingsDialog) WorkSettingsDialog(folder.key, settings.preferences) { settingsDialog = false }
}
