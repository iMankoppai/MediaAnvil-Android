package com.imankoppai.mediaanvil.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.data.PlaybackPreferences
import com.imankoppai.mediaanvil.data.playbackSpeeds

internal fun playbackSpeedLabel(speed: Float): String = if (speed % 1f == 0f) "${speed.toInt()}\u00D7" else "${speed}\u00D7"

@Composable
internal fun BatchSpeedDialog(onApply: (Float?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.batch_speed)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.batch_speed_hint), style = MaterialTheme.typography.bodySmall)
                playbackSpeeds.forEach { speed ->
                    TextButton(onClick = { onApply(speed); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(playbackSpeedLabel(speed))
                    }
                }
                TextButton(onClick = { onApply(null); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.playback_inherit))
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun WorkSettingsDialog(folder: String, preferences: PlaybackPreferences, onDismiss: () -> Unit) {
    var speed by remember(folder) { mutableStateOf(preferences.folderSpeed(folder)) }
    var rewind by remember(folder) { mutableStateOf(preferences.folderRewind(folder)) }
    var order by remember(folder) { mutableStateOf(preferences.folderOrder(folder)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.work_preferences)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.work_preferences_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.playback_speed_title))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(speed == null, { speed = null }, label = { Text(stringResource(R.string.playback_inherit)) })
                    playbackSpeeds.forEach { option -> FilterChip(speed == option, { speed = option }, label = { Text(playbackSpeedLabel(option)) }) }
                }
                Text(stringResource(R.string.work_resume_rewind))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(rewind == null, { rewind = null }, label = { Text(stringResource(R.string.playback_inherit)) })
                    listOf(0, 3, 5, 10).forEach { option -> FilterChip(rewind == option, { rewind = option }, label = { Text("${option}s") }) }
                }
                Text(stringResource(R.string.work_play_order))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(order == null, { order = null }, label = { Text(stringResource(R.string.playback_inherit)) })
                    listOf("natural" to R.string.work_order_natural, "shuffle" to R.string.shuffle_play, "loop" to R.string.work_order_loop).forEach { (key, label) ->
                        FilterChip(order == key, { order = key }, label = { Text(stringResource(label)) })
                    }
                }
            }
        }, confirmButton = {
            TextButton(onClick = { preferences.setWorkPlayback(folder, speed, rewind, order); onDismiss() }) { Text(stringResource(R.string.confirm)) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
