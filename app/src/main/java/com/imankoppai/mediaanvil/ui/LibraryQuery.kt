package com.imankoppai.mediaanvil.ui

/**
 * Pure search and ordering helpers shared by every library list. They work on plain
 * strings so the behaviour is unit testable without an Android device.
 */
import com.imankoppai.mediaanvil.model.AudioTrack

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

    /** Prepares search text and sorted snapshots once, off the UI thread. */
    class Index(val source: List<AudioTrack>, checkCancellation: () -> Unit = {}) {
        private data class Prepared(val track: AudioTrack, val text: String)
        private val entries = source.mapIndexed { index, track ->
            if (index % 256 == 0) checkCancellation()
            Prepared(track, listOf(track.title, track.artist.orEmpty(), track.album.orEmpty(), track.fileName, track.relativeFolder).joinToString(" ").lowercase())
        }
        private val byFileName = entries.sortedWith { a, b -> NaturalOrder.compare(a.track.fileName, b.track.fileName) }
        private val byTitle = entries.sortedWith { a, b -> NaturalOrder.compare(a.track.title, b.track.title) }
        private val byDuration = entries.sortedBy { it.track.durationMs }

        fun search(query: String, sortMode: String, checkCancellation: () -> Unit = {}): List<AudioTrack> {
            val terms = query.trim().lowercase().split(whitespace).filter(String::isNotEmpty)
            val sorted = when (sortMode) {
                "title" -> byTitle
                "duration" -> byDuration
                else -> byFileName
            }
            return buildList {
                sorted.forEachIndexed { index, entry ->
                    if (index % 256 == 0) checkCancellation()
                    if (terms.all(entry.text::contains)) add(entry.track)
                }
            }
        }
    }

    fun filterAndSort(tracks: List<AudioTrack>, query: String, sortMode: String): List<AudioTrack> =
        Index(tracks).search(query, sortMode)

    /** Resolves saved keys in their original order, skipping missing audio. */
    fun <T> resolve(available: List<T>, key: (T) -> String, wanted: List<String>): List<T> {
        if (wanted.isEmpty() || available.isEmpty()) return emptyList()
        val byKey = available.associateBy(key)
        return wanted.asSequence().distinct().mapNotNull { byKey[it] }.toList()
    }
}
