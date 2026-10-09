package com.imankoppai.mediaanvil.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.data.AudioBookmark
import com.imankoppai.mediaanvil.data.IntervalTimes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IntervalTagsSheet(
    uri: String, listening: ListeningViewModel, controller: MediaController?, durationMs: Long,
    loopA: Long, loopB: Long, onJump: (AudioBookmark) -> Unit, onLoop: (AudioBookmark) -> Unit, onDismiss: () -> Unit,
) {
    var editing by remember(uri) { mutableStateOf<AudioBookmark?>(null) }
    var adding by remember(uri) { mutableStateOf(false) }
    var start by remember(uri) { mutableStateOf(0L) }
    var end by remember(uri) { mutableStateOf<Long?>(null) }
    val current = controller?.currentMediaItem?.mediaId == uri
    val actualDuration = controller?.duration?.takeIf { current && it > 0 } ?: durationMs
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.bookmarks), style = MaterialTheme.typography.titleLarge)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = current, onClick = {
                    start = controller?.currentPosition?.coerceAtLeast(0) ?: 0
                    end = null
                    adding = true
                }) { Text(stringResource(R.string.bookmark_add)) }
                Button(enabled = current && IntervalTimes.valid(loopA, loopB, actualDuration), onClick = {
                    start = loopA; end = loopB; adding = true
                }) { Text(stringResource(R.string.interval_save_ab)) }
            }
            val entries = listening.bookmarks.filter { it.trackUri == uri }.sortedBy { it.positionMs }
            if (entries.isEmpty()) Text(stringResource(R.string.bookmarks_empty), Modifier.padding(vertical = 16.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(entries, key = { it.id }) { tag ->
                    val usable = current && IntervalTimes.valid(tag.positionMs, tag.endPositionMs, actualDuration)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable {
                            if (usable) { onJump(tag); onDismiss() }
                            else { editing = tag }
                        }.padding(vertical = 12.dp)) {
                            Text(if (tag.endPositionMs != null) "${IntervalTimes.format(tag.positionMs)} – ${IntervalTimes.format(tag.endPositionMs)}"
                                else stringResource(R.string.interval_legacy, IntervalTimes.format(tag.positionMs)),
                                color = MaterialTheme.colorScheme.primary)
                            Text(tag.note.ifBlank { stringResource(R.string.interval_unnamed) })
                            if (tag.endPositionMs != null && !IntervalTimes.valid(tag.positionMs, tag.endPositionMs, actualDuration)) {
                                Text(stringResource(R.string.interval_invalid), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        IconButton(enabled = usable, onClick = { onLoop(tag); onDismiss() }) {
                            Icon(Icons.Filled.Repeat, contentDescription = stringResource(R.string.interval_loop))
                        }
                        IconButton(onClick = { editing = tag }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.bookmark_edit))
                        }
                        IconButton(onClick = { listening.deleteBookmark(tag.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_action))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (adding || editing != null) {
        IntervalTagEditor(
            initialStart = editing?.positionMs ?: start, initialEnd = if (editing != null) editing?.endPositionMs else end,
            initialName = editing?.note.orEmpty(), durationMs = actualDuration,
            currentPosition = { controller?.currentPosition?.coerceAtLeast(0) ?: 0 },
            onDismiss = { adding = false; editing = null },
            onSave = { a, b, name ->
                val id = editing?.id
                if (id == null) listening.addIntervalTag(uri, a, b, name) else listening.editIntervalTag(id, a, b, name)
                adding = false; editing = null
            },
        )
    }
}

@Composable
internal fun IntervalTagEditor(
    initialStart: Long, initialEnd: Long?, initialName: String, durationMs: Long,
    currentPosition: () -> Long, onDismiss: () -> Unit, onSave: (Long, Long, String) -> Unit,
) {
    var startText by rememberSaveable { mutableStateOf(IntervalTimes.format(initialStart)) }
    var endText by rememberSaveable { mutableStateOf(initialEnd?.let(IntervalTimes::format).orEmpty()) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val start = if (startText == IntervalTimes.format(initialStart)) initialStart else IntervalTimes.parseMinutesSeconds(startText)
    val end = if (initialEnd != null && endText == IntervalTimes.format(initialEnd)) initialEnd else IntervalTimes.parseMinutesSeconds(endText)
    val valid = IntervalTimes.valid(start, end, durationMs)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmark_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(500) }, label = { Text(stringResource(R.string.bookmark_note)) },
                    isError = attempted && name.isBlank(), modifier = Modifier.fillMaxWidth().testTag("interval_name"))
                OutlinedTextField(startText, { startText = it.take(32) }, singleLine = true,
                    label = { Text(stringResource(R.string.interval_start)) }, modifier = Modifier.fillMaxWidth().testTag("interval_start"))
                TextButton(onClick = { startText = IntervalTimes.format(currentPosition()) }) { Text(stringResource(R.string.interval_current_start)) }
                OutlinedTextField(endText, { endText = it.take(32) }, singleLine = true,
                    label = { Text(stringResource(R.string.interval_end)) }, modifier = Modifier.fillMaxWidth().testTag("interval_end"))
                TextButton(onClick = { endText = IntervalTimes.format(currentPosition()) }) { Text(stringResource(R.string.interval_current_end)) }
                Text(stringResource(R.string.interval_time_hint), style = MaterialTheme.typography.bodySmall)
                if (attempted && (!valid || name.isBlank())) Text(stringResource(R.string.interval_invalid), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(modifier = Modifier.testTag("interval_save"), onClick = {
            attempted = true
            if (valid && name.isNotBlank()) onSave(start!!, end!!, name.trim())
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
