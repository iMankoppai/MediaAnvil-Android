package com.imankoppai.mediaanvil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure helpers of the folder-grant layer. The document lookups need a real
 * ContentResolver and are covered on-device instead.
 */
class SafStorageTest {
    @Test
    fun `folderFor strips surrounding slashes`() {
        assertEquals("Music/sub", SafStorage.folderFor("Music/sub/"))
        assertEquals("Music", SafStorage.folderFor("/Music"))
        assertEquals("", SafStorage.folderFor("/"))
    }

    @Test
    fun `sidecar extensions map to text mime types`() {
        assertEquals("text/plain", SafStorage.mimeTypeFor("lrc"))
        assertEquals("application/x-subrip", SafStorage.mimeTypeFor("srt"))
        assertEquals("text/vtt", SafStorage.mimeTypeFor("vtt"))
        assertEquals("text/plain", SafStorage.mimeTypeFor("LRC"))
        assertEquals("application/octet-stream", SafStorage.mimeTypeFor("bin"))
    }

    @Test
    fun `a per-item write grant is only required from API 30`() {
        // Scoped storage introduced the write request in API 30.
        assertFalse(SafStorage.requiresMediaWriteGrant(sdkInt = 26))
        assertFalse(SafStorage.requiresMediaWriteGrant(sdkInt = 29))
        assertTrue(SafStorage.requiresMediaWriteGrant(sdkInt = 30))
        assertTrue(SafStorage.requiresMediaWriteGrant(sdkInt = 36))
    }

    @Test
    fun `remembering a write grant applies to that file only`() {
        SafStorage.rememberMediaWriteGrant("content://media/audio/1")
        assertTrue(SafStorage.hasMediaWriteGrant("content://media/audio/1"))
        assertFalse(SafStorage.hasMediaWriteGrant("content://media/audio/2"))
    }

    @Test
    fun `only lyric files may be deleted`() {
        assertTrue(SafStorage.isSafeSubtitleName("song.lrc", "song.mp3"))
        assertTrue(SafStorage.isSafeSubtitleName("song.mp3.lrc", "song.mp3"))
        assertTrue(SafStorage.isSafeSubtitleName("song.srt", "song.mp3"))
        assertTrue(SafStorage.isSafeSubtitleName("song.vtt", "song.mp3"))
        // The audio itself is never a lyric target, whatever its extension.
        assertFalse(SafStorage.isSafeSubtitleName("song.lrc", "song.lrc"))
        assertFalse(SafStorage.isSafeSubtitleName("song.mp3", "song.mp3"))
        assertFalse(SafStorage.isSafeSubtitleName("song.flac", "song.mp3"))
        assertFalse(SafStorage.isSafeSubtitleName("", "song.mp3"))
    }

    @Test
    fun `a provider-appended txt suffix stays deletable`() {
        // Asking for "song.lrc" with the text/plain MIME type can be stored as
        // "song.lrc.txt" — observed on a real device, and the reason lyrics appeared
        // saved but could not be deleted or found again. The app wrote the file, so it
        // has to be able to remove it.
        assertTrue(SafStorage.isSafeSubtitleName("song.lrc.txt", "song.mp3"))
        assertTrue(SafStorage.isSafeSubtitleName("song.mp3.lrc.txt", "song.mp3"))
        assertTrue(SafStorage.isSafeSubtitleName("song.srt.txt", "song.mp3"))
        assertTrue(SafStorage.isSafeSubtitleName("song.lrc.TXT", "song.mp3"))
    }

    @Test
    fun `the txt allowance does not admit anything else`() {
        // A plain text file is not lyrics, and only one appended suffix is forgiven.
        assertFalse(SafStorage.isSafeSubtitleName("song.txt", "song.mp3"))
        assertFalse(SafStorage.isSafeSubtitleName("song.lrc.txt.txt", "song.mp3"))
        assertFalse(SafStorage.isSafeSubtitleName(".txt", "song.mp3"))
        // The audio file stays off limits even if it is named like lyrics.
        assertFalse(SafStorage.isSafeSubtitleName("song.lrc.txt", "song.lrc.txt"))
        // Ending in a lyric extension is what matters, whatever comes before it.
        assertTrue(SafStorage.isSafeSubtitleName("song.txt.lrc", "song.mp3"))
    }
}
