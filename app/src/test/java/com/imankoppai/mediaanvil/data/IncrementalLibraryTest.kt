package com.imankoppai.mediaanvil.data

import org.junit.Assert.*
import org.junit.Test

class IncrementalLibraryTest {
    private val scope = setOf("Books/")
    @Test fun checkpointRequiresSameProviderVersionAndScope() {
        val prior = MediaCheckpoint("db1", 100)
        assertTrue(IncrementalLibrary.reusable(prior, MediaCheckpoint("db1", 100), scope, scope))
        assertTrue(IncrementalLibrary.reusable(prior, MediaCheckpoint("db1", 101), scope, scope))
        assertFalse(IncrementalLibrary.reusable(prior, MediaCheckpoint("db2", 101), scope, scope))
        assertFalse(IncrementalLibrary.reusable(prior, MediaCheckpoint("db1", 99), scope, scope))
        assertFalse(IncrementalLibrary.reusable(prior, MediaCheckpoint("db1", 101), scope, emptySet()))
        assertFalse(IncrementalLibrary.reusable(null, MediaCheckpoint("db1", 101), scope, scope))
    }
    @Test fun changedRowsReplaceCachedMetadataAndDeletedRowsDisappear() {
        val cached = listOf("1:old", "2:deleted", "3:keep")
        val changed = listOf("1:new", "4:added", "5:alreadyDeleted")
        val result = IncrementalLibrary.merge(cached, changed, setOf("1", "3", "4")) { it.substringBefore(':') }
        assertEquals(listOf("1:new", "3:keep", "4:added"), result)
    }
    @Test fun unchangedRowsAreReusedAndEmptyProviderRemovesAllRows() {
        val cached = listOf("a", "b")
        assertEquals(cached, IncrementalLibrary.merge(cached, emptyList(), setOf("a", "b")) { it })
        assertTrue(IncrementalLibrary.merge(cached, emptyList(), emptySet()) { it }.isEmpty())
    }
}
