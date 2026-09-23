package com.imankoppai.mediaanvil.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.imankoppai.mediaanvil.data.DeviceAudioLibrary
import com.imankoppai.mediaanvil.data.LibraryCache
import com.imankoppai.mediaanvil.data.LibraryScan
import com.imankoppai.mediaanvil.data.PlaybackPreferences
import com.imankoppai.mediaanvil.data.SafStorage
import com.imankoppai.mediaanvil.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The scanned media library: which audio files were found, which one is selected,
 * and the sidecar lyrics attached to them.
 *
 * Playlists, favourites and history live in [PlaylistViewModel], settings in
 * [SettingsViewModel], and the update flow in [UpdateViewModel]; this class is only
 * the scan and its results.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext

    private val preferences = PlaybackPreferences(appContext)

    var tracks by mutableStateOf<List<AudioTrack>>(emptyList())
        private set
    var files by mutableStateOf<LibraryScan?>(null)
        private set
    var selectedIndex by mutableIntStateOf(-1)
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    /** Human-readable message for the latest playback failure, if any. */
    var playbackError by mutableStateOf<String?>(null)

    /** Tracks hidden from the player library, kept here so a scan can filter them out. */
    private var hiddenTrackUris: Set<String> = preferences.hiddenTrackUris

    /**
     * True when the media library can return audio rows. This needs only the audio
     * read permission — never "all files access".
     */
    fun hasStorageAccess(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            DeviceAudioLibrary.readPermission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Re-reads which tracks are hidden and drops them from the visible list, so the
     * library reacts at once after a track is hidden or restored.
     */
    fun refreshHiddenTracks() {
        hiddenTrackUris = preferences.hiddenTrackUris
        tracks = tracks.filterNot { it.uri.toString() in hiddenTrackUris }
    }

    val selectedTrack: AudioTrack? get() = tracks.getOrNull(selectedIndex)

    fun attachLyrics(trackUri: Uri, lyricsUri: Uri, extension: String) {
        val selectedUri = selectedTrack?.uri
        tracks = tracks.map { track ->
            if (track.uri == trackUri) {
                track.copy(subtitleUri = lyricsUri, subtitleExtension = extension.lowercase())
            } else {
                track
            }
        }
        selectedIndex = tracks.indexOfFirst { it.uri == selectedUri }
        persistCurrentScan()
    }

    fun detachLyrics(trackUri: Uri) {
        val selectedUri = selectedTrack?.uri
        tracks = tracks.map { track ->
            if (track.uri == trackUri) track.copy(subtitleUri = null, subtitleExtension = null) else track
        }
        selectedIndex = tracks.indexOfFirst { it.uri == selectedUri }
        persistCurrentScan()
    }

    /** Keeps the cache in step after an in-place change such as a lyric or tag edit. */
    private fun persistCurrentScan() {
        files = LibraryScan(tracks)
        LibraryCache.save(appContext, LibraryScan(tracks))
    }

    /**
     * Finds the sidecar lyrics sitting beside a track by looking inside the folders the
     * user granted. Without "all files access" the app cannot list an audio folder on
     * its own, so this only returns a result once a grant covers the track's folder.
     */
    fun findSidecarLyrics(track: AudioTrack): Pair<Uri, String>? =
        runCatching { SafStorage.findSubtitle(appContext, track) }.getOrNull()

    /** Show the cached library instantly, then refresh quietly in the background. */
    fun startup() {
        hiddenTrackUris = preferences.hiddenTrackUris
        val snapshot = LibraryCache.load(appContext)
        if (snapshot != null) {
            tracks = snapshot.tracks.map { record ->
                AudioTrack(
                    uri = record.uri,
                    fileName = record.fileName,
                    title = record.title,
                    artist = record.artist,
                    album = record.album,
                    durationMs = record.durationMs,
                    subtitleUri = record.subtitleUri,
                    subtitleExtension = record.subtitleExtension,
                    relativeFolder = record.relativeFolder,
                )
            }.filterNot { it.uri.toString() in hiddenTrackUris }
            files = LibraryScan(tracks)
            if (!LibraryCache.isFresh(snapshot)) {
                scanAll(quiet = true)
            }
        } else {
            scanAll(quiet = false)
        }
    }

    fun rescan(quiet: Boolean = false, onLoaded: (Int) -> Unit = {}) {
        scanAll(quiet, onLoaded)
    }

    private fun scanAll(quiet: Boolean, onLoaded: (Int) -> Unit = {}) {
        loading = !quiet
        if (!quiet) message = null
        val allowedFolders = allowedScanFolders()
        viewModelScope.launch {
            val previousUri = tracks.getOrNull(selectedIndex)?.uri
            val result = withContext(Dispatchers.IO) {
                runCatching { DeviceAudioLibrary.scan(appContext, allowedFolders) }
                    .getOrElse { LibraryScan(emptyList(), emptyList()) }
            }
            val visibleResult = result.copy(
                tracks = result.tracks.filterNot { it.uri.toString() in hiddenTrackUris },
            )
            files = visibleResult
            tracks = visibleResult.tracks
            selectedIndex = visibleResult.tracks.indexOfFirst { it.uri == previousUri }
            loading = false
            // Sidecar lyrics are located inside user-granted folders; without a grant
            // the track simply shows no lyrics instead of failing the scan.
            tracks = attachKnownSidecars(tracks)
            // Keep the complete scan in cache; hidden tracks are filtered only in the UI state.
            // This makes restoring them reliable even if the app closes during the restore scan.
            LibraryCache.save(appContext, result)
            if (visibleResult.tracks.isEmpty() && !quiet) {
                message = appContext.getString(com.imankoppai.mediaanvil.R.string.no_tracks)
            }
            onLoaded(visibleResult.tracks.size)
        }
    }

    /** Fills in sidecar lyrics for tracks whose folder the user has already granted. */
    private fun attachKnownSidecars(source: List<AudioTrack>): List<AudioTrack> =
        source.map { track ->
            if (track.subtitleUri != null) return@map track
            val found = findSidecarLyrics(track) ?: return@map track
            track.copy(subtitleUri = found.first, subtitleExtension = found.second)
        }

    /** Reflect an in-place tag edit immediately; MediaStore metadata lags behind. */
    fun applyTagEdit(uri: Uri, title: String, artist: String?) {
        val cleanArtist = artist?.takeIf(String::isNotBlank)
        tracks = tracks.map { track ->
            if (track.uri == uri) track.copy(title = title, artist = cleanArtist) else track
        }
        LibraryCache.load(appContext)?.let { cached ->
            val updatedTracks = cached.tracks.map { record ->
                if (record.uri == uri) record.copy(title = title, artist = cleanArtist) else record
            }
            LibraryCache.save(
                appContext,
                LibraryScan(
                    tracks = updatedTracks.map { record ->
                        AudioTrack(
                            uri = record.uri,
                            fileName = record.fileName,
                            title = record.title,
                            artist = record.artist,
                            album = record.album,
                            durationMs = record.durationMs,
                            subtitleUri = record.subtitleUri,
                            subtitleExtension = record.subtitleExtension,
                            relativeFolder = record.relativeFolder,
                        )
                    },
                ),
            )
        }
        // The media library caches its own metadata row; ask it to re-read the file so a
        // later scan reports the edited title instead of the stale one.
        runCatching {
            android.media.MediaScannerConnection.scanFile(appContext, arrayOf(uri.toString()), null, null)
        }
    }

    /** Folders scanned in "folders" mode; empty means scan everything. */
    private fun allowedScanFolders(): Set<String> =
        if (preferences.libraryScanMode == "folders") preferences.scanFolders else emptySet()
}
