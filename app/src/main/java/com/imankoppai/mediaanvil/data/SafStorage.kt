package com.imankoppai.mediaanvil.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * File access granted by the user through the system folder picker, replacing the
 * "all files access" permission.
 *
 * Only folders the user explicitly picked are reachable, so every read and write goes
 * through the SAF documents API rather than [java.io.File]. Folders are remembered by
 * their media-library relative path ("Music/sub/") so an audio track can be matched to
 * the grant that covers it.
 */
object SafStorage {
    private const val PREFERENCES = "mediaanvil_saf"
    private const val KEY_TREES = "tree_uris"

    /** Result of asking whether a track's folder is writable. */
    sealed interface FolderAccess {
        /** A user-granted folder covers this track; [treeUri] can create and delete files. */
        data class Granted(val treeUri: Uri, val relativeFolder: String) : FolderAccess

        /** Nothing granted covers this track yet; the caller must ask the user to pick a folder. */
        data class NeedsPermission(val relativeFolder: String) : FolderAccess
    }

    /** Grants the app persists for the lifetime of the install. */
    fun grantedTrees(context: Context): List<Uri> =
        preferences(context).getStringSet(KEY_TREES, emptySet())
            .orEmpty()
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            // Drop grants the user revoked from system settings, so the UI does not
            // promise access that no longer exists.
            .filter { hasPersistedPermission(context, it) }

    fun remember(context: Context, treeUri: Uri) {
        val key = treeUri.toString()
        val updated = preferences(context).getStringSet(KEY_TREES, emptySet()).orEmpty() + key
        preferences(context).edit { putStringSet(KEY_TREES, updated) }
    }

