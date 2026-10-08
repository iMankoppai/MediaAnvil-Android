package com.imankoppai.mediaanvil.ui

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** True when the latest scan failed and the library is showing its previous snapshot. */
    var scanFailed by mutableStateOf(false)
        private set

    /** Human-readable message for the latest playback failure, if any. */
    var playbackError by mutableStateOf<String?>(null)

    /** Tracks hidden from the player library, kept here so a scan can filter them out. */
    private var hiddenTrackUris: Set<String> = preferences.hiddenTrackUris

    private var scanJob: Job? = null
    private var pendingRescan = false
    private var pendingFullScan = false
    private var observerJob: Job? = null
    private val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val path = uri?.path.orEmpty()
            if (path.isNotEmpty() && !path.contains("/audio") && !path.contains("/file")) return
            observerJob?.cancel()
            observerJob = viewModelScope.launch {
                kotlinx.coroutines.delay(800)
                if (hasStorageAccess()) rescan(quiet = true)
            }
        }
    }

    init { appContext.contentResolver.registerContentObserver("content://media".toUri(), true, mediaObserver) }

    /** Serializes cache writes so a lyric edit cannot interleave with a scan snapshot. */
    private val cacheWriteMutex = Mutex()

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
        val scan = LibraryScan(tracks)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                cacheWriteMutex.withLock {
                    val cached = LibraryCache.load(appContext)?.asScan()
                    val updates = scan.tracks.associateBy { it.uri }
                    val combined = cached?.tracks?.map { updates[it.uri] ?: it } ?: scan.tracks
                    LibraryCache.save(appContext, (cached ?: scan).copy(tracks = combined))
                }
            }
        }
    }

    /**
     * Finds the sidecar lyrics sitting beside a track by looking inside the folders the
     * user granted. Without "all files access" the app cannot list an audio folder on
     * its own, so this only returns a result once a grant covers the track's folder.
     */
    fun findSidecarLyrics(track: AudioTrack): Pair<Uri, String>? =
        runCatching { SafStorage.findSubtitle(appContext, track) }.getOrNull()

    /**
     * Show the cached library instantly, then refresh quietly in the background.
     *
     * The cache read is disk I/O and used to run on the main thread, because this is
     * called from a `LaunchedEffect` during the first composition. It is moved to
     * [Dispatchers.IO]; [loading] is raised for the duration so the empty state does
     * not flash "no audio files" in the frame before the cache arrives.
     */
    fun startup() {
        hiddenTrackUris = preferences.hiddenTrackUris
        loading = tracks.isEmpty()
        scanFailed = false
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { LibraryCache.load(appContext) }
            if (snapshot != null) {
                tracks = snapshot.tracks.map { record ->
                    AudioTrack(
                        uri = record.uri,
                        fileName = record.fileName,
                        title = record.title,
                        artist = record.artist,
                        album = record.album,
                        durationMs = record.durationMs,
                        sizeBytes = record.sizeBytes,
                        subtitleUri = record.subtitleUri,
                        subtitleExtension = record.subtitleExtension,
                        relativeFolder = record.relativeFolder,
                    )
                }.filter { DeviceAudioLibrary.isFolderAllowed(it.relativeFolder, allowedScanFolders()) }
                    .filterNot { it.uri.toString() in hiddenTrackUris }
                files = LibraryScan(tracks)
                loading = false
                scanAll(quiet = true)
            } else {
                scanAll(quiet = false)
            }
        }
    }

    fun rescan(quiet: Boolean = false, full: Boolean = false, onLoaded: (Int) -> Unit = {}) {
        scanAll(quiet, onLoaded, full)
    }

    private fun scanAll(quiet: Boolean, onLoaded: (Int) -> Unit = {}, full: Boolean = false) {
        if (scanJob?.isActive == true) {
            pendingRescan = true
            pendingFullScan = pendingFullScan || full
            return
        }
        loading = !quiet
        if (!quiet) message = null
        val allowedFolders = allowedScanFolders()
        scanJob = viewModelScope.launch {
            val previousUri = tracks.getOrNull(selectedIndex)?.uri
            // Everything that touches the disk, the ContentResolver or the Storage
            // Access Framework happens inside this one IO block. Sidecar lookup in
            // particular issues a document query per track, and it used to run after
            // the block returned — i.e. back on the main thread — so a large library
            // stalled the frame that was supposed to show the scan results.
            val result = withContext(Dispatchers.IO) { cacheWriteMutex.withLock {
                val scanned = try {
                    DeviceAudioLibrary.scan(appContext, allowedFolders, LibraryCache.load(appContext), full)
                } catch (scanFailure: Exception) {
                    null
                }
                if (scanned == null) {
                    scanFailed = true
                    return@withLock null
                }
                // Sidecar lyrics are located inside user-granted folders; without a
                // grant the track simply shows no lyrics instead of failing the scan.
                val withSidecars = scanned.copy(tracks = attachKnownSidecars(scanned.tracks))
                // Keep the complete scan in cache; hidden tracks are filtered only in
                // the UI state. This makes restoring them reliable even if the app
                // closes during the restore scan. The mutex keeps this from racing a
                // lyric/tag edit that persisted its own snapshot.
                LibraryCache.save(appContext, withSidecars)
                android.util.Log.d("MediaAnvilScan", "incremental=${scanned.incremental} metadataRows=${scanned.metadataRowsRead} tracks=${scanned.tracks.size}")
                withSidecars
            } } ?: run {
                loading = false
                if (!quiet) message = appContext.getString(com.imankoppai.mediaanvil.R.string.scan_failed)
                onLoaded(tracks.size)
                return@launch
            }
            val visibleTracks = result.tracks.filterNot { it.uri.toString() in hiddenTrackUris }
            files = result.copy(tracks = visibleTracks)
            tracks = visibleTracks
            selectedIndex = visibleTracks.indexOfFirst { it.uri == previousUri }
            scanFailed = false
            loading = false
            if (visibleTracks.isEmpty() && !quiet) {
                message = appContext.getString(com.imankoppai.mediaanvil.R.string.no_tracks)
            }
            onLoaded(visibleTracks.size)
        }.also { job -> job.invokeOnCompletion {
            if (pendingRescan) viewModelScope.launch {
                val force = pendingFullScan
                pendingRescan = false; pendingFullScan = false
                scanAll(quiet = true, full = force)
            }
        } }
    }

    override fun onCleared() {
        appContext.contentResolver.unregisterContentObserver(mediaObserver)
        super.onCleared()
    }

    /** Fills in sidecar lyrics for tracks whose folder the user has already granted. */
    private fun attachKnownSidecars(source: List<AudioTrack>): List<AudioTrack> {
        val found = SafStorage.findSubtitles(appContext, source.filter { it.subtitleUri == null })
        return source.map { track ->
            val sidecar = found[track.uri]
            if (track.subtitleUri != null || sidecar == null) track
            else track.copy(subtitleUri = sidecar.first, subtitleExtension = sidecar.second)
        }
    }

    /** Reflect an in-place tag edit immediately; MediaStore metadata lags behind. */
    fun applyTagEdit(uri: Uri, title: String, artist: String?) {
        val cleanArtist = artist?.takeIf(String::isNotBlank)
        tracks = tracks.map { track ->
            if (track.uri == uri) track.copy(title = title, artist = cleanArtist) else track
        }
        // Cache read/update/write is JSON file I/O; run it off the main thread.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                cacheWriteMutex.withLock {
                    val cached = LibraryCache.load(appContext) ?: return@withLock
                    val updatedTracks = cached.tracks.map { record ->
                        if (record.uri == uri) record.copy(title = title, artist = cleanArtist) else record
                    }
                    LibraryCache.save(
                        appContext,
                        LibraryScan(tracks = updatedTracks.map { record ->
                            AudioTrack(
                                uri = record.uri,
                                fileName = record.fileName,
                                title = record.title,
                                artist = record.artist,
                                album = record.album,
                                durationMs = record.durationMs,
                                sizeBytes = record.sizeBytes,
                                subtitleUri = record.subtitleUri,
                                subtitleExtension = record.subtitleExtension,
                                relativeFolder = record.relativeFolder,
                            )
                        }),
                    )
                }
            }
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
