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
    private val audioExtensions = setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "opus")
    val subtitleExtensions = listOf("lrc", "srt", "vtt")

    /** Permission that lets the media library return audio rows on this OS version. */
    val readPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun scan(context: Context, allowedFolders: Set<String> = emptySet()): LibraryScan {
        val tracks = mutableListOf<AudioTrack>()
        audioCollections(context).forEach { collection ->
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
                if (useRelativePath) {
                    add(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    add(MediaStore.Audio.Media.DATA)
                }
            }.toTypedArray()

            runCatching {
                context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val pathColumn = cursor.getColumnIndex(
                        if (useRelativePath) MediaStore.Audio.Media.RELATIVE_PATH
                        else MediaStore.Audio.Media.DATA,
                    )
                    while (cursor.moveToNext()) {
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
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
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
                        )
                    }
                }
            }
        }
        return LibraryScan(
            tracks = tracks.distinctBy { it.uri }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName }),
            files = emptyList(),
        )
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
