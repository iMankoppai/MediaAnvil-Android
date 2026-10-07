package com.imankoppai.mediaanvil.data

import org.junit.Assert.*
import org.junit.Test

class BackupTrackMatcherTest {
    private val source = BackupTrackReference("old", "episode.mp3", "Books/", 60_000, 8000)

    @Test fun matchesMovedFileWithNewMediaId() {
        val new = source.copy(uri = "new", relativeFolder = "New/Books/")
        assertEquals("new", BackupTrackMatcher.match(source, listOf(new)).automaticUri)
    }

    @Test fun reusedRowIdCannotMatchDifferentAudio() {
        val wrong = source.copy(fileName = "other.mp3")
        assertTrue(BackupTrackMatcher.match(source, listOf(wrong)).candidates.isEmpty())
    }

    @Test fun sameNameWithDifferentSizeOrDurationIsRejected() {
        assertTrue(BackupTrackMatcher.match(source, listOf(source.copy(sizeBytes = 8001))).candidates.isEmpty())
        assertTrue(BackupTrackMatcher.match(source, listOf(source.copy(durationMs = 120_000))).candidates.isEmpty())
    }

    @Test fun duplicateFilesNeedExplicitChoiceEvenWhenOneHasOldUri() {
        val candidates = listOf(source, source.copy(uri = "second"))
        assertNull(BackupTrackMatcher.match(source, candidates).automaticUri)
    }

    @Test fun exactFolderWinsOverCopyInOtherFolder() {
        assertEquals("new", BackupTrackMatcher.match(source,
            listOf(source.copy(uri = "new"), source.copy(uri = "copy", relativeFolder = "Copies/"))).automaticUri)
    }

    @Test fun missingReferencesHaveStableNonMediaUris() {
        val missing = BackupTrackMatcher.missingUri(source.uri)
        assertTrue(missing.startsWith("mediaanvil-missing:"))
        assertEquals(missing, BackupTrackMatcher.missingUri(missing))
    }

    @Test fun metadataFreeV3RecordCannotMatchReusedMediaRow() {
        val missing = BackupTrackReference("old", "", "", 0)
        assertNull(BackupTrackMatcher.match(missing, listOf(source)).automaticUri)
        assertEquals("old", BackupTrackMatcher.match(missing, listOf(source), allowLegacyUri = true).automaticUri)
    }
}
