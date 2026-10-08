package com.imankoppai.mediaanvil.data

data class MediaCheckpoint(val version: String, val generation: Long)

/** Provider versions invalidate generations; folder scope changes require a fresh snapshot. */
object IncrementalLibrary {
    fun reusable(previous: MediaCheckpoint?, current: MediaCheckpoint, previousScope: Set<String>, scope: Set<String>): Boolean =
        previous != null && previous.version == current.version && current.generation >= previous.generation && previousScope == scope

    fun <T> merge(cached: List<T>, changed: List<T>, liveKeys: Set<String>, key: (T) -> String): List<T> {
        val result = cached.filter { key(it) in liveKeys }.associateByTo(linkedMapOf(), key)
        changed.forEach { item -> if (key(item) in liveKeys) result[key(item)] = item }
        return result.values.toList()
    }
}
