package com.imankoppai.mediaanvil.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import com.imankoppai.mediaanvil.model.AudioTrack

object PlayerDataBackup {
    /** 4 adds interval tags; old apps must not silently discard their end times. */
    private const val SCHEMA_VERSION = 4
    private const val MAX_BACKUP_BYTES = 16 * 1024 * 1024

    /** Versions this build can still read, so a V1.02 backup restores intact. */
    private val SUPPORTED_SCHEMA_VERSIONS = setOf(1, 2, 3, 4)

    data class RestorePlan(
        val root: JSONObject,
        val matches: List<BackupTrackMatcher.Match>,
        val inaccessibleCovers: Int,
        val legacy: Boolean,
    )

    fun export(context: Context, destination: Uri, preferences: PlaybackPreferences) {
        preferences.flushPendingWrites()
        val tracks = runCatching { DeviceAudioLibrary.scan(context).tracks }.getOrElse {
            LibraryCache.load(context)?.tracks.orEmpty().map { record ->
                AudioTrack(record.uri, record.fileName, record.title, record.artist, record.album,
                    record.durationMs, record.subtitleUri, record.subtitleExtension, record.relativeFolder, record.sizeBytes)
            }
        }
        val output = createBackup(preferences, tracks)
        val bytes = output.toString(2).toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BACKUP_BYTES) { "backup_too_large" }
        checkNotNull(context.contentResolver.openOutputStream(destination, "wt")).use { stream ->
            stream.write(bytes)
        }
    }

    internal fun createBackup(preferences: PlaybackPreferences, tracks: List<AudioTrack>): JSONObject {
        val output = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("settings", JSONObject()
                .put("language", preferences.language)
                .put("autoLoadLyrics", preferences.autoLoadLyrics)
                .put("playbackSpeed", preferences.playbackSpeed.toDouble())
                .put("seekBackSeconds", preferences.seekBackSeconds)
                .put("seekForwardSeconds", preferences.seekForwardSeconds)
                .put("shuffleEnabled", preferences.shuffleEnabled)
                .put("repeatMode", preferences.repeatMode)
                .put("librarySort", preferences.librarySort)
                .put("themeMode", preferences.themeMode)
                .put("doublePressAction", preferences.doublePressAction)
                .put("resumePlayback", preferences.resumePlayback)
                .put("resumeRewindSeconds", preferences.resumeRewindSeconds)
                .put("showLyricsTimestamps", preferences.showLyricsTimestamps)
                .put("sleepFinishTrack", preferences.sleepFinishTrack)
                .put("sleepCloseApp", preferences.sleepCloseApp)
                .put("libraryScanMode", preferences.libraryScanMode)
                .put("scanFolders", JSONArray(preferences.scanFolders.toList())))
            .put("groups", JSONArray().apply {
                preferences.trackGroups.forEach { group ->
                    put(JSONObject()
                        .put("id", group.id)
                        .put("name", group.name)
                        .put("trackUris", JSONArray(group.trackUris)))
                }
            })
            .put("hiddenTrackUris", JSONArray(preferences.hiddenTrackUris.toList()))
            .put("customCovers", preferences.customCoversJson())
            .put("lyricsOffsets", preferences.lyricsOffsetsJson())
            .put("favoriteTrackUris", JSONArray(preferences.favoriteTrackUris))
            .put("playHistory", preferences.playHistoryRaw)
            .put("playbackPositions", JSONObject(preferences.playbackPositions))
            .put("lastQueueUris", JSONArray(preferences.lastQueueUris))
            .put("lastQueueIndex", preferences.lastQueueIndex)
            .put("bookmarks", preferences.bookmarksRaw)
            .put("finishedTrackUris", JSONArray(preferences.finishedTrackUris.toList()))
        val references = tracks.associate { track ->
            track.uri.toString() to BackupTrackReference(track.uri.toString(), track.fileName,
                track.relativeFolder, track.durationMs, track.sizeBytes)
        }
        val retained = readReferences(JSONObject().put("trackReferences", JSONArray(preferences.backupTrackReferences)))
            .associateBy { it.uri }
        output.put("trackReferences", JSONArray().apply {
            referencedUris(output).forEach { uri ->
                val ref = references[uri] ?: retained[uri] ?: BackupTrackReference(uri, "", "", 0)
                put(referenceJson(ref))
            }
        })
        return output
    }

    /** Legacy API used by migration tests. The UI always previews against a fresh scan. */
    fun import(context: Context, source: Uri, preferences: PlaybackPreferences) {
        preferences.restoreFromBackup(read(context, source))
    }

    fun read(context: Context, source: Uri): JSONObject {
        val bytes = checkNotNull(context.contentResolver.openInputStream(source)).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= MAX_BACKUP_BYTES) { "backup_too_large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return validate(JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    internal fun validate(root: JSONObject): JSONObject = root.also {
        check(it.optInt("schemaVersion", -1) in SUPPORTED_SCHEMA_VERSIONS) { "unsupported_schema" }
        it.getJSONObject("settings")
        val groups = it.getJSONArray("groups")
        for (i in 0 until groups.length()) {
            groups.getJSONObject(i).getString("id")
            groups.getJSONObject(i).getString("name")
        }
    }

    fun preview(context: Context, source: Uri): RestorePlan {
        val root = read(context, source)
        val tracks = DeviceAudioLibrary.scan(context).tracks
        val available = tracks.map { BackupTrackReference(it.uri.toString(), it.fileName, it.relativeFolder, it.durationMs, it.sizeBytes) }
        val index = BackupTrackMatcher.Index(available)
        val references = readReferences(root).associateBy { it.uri }
        val matches = referencedUris(root).map { uri ->
            index.match(references[uri] ?: BackupTrackReference(uri, "", "", 0), root.optInt("schemaVersion") < 3)
        }
        val covers = root.optJSONObject("customCovers") ?: JSONObject()
        val inaccessible = covers.keys().asSequence().count { key ->
            runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(covers.getString(key)), "r")?.use { true } }
                .getOrNull() != true
        }
        return RestorePlan(root, matches, inaccessible, root.optInt("schemaVersion") < 3)
    }

    /** Remaps every reference consistently, preserving order and missing data for later recovery. */
    internal fun remap(plan: RestorePlan, choices: Map<String, String>): JSONObject {
        val root = JSONObject(plan.root.toString())
        val mapping = plan.matches.associate { match ->
            val chosen = choices[match.source.uri]
            check(chosen == null || match.candidates.any { it.uri == chosen }) { "invalid_match" }
            match.source.uri to (chosen ?: match.automaticUri ?: BackupTrackMatcher.missingUri(match.source.uri))
        }
        fun mapped(uri: String) = mapping[uri] ?: BackupTrackMatcher.missingUri(uri)
        fun array(value: JSONArray): JSONArray = JSONArray((0 until value.length()).map { mapped(value.getString(it)) }.distinct())
        val groups = root.getJSONArray("groups")
        for (i in 0 until groups.length()) {
            val group = groups.getJSONObject(i)
            group.put("trackUris", array(group.optJSONArray("trackUris") ?: JSONArray()))
        }
        listOf("hiddenTrackUris", "favoriteTrackUris", "lastQueueUris", "finishedTrackUris").forEach { key ->
            root.optJSONArray(key)?.let { root.put(key, array(it)) }
        }
        listOf("customCovers", "lyricsOffsets", "playbackPositions").forEach { key ->
            root.optJSONObject(key)?.let { original ->
                root.put(key, JSONObject().apply { original.keys().forEach { put(mapped(it), original.get(it)) } })
            }
        }
        if (root.has("playHistory")) root.put("playHistory", PlayHistory.encode(
            PlayHistory.decode(root.optString("playHistory")).map { it.copy(uri = mapped(it.uri)) }))
        if (root.has("bookmarks")) root.put("bookmarks", Bookmarks.encode(
            Bookmarks.decode(root.optString("bookmarks")).map { it.copy(trackUri = mapped(it.trackUri)) }))
        val originalQueue = plan.root.optJSONArray("lastQueueUris")
        val oldCurrent = originalQueue?.optString(plan.root.optInt("lastQueueIndex", -1))
        val queue = root.optJSONArray("lastQueueUris")
        if (queue != null) root.put("lastQueueIndex", (0 until queue.length()).firstOrNull { queue.optString(it) == oldCurrent?.let(::mapped) } ?: -1)
        root.put("trackReferences", JSONArray().apply {
            plan.matches.forEach { put(referenceJson(it.source.copy(uri = mapped(it.source.uri)))) }
        })
        return root
    }

    fun restore(plan: RestorePlan, choices: Map<String, String>, preferences: PlaybackPreferences) {
        preferences.restoreFromBackup(remap(plan, choices))
    }

    private fun referenceJson(ref: BackupTrackReference) = JSONObject()
        .put("uri", ref.uri).put("fileName", ref.fileName).put("relativeFolder", ref.relativeFolder)
        .put("durationMs", ref.durationMs).put("sizeBytes", ref.sizeBytes)

    private fun readReferences(root: JSONObject): List<BackupTrackReference> {
        val array = root.optJSONArray("trackReferences") ?: return emptyList()
        return (0 until array.length()).map { index ->
            val ref = array.getJSONObject(index)
            BackupTrackReference(ref.getString("uri"), ref.optString("fileName"), ref.optString("relativeFolder"), ref.optLong("durationMs"), ref.optLong("sizeBytes"))
        }
    }

    private fun referencedUris(root: JSONObject): Set<String> = buildSet {
        fun include(array: JSONArray?) { if (array != null) for (i in 0 until array.length()) add(array.getString(i)) }
        val groups = root.getJSONArray("groups")
        for (i in 0 until groups.length()) include(groups.getJSONObject(i).optJSONArray("trackUris"))
        listOf("hiddenTrackUris", "favoriteTrackUris", "lastQueueUris", "finishedTrackUris").forEach { include(root.optJSONArray(it)) }
        listOf("customCovers", "lyricsOffsets", "playbackPositions").forEach { key -> root.optJSONObject(key)?.keys()?.forEach { add(it) } }
        PlayHistory.decode(root.optString("playHistory")).forEach { add(it.uri) }
        Bookmarks.decode(root.optString("bookmarks")).forEach { add(it.trackUri) }
        remove("")
    }
}
