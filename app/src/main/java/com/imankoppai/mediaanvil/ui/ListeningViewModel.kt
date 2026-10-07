package com.imankoppai.mediaanvil.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imankoppai.mediaanvil.data.AudioBookmark
import com.imankoppai.mediaanvil.data.Bookmarks
import com.imankoppai.mediaanvil.data.PlaybackPreferences
import com.imankoppai.mediaanvil.data.PlayHistory
import kotlinx.coroutines.*
import org.json.JSONObject

/** Shared observable listening data. Parsing and persistence stay off the UI thread. */
class ListeningViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PlaybackPreferences(application)
    var bookmarks by mutableStateOf<List<AudioBookmark>>(emptyList())
        private set
    var positions by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    var finishedUris by mutableStateOf<Set<String>>(emptySet())
        private set
    var recentEntries by mutableStateOf<List<PlayHistory.Entry>>(emptyList())
        private set
    private val editDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var refreshJob: Job? = null
    private val listener: (String) -> Unit = { key ->
        if (key in setOf("audio_bookmarks", "playback_positions", "finished_track_uris", "play_history")) refresh()
    }

    init {
        preferences.registerChangeListener(listener)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshJob?.cancel()
            refreshJob = launch {
                val snapshot = withContext(Dispatchers.IO) {
                    val json = runCatching { JSONObject(preferences.playbackPositions) }.getOrElse { JSONObject() }
                    Snapshot(Bookmarks.decode(preferences.bookmarksRaw),
                        json.keys().asSequence().associateWith { json.optLong(it).coerceAtLeast(0) },
                        preferences.finishedTrackUris, preferences.playHistory())
                }
                bookmarks = snapshot.bookmarks
                positions = snapshot.positions
                finishedUris = snapshot.finished
                recentEntries = snapshot.recent
            }
        }
    }

    fun addBookmark(uri: String, positionMs: Long, note: String) {
        viewModelScope.launch(editDispatcher) {
            val entry = AudioBookmark(java.util.UUID.randomUUID().toString(), uri, positionMs.coerceAtLeast(0), note.trim().take(500), System.currentTimeMillis())
            preferences.bookmarksRaw = Bookmarks.encode(Bookmarks.decode(preferences.bookmarksRaw) + entry)
        }
    }

    fun editBookmark(id: String, note: String) {
        viewModelScope.launch(editDispatcher) {
            preferences.bookmarksRaw = Bookmarks.encode(Bookmarks.decode(preferences.bookmarksRaw).map {
                if (it.id == id) it.copy(note = note.trim().take(500)) else it
            })
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch(editDispatcher) {
            preferences.bookmarksRaw = Bookmarks.encode(Bookmarks.decode(preferences.bookmarksRaw).filterNot { it.id == id })
        }
    }

    fun markFinished(uri: String, finished: Boolean) {
        viewModelScope.launch(editDispatcher) { preferences.markFinished(uri, finished) }
    }

    override fun onCleared() {
        preferences.unregisterChangeListener(listener)
        super.onCleared()
    }

    private data class Snapshot(val bookmarks: List<AudioBookmark>, val positions: Map<String, Long>, val finished: Set<String>, val recent: List<PlayHistory.Entry>)
}
