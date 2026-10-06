package com.imankoppai.mediaanvil.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.subtitles.LyricsFileStore
import com.imankoppai.mediaanvil.subtitles.OnlineLyricsCandidate
import com.imankoppai.mediaanvil.subtitles.PreviewLyrics
import com.imankoppai.mediaanvil.subtitles.OnlineLyricsClient
import com.imankoppai.mediaanvil.model.SubtitleCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
@Composable
private fun lyricsStatusText(result: com.imankoppai.mediaanvil.subtitles.LyricsLoad?): String =
    when (result) {
        is com.imankoppai.mediaanvil.subtitles.LyricsLoad.Loaded -> stringResource(R.string.no_lyrics)
        com.imankoppai.mediaanvil.subtitles.LyricsLoad.Disabled ->
            stringResource(R.string.lyrics_status_disabled)
        com.imankoppai.mediaanvil.subtitles.LyricsLoad.NotLinked, null ->
            stringResource(R.string.lyrics_status_not_linked)
        com.imankoppai.mediaanvil.subtitles.LyricsLoad.Unreadable ->
            stringResource(R.string.lyrics_status_unreadable)
        com.imankoppai.mediaanvil.subtitles.LyricsLoad.UnsupportedFormat ->
            stringResource(R.string.lyrics_status_unsupported)
        com.imankoppai.mediaanvil.subtitles.LyricsLoad.EmptyFile ->
            stringResource(R.string.lyrics_status_empty)
        is com.imankoppai.mediaanvil.subtitles.LyricsLoad.NoTimestamps ->
            stringResource(
                if (result.metadataOnly) R.string.lyrics_status_metadata_only
                else R.string.lyrics_status_no_timestamps,
            )
    }

