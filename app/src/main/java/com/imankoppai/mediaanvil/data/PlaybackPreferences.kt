package com.imankoppai.mediaanvil.data

import android.content.Context
import android.net.Uri
import com.imankoppai.mediaanvil.model.TrackGroup
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every setting and every piece of player data the app remembers, read through
 * [SettingsStore] (Preferences DataStore). The public surface is unchanged from the
 * SharedPreferences version, so the rest of the app is unaware of the move; the
 * one-time copy of existing values is handled by [SettingsMigration] inside the store.
 */
class PlaybackPreferences(context: Context) {
    private val store = SettingsStore.get(context)

    /** Lets the playback service react to settings that only matter while it runs. */
    fun registerChangeListener(listener: (String) -> Unit) {
        store.addListener(listener)
    }

    fun unregisterChangeListener(listener: (String) -> Unit) {
        store.removeListener(listener)
    }

    /**
     * Blocks until accepted writes have reached disk. SharedPreferences flushed its
     * async `apply()` writes at process shutdown; DataStore needs an explicit wait, so
     * the playback service calls this when it is destroyed rather than losing a
     * position or history entry that was written moments earlier.
     */
    fun flushPendingWrites() {
        store.flush()
    }

    /** "", "zh-CN" or "en"; empty follows the system language. */
    var language: String
        get() = store.getString("language", "") ?: ""
        set(value) {
            store.put("language", value)
        }

    var autoLoadLyrics: Boolean
        get() = store.getBoolean("auto_load_lyrics", true)
        set(value) {
            store.put("auto_load_lyrics", value)
        }

    var playbackSpeed: Float
        get() = store.getFloat("playback_speed", 1f)
        set(value) {
            store.put("playback_speed", value)
        }

    var seekBackSeconds: Int
        get() = store.getInt("seek_back_seconds", 5).coerceIn(1, 300)
        set(value) {
            store.put("seek_back_seconds", value.coerceIn(1, 300))
        }

    var seekForwardSeconds: Int
        get() = store.getInt("seek_forward_seconds", 30).coerceIn(1, 300)
        set(value) {
            store.put("seek_forward_seconds", value.coerceIn(1, 300))
        }

    var shuffleEnabled: Boolean
        get() = store.getBoolean("shuffle_enabled", false)
        set(value) {
            store.put("shuffle_enabled", value)
        }

    var repeatMode: Int
        get() = store.getInt("repeat_mode", 0)
        set(value) {
            store.put("repeat_mode", value)
        }

    /** Sleep timer waits for the current track to finish before pausing. */
    var sleepFinishTrack: Boolean
        get() = store.getBoolean("sleep_finish_track", false)
        set(value) {
            store.put("sleep_finish_track", value)
        }

    /** Show each lyric line's timestamp above the text. */
    var showLyricsTimestamps: Boolean
        get() = store.getBoolean("show_lyrics_timestamps", false)
        set(value) {
            store.put("show_lyrics_timestamps", value)
        }

    /** Sleep timer closes the app when it fires. */
    var sleepCloseApp: Boolean
        get() = store.getBoolean("sleep_close_app", false)
        set(value) {
            store.put("sleep_close_app", value)
        }

    /**
     * Wall-clock deadline of the running sleep timer, 0 when none is armed. The
     * playback service owns the deadline so the timer survives screen rotation and
     * the app being backgrounded. Written synchronously: the service may be killed
     * moments after arming it.
     */
    var sleepTimerDeadlineAt: Long
        get() = store.getLong("sleep_timer_deadline_at", 0L)
        set(value) {
            store.put("sleep_timer_deadline_at", value, synchronous = true)
        }

    /** Set by the service when the sleep timer wants the app closed. */
    var sleepTimerClosedAt: Long
        get() = store.getLong("sleep_timer_closed_at", 0L)
        set(value) {
            store.put("sleep_timer_closed_at", value, synchronous = true)
        }

    /**
     * A/B loop points together with the track they were set on, so the markers survive
     * a screen rotation and keep matching the loop the service enforces.
     */
    var loopTrackUri: String
        get() = store.getString("loop_track_uri", "") ?: ""
        set(value) {
            store.put("loop_track_uri", value)
        }

    var loopStartMs: Long
        get() = store.getLong("loop_start_ms", -1L)
        set(value) {
            store.put("loop_start_ms", value)
        }

    var loopEndMs: Long
        get() = store.getLong("loop_end_ms", -1L)
        set(value) {
            store.put("loop_end_ms", value)
        }

    /** "all" imports every supported audio file; "folders" only imports scanFolders. */
    var libraryScanMode: String
        get() = store.getString("library_scan_mode", "all") ?: "all"
        set(value) {
            store.put("library_scan_mode", value)
        }

    /** Relative folder paths (trailing slash) that are scanned in "folders" mode. */
    var scanFolders: Set<String>
        get() = store.getStringSet("scan_folders", emptySet()) ?: emptySet()
        set(value) {
            store.put("scan_folders", value.toSet())
        }

