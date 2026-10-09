package com.imankoppai.mediaanvil.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted snapshot of the last media-library scan so the library opens instantly;
 * a background rescan keeps it fresh afterwards. Only paths and tags are cached —
 * never audio bytes.
 */
object LibraryCache {
    private const val FILE_NAME = "library_snapshot.json"
    private const val MAX_AGE_MS = 5 * 60 * 1000L

    data class TrackRecord(
        val uri: Uri,
        val fileName: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val relativeFolder: String,
        val subtitleUri: Uri?,
        val subtitleExtension: String?,
        val sizeBytes: Long = 0L,
    )

    data class Snapshot(
        val tracks: List<TrackRecord>,
        val savedAt: Long,
        val checkpoints: Map<String, MediaCheckpoint> = emptyMap(),
        val folderScope: Set<String> = emptySet(),
        val audioReadGranted: Boolean? = null,
    ) {
        fun asScan(): LibraryScan = LibraryScan(tracks.map { record ->
            com.imankoppai.mediaanvil.model.AudioTrack(record.uri, record.fileName, record.title, record.artist, record.album,
                record.durationMs, record.subtitleUri, record.subtitleExtension, record.relativeFolder, record.sizeBytes)
        }, checkpoints = checkpoints, folderScope = folderScope, audioReadGranted = audioReadGranted)
    }

    fun save(context: Context, scan: LibraryScan) {
        runCatching {
            val root = JSONObject()
                .put("savedAt", System.currentTimeMillis())
                .put("audioReadGranted", scan.audioReadGranted ?: DeviceAudioLibrary.hasReadAccess(context))
                .put("folderScope", JSONArray(scan.folderScope.sorted()))
                .put("checkpoints", JSONObject().apply {
                    scan.checkpoints.forEach { (volume, checkpoint) ->
                        put(volume, JSONObject().put("version", checkpoint.version).put("generation", checkpoint.generation))
                    }
                })
            val tracks = JSONArray()
            scan.tracks.forEach { track ->
                tracks.put(
                    JSONObject()
                        .put("uri", track.uri.toString())
                        .put("fileName", track.fileName)
                        .put("title", track.title)
                        .put("artist", track.artist.orEmpty())
                        .put("album", track.album.orEmpty())
                        .put("durationMs", track.durationMs)
                        .put("sizeBytes", track.sizeBytes)
                        .put("relativeFolder", track.relativeFolder)
                        .put("subtitleUri", track.subtitleUri?.toString().orEmpty())
                        .put("subtitleExtension", track.subtitleExtension.orEmpty()),
                )
            }
            root.put("tracks", tracks)
            context.cacheDir.mkdirs()
            val target = java.io.File(context.cacheDir, FILE_NAME)
            val tmp = java.io.File(context.cacheDir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    fun load(context: Context): Snapshot? = runCatching {
        val file = java.io.File(context.cacheDir, FILE_NAME)
        if (!file.isFile) return null
        val root = JSONObject(file.readText())
        val tracks = mutableListOf<TrackRecord>()
        val tracksArray = root.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until tracksArray.length()) {
            val item = tracksArray.getJSONObject(i)
            val subtitleUri = item.optString("subtitleUri").ifEmpty { null }?.let(Uri::parse)
            tracks += TrackRecord(
                uri = Uri.parse(item.getString("uri")),
                fileName = item.getString("fileName"),
                title = item.getString("title"),
                artist = item.optString("artist").ifEmpty { null },
                album = item.optString("album").ifEmpty { null },
                durationMs = item.optLong("durationMs"),
                sizeBytes = item.optLong("sizeBytes"),
                // Snapshots written by V1.02 stored an absolute parentPath; the
                // scanner re-derives a relative folder on the next refresh, so an
                // unreadable legacy value simply falls back to the root folder.
                relativeFolder = item.optString("relativeFolder").ifEmpty {
                    legacyParentPathToRelative(item.optString("parentPath"))
                },
                subtitleUri = subtitleUri,
                subtitleExtension = item.optString("subtitleExtension").ifEmpty { null },
            )
        }
        val metadata = root.optJSONObject("checkpoints") ?: JSONObject()
        val checkpoints = metadata.keys().asSequence().associateWith { volume ->
            val checkpoint = metadata.getJSONObject(volume)
            MediaCheckpoint(checkpoint.getString("version"), checkpoint.getLong("generation"))
                .also { require(it.version.isNotBlank() && it.generation >= 0) }
        }
        val scope = root.optJSONArray("folderScope") ?: JSONArray()
        Snapshot(tracks, root.optLong("savedAt"), checkpoints, (0 until scope.length()).map { scope.getString(it) }.toSet(),
            root.takeIf { it.has("audioReadGranted") }?.getBoolean("audioReadGranted"))
    }.getOrNull()

    /** Best-effort conversion of a V1.02 absolute parentPath into a relative folder. */
    private fun legacyParentPathToRelative(parentPath: String): String =
        if (parentPath.isBlank()) "" else DeviceAudioLibrary.relativeFolderFromAbsolute("$parentPath/x")

    fun isFresh(snapshot: Snapshot): Boolean =
        System.currentTimeMillis() - snapshot.savedAt < MAX_AGE_MS

}
