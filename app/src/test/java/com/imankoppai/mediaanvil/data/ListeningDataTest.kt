package com.imankoppai.mediaanvil.data

import org.junit.Assert.*
import org.junit.Test

class ListeningDataTest {
    @Test fun bookmarksRoundTripMultilineUnicodeAndTabs() {
        val entries = listOf(AudioBookmark("id", "content://media/audio/1", 123456, "第三章\n台词\t备注 😊", 100))
        assertEquals(entries, Bookmarks.decode(Bookmarks.encode(entries)))
    }

    @Test fun brokenRowsDoNotEraseOtherBookmarks() {
        val bookmark = AudioBookmark("id", "uri", 100, "", 100)
        assertEquals(listOf(bookmark), Bookmarks.decode("invalid\n" + Bookmarks.encode(listOf(bookmark)) + "\n1\tx\tx\t-1\tx\t0"))
    }

    @Test fun statusAndResumeHandleFinishedAndUnknownDuration() {
        assertEquals(ListeningProgress.Status.FINISHED, ListeningProgress.status(100, true))
        assertEquals(ListeningProgress.Status.IN_PROGRESS, ListeningProgress.status(100, false))
        assertEquals(ListeningProgress.Status.NOT_STARTED, ListeningProgress.status(-1, false))
        assertEquals(5000L, ListeningProgress.resumePosition(10000, 5, 30000))
        assertEquals(0L, ListeningProgress.resumePosition(1000, 5, 30000))
        assertEquals(10000L, ListeningProgress.resumePosition(10000, 0, 0))
        assertEquals(30000L, ListeningProgress.resumePosition(40000, 0, 30000))
    }

    @Test fun shortAudioIsNotFinishedAtItsBeginning() {
        assertFalse(ListeningProgress.isFinished(0, 3000))
        assertFalse(ListeningProgress.isFinished(1500, 3000))
        assertTrue(ListeningProgress.isFinished(3000, 3000))
        assertFalse(ListeningProgress.isFinished(1000, -1))
        assertTrue(ListeningProgress.isFinished(3_590_000, 3_600_000))
    }

    @Test fun sidecarsKeepExtensionPriorityAndProviderSuffix() {
        val files = mapOf("song.lrc" to "base", "song.mp3.lrc" to "double", "song.srt" to "srt")
        assertEquals("double" to "lrc", SidecarIndex.find("song.mp3", files))
        assertEquals("text" to "lrc", SidecarIndex.find("song.mp3", mapOf("song.mp3.lrc.txt" to "text")))
        assertNull(SidecarIndex.find("other.mp3", files))
    }

    @Test fun batchLookupReadsEachFolderOnceEvenWithThousandsOfTracks() {
        val audio = List(10000) { SidecarIndex.Audio(it, "Books/", "song$it.mp3") } +
            SidecarIndex.Audio(10000, "Other/", "song.mp3")
        val reads = mutableListOf<String>()
        val result = SidecarIndex.matchAll(audio) { folder ->
            reads += folder
            if (folder == "Books") mapOf("song5000.mp3.lrc" to "lyrics") else emptyMap()
        }
        assertEquals(listOf("Books", "Other"), reads)
        assertEquals(mapOf(5000 to ("lyrics" to "lrc")), result)
    }
}
