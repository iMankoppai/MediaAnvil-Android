package com.imankoppai.mediaanvil.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.model.TrackGroup

@Composable
internal fun TrackGroupView(
    library: LibraryViewModel,
    playlists: PlaylistViewModel,
    settings: SettingsViewModel,
    controller: androidx.media3.session.MediaController?,
    openGroupId: String?,
    onOpenGroup: (String?) -> Unit,
    onEditTrack: (AudioTrack) -> Unit,
) {
    var editingNameFor by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }
    var selectingTracksFor by remember { mutableStateOf<String?>(null) }

    fun openNameDialog(group: TrackGroup?) {
        editingNameFor = group?.id ?: ""
        nameInput = group?.name.orEmpty()
        nameError = false
    }

    val openGroup = playlists.trackGroups.firstOrNull { it.id == openGroupId }
    if (openGroupId != null && openGroup == null) {
        LaunchedEffect(openGroupId) { onOpenGroup(null) }
    }

    if (openGroup == null) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.groups_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { openNameDialog(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.group_create))
                }
            }
            if (playlists.trackGroups.isEmpty()) {
                SectionPlaceholder(stringResource(R.string.groups_empty))
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    itemsIndexed(playlists.trackGroups, key = { _, group -> group.id }) { _, group ->
                        val availableCount = library.tracks.count { it.uri.toString() in group.trackUris }
                        var menuOpen by remember(group.id) { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onOpenGroup(group.id) }
                                .padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(group.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.folder_track_count, availableCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.group_manage))
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.group_rename)) },
                                        onClick = {
                                            menuOpen = false
                                            openNameDialog(group)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.group_delete)) },
                                        onClick = {
                                            menuOpen = false
                                            playlists.deleteGroup(group.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    } else {
        // Resolved through the saved uri order, so the list plays back in the order
        // the user arranged rather than in library order.
        val groupTracks = remember(library.tracks, openGroup.trackUris) {
            playlists.tracksInGroup(openGroup, library.tracks)
        }
        var menuOpen by remember(openGroup.id) { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onOpenGroup(null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(
                    openGroup.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { selectingTracksFor = openGroup.id }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.group_add_tracks))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.group_manage))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.group_rename)) },
                            onClick = {
                                menuOpen = false
                                openNameDialog(openGroup)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.group_delete)) },
                            onClick = {
                                menuOpen = false
                                playlists.deleteGroup(openGroup.id)
                                onOpenGroup(null)
                            },
                        )
                    }
                }
            }
            if (groupTracks.isEmpty()) {
                SectionPlaceholder(stringResource(R.string.group_tracks_empty))
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    itemsIndexed(groupTracks, key = { _, track -> track.uri.toString() }) { _, track ->
                        TrackRow(
                            track = track,
                            current = track.uri == library.selectedTrack?.uri,
                            onClick = { playFromLibrary(library, settings, controller, groupTracks.indexOf(track), groupTracks) },
                            onEdit = { onEditTrack(track) },
                            onRemoveFromGroup = { playlists.removeTrackFromGroup(openGroup.id, track.uri) },
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
        }
    }

    editingNameFor?.let { groupId ->
        AlertDialog(
            onDismissRequest = { editingNameFor = null },
            title = { Text(stringResource(if (groupId.isEmpty()) R.string.group_create else R.string.group_rename)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = {
                            nameInput = it
                            nameError = false
                        },
                        label = { Text(stringResource(R.string.group_name)) },
                        singleLine = true,
                        isError = nameError,
                    )
                    if (nameError) {
                        Text(
                            stringResource(R.string.group_name_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = if (groupId.isEmpty()) playlists.createGroup(nameInput)
                    else playlists.renameGroup(groupId, nameInput)
                    if (saved) editingNameFor = null else nameError = true
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingNameFor = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    selectingTracksFor?.let { groupId ->
        playlists.trackGroups.firstOrNull { it.id == groupId }?.let { group ->
            GroupTrackPicker(
                group = group,
                tracks = library.tracks,
                onSave = {
                    playlists.setGroupTracks(group.id, it)
                    selectingTracksFor = null
                },
                onDismiss = { selectingTracksFor = null },
            )
        }
    }
}

@Composable
private fun GroupTrackPicker(
    group: TrackGroup,
    tracks: List<AudioTrack>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(group.id, group.trackUris) { mutableStateOf(group.trackUris.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_choose_tracks, group.name)) },
        text = {
            if (tracks.isEmpty()) {
                Text(stringResource(R.string.no_tracks))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    itemsIndexed(tracks, key = { _, track -> track.uri.toString() }) { _, track ->
                        val key = track.uri.toString()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = if (key in selected) selected - key else selected + key }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = key in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + key else selected - key
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    track.artist ?: stringResource(R.string.unknown_artist),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Keep the group's existing order, then append whatever the picker
                // added, so saving from the picker never reshuffles the playlist.
                val ordered = group.trackUris.filter { it in selected } +
                    tracks.map { it.uri.toString() }.filter { it in selected && it !in group.trackUris }
                onSave(ordered)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