@Composable
internal fun LyricsView(
    library: LibraryViewModel,
    settings: SettingsViewModel,
    track: AudioTrack,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    autoLoadExternal: Boolean,
    showTimestamps: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cues by remember(track.uri) { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    // Why there are no lyrics, so the empty state can say something useful.
    var loadResult by remember(track.uri) { mutableStateOf<com.imankoppai.mediaanvil.subtitles.LyricsLoad?>(null) }
    var candidates by remember(track.uri) { mutableStateOf<List<OnlineLyricsCandidate>>(emptyList()) }
    var searching by remember(track.uri) { mutableStateOf(false) }
    var searchFinished by remember(track.uri) { mutableStateOf(false) }
    var statusMessage by remember(track.uri) { mutableStateOf<String?>(null) }
    var savingId by remember(track.uri) { mutableStateOf<Long?>(null) }
    var previewCandidate by remember(track.uri) { mutableStateOf<OnlineLyricsCandidate?>(null) }
    var managingLyrics by remember(track.uri) { mutableStateOf(false) }
    var managementMenu by remember(track.uri) { mutableStateOf(false) }
    var confirmDelete by remember(track.uri) { mutableStateOf(false) }
    var lyricsOffsetMs by remember(track.uri) {
        mutableLongStateOf(settings.preferences.lyricsOffsetFor(track.uri))
    }
    var queryTitle by remember(track.uri) { mutableStateOf(track.title) }
    var queryArtist by remember(track.uri) { mutableStateOf(track.artist.orEmpty()) }

    // Saving beside the audio needs the user to grant that folder once; there is no
    // permission that grants a whole tree implicitly any more.
    var pendingAfterGrant by remember(track.uri) { mutableStateOf<(() -> Unit)?>(null) }
    val folderGrantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val treeUri = result.data?.data
        if (treeUri != null) {
            com.imankoppai.mediaanvil.data.SafStorage.takePersistablePermission(context, treeUri)
            library.rescan(quiet = true)
        }
        val action = pendingAfterGrant
        pendingAfterGrant = null
        action?.invoke()
    }

    /** Resolved during composition so callbacks never read resources from a stale Context. */
    val needFolderText = stringResource(R.string.lyrics_need_folder)
    val importFailedText = stringResource(R.string.lyrics_import_failed)
    val searchFailedText = stringResource(R.string.online_lyrics_search_failed)
    val searchNotFoundText = stringResource(R.string.online_lyrics_not_found)
    val saveFailedText = stringResource(R.string.online_lyrics_save_failed)
    val deletedText = stringResource(R.string.lyrics_deleted)
    val deleteFailedText = stringResource(R.string.lyrics_delete_failed)

    /** Runs [action] once a granted folder covers this track, asking for one if needed. */
    fun withFolderAccess(action: () -> Unit) {
        if (LyricsFileStore.canWrite(context, track)) {
            action()
        } else {
            statusMessage = needFolderText
            pendingAfterGrant = action
            folderGrantLauncher.launch(
                com.imankoppai.mediaanvil.data.DeviceAudioLibrary.folderPickerIntent(context),
            )
        }
    }

    val lyricsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        withFolderAccess {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                        val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
                        LyricsFileStore.import(context, track, bytes, extension)
                    }
                }
                result.onSuccess { savedUri ->
                    library.attachLyrics(track.uri, savedUri, savedUri.lastPathSegment.orEmpty().substringAfterLast('.', "lrc"))
                    cues = withContext(Dispatchers.IO) {
                        PreviewLyrics.load(context, library.selectedTrack ?: track, true)
                    }
                    managingLyrics = false
                    statusMessage = null
                    android.widget.Toast.makeText(context, R.string.lyrics_imported, android.widget.Toast.LENGTH_SHORT).show()
                }.onFailure {
                    statusMessage = importFailedText
                }
            }
        }
    }

    fun searchOnline() {
        if (searching) return
        searching = true
        previewCandidate = null
        statusMessage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    OnlineLyricsClient.search(
                        track = track,
                        queryTitle = queryTitle,
                        queryArtist = queryArtist.takeIf(String::isNotBlank),
                    )
                }
            }
            searching = false
            searchFinished = true
            candidates = result.getOrDefault(emptyList())
            statusMessage = when {
                result.isFailure -> searchFailedText
                candidates.isEmpty() -> searchNotFoundText
                else -> null
            }
        }
    }

    fun saveCandidate(candidate: OnlineLyricsCandidate) {
        if (savingId != null) return
        withFolderAccess {
            savingId = candidate.id
            statusMessage = null
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { LyricsFileStore.save(context, track, candidate.syncedLyrics) }
                }
                savingId = null
                saved.onSuccess { savedUri ->
                    library.attachLyrics(track.uri, savedUri, "lrc")
                    cues = PreviewLyrics.parseLrcTimeline(candidate.syncedLyrics)
                    candidates = emptyList()
                    previewCandidate = null
                    managingLyrics = false
                    android.widget.Toast.makeText(
                        context,
                        R.string.online_lyrics_saved,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure {
                    statusMessage = saveFailedText
                }
            }
        }
    }

    LaunchedEffect(track.uri, autoLoadExternal) {
        val result = withContext(Dispatchers.IO) {
            runCatching { PreviewLyrics.loadResult(context, track, autoLoadExternal) }
                .getOrDefault(com.imankoppai.mediaanvil.subtitles.LyricsLoad.NotLinked)
        }
        loadResult = result
        cues = result.cues
        candidates = emptyList()
        previewCandidate = null
        managingLyrics = false
        statusMessage = null
        searchFinished = false
        // Only reach for the network when there is genuinely no local lyric file.
        if (cues.isEmpty() && track.subtitleUri == null && autoLoadExternal) searchOnline()
    }
    // The active line is the last one that has started; its end time is
    // ignored so the line stays blue through instrumental gaps and until the
    // next line begins.
    val currentIndex = cues.indexOfLast { cue ->
        positionMs >= (cue.startMs + lyricsOffsetMs).coerceAtLeast(0L)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex < 0) return@LaunchedEffect
        fun centerDelta(): Float? {
            val info = listState.layoutInfo
            if (info.viewportEndOffset <= info.viewportStartOffset) return null
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val item = info.visibleItemsInfo.firstOrNull { it.index == currentIndex } ?: return null
            return (item.offset + item.size / 2f) - viewportCenter
        }
        // Jump first when the target line is off-screen, then settle it in the middle.
        if (centerDelta() == null) {
            listState.scrollToItem(currentIndex)
        }
        centerDelta()?.let { delta ->
            if (kotlin.math.abs(delta) > 2f) {
                listState.animateScrollBy(delta)
            }
        }
    }
    if (cues.isEmpty() || managingLyrics) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                searching -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.online_lyrics_searching),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                previewCandidate != null -> {
                    val candidate = requireNotNull(previewCandidate)
                    val previewCues = remember(candidate.id, candidate.syncedLyrics) {
                        PreviewLyrics.parseLrcTimeline(candidate.syncedLyrics)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.online_lyrics_preview),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        androidx.compose.material3.TextButton(
                            enabled = savingId == null,
                            onClick = {
                                previewCandidate = null
                                statusMessage = null
                            },
                        ) {
                            Text(stringResource(R.string.online_lyrics_back_to_results))
                        }
                    }
                    Text(
                        listOf(candidate.trackName, candidate.artistName)
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (candidate.possibleMismatch) {
                        Text(
                            stringResource(R.string.online_lyrics_possible_mismatch),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    statusMessage?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(
                            previewCues,
                            key = { index, cue -> "${cue.startMs}-$index" },
                        ) { _, cue ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSeek(cue.startMs) }
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    formatTime(cue.startMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(52.dp),
                                )
                                Text(
                                    cue.text.ifEmpty { " " },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    Button(
                        enabled = savingId == null && previewCues.isNotEmpty(),
                        onClick = { saveCandidate(candidate) },
                    ) {
                        Text(
                            stringResource(
                                if (savingId == null) R.string.online_lyrics_confirm_save
                                else R.string.online_lyrics_saving,
                            ),
                        )
                    }
                }
                candidates.isNotEmpty() -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.online_lyrics_choose),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        androidx.compose.material3.TextButton(
                            onClick = {
                                candidates = emptyList()
                                previewCandidate = null
                                statusMessage = null
                                searchFinished = false
                            },
                        ) {
                            Text(stringResource(R.string.online_lyrics_edit_query))
                        }
                    }
                    Text(
                        stringResource(R.string.online_lyrics_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(candidates, key = { _, item -> item.id }) { _, candidate ->
                            androidx.compose.material3.Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable(enabled = savingId == null) {
                                        previewCandidate = candidate
                                        statusMessage = null
                                    },
                            ) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    Text(candidate.trackName, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        listOf(candidate.artistName, candidate.albumName)
                                            .filter(String::isNotBlank)
                                            .joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.online_lyrics_duration,
                                            formatTime(candidate.durationSeconds * 1_000L),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    if (candidate.possibleMismatch) {
                                        Text(
                                            stringResource(R.string.online_lyrics_possible_mismatch),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (cues.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            managingLyrics = false
                            candidates = emptyList()
                            previewCandidate = null
                            statusMessage = null
                        }) { Text(stringResource(R.string.lyrics_keep_current)) }
                    }
                }
                else -> {
                    Text(
                        statusMessage ?: lyricsStatusText(loadResult),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = queryTitle,
                        onValueChange = { queryTitle = it },
                        label = { Text(stringResource(R.string.online_lyrics_query_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = queryArtist,
                        onValueChange = { queryArtist = it },
                        label = { Text(stringResource(R.string.online_lyrics_query_artist)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = queryTitle.isNotBlank(),
                        onClick = { searchOnline() },
                    ) {
                        Text(
                            stringResource(
                                if (searchFinished) R.string.online_lyrics_retry else R.string.online_lyrics_search,
                            ),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.online_lyrics_windows_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (cues.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                managingLyrics = false
                                statusMessage = null
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text(stringResource(R.string.lyrics_keep_current)) }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = { managementMenu = true }) {
                    Text(stringResource(R.string.lyrics_manage))
                }
                DropdownMenu(
                    expanded = managementMenu,
                    onDismissRequest = { managementMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lyrics_search_again)) },
                        onClick = {
                            managementMenu = false
                            managingLyrics = true
                            searchFinished = false
                            candidates = emptyList()
                            searchOnline()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lyrics_choose_local)) },
                        onClick = {
                            managementMenu = false
                            lyricsPicker.launch(arrayOf("text/plain", "application/x-subrip"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lyrics_earlier)) },
                        onClick = {
                            managementMenu = false
                            lyricsOffsetMs = (lyricsOffsetMs - 500L).coerceAtLeast(-60_000L)
                            settings.preferences.setLyricsOffset(track.uri, lyricsOffsetMs)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lyrics_later)) },
                        onClick = {
                            managementMenu = false
                            lyricsOffsetMs = (lyricsOffsetMs + 500L).coerceAtMost(60_000L)
                            settings.preferences.setLyricsOffset(track.uri, lyricsOffsetMs)
                        },
                    )
                    if (lyricsOffsetMs != 0L) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lyrics_offset_reset)) },
                            onClick = {
                                managementMenu = false
                                lyricsOffsetMs = 0L
                                settings.preferences.setLyricsOffset(track.uri, 0L)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lyrics_delete)) },
                        onClick = {
                            managementMenu = false
                            confirmDelete = true
                        },
                    )
                }
            }
            if (lyricsOffsetMs != 0L) {
                Text(
                    stringResource(R.string.lyrics_offset_value, lyricsOffsetMs / 1000f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(cues) { index, cue ->
                    val active = index == currentIndex
                    val effectiveStart = (cue.startMs + lyricsOffsetMs).coerceAtLeast(0L)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeek(effectiveStart) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (showTimestamps) {
                            Text(
                                lyricsTimestampLabel(effectiveStart),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            cue.text.ifEmpty { " " },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.lyrics_delete_confirm_title)) },
            text = { Text(stringResource(R.string.lyrics_delete_confirm_body)) },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            runCatching { LyricsFileStore.delete(context, track) }.getOrDefault(false)
                        }
                        if (deleted) {
                            library.detachLyrics(track.uri)
                            settings.preferences.setLyricsOffset(track.uri, 0L)
                            lyricsOffsetMs = 0L
                            cues = emptyList()
                            statusMessage = deletedText
                        } else {
                            statusMessage = deleteFailedText
                        }
                    }
                }) { Text(stringResource(R.string.lyrics_delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
