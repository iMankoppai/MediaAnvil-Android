package com.imankoppai.mediaanvil.data

import com.imankoppai.mediaanvil.model.AudioTrack

/** A completed media-library scan. */
data class LibraryScan(val tracks: List<AudioTrack>, val files: List<ScannedFile> = emptyList())

/** Retained for the cache/backup shape; the media-library scan no longer lists raw files. */
data class ScannedFile(val uri: android.net.Uri, val name: String, val relativeFolder: String)
