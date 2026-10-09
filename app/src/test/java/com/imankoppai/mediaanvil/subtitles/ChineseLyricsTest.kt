package com.imankoppai.mediaanvil.subtitles

import org.junit.Assert.*
import org.junit.Test

class ChineseLyricsTest {
    @Test fun convertsBothDirectionsWithoutChangingTimingMetadataOrLatinText() {
        val original = "[ti:繁體歌名]\r\n[offset:-125]\r\n[00:01.234][00:03.456]聽見風聲，為你高興 Hello English 😊\r\n"
        val simplified = ChineseLyrics.convertLrc(original, LyricsScript.Simplified)
        assertEquals("[ti:繁體歌名]\r\n[offset:-125]\r\n[00:01.234][00:03.456]听见风声，为你高兴 Hello English 😊\r\n", simplified)
        assertEquals(original, ChineseLyrics.convertLrc(original, LyricsScript.Original))
        val traditional = ChineseLyrics.convertLrc("[00:01.234]头发长长，发现风景", LyricsScript.Traditional)
        assertEquals("[00:01.234]頭髮長長，發現風景", traditional)
        assertEquals(PreviewLyrics.parseLrcTimeline(original).map { it.startMs }, PreviewLyrics.parseLrcTimeline(simplified).map { it.startMs })
    }

    @Test fun eachChoiceStartsFromOriginalAndLongLinesAreSupported() {
        val original = "[00:01]滑鼠裡的聲音，Hello 123 😊"
        val simplified = ChineseLyrics.convertLrc(original, LyricsScript.Simplified)
        assertTrue(simplified.contains("里的声音"))
        assertEquals(original, ChineseLyrics.convertLrc(original, LyricsScript.Original))
        val line = "[00:02]" + "风景与头发，".repeat(1000)
        assertEquals("[00:02]" + "風景與頭髮，".repeat(1000), ChineseLyrics.convertLrc(line, LyricsScript.Traditional))
    }
}