    /** Remember playback position and the last queue across app restarts. */
    var resumePlayback: Boolean
        get() = store.getBoolean("resume_playback", true)
        set(value) {
            store.put("resume_playback", value)
        }

    /** JSON object mapping track uri -> saved playback position in ms. */
    var playbackPositions: String
        get() = store.getString("playback_positions", "{}") ?: "{}"
        set(value) {
            store.put("playback_positions", value)
        }

    fun playbackPositionFor(uri: String): Long = runCatching {
        JSONObject(playbackPositions).optLong(uri, -1L)
    }.getOrDefault(-1L)

    fun setPlaybackPosition(uri: String, positionMs: Long) {
        runCatching {
            val root = JSONObject(playbackPositions)
            if (positionMs > 0L) root.put(uri, positionMs) else root.remove(uri)
            store.put("playback_positions", root.toString())
        }
    }

    /** Saves the resume position and queue in one disk transaction. */
    fun savePlaybackSnapshot(
        uri: String,
        positionMs: Long,
        queueUris: List<String>,
        queueIndex: Int,
        synchronous: Boolean,
    ) {
        runCatching {
            val positions = JSONObject(playbackPositions)
            if (positionMs > 0L) positions.put(uri, positionMs) else positions.remove(uri)
            store.putAll(
                mapOf(
                    "playback_positions" to positions.toString(),
                    "last_queue_uris" to JSONArray(queueUris).toString(),
                    "last_queue_index" to queueIndex,
                ),
                synchronous = synchronous,
            )
        }
    }

    /** JSON array of the last queue media ids (track uris). */
    var lastQueueUris: String
        get() = store.getString("last_queue_uris", "[]") ?: "[]"
        set(value) {
            store.put("last_queue_uris", value)
        }

    /** Index into lastQueueUris that was current when playback last stopped. */
    var lastQueueIndex: Int
        get() = store.getInt("last_queue_index", -1)
        set(value) {
            store.put("last_queue_index", value)
        }

    /** Epoch-ms of the last automatic update check, throttling it to once a day. */
    var updateLastCheckAt: Long
        get() = store.getLong("update_last_check_at", 0L)
        set(value) {
            store.put("update_last_check_at", value)
        }

    /** "fileName", "title" or "duration". */
    var librarySort: String
        get() = store.getString("library_sort", "fileName") ?: "fileName"
        set(value) {
            store.put("library_sort", value)
        }

