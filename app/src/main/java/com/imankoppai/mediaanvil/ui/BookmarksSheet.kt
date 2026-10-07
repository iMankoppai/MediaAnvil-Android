package com.imankoppai.mediaanvil.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.data.AudioBookmark
import androidx.media3.session.MediaController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarksSheet(uri: String, listening: ListeningViewModel, controller: MediaController?, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<AudioBookmark?>(null) }
    var addingAt by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.bookmarks), style = MaterialTheme.typography.titleLarge)
            Button(enabled = controller?.currentMediaItem?.mediaId == uri, onClick = {
                addingAt = controller?.currentPosition?.coerceAtLeast(0)
                note = ""
            }) { Text(stringResource(R.string.bookmark_add)) }
            val entries = listening.bookmarks.filter { it.trackUri == uri }.sortedBy { it.positionMs }
            if (entries.isEmpty()) Text(stringResource(R.string.bookmarks_empty), Modifier.padding(vertical = 16.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(entries, key = { it.id }) { bookmark ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable {
                            if (controller?.currentMediaItem?.mediaId == uri) {
                                controller.seekTo(bookmark.positionMs)
                                onDismiss()
                            }
                        }.padding(vertical = 12.dp)) {
                            Text(formatTime(bookmark.positionMs), color = MaterialTheme.colorScheme.primary)
                            if (bookmark.note.isNotBlank()) Text(bookmark.note)
                        }
                        IconButton(onClick = { editing = bookmark; note = bookmark.note }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.bookmark_edit))
                        }
                        IconButton(onClick = { listening.deleteBookmark(bookmark.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_action))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (addingAt != null || editing != null) {
        AlertDialog(
            onDismissRequest = { addingAt = null; editing = null },
            title = { Text(stringResource(if (editing == null) R.string.bookmark_add else R.string.bookmark_edit)) },
            text = { OutlinedTextField(value = note, onValueChange = { note = it.take(500) }, label = { Text(stringResource(R.string.bookmark_note)) }) },
            confirmButton = { TextButton(onClick = {
                val current = editing
                if (current != null) listening.editBookmark(current.id, note)
                else addingAt?.let { listening.addBookmark(uri, it, note) }
                addingAt = null
                editing = null
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { addingAt = null; editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
