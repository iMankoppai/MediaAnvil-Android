package com.imankoppai.mediaanvil.playback

import org.junit.Assert.*
import org.junit.Test

class FullPlayTrackerTest {
    private val attempts = mutableMapOf<String, FullPlayAttempt>()
    private val counts = mutableMapOf<String, Int>()
    private var now = 0L
    private fun tracker() = FullPlayTracker({ attempts[it] }, { id, attempt ->
        if (attempt == null) attempts.remove(id) else attempts[id] = attempt
    }, { counts[it] = (counts[it] ?: 0) + 1 })
    private fun FullPlayTracker.at(position: Long, playing: Boolean = true, speed: Float = 1f, loop: Boolean = false, length: Long = 10000) {
        now += 1000
        observe("song", position, length, playing, speed, now, loop)
    }

    @Test fun fullNaturalEndCountsExactlyOnceAndReplayCountsAgain() {
        val tracker = tracker()
        for (position in 0L..10000L step 1000) tracker.at(position)
        assertTrue(counts.isEmpty())
        tracker.finish(false); tracker.finish(false)
        assertEquals(1, counts["song"])
        tracker.discontinuity("song", 10000, "song", 0, false, 10000, true, 1f, now, false)
        for (position in 1000L..10000L step 1000) tracker.at(position)
        tracker.finish(false)
        assertEquals(2, counts["song"])
    }

    @Test fun seekingToEndOrSkippingEvenASmallGapDoesNotCount() {
        val tracker = tracker()
        tracker.at(0); tracker.at(1000)
        tracker.discontinuity("song", 1000, "song", 9500, false, 10000, true, 1f, now, false)
        tracker.at(10000); tracker.finish(false)
        assertTrue(counts.isEmpty())
        val next = tracker()
        next.at(0); next.at(1000)
        next.discontinuity("song", 1000, "song", 1200, false, 10000, true, 1f, now, false)
        for (position in 2200L..9200L step 1000) next.at(position)
        next.at(10000); next.finish(false)
        assertTrue(counts.isEmpty())
    }

    @Test fun pausesAndRestartKeepTheHeardPrefixWithoutCreditingPausedTime() {
        val tracker = tracker()
        tracker.at(0); tracker.at(1000); tracker.at(2000, false); tracker.checkpoint()
        assertEquals(2000L, attempts["song"]?.throughMs)
        val restored = tracker()
        restored.at(2000, false); restored.at(2000)
        for (position in 3000L..10000L step 1000) restored.at(position)
        restored.finish(false)
        assertEquals(1, counts["song"])
    }

    @Test fun backwardSeekCanFillAGapButNeverCreditsItDirectly() {
        val tracker = tracker()
        tracker.at(0); tracker.at(1000)
        tracker.discontinuity("song", 1000, "song", 5000, false, 10000, true, 1f, now, false)
        tracker.at(6000)
        tracker.discontinuity("song", 6000, "song", 1000, false, 10000, true, 1f, now, false)
        for (position in 2000L..10000L step 1000) tracker.at(position)
        tracker.finish(false)
        assertEquals(1, counts["song"])
    }

    @Test fun halfSpeedAndTripleSpeedBothCountButLargeUnreportedJumpsDoNot() {
        for (rate in listOf(0.5f, 3f)) {
            counts.clear(); attempts.clear()
            val tracker = tracker()
            val step = (rate * 1000).toLong()
            for (position in 0L until 10000L step step) tracker.at(position, speed = rate)
            tracker.at(10000, speed = rate); tracker.finish(false)
            assertEquals(1, counts["song"])
        }
        counts.clear(); attempts.clear()
        val tracker = tracker()
        tracker.at(0); tracker.at(10000); tracker.finish(false)
        assertTrue(counts.isEmpty())
    }

    @Test fun ABLoopAndUnknownDurationNeverCount() {
        val tracker = tracker()
        for (position in 0L..10000L step 1000) tracker.at(position, loop = true)
        tracker.finish(true)
        assertTrue(counts.isEmpty())
        val unknown = tracker()
        for (position in 0L..10000L step 1000) unknown.at(position, length = -1)
        unknown.finish(false)
        assertTrue(counts.isEmpty())
    }

    @Test fun automaticRepeatStartsAFreshPassAndManualSkipDoesNotCount() {
        val tracker = tracker()
        for (position in 0L..9000L step 1000) tracker.at(position)
        now += 1000
        tracker.discontinuity("song", 10000, "song", 0, true, 10000, true, 1f, now, false)
        assertEquals(1, counts["song"])
        for (position in 1000L..10000L step 1000) tracker.at(position)
        tracker.finish(false)
        assertEquals(2, counts["song"])
        val skipped = tracker()
        skipped.at(0); skipped.at(1000)
        skipped.discontinuity("song", 1500, "other", 0, false, 10000, true, 1f, now + 500, false)
        assertEquals(2, counts["song"])
    }
}
