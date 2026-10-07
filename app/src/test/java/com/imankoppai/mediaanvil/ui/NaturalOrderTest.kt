package com.imankoppai.mediaanvil.ui

import org.junit.Assert.*
import org.junit.Test

class NaturalOrderTest {
    @Test fun episodeNumbersSortInListeningOrder() {
        assertEquals(listOf("第1集.mp3", "第2集.mp3", "第10集.mp3", "第100集.mp3"),
            listOf("第10集.mp3", "第2集.mp3", "第100集.mp3", "第1集.mp3").sortedWith(NaturalOrder))
    }
    @Test fun leadingZerosAndLongNumbersDoNotOverflow() {
        assertEquals(0, NaturalOrder.compare("Track02", "track2"))
        assertTrue(NaturalOrder.compare("9999999999999999999999", "10000000000000000000000") < 0)
        assertTrue(NaturalOrder.compare("a", "ab") < 0)
    }
}
