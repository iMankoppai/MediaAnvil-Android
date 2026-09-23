package com.imankoppai.mediaanvil.subtitles

import com.imankoppai.mediaanvil.model.SubtitleCue

/**
 * Why lyrics are or are not on screen.
 *
 * The player used to show one message — "No matching sidecar lyrics or subtitles" —
 * for every case, including "this file is broken" and "this file has only `[ti:]`
 * tags". Those need different answers from the user, so the reason is now carried
 * out of the loader instead of being flattened into an empty list.
 */
sealed interface LyricsLoad {
    data class Loaded(override val cues: List<SubtitleCue>) : LyricsLoad

    /** Automatic loading is switched off in settings. */
    data object Disabled : LyricsLoad

    /** No lyric file has been linked to this track yet. */
    data object NotLinked : LyricsLoad

    /** A file is linked but could not be opened — moved, deleted, or no permission. */
    data object Unreadable : LyricsLoad

    /** Linked file has an extension the app does not parse. */
    data object UnsupportedFormat : LyricsLoad

    /** The file opened but holds nothing. */
    data object EmptyFile : LyricsLoad

    /**
     * Text was read but it contains no timestamps. [metadataOnly] is true when the
     * file is entirely `[ar:]`/`[ti:]` tags, which is a different fix for the user
     * than a plain-text lyric sheet with no timing at all.
     */
    data class NoTimestamps(val metadataOnly: Boolean) : LyricsLoad

    val cues: List<SubtitleCue>
        get() = (this as? Loaded)?.cues.orEmpty()
}
