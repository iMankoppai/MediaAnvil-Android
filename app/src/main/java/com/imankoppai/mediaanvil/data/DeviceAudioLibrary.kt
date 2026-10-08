package com.imankoppai.mediaanvil.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.tags.WavInfoTagIO

/**
 * Reads supported audio files indexed in the media library.
 *
 * Scanning never needs broad storage access: the media library already indexes every
 * audio file on the device, so [READ_MEDIA_AUDIO] (or the legacy read permission) is
 * enough. Folders are described with the library's own relative path
 * ("Music/sub/") instead of an absolute file path, which is what lets the player run
 * without "all files access".
 */
object DeviceAudioLibrary {
    /** Raised when none of the media-library audio collections could be queried. */
    class ScanException(failures: List<Throwable>) : Exception(failures.firstOrNull()?.message, failures.firstOrNull())

    private val audioExtensions = setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "opus")
    val subtitleExtensions = listOf("lrc", "srt", "vtt")

    /** Permission that lets the media library return audio rows on this OS version. */
    val readPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun scan(context: Context, allowedFolders: Set<String> = emptySet(), previous: LibraryCache.Snapshot? = null,
        full: Boolean = false): LibraryScan {
        val cached = previous?.asScan()?.tracks.orEmpty()
        val tracks = mutableListOf<AudioTrack>()
        val checkpoints = mutableMapOf<String, MediaCheckpoint>()
        var reused = 0
        var rowsRead = 0
        audioCollections(context).forEach { collection ->
            val volume = collection.pathSegments.first()
            try {
                // Capture before querying. Changes during the scan have a larger generation
                // and are read again next time, never skipped by an advanced checkpoint.
                val checkpoint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
                    MediaCheckpoint(MediaStore.getVersion(context, volume), MediaStore.getGeneration(context, volume))
                }.getOrNull() else null
                val prior = previous?.checkpoints?.get(volume)
                val canReuse = !full && checkpoint != null && IncrementalLibrary.reusable(prior, checkpoint,
                    previous?.folderScope.orEmpty(), allowedFolders)
                val volumeCached = cached.filter { it.uri.pathSegments.firstOrNull() == volume }
                val delta = if (canReuse && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
                    val old = checkNotNull(prior)
                    val live = linkedSetOf<String>()
                    checkNotNull(context.contentResolver.query(collection, arrayOf(MediaStore.Audio.Media._ID), null, null, null))
                        .use { cursor -> while (cursor.moveToNext()) live += ContentUris.withAppendedId(collection, cursor.getLong(0)).toString() }
                    val changes = if (checkpoint.generation == old.generation) TrackRows(emptyList(), emptySet(), 0) else
                        queryTracks(context, collection, allowedFolders, "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?",
                            arrayOf(old.generation.toString()))
                    // Rows moving outside the selected folder scope are also changes:
                    // remove every changed ID before adding the rows that remain allowed.
                    IncrementalLibrary.merge(volumeCached.filterNot { it.uri.toString() in changes.ids }, changes.tracks, live) { it.uri.toString() } to changes.rowsRead
                }.getOrNull() else null
                if (delta != null) {
                    tracks += delta.first
                    rowsRead += delta.second
                    reused++
                } else {
                    val result = queryTracks(context, collection, allowedFolders)
                    tracks += result.tracks
                    rowsRead += result.rowsRead
                }
                if (checkpoint != null) checkpoints[volume] = checkpoint
            } catch (failure: Exception) {
                // Never cache a partial result if one volume or provider query fails.
                throw ScanException(listOf(failure))
            }
        }
        return LibraryScan(tracks.distinctBy { it.uri }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName }),
            checkpoints = checkpoints, folderScope = allowedFolders, incremental = reused > 0, metadataRowsRead = rowsRead)
    }

    private data class TrackRows(val tracks: List<AudioTrack>, val ids: Set<String>, val rowsRead: Int)

    private fun queryTracks(context: Context, collection: Uri, allowedFolders: Set<String>, selection: String? = null,
        args: Array<String>? = null): TrackRows {
        val tracks = mutableListOf<AudioTrack>()
        val ids = linkedSetOf<String>()
        var rowsRead = 0
        // RELATIVE_PATH only exists from API 29; older devices still expose DATA,
        // which the legacy read permission covers.
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            if (useRelativePath) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        checkNotNull(context.contentResolver.query(collection, projection, selection, args, null)).use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val pathColumn = cursor.getColumnIndex(
                if (useRelativePath) MediaStore.Audio.Media.RELATIVE_PATH
                else MediaStore.Audio.Media.DATA,
            )
            while (cursor.moveToNext()) {
                rowsRead++
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                ids += uri.toString()
                val name = cursor.getString(nameColumn).orEmpty()
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in audioExtensions) continue
                val rawPath = pathColumn.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                val relativeFolder = if (useRelativePath) {
                    normalizeRelativeFolder(rawPath)
                } else {
                    @Suppress("DEPRECATION")
                    relativeFolderFromAbsolute(rawPath)
                }
                if (!isFolderAllowed(relativeFolder, allowedFolders)) continue
                var title = cursor.getString(titleColumn).cleanMetadata()
                var artist = cursor.getString(artistColumn).cleanMetadata()
                if (extension == "wav") {
                    val riff = runCatching {
                        context.contentResolver.openInputStream(uri)?.use(WavInfoTagIO::read)
                    }.getOrNull()
                    title = riff?.title?.takeIf(String::isNotBlank) ?: title
                    artist = riff?.artist?.takeIf(String::isNotBlank) ?: artist
                }
                tracks += AudioTrack(
                    uri = uri,
                    fileName = name,
                    title = title ?: name.substringBeforeLast('.', name),
                    artist = artist,
                    album = cursor.getString(albumColumn).cleanMetadata(),
                    durationMs = cursor.getLong(durationColumn).coerceAtLeast(0L),
                    subtitleUri = null,
                    subtitleExtension = null,
                    relativeFolder = relativeFolder,
                    sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                )
            }
        }
        return TrackRows(tracks, ids, rowsRead)
    }

    private fun audioCollections(context: Context): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                MediaStore.getExternalVolumeNames(context).map(MediaStore.Audio.Media::getContentUri)
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        } else {
            listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }

    /** "Music/sub" -> "Music/sub/"; the media library may or may not add the slash. */
    internal fun normalizeRelativeFolder(relativePath: String): String {
        val trimmed = relativePath.trim().trim('/')
        return if (trimmed.isEmpty()) "" else "$trimmed/"
    }

    /**
     * Legacy DATA column value -> the same relative form the media library reports.
     * [storageRoot] is injected so the conversion can be unit tested without a device.
     */
    internal fun relativeFolderFromAbsolute(
        absolutePath: String,
        storageRoot: String = defaultStorageRoot(),
    ): String {
        val parent = absolutePath.substringBeforeLast('/', "")
        if (parent.isEmpty()) return ""
        val root = storageRoot.trimEnd('/')
        val relative = when {
            root.isNotEmpty() && parent.startsWith("$root/") -> parent.removePrefix("$root/")
            parent.startsWith("/storage/") -> parent.removePrefix("/storage/")
            parent.startsWith("/") -> parent.removePrefix("/")
            else -> parent
        }
        return normalizeRelativeFolder(relative)
    }

    private fun defaultStorageRoot(): String =
        runCatching { android.os.Environment.getExternalStorageDirectory()?.absolutePath }
            .getOrNull().orEmpty()

    /** "primary:Music/sub" from a SAF tree Uri becomes the relative folder "Music/sub/". */
    internal fun treeDocumentIdToRelativeFolder(documentId: String): String? {
        val parts = documentId.split(":", limit = 2)
        if (parts.size != 2) return null
        val path = parts[1].trim('/')
        if (path.isEmpty()) return null
        val relative = if (parts[0].equals("primary", ignoreCase = true)) path else "${parts[0]}/$path"
        return normalizeRelativeFolder(relative)
    }

    /** AOSP documentsui uses standard tree ids; vendor file managers often do not. */
    fun folderPickerIntent(context: Context): Intent {
        val base = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        val aosp = Intent(base).setPackage("com.android.documentsui")
        val resolved = runCatching {
            context.packageManager.resolveActivity(aosp, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull() != null
        return if (resolved) aosp else base
    }

    fun treeUriToRelativeFolder(uri: Uri): String? =
        try {
            treeDocumentIdToRelativeFolder(DocumentsContract.getTreeDocumentId(uri))
        } catch (failure: Throwable) {
            null
        }

    /** Empty [folders] scans everything; otherwise the folder must sit inside a selected one. */
    internal fun isFolderAllowed(relativeFolder: String, folders: Set<String>): Boolean {
        if (folders.isEmpty()) return true
        return folders.any { selected -> relativeFolder.startsWith(selected) }
    }

    private fun String?.cleanMetadata(): String? =
        this?.takeIf { it.isNotBlank() && it != "<unknown>" }
}
