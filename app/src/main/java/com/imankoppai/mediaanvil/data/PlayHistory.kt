package com.imankoppai.mediaanvil.data

/**
 * Newest-first playback history written by the playback service.
 *
 * It is persisted as tab-separated `uri<TAB>timestamp` lines: the format needs no
 * JSON parser, survives partial writes, and a malformed line is dropped instead of
 * taking the player down with it.
 */
object PlayHistory {
    /** Enough to cover a long listening session without growing without bound. */
    const val MAX_ENTRIES = 200

    data class Entry(val uri: String, val playedAt: Long)

    fun encode(entries: List<Entry>): String =
        entries.joinToString("\n") { "${it.uri}\t${it.playedAt}" }

    /** Tolerant decode: unknown or truncated lines are skipped, never thrown. */
    fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                // lastIndexOf keeps uris that happen to contain a tab intact.
                val separator = line.lastIndexOf('\t')
                if (separator <= 0) return@mapNotNull null
                val uri = line.substring(0, separator)
                val playedAt = line.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
                if (uri.isBlank()) null else Entry(uri, playedAt)
            }
            .toList()
    }

    /** Moves [uri] to the front with a fresh timestamp, then caps the list. */
    fun record(
        entries: List<Entry>,
        uri: String,
        playedAt: Long,
        maxEntries: Int = MAX_ENTRIES,
    ): List<Entry> {
        if (uri.isBlank() || maxEntries <= 0) return entries
        val withoutDuplicate = entries.filterNot { it.uri == uri }
        return (listOf(Entry(uri, playedAt)) + withoutDuplicate).take(maxEntries)
    }

    /**
     * Uris that are still present in the library, newest first. Entries for audio
     * that was moved or deleted stay in storage so a temporarily unmounted card
     * does not erase the user's history; they are only filtered out of the list.
     */
    fun availableUris(entries: List<Entry>, available: Set<String>): List<String> =
        entries.asSequence().map { it.uri }.filter { it in available }.distinct().toList()
}
