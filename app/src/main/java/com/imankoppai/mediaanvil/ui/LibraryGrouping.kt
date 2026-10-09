package com.imankoppai.mediaanvil.ui

import androidx.compose.runtime.*
import com.imankoppai.mediaanvil.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class LibraryGroups(
    val source: List<AudioTrack>,
    val albumLabel: String,
    val artistLabel: String,
    val albums: List<Pair<String, List<AudioTrack>>>,
    val artists: List<Pair<String, List<AudioTrack>>>,
)

internal fun groupLibrary(tracks: List<AudioTrack>, albumLabel: String, artistLabel: String,
    checkCancelled: () -> Unit = {}): LibraryGroups {
    fun group(selector: (AudioTrack) -> String?): List<Pair<String, List<AudioTrack>>> {
        val result = linkedMapOf<String, MutableList<AudioTrack>>()
        tracks.forEach { track ->
            checkCancelled()
            result.getOrPut(selector(track).orEmpty()) { mutableListOf() }.add(track)
        }
        return result.map { it.key to it.value.toList() }.sortedBy { it.first.lowercase() }
    }
    return LibraryGroups(tracks, albumLabel, artistLabel,
        group { it.album?.takeIf(String::isNotBlank) ?: albumLabel },
        group { it.artist?.takeIf(String::isNotBlank) ?: artistLabel })
}

@Composable
internal fun rememberLibraryGroups(tracks: List<AudioTrack>, albumLabel: String, artistLabel: String): LibraryGroups? {
    val result by produceState<LibraryGroups?>(null, tracks, albumLabel, artistLabel) {
        value = withContext(Dispatchers.Default) {
            groupLibrary(tracks, albumLabel, artistLabel) { coroutineContext.ensureActive() }
        }
    }
    return result?.takeIf { it.source === tracks && it.albumLabel == albumLabel && it.artistLabel == artistLabel }
}
