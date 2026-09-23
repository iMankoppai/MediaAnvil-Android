package com.imankoppai.mediaanvil.model

import android.net.Uri

/**
 * A local audio file as reported by the media library.
 *
 * [relativeFolder] is the media library's own folder form ("Music/sub/"), not an
 * absolute file path. Keeping it relative is what lets the player work without
 * "all files access": the folder is used to group tracks and to locate the matching
 * user-granted folder when a sidecar lyric or a tag edit needs real file access.
 */
data class AudioTrack(
    val uri: Uri,
    val fileName: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val subtitleUri: Uri?,
    val subtitleExtension: String?,
    val relativeFolder: String = "",
)

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
