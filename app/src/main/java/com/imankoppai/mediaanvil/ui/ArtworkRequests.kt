package com.imankoppai.mediaanvil.ui

import kotlinx.coroutines.*

/** Shared requests survive an individual row leaving the screen. Invalidated work cannot repopulate the cache. */
internal class ArtworkRequests<T>(
    private val scope: CoroutineScope,
    private val cached: (String) -> T?,
    private val save: (String, T) -> Unit,
    private val clock: () -> Long,
) {
    private val lock = Any()
    private val requests = mutableMapOf<String, Deferred<T?>>()
    private val missing = linkedMapOf<String, Long>()
    private var generation = 0L

    suspend fun load(key: String, loader: suspend () -> T?): T? {
        val request = synchronized(lock) {
            cached(key)?.let { return it }
            missing[key]?.let { if (clock() - it < 60_000L) return null else missing.remove(key) }
            requests[key] ?: scope.async(start = CoroutineStart.LAZY) {
                val job = currentCoroutineContext()[Job]
                val startedGeneration = synchronized(lock) { generation }
                try {
                    val result = loader()
                    synchronized(lock) {
                        if (generation == startedGeneration && requests[key] === job) {
                            if (result != null) save(key, result)
                            else {
                                missing[key] = clock()
                                if (missing.size > 512) missing.remove(missing.keys.first())
                            }
                        }
                    }
                    result
                } finally {
                    synchronized(lock) {
                        if (requests[key] === job) requests.remove(key)
                    }
                }
            }.also { requests[key] = it; it.start() }
        }
        return request.await()
    }

    fun invalidate(clear: () -> Unit) = synchronized(lock) {
        generation++
        requests.values.forEach { it.cancel() }
        requests.clear()
        missing.clear()
        clear()
    }
}

/** Bound the longest side, including portrait/panorama artwork. */
internal fun artworkSampleSize(width: Int, height: Int, target: Int): Int {
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / sample > target && sample <= (1 shl 29)) sample *= 2
    return sample
}
