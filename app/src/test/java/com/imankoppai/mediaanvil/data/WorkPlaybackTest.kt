package com.imankoppai.mediaanvil.data

import org.junit.Assert.*
import org.junit.Test

class WorkPlaybackTest {
    @Test fun individualSpeedOverridesFolderThenGlobalAndHalfSpeedIsValid() {
        assertEquals(0.5f, effectivePlaybackSpeed(0.5f, 1.25f, 1.5f))
        assertEquals(1.25f, effectivePlaybackSpeed(null, 1.25f, 1.5f))
        assertEquals(1.5f, effectivePlaybackSpeed(null, null, 1.5f))
        assertEquals(1f, effectivePlaybackSpeed(Float.NaN, null, 1.5f))
    }
}
