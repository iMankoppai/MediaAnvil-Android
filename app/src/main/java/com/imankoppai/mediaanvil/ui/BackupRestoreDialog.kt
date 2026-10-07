package com.imankoppai.mediaanvil.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.data.PlayerDataBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BackupRestoreDialog(
    source: Uri,
    onDismiss: () -> Unit,
    onRestore: (PlayerDataBackup.RestorePlan, Map<String, String>) -> Unit,
) {
    val context = LocalContext.current
    var plan by remember(source) { mutableStateOf<PlayerDataBackup.RestorePlan?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    var choices by remember(source) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showMatched by remember(source) { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context,
        com.imankoppai.mediaanvil.data.DeviceAudioLibrary.readPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { hasPermission = it }
    LaunchedEffect(source, hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { runCatching { PlayerDataBackup.preview(context, source) } }
        plan = result.getOrNull()
        failed = result.isFailure
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_confirm_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.backup_import_confirm_body)) }
                val preview = plan
                when {
                    !hasPermission -> item {
                        Text(stringResource(R.string.backup_audio_permission))
                        Button(onClick = { permission.launch(com.imankoppai.mediaanvil.data.DeviceAudioLibrary.readPermission) }) {
                            Text(stringResource(R.string.storage_access_action))
                        }
                    }
                    failed -> item { Text(stringResource(R.string.backup_import_failed), color = MaterialTheme.colorScheme.error) }
                    preview == null -> item { CircularProgressIndicator() }
                    else -> {
                        item {
                            val resolved = preview.matches.count { it.automaticUri != null || choices.containsKey(it.source.uri) }
                            Text(stringResource(R.string.backup_match_summary, resolved, preview.matches.size - resolved))
                            TextButton(onClick = { showMatched = !showMatched }) {
                                Text(stringResource(if (showMatched) R.string.backup_hide_matches else R.string.backup_review_matches))
                            }
                            if (preview.legacy) Text(stringResource(R.string.backup_legacy_hint))
                            if (preview.inaccessibleCovers > 0) Text(stringResource(R.string.backup_cover_permission, preview.inaccessibleCovers))
                            Text(stringResource(R.string.backup_missing_hint), style = MaterialTheme.typography.bodySmall)
                        }
                        items(preview.matches.filter { showMatched || preview.legacy || it.automaticUri == null }, key = { it.source.uri }) { match ->
                            Column {
                                Text(match.source.fileName.ifEmpty { match.source.uri }, style = MaterialTheme.typography.titleSmall)
                                Text(match.source.relativeFolder, style = MaterialTheme.typography.bodySmall)
                                if (match.automaticUri != null) {
                                    val target = match.candidates.single()
                                    Text(target.relativeFolder + target.fileName, color = MaterialTheme.colorScheme.primary)
                                    Text(target.uri, style = MaterialTheme.typography.bodySmall)
                                } else if (match.candidates.isEmpty()) Text(stringResource(R.string.backup_file_missing))
                                else {
                                    Text(stringResource(R.string.backup_choose_match))
                                    match.candidates.forEach { candidate ->
                                        TextButton(onClick = { choices = choices + (match.source.uri to candidate.uri) }) {
                                            Text((if (choices[match.source.uri] == candidate.uri) "✓ " else "") +
                                                candidate.relativeFolder + candidate.fileName + "\n" + candidate.uri)
                                        }
                                    }
                                    TextButton(onClick = { choices = choices - match.source.uri }) { Text(stringResource(R.string.backup_keep_missing)) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = plan != null, onClick = { plan?.let { onRestore(it, choices) } }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
