package com.imankoppai.mediaanvil.ui

import androidx.compose.runtime.*
import com.imankoppai.mediaanvil.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class LibrarySearchResult(
    val source: List<AudioTrack>, val query: String, val sort: String, val tracks: List<AudioTrack>, val loading: Boolean = false,
)

/** Index once per library snapshot. Keyed effects cancel old work when input changes. */
@Composable
internal fun rememberLibrarySearch(tracks: List<AudioTrack>, query: String, sort: String): LibrarySearchResult {
    val index by produceState<LibraryQuery.Index?>(null, tracks) {
        value = withContext(Dispatchers.Default) { LibraryQuery.Index(tracks) { coroutineContext.ensureActive() } }
    }
    val result by produceState<LibrarySearchResult?>(null, index, tracks, query, sort) {
        val ready = index?.takeIf { it.source === tracks } ?: return@produceState
        if (query.isNotBlank()) delay(150)
        value = withContext(Dispatchers.Default) {
            LibrarySearchResult(tracks, query, sort, ready.search(query, sort) { coroutineContext.ensureActive() })
        }
    }
    return result?.takeIf { it.source === tracks && it.query == query && it.sort == sort }
        ?: LibrarySearchResult(tracks, query, sort, emptyList(), loading = true)
}
