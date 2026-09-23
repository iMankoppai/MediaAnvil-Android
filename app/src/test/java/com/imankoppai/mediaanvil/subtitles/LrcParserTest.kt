package com.imankoppai.mediaanvil.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the LRC behaviours that were wrong or missing before Phase 6: the
 * `[offset:]` tag, decimal-fraction timestamps, and the fact that the sidecar
 * parser and the player timeline now agree because they share one implementation.
 */
class LrcParserTest {

    @Test
    fun `a positive offset shifts every line later`() {
        // [offset:500] means the words show up before they are sung, so the file
        // asks for them to be shown 500 ms later.
        val result = LrcParser.parse("[offset:500]\n[00:10.00]第一句\n[00:20.00]第二句")

        assertEquals(500L, result.offsetMs)
        assertEquals(10_500L, result.lines[0].startMs)
        assertEquals(20_500L, result.lines[1].startMs)
    }

    @Test
    fun `a negative offset shifts every line earlier`() {
        val result = LrcParser.parse("[offset:-500]\n[00:10.00]第一句")

        assertEquals(-500L, result.offsetMs)
        assertEquals(9_500L, result.lines.single().startMs)
    }

    @Test
    fun `an offset cannot push a line before the start of the track`() {
        val result = LrcParser.parse("[offset:-30000]\n[00:10.00]第一句")

        assertEquals(0L, result.lines.single().startMs)
    }

    @Test
    fun `an absurd offset is clamped instead of destroying the timeline`() {
        val result = LrcParser.parse("[offset:999999999]\n[00:10.00]第一句")

        assertEquals(600_000L, result.offsetMs)
        assertTrue(result.lines.single().startMs <= 610_000L)
    }

    @Test
    fun `a malformed offset tag is ignored`() {
        val result = LrcParser.parse("[offset:abc]\n[00:10.00]第一句")

        assertEquals(0L, result.offsetMs)
        assertEquals(10_000L, result.lines.single().startMs)
    }

    @Test
    fun `offset is recognised regardless of case and spacing`() {
        assertEquals(250L, LrcParser.readOffset("[OFFSET: 250]"))
        assertEquals(-250L, LrcParser.readOffset("[Offset:-250]"))
    }

    @Test
    fun `fractions are decimal so five tenths is half a second`() {
        // ".5" must contribute 500 ms (not 5 ms), so 00:01.5 is 1.5 s in total.
        assertEquals(1_500L, LrcParser.parse("[00:01.5]X").lines.single().startMs)
        assertEquals(1_500L, LrcParser.parse("[00:01.50]X").lines.single().startMs)
        assertEquals(1_500L, LrcParser.parse("[00:01.500]X").lines.single().startMs)
        // ".05" is five hundredths: 1 s + 50 ms.
        assertEquals(1_050L, LrcParser.parse("[00:01.05]X").lines.single().startMs)
        assertEquals(1_000L, LrcParser.parse("[00:01]X").lines.single().startMs)
    }

    @Test
    fun `a line with several timestamps keeps its text on each of them`() {
        val result = LrcParser.parse("[00:01.00][00:05.00]Chorus")

        assertEquals(2, result.lines.size)
        assertEquals("Chorus", result.lines[0].text)
        assertEquals("Chorus", result.lines[1].text)
        assertEquals(1_000L, result.lines[0].startMs)
        assertEquals(5_000L, result.lines[1].startMs)
    }

    @Test
    fun `original and translation at the same timestamp become one two-line cue`() {
        val result = LrcParser.parse("[00:02.00]Hello world\n[00:02.00]你好，世界")

        assertEquals(1, result.lines.size)
        assertEquals("Hello world\n你好，世界", result.lines.single().text)
    }

    @Test
    fun `a blank timestamp is dropped so the previous line stays highlighted`() {
        val result = LrcParser.parse("[00:02.00]第一句\n[00:08.00]\n[00:20.00]第二句")

        assertEquals(2, result.lines.size)
        assertEquals("第一句", result.lines[0].text)
        assertEquals("第二句", result.lines[1].text)
    }

    @Test
    fun `metadata lines are counted but never become lyrics`() {
        val result = LrcParser.parse("[ar:歌手]\n[ti:标题]\n[00:01.00]正文")

        assertEquals(2, result.metadataLines)
        assertEquals(1, result.lines.size)
        assertEquals("正文", result.lines.single().text)
    }

    @Test
    fun `a file with only metadata yields no lines but reports why`() {
        val result = LrcParser.parse("[ar:歌手]\n[ti:标题]")

        assertTrue(result.isEmpty)
        assertEquals(2, result.metadataLines)
    }

    @Test
    fun `timestamps out of order are sorted`() {
        val result = LrcParser.parse("[00:20.00]Later\n[00:05.00]Earlier")

        assertEquals(listOf("Earlier", "Later"), result.lines.map { it.text })
    }

    @Test
    fun `both lyric readers produce the same timeline`() {
        val text = "[offset:200]\n[ti:标题]\n[00:01.50]First\n[00:04.00]Second"

        val viaSidecar = SubtitleParser.parseLrc(text)
        val viaPlayer = PreviewLyrics.parseLrcTimeline(text)

        assertEquals(viaSidecar.map { it.startMs }, viaPlayer.map { it.startMs })
        assertEquals(viaSidecar.map { it.text }, viaPlayer.map { it.text })
        assertEquals(1_700L, viaPlayer[0].startMs)
    }
}
