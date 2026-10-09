package com.imankoppai.mediaanvil.playback

import kotlin.math.abs

data class FullPlayAttempt(val throughMs: Long, val durationMs: Long)

/** Tracks a heard prefix, never granting credit for a seek or an A/B loop. */
internal class FullPlayTracker(
    private val load: (String) -> FullPlayAttempt?,
    private val save: (String, FullPlayAttempt?) -> Unit,
    private val complete: (String) -> Unit,
) {
    private var uri: String? = null
    private var through = 0L
    private var duration = 0L
    private var position = 0L
    private var playing = false
    private var lastAt = 0L
    private var speed = 1f
    private var boundaryHandled = false

    fun observe(id: String, at: Long, length: Long, isPlaying: Boolean, rate: Float, now: Long, looping: Boolean) {
        if (uri != id) {
            checkpoint()
            uri = id
            val attempt = load(id)
            duration = length.takeIf { it > 0 } ?: attempt?.durationMs ?: 0
            through = attempt?.takeIf { length <= 0 || abs(it.durationMs - length) <= 250 }?.throughMs ?: 0
            boundaryHandled = false
            position = at.coerceAtLeast(0)
            lastAt = now
            playing = isPlaying && !looping
            speed = rate
            return
        }
        if (length > 0) {
            if (duration > 0 && abs(length - duration) > 250) through = 0
            duration = length
        }
        val next = at.coerceAtLeast(0)
        val elapsedBudget = ((now - lastAt).coerceAtLeast(0) * maxOf(speed, rate)).toLong() + 250
        if (!boundaryHandled && playing && !looping && next >= position &&
            next - position <= elapsedBudget && position <= through + 30) {
            through = maxOf(through, next).let { if (duration > 0) it.coerceAtMost(duration) else it }
        }
        position = next
        lastAt = now
        playing = isPlaying && !looping
        speed = rate
    }

    fun discontinuity(oldId: String?, oldPosition: Long, newId: String?, newPosition: Long,
        naturalEnd: Boolean, newLength: Long, isPlaying: Boolean, rate: Float, now: Long, looping: Boolean) {
        if (oldId == uri && oldId != null) {
            observe(oldId, oldPosition, duration, playing, rate, now, looping)
            if (naturalEnd) finish(looping)
            checkpoint()
        }
        if (newId == null) { reset(); return }
        if (newId != uri || naturalEnd) {
            // A natural boundary starts a fresh pass, including repeat-one.
            uri = null
            observe(newId, newPosition, newLength, isPlaying, rate, now, looping)
        } else {
            if (boundaryHandled && newPosition <= 30) { boundaryHandled = false; through = 0 }
            position = newPosition.coerceAtLeast(0)
            lastAt = now
            playing = isPlaying && !looping
            speed = rate
        }
    }

    fun finish(looping: Boolean) {
        val id = uri ?: return
        if (boundaryHandled) return
        boundaryHandled = true
        if (!looping && duration > 0 && through >= duration - minOf(50L, duration / 100)) complete(id)
        through = 0
        save(id, null)
    }

    fun checkpoint() {
        val id = uri ?: return
        save(id, if (!boundaryHandled && through > 0 && duration > 0) FullPlayAttempt(through, duration) else null)
    }

    /** Restoring a backup replaces the persisted attempts; discard the old live one. */
    fun reset() { uri = null; playing = false; through = 0; duration = 0; boundaryHandled = false }
}
