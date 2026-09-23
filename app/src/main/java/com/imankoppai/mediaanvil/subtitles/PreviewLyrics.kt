package com.imankoppai.mediaanvil.subtitles

import android.content.Context
import android.net.Uri
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.model.SubtitleCue

/**
 * Load sidecar LRC/SRT/VTT lyrics for the player. LRC lines stay highlighted
 * until the next line; timed subtitle cues clear after their end.
 */
object PreviewLyrics {
    const val NO_END = Long.MAX_VALUE

    fun load(context: Context, track: AudioTrack, autoLoadExternal: Boolean = true): List<SubtitleCue> =
        loadResult(context, track, autoLoadExternal).cues

    /**
     * Loads the sidecar lyrics and reports *why* nothing is showing, so the player
     * can tell "no file linked" apart from "the file is broken".
     */
    fun loadResult(
        context: Context,
        track: AudioTrack,
        autoLoadExternal: Boolean = true,
    ): LyricsLoad {
        if (!autoLoadExternal) return LyricsLoad.Disabled
        val uri = track.subtitleUri ?: return LyricsLoad.NotLinked
        return read(context, uri, track.subtitleExtension)
    }

    /** Reads and parses one lyric file, mapping every failure to a reason. */
    fun read(context: Context, uri: Uri, extension: String?): LyricsLoad {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return LyricsLoad.Unreadable

        if (bytes.isEmpty()) return LyricsLoad.EmptyFile

        val normalised = extension?.lowercase()?.removePrefix(".")
        val text = SubtitleDecoder.decode(bytes)

        return fromText(text, normalised)
    }

    /**
     * Maps already-decoded lyric text to a load result. Separated from [read] so
     * the "why is nothing showing" logic is testable without a Context or a file.
     */
    internal fun fromText(text: String, extension: String?): LyricsLoad = when (extension) {
        "lrc" -> fromLrc(text)
        "srt", "vtt" -> {
            val cues = SubtitleParser.parseTimedBlocks(text)
            if (cues.isEmpty()) LyricsLoad.NoTimestamps(metadataOnly = false) else LyricsLoad.Loaded(cues)
        }

        // Unknown extension: try LRC, since that is what a mistyped sidecar
        // usually is, and only then report it as unsupported.
        null, "" -> fromLrc(text).let { result ->
            if (result is LyricsLoad.Loaded) result else LyricsLoad.UnsupportedFormat
        }

        else -> LyricsLoad.UnsupportedFormat
    }

    internal fun fromLrc(text: String): LyricsLoad {
        val parsed = LrcParser.parse(text)
        if (parsed.lines.isNotEmpty()) return LyricsLoad.Loaded(toCues(parsed.lines))
        if (text.isBlank()) return LyricsLoad.EmptyFile
        // Only tags means the file is a lyric sheet without timing information.
        return LyricsLoad.NoTimestamps(metadataOnly = parsed.metadataLines > 0)
    }

    /**
     * LRC timeline where every line ends when the next one starts and the last
     * line stays highlighted, matching the desktop preview behaviour.
     *
     * Delegates to the shared [LrcParser], which also applies the file's own
     * `[offset:]` tag; that adjustment is already baked into the returned times.
     */
    fun parseLrcTimeline(text: String): List<SubtitleCue> =
        toCues(LrcParser.parse(text).lines)

    private fun toCues(lines: List<LrcParser.Line>): List<SubtitleCue> =
        lines.mapIndexed { index, line ->
            SubtitleCue(
                startMs = line.startMs,
                endMs = lines.getOrNull(index + 1)?.startMs ?: NO_END,
                text = line.text,
            )
        }
}
