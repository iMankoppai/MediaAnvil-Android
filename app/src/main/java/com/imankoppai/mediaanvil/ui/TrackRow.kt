package com.imankoppai.mediaanvil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.media3.common.Player
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.model.AudioTrack

internal val LocalFullPlayCounts = androidx.compose.runtime.compositionLocalOf<Map<String, Long>> { emptyMap() }

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun TrackRow(
    track: AudioTrack,
    current: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onRemoveFromGroup: (() -> Unit)? = null,
    onRemoveFromPlayer: (() -> Unit)? = null,
    selectable: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    highlight: String = "",
    detail: String? = null,
    finished: Boolean = false,
    onToggleFinished: (() -> Unit)? = null,
) {
    var actionsOpen by remember(track.uri) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    current -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else -> Color.Transparent
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect?.invoke() })
        } else {
            TrackCover(track, size = 52.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            HighlightedText(
                text = track.title,
                highlight = highlight,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                track.artist ?: stringResource(R.string.unknown_artist),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatLabel(track)} · ${formatTime(track.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
        if (!selectable && onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(
                        if (isFavorite) R.string.favorite_remove else R.string.favorite_add,
                    ),
                    tint = if (isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        val playCount = LocalFullPlayCounts.current[track.uri.toString()] ?: 0L
        if (!selectable && playCount > 0) {
            val description = androidx.compose.ui.res.pluralStringResource(R.plurals.full_play_count_description, if (playCount == 1L) 1 else 2, playCount)
            Row(Modifier.padding(start = 4.dp, end = 4.dp).clearAndSetSemantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.full_play_count_short, playCount), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!selectable && (onEdit != null || onRemoveFromGroup != null || onRemoveFromPlayer != null || onToggleFinished != null)) {
            Box {
                IconButton(onClick = { actionsOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.track_actions))
                }
                DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                    onToggleFinished?.let { toggle ->
                        DropdownMenuItem(
                            text = { Text(stringResource(if (finished) R.string.mark_unfinished else R.string.mark_finished)) },
                            onClick = { actionsOpen = false; toggle() },
                        )
                    }
                    onEdit?.let { edit ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit_tags)) },
                            onClick = {
                                actionsOpen = false
                                edit()
                            },
                        )
                    }
                    onRemoveFromGroup?.let { remove ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove_from_group)) },
                            onClick = {
                                actionsOpen = false
                                remove()
                            },
                        )
                    }
                    onRemoveFromPlayer?.let { remove ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove_from_player)) },
                            onClick = {
                                actionsOpen = false
                                remove()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders [text] with every occurrence of the search terms picked out in the primary
 * colour, so a match is visible even when it sits in a field the row does not show.
 */
@Composable
private fun HighlightedText(
    text: String,
    highlight: String,
    style: TextStyle,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    val terms = remember(highlight) {
        highlight.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    val annotated: AnnotatedString = remember(text, terms) {
        if (terms.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                terms.forEach { term ->
                    var from = text.indexOf(term, ignoreCase = true)
                    while (from >= 0) {
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), from, from + term.length)
                        from = text.indexOf(term, from + term.length, ignoreCase = true)
                    }
                }
            }
        }
    }
    Text(
        annotated,
        style = style,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun removeTrackFromPlayer(
    library: LibraryViewModel,
    playlists: PlaylistViewModel,
    controller: androidx.media3.session.MediaController?,
    track: AudioTrack,
) {
    controller?.let { player ->
        val queueIndex = (0 until player.mediaItemCount)
            .firstOrNull { player.getMediaItemAt(it).mediaId == track.uri.toString() }
        if (queueIndex != null) player.removeMediaItem(queueIndex)
    }
    playlists.hideTrack(track.uri)
    library.refreshHiddenTracks()
}
