package com.imankoppai.mediaanvil.subtitles

/**
 * One LRC parser shared by the sidecar loader and the player timeline.
 *
 * Previously the timestamp arithmetic and the "which text belongs to this line"
 * rule were written out twice — once in [SubtitleParser.parseLrc] and once in
 * [PreviewLyrics.parseLrcTimeline] — and the two disagreed: one stripped every
 * timestamp out of the line, the other took the text after the last one. The
 * player-facing rule (text after the last timestamp) is the correct one for a
 * line that carries several timestamps, so it is the rule kept here.
 */
internal object LrcParser {
    /** A lyric line and the moment it starts. */
    data class Line(val startMs: Long, val text: String)

    /**
     * @param lines every timed lyric line, sorted by start time, with lines that
     *   share a timestamp merged into one multi-line entry (an original line and
     *   its translation are usually written that way).
     * @param offsetMs the file's own `[offset:]` adjustment, already applied to
     *   every entry in [lines].
     * @param metadataLines how many `[ar:]`-style tag lines were seen, so a caller
     *   can tell "this file is only tags" from "this file has no timestamps".
     */
    data class Result(
        val lines: List<Line>,
        val offsetMs: Long,
        val metadataLines: Int,
    ) {
        val isEmpty: Boolean get() = lines.isEmpty()
    }

    private val offsetTag = Regex("^\\s*\\[offset:\\s*([+-]?\\d+)\\s*]\\s*$", RegexOption.IGNORE_CASE)

    /** Clamp so a nonsense tag cannot throw every line far off the timeline. */
    private const val MAX_OFFSET_MS = 600_000L

    fun parse(text: String): Result {
        val offsetMs = readOffset(text)
        var metadataLines = 0
        val raw = mutableListOf<Line>()

        for (line in text.lineSequence()) {
            if (LrcText.isMetadataLine(line)) {
                metadataLines++
                continue
            }
            val matches = SubtitleParser.lrcTimestamp.findAll(line).toList()
            if (matches.isEmpty()) continue
            // Text after the last timestamp: for "[00:01][00:05]Chorus" the word
            // belongs to both timestamps, and nothing is left dangling.
            val content = line.substring(matches.last().range.last + 1).trim()
            for (match in matches) {
                raw += Line(LrcTimestamp.toMillis(match), content)
            }
        }

        val merged = raw
            .groupBy { it.startMs }
            .toSortedMap()
            .map { (startMs, sameTime) ->
                // Same timestamp = original plus translation; keep both lines.
                Line(
                    startMs = (startMs + offsetMs).coerceAtLeast(0L),
                    text = sameTime.map { it.text }
                        .filter(String::isNotEmpty)
                        .distinct()
                        .joinToString("\n"),
                )
            }
            // A timestamp with no text marks an instrumental gap. Dropping it keeps
            // the previous line highlighted through the gap instead of moving the
            // highlight onto an invisible empty row.
            .filter { it.text.isNotEmpty() }

        return Result(lines = merged, offsetMs = offsetMs, metadataLines = metadataLines)
    }

    /**
     * Reads the `[offset:]` tag. A positive value shifts every timestamp later, so
     * the lyrics appear later — the convention the LRC format defines and that other
     * players follow: use a positive offset when the words show up before they are
     * sung, and a negative one when they lag behind.
     */
    internal fun readOffset(text: String): Long =
        text.lineSequence()
            .mapNotNull { offsetTag.find(it)?.groupValues?.get(1)?.toLongOrNull() }
            .firstOrNull()
            ?.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
            ?: 0L
}
