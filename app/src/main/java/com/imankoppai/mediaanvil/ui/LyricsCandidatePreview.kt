package com.imankoppai.mediaanvil.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.subtitles.ChineseLyrics
import com.imankoppai.mediaanvil.subtitles.LyricsScript
import com.imankoppai.mediaanvil.subtitles.OnlineLyricsCandidate
import com.imankoppai.mediaanvil.subtitles.PreviewLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ConvertedLyrics(val original: String, val script: LyricsScript, val result: Result<String>)

@Composable
internal fun ColumnScope.LyricsCandidatePreview(
    candidate: OnlineLyricsCandidate, script: LyricsScript, onScript: (LyricsScript) -> Unit,
    saving: Boolean, statusMessage: String?, onBack: () -> Unit, onSeek: (Long) -> Unit, onSave: (String) -> Unit,
) {
    val conversion by produceState<ConvertedLyrics?>(null, candidate.syncedLyrics, script) {
        val result = try {
            Result.success(withContext(Dispatchers.Default) { ChineseLyrics.convertLrc(candidate.syncedLyrics, script) })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failed: Exception) { Result.failure(failed) }
        value = ConvertedLyrics(candidate.syncedLyrics, script, result)
    }
    // An old result must never be saved under a newly selected mode, even before
    // the conversion coroutine has started. Rapid selections cancel older work.
    val ready = conversion?.takeIf { it.original == candidate.syncedLyrics && it.script == script }
    val lyrics = ready?.result?.getOrNull()
    val cues = remember(lyrics) { lyrics?.let(PreviewLyrics::parseLrcTimeline).orEmpty() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.online_lyrics_preview), style = MaterialTheme.typography.titleMedium)
        TextButton(enabled = !saving, onClick = onBack) { Text(stringResource(R.string.online_lyrics_back_to_results)) }
    }
    Text(listOf(candidate.trackName, candidate.artistName).filter(String::isNotBlank).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (candidate.possibleMismatch) Text(stringResource(R.string.online_lyrics_possible_mismatch),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LyricsScript.entries.forEach { option ->
            FilterChip(modifier = Modifier.testTag("lyrics_script_${option.value}"), selected = script == option, enabled = !saving, onClick = { onScript(option) }, label = {
                Text(stringResource(when (option) {
                    LyricsScript.Original -> R.string.lyrics_script_original
                    LyricsScript.Simplified -> R.string.lyrics_script_simplified
                    LyricsScript.Traditional -> R.string.lyrics_script_traditional
                }))
            })
        }
    }
    statusMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    if (ready == null) Text(stringResource(R.string.lyrics_script_converting), style = MaterialTheme.typography.labelSmall)
    else if (ready.result.isFailure) Text(stringResource(R.string.lyrics_script_failed), color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(4.dp))
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        itemsIndexed(cues, key = { index, cue -> "${cue.startMs}-$index" }) { _, cue ->
            Row(Modifier.fillMaxWidth().clickable { onSeek(cue.startMs) }.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Text(formatTime(cue.startMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(52.dp))
                Text(cue.text.ifEmpty { " " }, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
    }
    Button(modifier = Modifier.testTag("lyrics_preview_save"), enabled = !saving && lyrics != null && cues.isNotEmpty(), onClick = { lyrics?.let(onSave) }) {
        Text(stringResource(if (saving) R.string.online_lyrics_saving else R.string.online_lyrics_confirm_save))
    }
}
