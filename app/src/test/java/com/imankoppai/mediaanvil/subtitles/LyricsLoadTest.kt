package com.imankoppai.mediaanvil.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every "no lyrics" case used to collapse into one message. These assertions pin
 * down that each reason is now distinguishable, because they need different
 * answers from the user: link a file, fix a path, or use a timed LRC.
 */
class LyricsLoadTest {

    private fun lrc(text: String): LyricsLoad = PreviewLyrics.fromText(text, "lrc")

    private fun asFile(text: String, extension: String?): LyricsLoad =
        PreviewLyrics.fromText(text, extension)

    @Test
    fun `timed lyrics load normally`() {
        val result = lrc("[00:01.00]第一句\n[00:05.00]第二句")

        assertTrue(result is LyricsLoad.Loaded)
        assertEquals(2, result.cues.size)
    }

    @Test
    fun `a file with only tag lines reports metadata rather than empty`() {
        val result = lrc("[ti:标题]\n[ar:歌手]")

        assertTrue(result is LyricsLoad.NoTimestamps)
        assertTrue("tags present must be reported", (result as LyricsLoad.NoTimestamps).metadataOnly)
    }

    @Test
    fun `plain text with no tags and no timestamps is a different reason`() {
        val result = lrc("这只是一段没有任何时间戳的文字")

        assertTrue(result is LyricsLoad.NoTimestamps)
        assertTrue("no tags seen", !(result as LyricsLoad.NoTimestamps).metadataOnly)
    }

    @Test
    fun `a blank file is reported as empty`() {
        assertTrue(lrc("   \n  \n") is LyricsLoad.EmptyFile)
    }

    @Test
    fun `the player timeline and the status agree on the same file`() {
        val text = "[00:01.00]第一句"

        val result = lrc(text)
        val timeline = PreviewLyrics.parseLrcTimeline(text)

        assertTrue(result is LyricsLoad.Loaded)
        assertEquals(result.cues.map { it.startMs }, timeline.map { it.startMs })
    }

    @Test
    fun `an unlinked track is not the same as an unreadable file`() {
        // These are separate states by construction; assert the distinction holds.
        assertTrue(LyricsLoad.NotLinked != LyricsLoad.Unreadable)
        assertTrue(LyricsLoad.NotLinked.cues.isEmpty())
        assertTrue(LyricsLoad.Unreadable.cues.isEmpty())
        assertTrue(LyricsLoad.Disabled.cues.isEmpty())
    }

    @Test
    fun `only the loaded state exposes cues`() {
        assertTrue(LyricsLoad.EmptyFile.cues.isEmpty())
        assertTrue(LyricsLoad.UnsupportedFormat.cues.isEmpty())
        assertTrue(LyricsLoad.NoTimestamps(metadataOnly = false).cues.isEmpty())
        assertEquals(1, LyricsLoad.Loaded(PreviewLyrics.parseLrcTimeline("[00:01.00]X")).cues.size)
    }

    @Test
    fun `an unsupported extension is reported as unsupported`() {
        assertTrue(asFile("[00:01.00]X", "txt") is LyricsLoad.UnsupportedFormat)
        assertTrue(asFile("[00:01.00]X", "ass") is LyricsLoad.UnsupportedFormat)
    }

    @Test
    fun `a timed srt file loads`() {
        val srt = "1\n00:00:02,000 --> 00:00:04,500\nFirst line"

        val result = asFile(srt, "srt")

        assertTrue(result is LyricsLoad.Loaded)
        assertEquals(2_000L, result.cues.single().startMs)
    }

    @Test
    fun `an srt file without timestamps reports no timestamps`() {
        assertTrue(asFile("just some words", "srt") is LyricsLoad.NoTimestamps)
    }

    @Test
    fun `a file with no extension is still read as lrc when it has timestamps`() {
        val result = asFile("[00:01.00]第一句", null)

        assertTrue(result is LyricsLoad.Loaded)
        assertEquals(1, result.cues.size)
    }
}
