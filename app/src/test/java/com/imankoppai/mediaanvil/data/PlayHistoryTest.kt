package com.imankoppai.mediaanvil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHistoryTest {
    @Test
    fun `round trips entries through the stored text format`() {
        val entries = listOf(
            PlayHistory.Entry("content://media/audio/2", 1_700_000_000_000L),
            PlayHistory.Entry("content://media/audio/1", 1_600_000_000_000L),
        )
        assertEquals(entries, PlayHistory.decode(PlayHistory.encode(entries)))
    }

    @Test
    fun `decode skips malformed lines instead of failing`() {
        val raw = "content://media/audio/1\t100\n" +
            "garbage-without-a-tab\n" +
            "content://media/audio/2\tnot-a-number\n" +
            "\t500\n" +
            "content://media/audio/3\t300\n"
        assertEquals(
            listOf(
                PlayHistory.Entry("content://media/audio/1", 100L),
                PlayHistory.Entry("content://media/audio/3", 300L),
            ),
            PlayHistory.decode(raw),
        )
    }

    @Test
    fun `decode tolerates empty and missing history`() {
        assertTrue(PlayHistory.decode(null).isEmpty())
        assertTrue(PlayHistory.decode("").isEmpty())
    }

    @Test
    fun `recording a track moves it to the front without duplicating it`() {
        val first = PlayHistory.record(emptyList(), "a", 100L)
        val second = PlayHistory.record(first, "b", 200L)
        val replayed = PlayHistory.record(second, "a", 300L)

        assertEquals(listOf("a", "b"), replayed.map { it.uri })
        assertEquals(300L, replayed.first().playedAt)
        assertEquals(2, replayed.size)
    }

    @Test
    fun `history is capped so it cannot grow without bound`() {
        var entries = emptyList<PlayHistory.Entry>()
        repeat(10) { index -> entries = PlayHistory.record(entries, "uri-$index", index.toLong(), maxEntries = 3) }

        assertEquals(3, entries.size)
        assertEquals(listOf("uri-9", "uri-8", "uri-7"), entries.map { it.uri })
    }

    @Test
    fun `blank uris are never recorded`() {
        assertTrue(PlayHistory.record(emptyList(), "", 100L).isEmpty())
    }

    @Test
    fun `availableUris drops entries whose audio is gone but keeps the rest in order`() {
        val entries = listOf(
            PlayHistory.Entry("a", 3L),
            PlayHistory.Entry("deleted", 2L),
            PlayHistory.Entry("b", 1L),
        )
        assertEquals(listOf("a", "b"), PlayHistory.availableUris(entries, setOf("a", "b")))
    }
}
