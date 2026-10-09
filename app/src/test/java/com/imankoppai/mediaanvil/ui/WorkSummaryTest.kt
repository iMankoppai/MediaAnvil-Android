package com.imankoppai.mediaanvil.ui

import org.junit.Assert.*
import org.junit.Test

class WorkSummaryTest {
    private val episodes = listOf(EpisodeProgress("1", 100, 0, true),
        EpisodeProgress("2", 200, 50, false), EpisodeProgress("3", 100, 0, false))
    @Test fun resumeUsesMostRecentUnfinishedEpisodeAndProgressUsesDuration() {
        val summary = summarizeWork(episodes, listOf("missing", "2", "1"))
        assertEquals(1, summary.continueIndex)
        assertEquals(2, summary.nextIndex)
        assertEquals(1, summary.finishedCount)
        assertEquals(0.375f, summary.progress, 0.001f)
    }
    @Test fun completedLatestEpisodeContinuesAtFirstUnfinishedAndDoesNotWrapNext() {
        assertEquals(1, summarizeWork(episodes, listOf("1")).continueIndex)
        assertNull(summarizeWork(episodes, listOf("3")).nextIndex)
        assertNull(summarizeWork(episodes.map { it.copy(finished = true) }, listOf("3")).continueIndex)
    }
    @Test fun unknownDurationsAndEmptyWorksAreSafe() {
        assertEquals(0.5f, summarizeWork(listOf(EpisodeProgress("1", 0, 0, true), EpisodeProgress("2", 0, 999, false)), emptyList()).progress)
        assertEquals(0f, summarizeWork(emptyList(), emptyList()).progress)
    }
    @Test fun finishingAnEpisodeContinuesForwardBeforeReturningToSkippedEarlierEpisodes() {
        val work = episodes.map { if (it.uri == "2") it.copy(finished = true) else if (it.uri == "1") it.copy(finished = false) else it }
        assertEquals(2, summarizeWork(work, listOf("2")).continueIndex)
    }
}
