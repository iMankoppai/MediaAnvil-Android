package com.imankoppai.mediaanvil.data

import java.util.Base64

data class AudioBookmark(val id: String, val trackUri: String, val positionMs: Long, val note: String, val createdAt: Long)

/** Versioned rows avoid delimiter collisions in notes and keep malformed rows isolated. */
object Bookmarks {
    private fun encodeText(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decodeText(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    fun encode(entries: List<AudioBookmark>): String = entries.joinToString("\n") {
        "1\t${encodeText(it.id)}\t${encodeText(it.trackUri)}\t${it.positionMs}\t${encodeText(it.note)}\t${it.createdAt}"
    }

    fun decode(raw: String): List<AudioBookmark> = raw.lineSequence().mapNotNull { row ->
        runCatching {
            val fields = row.split('\t')
            require(fields.size == 6 && fields[0] == "1")
            AudioBookmark(decodeText(fields[1]), decodeText(fields[2]), fields[3].toLong(), decodeText(fields[4]), fields[5].toLong())
                .also { require(it.id.isNotBlank() && it.trackUri.isNotBlank() && it.positionMs >= 0 && it.createdAt >= 0) }
        }.getOrNull()
    }.distinctBy { it.id }.toList()
}

object ListeningProgress {
    enum class Status { NOT_STARTED, IN_PROGRESS, FINISHED }
    fun status(positionMs: Long, finished: Boolean): Status = when {
        finished -> Status.FINISHED
        positionMs > 0 -> Status.IN_PROGRESS
        else -> Status.NOT_STARTED
    }
    fun resumePosition(positionMs: Long, rewindSeconds: Int, durationMs: Long): Long =
        (positionMs.coerceAtLeast(0) - rewindSeconds.coerceIn(0, 30) * 1000L)
            .coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)

    fun isFinished(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 &&
        positionMs >= durationMs - minOf(15_000, durationMs / 20)
}