    /**
     * User-created local groups containing stable SAF document URIs. The stored order
     * of `trackUris` is the playback order, so it is read back as a list and
     * de-duplicated without re-sorting. Groups written by V1.02 stored a set, which
     * reads back here in its previous (insertion) order.
     */
    var trackGroups: List<TrackGroup>
        get() {
            val raw = store.getString("track_groups", null) ?: return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    val uris = item.optJSONArray("trackUris") ?: JSONArray()
                    TrackGroup(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        trackUris = buildList {
                            for (uriIndex in 0 until uris.length()) {
                                val uri = uris.optString(uriIndex)
                                if (uri.isNotEmpty() && uri !in this) add(uri)
                            }
                        },
                    )
                }
            }.getOrDefault(emptyList())
        }
        set(value) {
            val array = JSONArray()
            value.forEach { group ->
                array.put(
                    JSONObject()
                        .put("id", group.id)
                        .put("name", group.name)
                        .put("trackUris", JSONArray(group.trackUris.distinct())),
                )
            }
            store.put("track_groups", array.toString())
        }

    /** Favourited track uris, in the order the user added them. */
    var favoriteTrackUris: List<String>
        get() {
            val raw = store.getString("favorite_track_uris", null) ?: return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val uri = array.optString(index)
                        if (uri.isNotEmpty() && uri !in this) add(uri)
                    }
                }
            }.getOrDefault(emptyList())
        }
        set(value) {
            store.put("favorite_track_uris", JSONArray(value.distinct()).toString())
        }

    /** Newest-first playback history, written by the playback service. */
    var playHistoryRaw: String
        get() = store.getString("play_history", "") ?: ""
        set(value) {
            store.put("play_history", value)
        }

    fun playHistory(): List<PlayHistory.Entry> = PlayHistory.decode(playHistoryRaw)

    fun setPlayHistory(entries: List<PlayHistory.Entry>) {
        playHistoryRaw = PlayHistory.encode(entries)
    }

    /**
     * Adds one played track. [force] makes the write synchronous so a track that is
     * recorded right before the process dies is not lost.
     */
    fun recordPlayed(uri: String, playedAt: Long = System.currentTimeMillis(), force: Boolean = false) {
        if (uri.isBlank()) return
        val updated = PlayHistory.record(playHistory(), uri, playedAt)
        store.put("play_history", PlayHistory.encode(updated), synchronous = force)
    }

    /** Tracks hidden from the player library; the underlying documents are untouched. */
    var hiddenTrackUris: Set<String>
        get() = store.getStringSet("hidden_track_uris", emptySet()) ?: emptySet()
        set(value) {
            store.put("hidden_track_uris", value.toSet())
        }

    /** Theme mode: "" = follow system, "light", "dark". */
    var themeMode: String
        get() = store.getString("theme_mode", "") ?: ""
        set(value) {
            store.put("theme_mode", value)
        }

    /** Media button double press action: "" = next track, "previous" or "none". */
    var doublePressAction: String
        get() = when (val saved = store.getString("double_press_action", "") ?: "") {
            "none" -> "pause"
            "", "previous", "pause" -> saved
            else -> ""
        }
        set(value) {
            store.put("double_press_action", value)
        }

    fun customCoverFor(trackUri: Uri): Uri? {
        val raw = store.getString("custom_covers", null) ?: return null
        return runCatching {
            JSONObject(raw).optString(trackUri.toString())
                .takeIf(String::isNotEmpty)
                ?.let(Uri::parse)
        }.getOrNull()
    }

    fun setCustomCover(trackUri: Uri, coverUri: Uri) {
        val covers = customCoversJson()
        covers.put(trackUri.toString(), coverUri.toString())
        store.put("custom_covers", covers.toString())
    }

    fun lyricsOffsetFor(trackUri: Uri): Long = runCatching {
        lyricsOffsetsJson().optLong(trackUri.toString(), 0L).coerceIn(-60_000L, 60_000L)
    }.getOrDefault(0L)

    fun setLyricsOffset(trackUri: Uri, offsetMs: Long) {
        val offsets = lyricsOffsetsJson()
        val value = offsetMs.coerceIn(-60_000L, 60_000L)
        if (value == 0L) offsets.remove(trackUri.toString()) else offsets.put(trackUri.toString(), value)
        store.put("lyrics_offsets", offsets.toString())
    }

    internal fun customCoversJson(): JSONObject = runCatching {
        JSONObject(store.getString("custom_covers", null) ?: "{}")
    }.getOrElse { JSONObject() }

    internal fun lyricsOffsetsJson(): JSONObject = runCatching {
        JSONObject(store.getString("lyrics_offsets", null) ?: "{}")
    }.getOrElse { JSONObject() }

    internal fun restoreFromBackup(root: JSONObject) {
        val settings = root.getJSONObject("settings")
        val groupsJson = root.getJSONArray("groups")
        val restoredGroups = List(groupsJson.length()) { index ->
            val item = groupsJson.getJSONObject(index)
            val uris = item.optJSONArray("trackUris") ?: JSONArray()
            TrackGroup(
                id = item.getString("id"),
                name = item.getString("name"),
                // Keeps the backup's order: that order is the playback order.
                trackUris = buildList {
                    for (uriIndex in 0 until uris.length()) {
                        val uri = uris.optString(uriIndex)
                        if (uri.isNotEmpty() && uri !in this) add(uri)
                    }
                },
            )
        }
        val hidden = root.optJSONArray("hiddenTrackUris") ?: JSONArray()
        val covers = root.optJSONObject("customCovers") ?: JSONObject()
        val offsets = root.optJSONObject("lyricsOffsets") ?: JSONObject()
        // Backups written before V1.03 have neither list; an absent key must leave the
        // current favourites and history alone rather than wiping them.
        val favorites = root.optJSONArray("favoriteTrackUris")
        val history = if (root.has("playHistory")) root.optString("playHistory") else null

        val restored = linkedMapOf<String, Any?>(
            "language" to settings.optString("language", ""),
            "auto_load_lyrics" to settings.optBoolean("autoLoadLyrics", true),
            "playback_speed" to settings.optDouble("playbackSpeed", 1.0).toFloat().coerceIn(0.25f, 3f),
            "seek_back_seconds" to settings.optInt("seekBackSeconds", 5).coerceIn(1, 300),
            "seek_forward_seconds" to settings.optInt("seekForwardSeconds", 30).coerceIn(1, 300),
            "shuffle_enabled" to settings.optBoolean("shuffleEnabled", false),
            "repeat_mode" to settings.optInt("repeatMode", 0),
            "library_sort" to settings.optString("librarySort", "fileName"),
            "theme_mode" to settings.optString("themeMode", ""),
            "double_press_action" to settings.optString("doublePressAction", ""),
            "track_groups" to JSONArray().apply {
                restoredGroups.forEach { group ->
                    put(JSONObject()
                        .put("id", group.id)
                        .put("name", group.name)
                        .put("trackUris", JSONArray(group.trackUris)))
                }
            }.toString(),
            "hidden_track_uris" to buildSet {
                for (index in 0 until hidden.length()) add(hidden.getString(index))
            },
            "custom_covers" to covers.toString(),
            "lyrics_offsets" to offsets.toString(),
        )
        if (favorites != null) {
            restored["favorite_track_uris"] = JSONArray().apply {
                for (index in 0 until favorites.length()) {
                    val uri = favorites.optString(index)
                    if (uri.isNotEmpty()) put(uri)
                }
            }.toString()
        }
        if (history != null) restored["play_history"] = history

        // One synchronous write: a restore must be on disk before the screen reloads
        // from it, and a half-applied restore would be worse than a failed one.
        store.putAll(restored, synchronous = true)
    }

}