    /**
     * Persists the grant the picker returned. The picker result carries a temporary
     * grant, so it must be taken explicitly or it disappears when the process dies.
     */
    fun takePersistablePermission(context: Context, treeUri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val taken = runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }.isSuccess
        if (taken) remember(context, treeUri)
        return taken
    }

    private fun hasPersistedPermission(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }

    /** The folder that would hold this track's sidecar lyrics, from the media library path. */
    fun folderFor(relativeFolder: String): String = relativeFolder.trim('/')

    /**
     * Finds the granted folder that covers [relativeFolder].
     *
     * The deepest matching grant wins, so a user who granted both "Music/" and
     * "Music/live/" gets the more specific one.
     */
    fun findGrant(context: Context, relativeFolder: String): FolderAccess {
        val wanted = relativeFolder.trim('/')
        val match = grantedTrees(context)
            .mapNotNull { tree -> treeRelativeFolder(context, tree)?.let { it to tree } }
            .filter { (folder, _) -> wanted == folder || wanted.startsWith("$folder/") }
            .maxByOrNull { (folder, _) -> folder.length }
        return if (match != null) {
            FolderAccess.Granted(match.second, match.first)
        } else {
            FolderAccess.NeedsPermission(wanted)
        }
    }

    /** The media-library relative folder a granted tree corresponds to. */
    private fun treeRelativeFolder(context: Context, treeUri: Uri): String? =
        runCatching {
            val documentId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            val parts = documentId.split(":", limit = 2)
            if (parts.size != 2) return@runCatching null
            val path = parts[1].trim('/')
            if (parts[0].equals("primary", ignoreCase = true)) path else "${parts[0]}/$path"
        }.getOrNull()

    /** The document for [relativeFolder] inside a granted tree, if it exists. */
    private fun folderDocument(context: Context, treeUri: Uri, relativeFolder: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val treeFolder = treeRelativeFolder(context, treeUri).orEmpty()
        val wanted = relativeFolder.trim('/')
        if (wanted == treeFolder) return root
        // Descend the part of the path below the granted folder.
        val remainder = wanted.removePrefix(treeFolder).trim('/')
        if (remainder.isEmpty()) return root
        var current = root
        remainder.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            current = current?.findFile(segment) ?: return null
        }
        return current
    }

    /** An existing sidecar document for [track], looked up by the naming rules. */
    fun findSubtitle(context: Context, track: com.imankoppai.mediaanvil.model.AudioTrack): Pair<Uri, String>? {
        val access = findGrant(context, track.relativeFolder)
        if (access !is FolderAccess.Granted) return null
        val folder = folderDocument(context, access.treeUri, track.relativeFolder) ?: return null
        val base = track.fileName.substringBeforeLast('.', track.fileName)
        DeviceAudioLibrary.subtitleExtensions.forEach { extension ->
            // "song.mp3.lrc" wins over "song.lrc", matching the previous scanner.
            //
            // The ".txt" forms cover files a provider stored under an extension it
            // derived from the MIME type — see [renamedTo]. They are accepted on read
            // and on delete so that lyrics saved before that was corrected, or saved
            // somewhere the rename is refused, still work instead of going missing.
            listOf(
                "${track.fileName}.$extension",
                "$base.$extension",
                "${track.fileName}.$extension.txt",
                "$base.$extension.txt",
            ).forEach { candidate ->
                folder.findFile(candidate)?.let { return it.uri to extension }
            }
        }
        return null
    }

    /**
     * Writes [bytes] as a sidecar file beside the audio, replacing any existing file of
     * the same name atomically from the reader's point of view.
     */
    fun writeSubtitle(
        context: Context,
        track: com.imankoppai.mediaanvil.model.AudioTrack,
        bytes: ByteArray,
        extension: String,
    ): Uri {
        val access = findGrant(context, track.relativeFolder)
        check(access is FolderAccess.Granted) { "folder_permission_required" }
        val folder = folderDocument(context, access.treeUri, track.relativeFolder)
            ?: error("audio_folder_unavailable")
        val base = track.fileName.substringBeforeLast('.', track.fileName)
        val target = "$base.$extension"
        // A previous "song.mp3.lrc" would otherwise shadow the new file.
        listOf("${track.fileName}.$extension", target).forEach { name ->
            folder.findFile(name)?.delete()
        }
        val created = folder.createFile(mimeTypeFor(extension), target)
            ?: error("lyrics_create_failed")
        // A provider may rename what it was given: asking for "song.lrc" with the
        // text/plain MIME type yields "song.lrc.txt", because the extension is derived
        // from the MIME type rather than taken from the requested name. The rest of the
        // app finds and deletes sidecars *by name*, so a file under the wrong name is
        // silently invisible on the next scan and cannot be deleted. Put the name back.
        val document = created.renamedTo(context, target)
        context.contentResolver.openOutputStream(document.uri, "wt")?.use { output ->
            output.write(bytes)
        } ?: error("lyrics_write_failed")
        check(
            context.contentResolver.openInputStream(document.uri)?.use { it.read() } != null,
        ) { "lyrics_write_failed" }
        return document.uri
    }

    /**
     * Returns a document called [wanted], renaming [this] if the provider stored it
     * under a different name.
     *
     * A failed rename is not fatal: the lyrics are still written, and both the lookup
     * and the delete check accept the ".txt" form a provider may have appended, so the
     * file stays usable even when the rename is refused.
     */
    private fun DocumentFile.renamedTo(context: Context, wanted: String): DocumentFile {
        if (name == wanted) return this
        val renamed = runCatching { renameTo(wanted) }.getOrDefault(false)
        if (!renamed) return this
        // The document URI survives a rename; re-read it so [name] is not the stale one.
        return runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull() ?: this
    }

    /**
     * Whether a file name may be treated as deletable sidecar lyrics. It must end in a
     * lyric extension and must not be the audio file itself, so a delete can never
     * remove the track it belongs to.
     */
    internal fun isSafeSubtitleName(name: String, audioFileName: String?): Boolean {
        if (name.isBlank()) return false
        if (audioFileName != null && name.equals(audioFileName, ignoreCase = true)) return false
        if (hasLyricExtension(name)) return true
        // A provider may derive the stored extension from the MIME type, so a request
        // for "song.lrc" as text/plain lands as "song.lrc.txt". The app wrote that file
        // and has to be able to delete it again; dropping one trailing ".txt" recognises
        // it without loosening the rule for anything else.
        return name.endsWith(".txt", ignoreCase = true) &&
            hasLyricExtension(name.dropLast(4))
    }

    /** True when [name] ends in one of the lyric extensions this app writes. */
    private fun hasLyricExtension(name: String): Boolean =
        DeviceAudioLibrary.subtitleExtensions.any { name.endsWith(".$it", ignoreCase = true) }

    /**
     * Deletes a sidecar file. Only files this app is allowed to treat as lyrics are
     * removable: the name must end in a lyric extension, and the audio's own file name
     * must never be the target.
     */
    fun deleteSubtitle(
        context: Context,
        subtitleUri: Uri,
        audioFileName: String? = null,
    ): Boolean {
        val name = subtitleUri.lastPathSegment?.substringAfterLast('/').orEmpty()
        check(isSafeSubtitleName(name, audioFileName)) { "unsafe_lyrics_path" }
        val document = DocumentFile.fromSingleUri(context, subtitleUri)
            ?: DocumentFile.fromTreeUri(context, subtitleUri)
            ?: return false
        return !document.exists() || document.delete()
    }

    fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        "lrc" -> "text/plain"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }

    /**
     * Media-library write grants the user approved in this session.
     *
     * From API 30 the system hands out per-item write access for a limited time, so the
     * app must ask through `MediaStore.createWriteRequest` before replacing an audio
     * file's tags. Grants are tracked in memory to avoid prompting on every save.
     */
    private val mediaWriteGrants = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** True when the platform requires a per-item write grant before replacing a file. */
    internal fun requiresMediaWriteGrant(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.R

    internal fun hasMediaWriteGrant(key: String): Boolean = key in mediaWriteGrants

    internal fun rememberMediaWriteGrant(key: String) {
        mediaWriteGrants.add(key)
    }

    fun hasMediaWriteAccess(uri: Uri): Boolean =
        !requiresMediaWriteGrant() || hasMediaWriteGrant(uri.toString())

    fun rememberMediaWriteAccess(uri: Uri) {
        rememberMediaWriteGrant(uri.toString())
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Kept for callers that still need a cache file, e.g. tag editing. */
    fun cacheFile(context: Context, name: String): File = File(context.cacheDir, name)
}
