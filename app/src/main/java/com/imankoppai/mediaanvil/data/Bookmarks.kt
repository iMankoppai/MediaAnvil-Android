package com.imankoppai.mediaanvil.data

import java.util.Base64

// A null end is a legacy point bookmark awaiting an explicit user conversion.
data class AudioBookmark(val id: String, val trackUri: String, val positionMs: Long, val note: String, val createdAt: Long,
    val endPositionMs: Long? = null)

/** Versioned rows avoid delimiter collisions in notes and keep malformed rows isolated. */
object Bookmarks {
    private fun encodeText(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decodeText(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    fun encode(entries: List<AudioBookmark>): String = entries.joinToString("\n") {
        "2\t${encodeText(it.id)}\t${encodeText(it.trackUri)}\t${it.positionMs}\t${encodeText(it.note)}\t${it.createdAt}\t${it.endPositionMs ?: ""}"
    }

    fun decode(raw: String): List<AudioBookmark> = raw.lineSequence().mapNotNull { row ->
        runCatching {
            val fields = row.split('\t')
            require((fields.size == 6 && fields[0] == "1") || (fields.size == 7 && fields[0] == "2"))
            AudioBookmark(decodeText(fields[1]), decodeText(fields[2]), fields[3].toLong(), decodeText(fields[4]), fields[5].toLong(),
                fields.getOrNull(6)?.takeIf(String::isNotEmpty)?.toLong())
                .also { require(it.id.isNotBlank() && it.trackUri.isNotBlank() && it.positionMs >= 0 && it.createdAt >= 0 &&
                    (it.endPositionMs == null || it.endPositionMs > it.positionMs)) }
        }.getOrNull()
    }.distinctBy { it.id }.toList()
}

/** Strict timestamps for range labels: seconds, mm:ss or hh:mm:ss, with optional milliseconds. */
object IntervalTimes {
    fun parse(text: String): Long? = runCatching {
        require(Regex("[0-9]+(?::[0-9]{1,2}){0,2}(?:\\.[0-9]{1,3})?").matches(text.trim()))
        val parts = text.trim().split('.')
        val units = parts[0].split(':').map(String::toLong)
        require(units.drop(1).all { it < 60 })
        val seconds = units.fold(0L) { total, unit -> Math.addExact(Math.multiplyExact(total, 60), unit) }
        Math.addExact(Math.multiplyExact(seconds, 1000), parts.getOrNull(1)?.padEnd(3, '0')?.toLong() ?: 0)
    }.getOrNull()

    fun format(ms: Long): String {
        val value = ms.coerceAtLeast(0)
        val seconds = value / 1000
        val base = if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
        return base + if (value % 1000 != 0L) ".%03d".format(java.util.Locale.ROOT, value % 1000) else ""
    }

    fun valid(start: Long?, end: Long?, durationMs: Long): Boolean = start != null && end != null &&
        start >= 0 && end > start && (durationMs <= 0 || end <= durationMs)
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
