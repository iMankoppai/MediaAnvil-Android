package com.imankoppai.mediaanvil.ui

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ArtworkRequestsTest {
    @Test fun panoramaAndPortraitDecodeAreBoundedByLongestSide() {
        assertEquals(32, artworkSampleSize(4096, 64, 160))
        assertEquals(32, artworkSampleSize(64, 4096, 160))
        assertEquals(1, artworkSampleSize(120, 120, 160))
    }
    @Test fun concurrentRowsShareOneRequestAndMissingResultsExpire() = runBlocking {
        val memory = mutableMapOf<String, String>()
        var now = 0L
        var loads = 0
        val cache = ArtworkRequests(this, { memory[it] }, { key, value -> memory[key] = value }, { now })
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = async { cache.load("cover") { loads++; started.complete(Unit); finish.await(); "art" } }
        started.await()
        val second = async { cache.load("cover") { error("duplicate request") } }
        yield(); finish.complete(Unit)
        assertEquals("art", first.await()); assertEquals("art", second.await()); assertEquals(1, loads)
        repeat(2) { assertNull(cache.load("missing") { loads++; null }) }
        assertEquals(2, loads)
        now = 60_001
        assertEquals("new", cache.load("missing") { loads++; "new" })
        assertEquals(3, loads)
    }
    @Test fun cancellingOneRowDoesNotCancelSharedLoadAndInvalidationDropsOldWork() = runBlocking {
        val memory = mutableMapOf<String, String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = ArtworkRequests(scope, { memory[it] }, { key, value -> memory[key] = value }, { 0L })
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        try {
            val row = async { cache.load("cover") { started.complete(Unit); finish.await(); "old" } }
            started.await(); row.cancelAndJoin()
            val other = async { cache.load("cover") { error("shared load cancelled") } }
            yield(); finish.complete(Unit)
            assertEquals("old", other.await())
            cache.invalidate { memory.clear() }
            assertEquals("fresh", cache.load("cover") { "fresh" })
        } finally { scope.cancel() }
    }
}
