package com.imankoppai.mediaanvil.data

import org.junit.Assert.*
import org.junit.Test

class IntervalTagsTest {
    @Test fun rangesPreserveBoundariesAndUnicodeLabels() {
        val tag = AudioBookmark("a", "uri", 113123, "副歌\n备注\t😊", 42, 150456)
        assertEquals(listOf(tag), Bookmarks.decode(Bookmarks.encode(listOf(tag))))
    }
    @Test fun oldPointsRemainPendingWithoutInventingAnEnd() {
        val old = "1\taWQ\tdXJp\t113000\t5rWL6K-V\t42"
        val tag = Bookmarks.decode(old).single()
        assertEquals("测试", tag.note)
        assertNull(tag.endPositionMs)
        assertEquals(listOf(tag), Bookmarks.decode(Bookmarks.encode(listOf(tag))))
    }
    @Test fun malformedRangeCannotBecomeAValidPoint() {
        val valid = AudioBookmark("a", "uri", 1000, "keep", 42, 2000)
        val encoded = Bookmarks.encode(listOf(valid))
        assertTrue(Bookmarks.decode(encoded.replace("\t2000", "\t999")).isEmpty())
        assertEquals(listOf(valid), Bookmarks.decode("invalid\n$encoded"))
    }
    @Test fun timesParseHoursMinutesAndMilliseconds() {
        assertEquals(113123L, IntervalTimes.parse("1:53.123"))
        assertEquals(3723456L, IntervalTimes.parse("1:02:03.456"))
        assertEquals(1500L, IntervalTimes.parse("1.5"))
        assertEquals(7205000L, IntervalTimes.parse("120:05"))
    }
    @Test fun invalidAndOverflowingTimesAreRejected() {
        listOf("", "-1:00", "1:60", "1:99:01", "1:02.1234", "Infinity", "99999999999999999999999", "1::03").forEach {
            assertNull(it, IntervalTimes.parse(it))
        }
    }
    @Test fun displayUsesMinutesAndWholeSecondsIncludingLongAudio() {
        val expected = mapOf(0L to "0:00", 123L to "0:00", 113123L to "1:53", 3600000L to "60:00", 3723456L to "62:03")
        expected.forEach { (time, label) -> assertEquals(label, IntervalTimes.format(time)) }
    }
    @Test fun editorAcceptsOnlyMinutesAndSeconds() {
        assertEquals(113000L, IntervalTimes.parseMinutesSeconds("1:53"))
        assertEquals(7205000L, IntervalTimes.parseMinutesSeconds("120:05"))
        listOf("1:53.123", "1:02:03", "1:60", "113", "").forEach { assertNull(IntervalTimes.parseMinutesSeconds(it)) }
    }
    @Test fun boundariesMustBeOrderedAndFitKnownDuration() {
        assertTrue(IntervalTimes.valid(1000, 2000, 2000))
        assertTrue(IntervalTimes.valid(1000, 2000, -1))
        assertFalse(IntervalTimes.valid(2000, 1000, 5000))
        assertFalse(IntervalTimes.valid(1000, 1000, 5000))
        assertFalse(IntervalTimes.valid(-1, 1000, 5000))
        assertFalse(IntervalTimes.valid(1000, null, 5000))
        assertFalse(IntervalTimes.valid(1000, 5001, 5000))
    }
}
