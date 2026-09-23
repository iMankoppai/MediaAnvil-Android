package com.imankoppai.mediaanvil.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.imankoppai.mediaanvil.data.FavoriteTracks
import com.imankoppai.mediaanvil.data.PlaybackPreferences
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.model.TrackGroup

/**
 * Everything the user curates: playlists, favourites, the play history and the
 * tracks hidden from the player.
 *
 * Split out of [LibraryViewModel] because none of this is produced by a scan — it
 * is user data that merely *refers* to scanned tracks by uri. Keeping it separate
 * means a rescan cannot disturb it, and the resolve step (which drops entries whose
 * audio has since disappeared) is a pure function of the scanned list, so it is
 * computed on read instead of being cached out of step.
 */
class PlaylistViewModel(application: Application) : AndroidViewModel(application) {
    val preferences = PlaybackPreferences(application.applicationContext)

    /** User-created playlists, mirrored into storage. */
    var trackGroups by mutableStateOf(preferences.trackGroups)
        private set

    /** Tracks hidden from the player library; the underlying documents are untouched. */
    var hiddenTrackUris by mutableStateOf(preferences.hiddenTrackUris)
        private set

    /** Favourites, in the order the user added them. */
    var favoriteTrackUris by mutableStateOf(preferences.favoriteTrackUris)
        private set

    /** Newest-first playback history, written by the playback service. */
    var playHistory by mutableStateOf(preferences.playHistory())
        private set

    fun hideTrack(uri: Uri) {
        hiddenTrackUris = hiddenTrackUris + uri.toString()
        preferences.hiddenTrackUris = hiddenTrackUris
    }

    fun restoreHiddenTracks() {
        hiddenTrackUris = emptySet()
        preferences.hiddenTrackUris = emptySet()
    }

    /** Re-reads every stored list, e.g. after a backup was imported over it. */
    fun reload() {
        trackGroups = preferences.trackGroups
        hiddenTrackUris = preferences.hiddenTrackUris
        favoriteTrackUris = preferences.favoriteTrackUris
        playHistory = preferences.playHistory()
    }

    fun createGroup(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || trackGroups.any { it.name.equals(clean, ignoreCase = true) }) return false
        trackGroups = trackGroups + TrackGroup(java.util.UUID.randomUUID().toString(), clean, emptyList())
        preferences.trackGroups = trackGroups
        return true
    }

    fun renameGroup(id: String, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || trackGroups.any { it.id != id && it.name.equals(clean, ignoreCase = true) }) return false
        trackGroups = trackGroups.map { if (it.id == id) it.copy(name = clean) else it }
        preferences.trackGroups = trackGroups
        return true
    }

    fun deleteGroup(id: String) {
        trackGroups = trackGroups.filterNot { it.id == id }
        preferences.trackGroups = trackGroups
    }

    /** Replaces a playlist's tracks; the given order becomes the playback order. */
    fun setGroupTracks(id: String, uris: List<String>) {
        val cleaned = uris.filter { it.isNotBlank() }.distinct()
        trackGroups = trackGroups.map { if (it.id == id) it.copy(trackUris = cleaned) else it }
        preferences.trackGroups = trackGroups
    }

    /** Appends tracks the user selected, keeping their existing order first. */
    fun addTracksToGroup(id: String, uris: Collection<String>) {
        val group = trackGroups.firstOrNull { it.id == id } ?: return
        setGroupTracks(id, group.trackUris + uris)
    }

    fun removeTrackFromGroup(id: String, uri: Uri) {
        val group = trackGroups.firstOrNull { it.id == id } ?: return
        setGroupTracks(id, group.trackUris - uri.toString())
    }

    /** A playlist's still-available tracks, in the order the user arranged them. */
    fun tracksInGroup(group: TrackGroup, tracks: List<AudioTrack>): List<AudioTrack> =
        LibraryQuery.resolve(tracks, { it.uri.toString() }, group.trackUris)

    fun isFavorite(uri: Uri): Boolean = uri.toString() in favoriteTrackUris

    fun toggleFavorite(uri: Uri) {
        favoriteTrackUris = FavoriteTracks.toggle(favoriteTrackUris, uri.toString())
        preferences.favoriteTrackUris = favoriteTrackUris
    }

    /** Still-available favourites in saved order; missing audio is skipped. */
    fun favoriteTracks(tracks: List<AudioTrack>): List<AudioTrack> =
        LibraryQuery.resolve(tracks, { it.uri.toString() }, favoriteTrackUris)

    /** Records a track as played and refreshes the visible history. */
    fun recordPlayed(uri: String) {
        preferences.recordPlayed(uri)
        playHistory = preferences.playHistory()
    }

    /** Re-reads the history so a track recorded by the service shows up here. */
    fun refreshPlayHistory() {
        playHistory = preferences.playHistory()
    }

    /** Recently played tracks that are still accessible, newest first. */
    fun recentTracks(tracks: List<AudioTrack>): List<AudioTrack> =
        LibraryQuery.resolve(tracks, { it.uri.toString() }, playHistory.map { it.uri })
}
