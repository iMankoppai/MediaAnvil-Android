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
    val groups by produceState<List<AudioFolder>?>(null, tracks) {
        value = withContext(Dispatchers.Default) { folderGroups(tracks) }
    }
    BackHandler(selected != null) { selected = null }
    val folders = groups
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
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(open?.path?.ifBlank { stringResource(R.string.folder_root) } ?: stringResource(R.string.folder_missing), Modifier.weight(1f))
            }
            TextButton(enabled = open?.tracks?.isNotEmpty() == true, onClick = {
                open?.tracks?.let { playFromLibrary(library, settings, controller, 0, it) }
            }) { Text(stringResource(R.string.folder_play_all)) }
            if (open == null) Text(stringResource(R.string.folder_missing), Modifier.padding(16.dp))
            else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                itemsIndexed(open.tracks, key = { _, track -> track.uri.toString() }) { index, track ->
                    TrackRow(track, current = library.selectedTrack?.uri == track.uri,
                        onClick = { playFromLibrary(library, settings, controller, index, open.tracks) },
                        onEdit = { onEditTrack(track) }, isFavorite = playlists.isFavorite(track.uri),
                        onToggleFavorite = { playlists.toggleFavorite(track.uri) },
                        finished = track.uri.toString() in listening.finishedUris,
                        onToggleFinished = { listening.markFinished(track.uri.toString(), track.uri.toString() !in listening.finishedUris) })
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
}
