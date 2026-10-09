package com.imankoppai.mediaanvil.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.model.AudioTrack

@Composable
internal fun GroupBrowser(
    groups: List<Pair<String, List<AudioTrack>>>,
    loading: Boolean = false,
    openGroup: String?,
    onOpenGroup: (String?) -> Unit,
    groupIcon: ImageVector,
    library: LibraryViewModel,
    playlists: PlaylistViewModel,
    settings: SettingsViewModel,
    controller: androidx.media3.session.MediaController?,
    onEditTrack: (AudioTrack) -> Unit,
) {
    if (loading) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }
    if (openGroup == null) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            itemsIndexed(groups, key = { _, group -> group.first }) { _, group ->
                val (name, tracks) = group
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenGroup(name) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(groupIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.folder_track_count, tracks.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenGroup(null) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    openGroup,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val groupTracks = groups.firstOrNull { it.first == openGroup }?.second ?: emptyList()
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                itemsIndexed(groupTracks, key = { _, track -> track.uri.toString() }) { _, track ->
                    TrackRow(
                        track = track,
                        current = track.uri == library.selectedTrack?.uri,
                        onClick = { playFromLibrary(library, settings, controller, groupTracks.indexOf(track), groupTracks) },
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
    }
}
