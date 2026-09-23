package com.imankoppai.mediaanvil.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.imankoppai.mediaanvil.data.DeviceAudioLibrary
import com.imankoppai.mediaanvil.data.FavoriteTracks
import com.imankoppai.mediaanvil.data.LibraryCache
import com.imankoppai.mediaanvil.data.LibraryScan
import com.imankoppai.mediaanvil.data.PlaybackPreferences
import com.imankoppai.mediaanvil.data.SafStorage
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.model.TrackGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lifecycle-aware library state shared by playback, settings, and tag editing. */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext

    val preferences = PlaybackPreferences(appContext)

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

    /** Epoch-ms deadline of the sleep timer, or null when off. */
    var sleepTimerEndAt by mutableStateOf<Long?>(null)
        private set

    /**
     * True when the media library can return audio rows. This needs only the audio
     * read permission — never "all files access".
     */
    fun hasStorageAccess(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            com.imankoppai.mediaanvil.data.DeviceAudioLibrary.readPermission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Mirrors [PlaybackPreferences.autoLoadLyrics] so open screens react immediately. */
    var autoLoadLyrics by mutableStateOf(preferences.autoLoadLyrics)
        private set

    fun updateAutoLoadLyrics(value: Boolean) {
        autoLoadLyrics = value
        preferences.autoLoadLyrics = value
    }

    /** Mirrors [PlaybackPreferences.resumePlayback] so the settings switch reacts immediately. */
    var resumePlayback by mutableStateOf(preferences.resumePlayback)
        private set

    fun updateResumePlayback(value: Boolean) {
        resumePlayback = value
        preferences.resumePlayback = value
    }

    /** Mirrors [PlaybackPreferences.showLyricsTimestamps] so open screens react immediately. */
    var showLyricsTimestamps by mutableStateOf(preferences.showLyricsTimestamps)
        private set

    fun updateShowLyricsTimestamps(value: Boolean) {
        showLyricsTimestamps = value
        preferences.showLyricsTimestamps = value
    }

    /**
     * Arms the sleep timer. The playback service watches the stored deadline and
     * applies the stop policy, so the timer keeps running across rotation and while
     * the app sits in the background.
     */
    fun startSleepTimer(minutes: Int) {
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        preferences.sleepTimerDeadlineAt = deadline
        sleepTimerEndAt = deadline
    }

    fun cancelSleepTimer() {
        preferences.sleepTimerDeadlineAt = 0L
        sleepTimerEndAt = null
    }

    /** Re-reads the deadline so screens reflect a timer that fired elsewhere. */
    fun refreshSleepTimer() {
        val deadline = preferences.sleepTimerDeadlineAt
        sleepTimerEndAt = deadline.takeIf { it > System.currentTimeMillis() }
    }

    /** User-created playlist-like groups, mirrored into preferences. */
    var trackGroups by mutableStateOf(preferences.trackGroups)
        private set

    var hiddenTrackUris by mutableStateOf(preferences.hiddenTrackUris)
        private set

    fun hideTrack(uri: Uri) {
        val key = uri.toString()
        val selectedUri = selectedTrack?.uri
        hiddenTrackUris = hiddenTrackUris + key
        preferences.hiddenTrackUris = hiddenTrackUris
        tracks = tracks.filterNot { it.uri == uri }
        selectedIndex = tracks.indexOfFirst { it.uri == selectedUri }
    }

    fun restoreHiddenTracks() {
        hiddenTrackUris = emptySet()
        preferences.hiddenTrackUris = emptySet()
        rescan(quiet = false)
    }

    fun reloadAfterPreferencesRestore() {
        trackGroups = preferences.trackGroups
        hiddenTrackUris = preferences.hiddenTrackUris
        favoriteTrackUris = preferences.favoriteTrackUris
        playHistory = preferences.playHistory()
        rescan(quiet = false)
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

    /** Replaces a group's tracks; the given order becomes the playback order. */
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

    /** A group's still-available tracks, in the order the user arranged them. */
    fun tracksInGroup(group: TrackGroup): List<AudioTrack> =
        LibraryQuery.resolve(tracks, { it.uri.toString() }, group.trackUris)

    /** Favourites mirrored into preferences, in the order the user added them. */
    var favoriteTrackUris by mutableStateOf(preferences.favoriteTrackUris)
        private set

    fun isFavorite(uri: Uri): Boolean = uri.toString() in favoriteTrackUris

    fun toggleFavorite(uri: Uri) {
        favoriteTrackUris = FavoriteTracks.toggle(favoriteTrackUris, uri.toString())
        preferences.favoriteTrackUris = favoriteTrackUris
    }

    /** Still-available favourites in saved order; missing audio is skipped. */
    val favoriteTracks: List<AudioTrack>
        get() = LibraryQuery.resolve(tracks, { it.uri.toString() }, favoriteTrackUris)

    /** Newest-first playback history, written by the playback service. */
    var playHistory by mutableStateOf(preferences.playHistory())
        private set

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
    val recentTracks: List<AudioTrack>
        get() = LibraryQuery.resolve(tracks, { it.uri.toString() }, playHistory.map { it.uri })

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
        // A sleep timer armed before the screen was recreated is still running.
        refreshSleepTimer()
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

    /** Newest GitHub release when an in-app update is available, else null. */
    var updateRelease by mutableStateOf<com.imankoppai.mediaanvil.update.AppRelease?>(null)
        private set

    /** -1 while idle, 0..100 while the update APK is downloading. */
    var updateProgress by mutableIntStateOf(-1)
        private set

    /** True once the update APK is fully downloaded and ready to install. */
    var updateApkReady by mutableStateOf(false)
        private set

    /** True when the last download attempt failed; offers a retry. */
    var updateFailed by mutableStateOf(false)
        private set

    fun reportUpdateRelease(release: com.imankoppai.mediaanvil.update.AppRelease?) {
        updateRelease = release
        updateProgress = -1
        updateApkReady = false
        updateFailed = false
    }

    fun dismissUpdate() {
        updateRelease = null
    }

    /** Silent startup check for a newer GitHub release, throttled to once a day.
        The timestamp is only recorded on success so a failed check (offline)
        is retried on the next launch instead of being suppressed for a day. */
    fun maybeCheckForUpdate() {
        val now = System.currentTimeMillis()
        if (now - preferences.updateLastCheckAt < UPDATE_CHECK_INTERVAL_MS) return
        viewModelScope.launch {
            val release = withContext(Dispatchers.IO) {
                runCatching { com.imankoppai.mediaanvil.update.AppUpdateChecker.fetchLatest() }.getOrNull()
            } ?: return@launch
            preferences.updateLastCheckAt = System.currentTimeMillis()
            val current = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            }.getOrNull() ?: return@launch
            if (com.imankoppai.mediaanvil.update.AppUpdateChecker.isNewer(release.tagName, current)) {
                reportUpdateRelease(release)
            }
        }
    }

    fun startUpdateDownload() {
        val release = updateRelease ?: return
        if (updateProgress >= 0) return
        updateProgress = 0
        updateFailed = false
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.imankoppai.mediaanvil.update.AppUpdateInstaller.downloadApk(
                        appContext,
                        release.apkUrl,
                        release.sha256Url,
                    ) { percent ->
                        viewModelScope.launch {
                            if (updateProgress in 0..99) updateProgress = percent
                        }
                    }
                }
            }
            result.onSuccess {
                updateProgress = 100
                updateFailed = false
                updateApkReady = true
                if (com.imankoppai.mediaanvil.update.AppUpdateInstaller.canInstall(appContext)) {
                    installDownloadedUpdate()
                }
            }.onFailure {
                updateProgress = -1
                updateApkReady = false
                updateFailed = true
            }
        }
    }

    /** Launches the system installer, or the one-time unknown-sources permission page. */
    fun installDownloadedUpdate() {
        if (!updateApkReady) return
        val installer = com.imankoppai.mediaanvil.update.AppUpdateInstaller
        if (installer.canInstall(appContext)) {
            installer.install(appContext, installer.apkFile(appContext))
        } else {
            installer.unknownSourcesSettings(appContext)
        }
    }

    companion object {
        private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
