package com.imankoppai.mediaanvil.data

import com.imankoppai.mediaanvil.model.AudioTrack

internal val playbackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
internal const val WORK_KEY = "mediaanvil_work"

internal fun workKey(track: AudioTrack): String =
    "${track.uri.pathSegments.firstOrNull().orEmpty()}|${track.relativeFolder.trim('/')}"

internal fun effectivePlaybackSpeed(track: Float?, folder: Float?, global: Float): Float =
    (track ?: folder ?: global).takeIf { it.isFinite() && it in 0.5f..3f } ?: 1f
