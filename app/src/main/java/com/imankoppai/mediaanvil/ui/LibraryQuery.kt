package com.imankoppai.mediaanvil.ui

/**
 * Pure search and ordering helpers shared by every library list. They work on plain
 * strings so the behaviour is unit testable without an Android device.
 */
internal object LibraryQuery {
    private val whitespace = Regex("\\s+")

    /**
     * Every whitespace-separated term must appear somewhere in the track's text, so
     * "deer 稻香" narrows instead of widening. An empty query matches everything.
     */
    fun matches(
        title: String?,
        artist: String?,
        album: String?,
        fileName: String,
        query: String,
    ): Boolean {
        val terms = query.trim().split(whitespace).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val haystack = buildString {
            append(title.orEmpty())
            append(' ')
            append(artist.orEmpty())
            append(' ')
            append(album.orEmpty())
            append(' ')
            append(fileName)
        }.lowercase()
        return terms.all { haystack.contains(it.lowercase()) }
    }

    /**
     * Resolves [wanted] keys against [available] items, keeping the order the user
     * saved and silently skipping entries whose audio is gone. This is what makes a
     * playlist or favourite list play back in its own order rather than library order.
     */
    fun <T> resolve(available: List<T>, key: (T) -> String, wanted: List<String>): List<T> {
        if (wanted.isEmpty() || available.isEmpty()) return emptyList()
        val byKey = available.associateBy(key)
        return wanted.asSequence().distinct().mapNotNull { byKey[it] }.toList()
    }
}
