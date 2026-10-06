package com.imankoppai.mediaanvil.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryTest {
    private fun matches(query: String) = LibraryQuery.matches(
        title = "稻香",
        artist = "DEER迪迩",
        album = "Night Drive",
        fileName = "daoxiang.mp3",
        query = query,
    )

    @Test
    fun `an empty query matches every track`() {
        assertTrue(matches(""))
        assertTrue(matches("   "))
    }

    @Test
    fun `search covers title artist album and file name`() {
        assertTrue(matches("稻香"))
        assertTrue(matches("DEER"))
        assertTrue(matches("night drive"))
        assertTrue(matches("daoxiang"))
    }

    @Test
    fun `search ignores case`() {
        assertTrue(matches("NIGHT"))
        assertTrue(matches("DaOxIaNg"))
    }

    @Test
    fun `every term must match so extra words narrow the result`() {
        assertTrue(matches("稻香 DEER"))
        assertFalse(matches("稻香 missing-artist"))
    }

    @Test
    fun `a track that does not match is rejected`() {
        assertFalse(matches("something-else"))
    }


    private data class SortableTrack(
        val fileName: String,
        val title: String = fileName,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = 0L,
    )

    @Test
    fun `sort ordering matches title and duration modes`() {
        val tracks = listOf(
            SortableTrack("b.mp3", title = "B", durationMs = 3000),
            SortableTrack("a.mp3", title = "A", durationMs = 5000),
        )
        val byTitle = tracks.sortedBy { it.title.lowercase() }.map { it.fileName }
        val byDuration = tracks.sortedBy { it.durationMs }.map { it.fileName }
        assertEquals(listOf("a.mp3", "b.mp3"), byTitle)
        assertEquals(listOf("b.mp3", "a.mp3"), byDuration)
    }

    @Test
    fun `resolve keeps the saved playlist order`() {
        val library = listOf("a", "b", "c")
        val playlist = listOf("c", "a", "b")
        assertEquals(
            playlist,
            LibraryQuery.resolve(library, { it }, playlist),
        )
    }

    @Test
    fun `resolve skips entries whose audio is no longer available`() {
        val library = listOf("a", "c")
        assertEquals(listOf("c", "a"), LibraryQuery.resolve(library, { it }, listOf("c", "deleted", "a")))
    }

    @Test
    fun `resolve ignores duplicates and empty input`() {
        assertEquals(listOf("a"), LibraryQuery.resolve(listOf("a"), { it }, listOf("a", "a")))
        assertTrue(LibraryQuery.resolve(listOf("a"), { it }, emptyList()).isEmpty())
        assertTrue(LibraryQuery.resolve(emptyList<String>(), { it }, listOf("a")).isEmpty())
    }
}
