package com.imankoppai.mediaanvil.ui

internal data class EpisodeProgress(val uri: String, val duration: Long, val position: Long, val finished: Boolean)
internal data class WorkSummary(val finishedCount: Int, val progress: Float, val continueIndex: Int?, val nextIndex: Int?)

internal fun summarizeWork(episodes: List<EpisodeProgress>, recentUris: List<String>): WorkSummary {
    val latest = recentUris.firstNotNullOfOrNull { uri -> episodes.indexOfFirst { it.uri == uri }.takeIf { it >= 0 } }
    val nextAfterLatest = latest?.let {
        ((latest + 1)..episodes.lastIndex).firstOrNull { !episodes[it].finished }
    }
    val continueIndex = latest?.takeIf { !episodes[it].finished } ?: nextAfterLatest
        ?: episodes.indexOfFirst { !it.finished }.takeIf { it >= 0 }
    val nextIndex = if (latest == null) continueIndex else nextAfterLatest
    val duration = episodes.sumOf { it.duration.coerceAtLeast(0) }
    val listened = episodes.sumOf {
        if (it.finished) it.duration.coerceAtLeast(0) else it.position.coerceIn(0L, it.duration.coerceAtLeast(0L))
    }
    val finishedCount = episodes.count { it.finished }
    val progress = if (duration > 0L) listened.toDouble() / duration
        else if (episodes.isNotEmpty()) finishedCount.toDouble() / episodes.size else 0.0
    return WorkSummary(finishedCount, progress.toFloat().coerceIn(0f, 1f), continueIndex, nextIndex)
}
